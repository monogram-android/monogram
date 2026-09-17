package org.monogram.network.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer

class DownloadQueueTest {
    @Test
    fun priorityIsDescendingAndStableForEqualPriority(): Unit = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(3)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            order += exchange.requestURI.path.removePrefix("/")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(byteArrayOf(1)) }
            done.countDown()
        }
        server.start()
        try {
            val scope = CoroutineScope(SupervisorJob())
            val queue = DownloadQueue(
                client = HttpClient(OkHttp),
                scope = scope,
                maxConcurrent = 1,
            )
            val root = java.nio.file.Files.createTempDirectory("monogram-priority").toFile()
            val base = "http://127.0.0.1:${server.address.port}"
            queue.enqueue(
                DownloadRequest(
                    url = "$base/low",
                    destination = File(root, "low"),
                    priority = MediaPriority.IDLE,
                ),
            )
            queue.enqueue(
                DownloadRequest(
                    url = "$base/high",
                    destination = File(root, "high"),
                    priority = MediaPriority.VISIBLE,
                ),
            )
            queue.enqueue(
                DownloadRequest(
                    url = "$base/high2",
                    destination = File(root, "high2"),
                    priority = MediaPriority.VISIBLE,
                ),
            )
            queue.start()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("high", "high2", "low"), order)
            queue.shutdown()
            scope.cancel()
            root.deleteRecursively()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelMarksQueuedDownloadAndShutdownStopsWorkers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val queue = DownloadQueue(
            client = HttpClient(OkHttp),
            scope = scope,
            maxConcurrent = 1,
        )
        val dest = File.createTempFile("monogram-dl", ".bin")
        dest.deleteOnExit()
        val id = queue.enqueue(
            DownloadRequest(
                url = "http://127.0.0.1:1/never",
                destination = dest,
            ),
        )
        assertTrue(queue.statusMap.value[id] is DownloadStatus.Queued)
        queue.cancel(id)
        assertEquals(DownloadStatus.Cancelled, queue.statusMap.value[id])
        queue.start()
        queue.shutdown()
        assertEquals(DownloadStatus.Cancelled, queue.statusMap.value[id])
    }
}
