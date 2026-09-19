package org.monogram

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.ui.DownloadSettings
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.network.bridge.BridgedMtprotoClient

/**
 * Live netcode measurement harness: read-only calls against the signed-in account
 * on the running emulator, driven by `scripts/perf/netcode-perf.sh`.
 *
 * Never sends or deletes anything, never logs message text. Turn perf logging on
 * before launching the process:
 *
 * ```console
 * adb shell setprop log.tag.monogram.perf DEBUG
 * ```
 */
class NetcodePerfHarnessTest {
    private val tag = PerfLog.TAG

    @Test
    fun measureLiveNetcode() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MonogramApp
        val client = app.client
        val repository = app.mediaRepository
        val samples = mutableListOf<String>()

        fun emit(line: String) {
            Log.i(tag, line)
            samples += line
        }

        emit("HARNESS start perf_enabled=${PerfLog.isEnabled()}")

        var connected: Outcome<Unit>? = null
        val connectMs = measure { connected = withTimeoutOrNull(60_000) { client.connect() } }
        assertOk("connect", connected)
        emit("HARNESS connect_cold ms=$connectMs result=ok")
        var warmConnect: Outcome<Unit>? = null
        val warmConnectMs = measure { warmConnect = withTimeoutOrNull(10_000) { client.connect() } }
        assertOk("warm connect", warmConnect)
        emit("HARNESS connect_warm ms=$warmConnectMs result=ok")

        val chatsOutcome = withTimeoutOrNull(60_000) { client.getChats() }
        assertOk("get chats", chatsOutcome)
        val chats = (chatsOutcome as Outcome.Ok).value
        emit("HARNESS chats count=${chats.size}")

        val media = collectMedia(client, chats, 6)
        emit("HARNESS media candidates=${media.size} sizes=${media.take(8).map { it.fileSize ?: 0 }}")
        assumeTrue("no downloadable media on this account", media.isNotEmpty())

        // First-page history latency (dialog open) and thumbnail retrieval latency.
        val firstChat = media.first().id.chatId
        var historyOutcome: Outcome<List<Message>>? = null
        val historyMs = measure { historyOutcome = withTimeoutOrNull(60_000) { client.getHistory(firstChat, 50) } }
        assertOk("first history page", historyOutcome)
        emit("HARNESS history_first_page chat_ms=$historyMs")

        val thumbTarget = media.first()
        thumbTarget.thumbCacheKey?.let { repository.removeCachedFile(it) }
        var thumbOutcome: Outcome<File>? = null
        val thumbMs = measure { thumbOutcome = withTimeoutOrNull(60_000) { repository.ensureLocalMessageThumb(thumbTarget) } }
        assertOk("first thumbnail retrieval", thumbOutcome)
        emit("HARNESS thumb_fetch ms=$thumbMs")
        var thumbCachedOutcome: Outcome<File>? = null
        val thumbCachedMs = measure { thumbCachedOutcome = withTimeoutOrNull(10_000) { repository.ensureLocalMessageThumb(thumbTarget) } }
        assertOk("cached thumbnail retrieval", thumbCachedOutcome)
        emit("HARNESS thumb_cache_hit ms=$thumbCachedMs")

        // Single-file throughput on the real user path (queue -> native -> publish).
        val target = media.maxByOrNull { it.fileSize ?: 0L } ?: media.first()
        val targetKey = target.mediaCacheKey
        if (targetKey != null) repository.removeCachedFile(targetKey)
        val size = target.fileSize ?: 0L
        var singleOutcome: Outcome<File>? = null
        val singleMs = measure { singleOutcome = withTimeoutOrNull(180_000) { repository.ensureLocalMessageMedia(target) } }
        assertOk("single download", singleOutcome)
        emit("HARNESS download_single bytes=$size ms=$singleMs mib_s=${mibPerSecond(size, singleMs)}")

        // Same file again, cache cleared: separates first-use lane/transport cost from
        // steady-state throughput on the user path.
        if (targetKey != null) repository.removeCachedFile(targetKey)
        var warmOutcome: Outcome<File>? = null
        val warmMs = measure { warmOutcome = withTimeoutOrNull(180_000) { repository.ensureLocalMessageMedia(target) } }
        assertOk("warm single download", warmOutcome)
        emit("HARNESS download_single_warm bytes=$size ms=$warmMs mib_s=${mibPerSecond(size, warmMs)}")

        // Warm-lane round trip: same 512 KiB chunk, fetched repeatedly (repo cache bypassed).
        // If this is close to the per-part time above, the part loop is latency-bound.
        val warmDir = File(app.cacheDir, "perf-warm").apply { mkdirs() }
        val warmLatencies = (1..3).mapNotNull { attempt ->
            val warmFile = File(warmDir, "warm-$attempt.part")
            var chunkOutcome: Outcome<String>? = null
            val latency = measure {
                chunkOutcome = withTimeoutOrNull(60_000) {
                    client.downloadMessageMediaChunk(target.id.chatId, target.id.id, warmFile.absolutePath, 0L)
                }
            }
            assertOk("warm chunk $attempt", chunkOutcome)
            warmFile.delete()
            latency
        }
        assertTrue("expected three successful warm chunks", warmLatencies.size == 3)
        emit("HARNESS rtt_warm_chunk ms=${warmLatencies.joinToString(",")} min=${warmLatencies.minOrNull() ?: 0}")

        // Parallel files: does a second and fourth concurrent download raise throughput?
        val others = media.filter { it.mediaCacheKey != null && it.id != target.id }.take(5)
        for (width in listOf(2, 4)) {
            val batch = others.take(width - 1)
            if (batch.size < width - 1) continue
            val jobs = batch + target
            jobs.forEach { it.mediaCacheKey?.let { key -> repository.removeCachedFile(key) } }
            val bytes = jobs.sumOf { it.fileSize ?: 0L }
            val parallelMs = measure {
                coroutineScope {
                    jobs.map { message -> async(Dispatchers.IO) { repository.ensureLocalMessageMedia(message) } }
                        .forEachIndexed { index, job -> assertOk("parallel download $index", job.await()) }
                }
            }
            emit("HARNESS download_parallel width=$width bytes=$bytes ms=$parallelMs mib_s=${mibPerSecond(bytes, parallelMs)}")
        }

        // Two large files together: the honest test of whether lanes scale.
        val large = media.sortedByDescending { it.fileSize ?: 0L }.take(2)
        if (large.size == 2 && (large[1].fileSize ?: 0L) > 1024L * 1024) {
            large.forEach { message -> message.mediaCacheKey?.let { key -> repository.removeCachedFile(key) } }
            val largeBytes = large.sumOf { it.fileSize ?: 0L }
            val largeMs = measure {
                coroutineScope {
                    large.map { message -> async(Dispatchers.IO) { repository.ensureLocalMessageMedia(message) } }
                        .forEachIndexed { index, job -> assertOk("large parallel download $index", job.await()) }
                }
            }
            emit("HARNESS download_two_large bytes=$largeBytes ms=$largeMs mib_s=${mibPerSecond(largeBytes, largeMs)}")
        }

        // Overlap proof: a history RPC must not serialize a media download.
        val overlapMessage = others.lastOrNull { it.mediaCacheKey != null }
        if (overlapMessage != null) {
            overlapMessage.mediaCacheKey?.let { repository.removeCachedFile(it) }
            val downloadStart = SystemClock.elapsedRealtime()
            val downloadEnd = AtomicLong(0L)
            val download = async(Dispatchers.IO) {
                val result = withTimeoutOrNull(180_000) { repository.ensureLocalMessageMedia(overlapMessage) }
                downloadEnd.set(SystemClock.elapsedRealtime())
                result
            }
            delay(150)
            val historyStart = SystemClock.elapsedRealtime()
            val history = withTimeoutOrNull(60_000) { client.getHistory(overlapMessage.id.chatId, 50) }
            val historyEnd = SystemClock.elapsedRealtime()
            assertOk("overlap history", history)
            assertOk("overlap download", download.await())
            val completedAt = downloadEnd.get()
            val overlap = historyStart < completedAt && downloadStart < historyEnd
            assertTrue("history and media operations must overlap", overlap)
            emit(
                "HARNESS overlap history=[${historyStart - downloadStart},${historyEnd - downloadStart}] " +
                    "download=[0,${completedAt - downloadStart}] overlap=$overlap history_ok=${history is Outcome.Ok}",
            )
        }

        val expected = DownloadSettings.concurrency
        client.applyDownloadConcurrency(expected.lanes, expected.parts)
        emit(
            "HARNESS concurrency expected=[${expected.lanes},${expected.parts}] " +
                "applied=${client.downloadConcurrency()}",
        )

        // Device ceiling for the same network: one raw HTTP transfer, no Telegram path.
        var ceiling = 0L to 0L
        val ceilingWallMs = measure { ceiling = withContext(Dispatchers.IO) { rawHttpThroughput() } }
        val ceilingMs = if (ceiling.first > 0) ceiling.first else ceilingWallMs
        emit("HARNESS http_ceiling bytes=${ceiling.second} ms=$ceilingMs mib_s=${mibPerSecond(ceiling.second, ceilingMs)}")

        // A single HTTP stream understates the device: measure the aggregate too.
        var parallelCeilingBytes = 0L
        val parallelCeilingMs = measure {
            val results = coroutineScope {
                (1..3).map { async(Dispatchers.IO) { rawHttpThroughput() } }.map { it.await() }
            }
            parallelCeilingBytes = results.sumOf { it.second }
        }
        emit(
            "HARNESS http_ceiling_parallel streams=3 bytes=$parallelCeilingBytes ms=$parallelCeilingMs " +
                "mib_s=${mibPerSecond(parallelCeilingBytes, parallelCeilingMs)}",
        )

        // Repeat the same file to separate first-use cost from steady state. Pipeline
        // depth lives in native (`PIPELINE_PARTS`, swept by `curve-pipeline`).
        repeat(3) { round ->
            if (targetKey != null) repository.removeCachedFile(targetKey)
            var repeatOutcome: Outcome<File>? = null
            val millis = measure { repeatOutcome = withTimeoutOrNull(240_000) { repository.ensureLocalMessageMedia(target) } }
            assertOk("repeat download ${round + 1}", repeatOutcome)
            emit("HARNESS repeat round=${round + 1} bytes=$size ms=$millis mib_s=${mibPerSecond(size, millis)}")
        }

        PerfLog.dump("perf:harness")
        emit("PERF-SUMMARY ${samples.filter { it.startsWith("HARNESS") }.joinToString(" | ")}")
        client.close()
    }

    private fun assertOk(label: String, outcome: Outcome<*>?) {
        assertTrue("$label failed or timed out: $outcome", outcome is Outcome.Ok)
    }

    private suspend fun collectMedia(
        client: BridgedMtprotoClient,
        chats: List<Chat>,
        limit: Int,
    ): List<Message> {
        val found = mutableListOf<Message>()
        for (chat in chats.take(limit)) {
            val history = withTimeoutOrNull(30_000) {
                when (val outcome = client.getHistory(chat.id, 50)) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> emptyList()
                }
            } ?: continue
            found += history.filter { it.mediaCacheKey != null && (it.fileSize ?: 0L) in (64L * 1024)..(16L * 1024 * 1024) }
            if (found.size >= 8) break
        }
        return found.distinctBy { it.id }
    }

    private suspend inline fun measure(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private fun mibPerSecond(bytes: Long, millis: Long): String {
        if (bytes <= 0L || millis <= 0L) return "0.00"
        val seconds = millis / 1000.0
        return String.format(java.util.Locale.US, "%.2f", bytes / 1024.0 / 1024.0 / seconds)
    }

    /** Raw transfer over the emulator's network stack; skipped when unreachable. */
    private fun rawHttpThroughput(): Pair<Long, Long> {
        val bytes = 8 * 1024 * 1024
        val endpoints = listOf(
            "https://speed.cloudflare.com/__down?bytes=$bytes",
            "https://proof.ovh.net/files/10Mb.dat",
        )
        for (endpoint in endpoints) {
            val start = SystemClock.elapsedRealtime()
            var read = 0L
            val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }.getOrNull()
                ?: continue
            val ok = runCatching {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (read < bytes) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        read += count
                    }
                }
            }.isSuccess
            connection.disconnect()
            if (ok && read > 0) return (SystemClock.elapsedRealtime() - start) to read
        }
        return 0L to 0L
    }
}
