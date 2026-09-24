package org.monogram

import org.json.JSONArray
import org.json.JSONObject
import org.monogram.core.common.AppLog
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal object TdlibSessionImport {
    private const val EMPTY_KEY = "cucumber"
    private const val HASH_MSG = "cucumbers everywhere"
    private const val HEADER = 28
    private const val TAIL = 4
    private const val MIN_EVENT = HEADER + TAIL
    private const val AES_CTR = -3
    private const val PMC = 0x4327
    private const val CONFIG_PMC = 0x1f18
    private const val AUTH_FLAG = 1
    private const val MAX_BINLOG = 512L * 1024 * 1024

    fun maybeImport(filesDir: File, sessionFile: File, cacheDir: File = filesDir) {
        if (sessionHasUser(sessionFile)) {
            if (File(filesDir, "td-db").isDirectory) wipeTdlib(filesDir, cacheDir)
            return
        }
        val tdDb = File(filesDir, "td-db")
        if (!tdDb.isDirectory) return
        val imported = runCatching { scan(tdDb) }.getOrNull()
        if (imported == null) {
            AppLog.api("session", "tdlib db present but auth key not decoded")
            return
        }
        val json = sessionJson(imported.dcId, imported.authKey, imported.userId)
        val tmp = File(sessionFile.parentFile, "${sessionFile.name}.tdlib-import")
        tmp.writeText(json)
        if (!tmp.renameTo(sessionFile)) {
            sessionFile.writeText(json)
            tmp.delete()
        }
        wipeTdlib(filesDir, cacheDir)
        AppLog.api("session", "imported tdlib session dc=${imported.dcId}")
    }

    internal fun wipeTdlib(filesDir: File, cacheDir: File) {
        val names = listOf(
            "td-db",
            "td-files",
            "tdlib",
            "tdlib-db",
            "tdlib-files",
        )
        for (name in names) {
            File(filesDir, name).deleteRecursively()
            File(cacheDir, name).deleteRecursively()
        }
    }

    internal data class Imported(val dcId: Int, val authKey: ByteArray, val userId: Long?)

    internal fun scan(tdDb: File): Imported? {
        val files = binlogFiles(tdDb)
        for (file in files) {
            if (file.length() > MAX_BINLOG) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            parseBinlog(bytes)?.let { return it }
            findAuthKey(bytes)?.let { key ->
                return Imported(dcId = findDcId(bytes) ?: 2, authKey = key, userId = null)
            }
        }
        return null
    }

    private fun binlogFiles(tdDb: File): List<File> {
        val named = listOf(File(tdDb, "td.binlog"), File(tdDb, "db.binlog")).filter { it.isFile }
        val walked = tdDb.walkTopDown()
            .filter { it.isFile && it.name.contains("binlog") }
            .toList()
        return (named + walked).distinctBy { it.canonicalPath }
    }

    internal fun parseBinlog(bytes: ByteArray): Imported? {
        val pmc = linkedMapOf<String, ByteArray>()
        var buf = bytes
        var offset = 0
        var decrypted = false
        while (offset + MIN_EVENT <= buf.size) {
            val size = leInt(buf, offset)
            if (size < MIN_EVENT || size > 1 shl 24 || size % 4 != 0 || offset + size > buf.size) break
            val type = leInt(buf, offset + 12)
            val data = buf.copyOfRange(offset + HEADER, offset + size - TAIL)
            if (type == AES_CTR && !decrypted) {
                val rest = buf.copyOfRange(offset + size, buf.size)
                val plain = decryptRest(data, rest) ?: break
                buf = plain
                offset = 0
                decrypted = true
                continue
            }
            if (type == PMC || type == CONFIG_PMC) {
                val pair = fetchKeyValue(data) ?: continue
                pmc[pair.first] = pair.second
            }
            offset += size
        }
        return importedFromPmc(pmc) ?: findAuthKey(buf)?.let {
            Imported(dcId = pmcDcId(pmc) ?: findDcId(buf) ?: 2, authKey = it, userId = null)
        }
    }

    private fun importedFromPmc(pmc: Map<String, ByteArray>): Imported? {
        val dcId = pmcDcId(pmc) ?: (1..5).firstOrNull { pmc.containsKey("auth$it") } ?: return null
        val raw = pmc["auth$dcId"] ?: return null
        val key = parseAuthKey(raw) ?: return null
        return Imported(dcId = dcId, authKey = key, userId = pmcUserId(pmc))
    }

    private fun pmcUserId(pmc: Map<String, ByteArray>): Long? {
        val raw = pmc["my_id"] ?: return null
        val text = raw.toString(Charsets.UTF_8).trim()
        return text.toLongOrNull()?.takeIf { it > 0 }
            ?: text.removePrefix("user").toLongOrNull()?.takeIf { it > 0 }
    }

    private fun pmcDcId(pmc: Map<String, ByteArray>): Int? {
        val raw = pmc["main_dc_id"] ?: return null
        val text = raw.toString(Charsets.UTF_8).trim()
        return text.toIntOrNull()?.takeIf { it in 1..5 }
    }

    internal fun parseAuthKey(bytes: ByteArray): ByteArray? {
        if (bytes.size < 12) return null
        val storedId = leLong(bytes, 0)
        var pos = 12
        val key = fetchTlString(bytes, pos) ?: return null
        if (key.size != 256) return null
        if (authKeyId(key) != storedId) return null
        return key
    }

    private fun decryptRest(eventData: ByteArray, rest: ByteArray): ByteArray? {
        if (rest.isEmpty()) return ByteArray(0)
        var pos = 0
        if (pos + 4 > eventData.size) return null
        pos += 4
        val salt = fetchTlString(eventData, pos) ?: return null
        pos = saltPosAfter(eventData, 4)
        val iv = fetchTlString(eventData, pos) ?: return null
        pos = saltPosAfter(eventData, pos)
        val hash = fetchTlString(eventData, pos) ?: return null
        if (salt.isEmpty() || iv.size != 16 || hash.size != 32) return null
        val key = pbkdf2Sha256(EMPTY_KEY.toByteArray(), salt, 2, 32)
        val expected = hmacSha256(key, HASH_MSG.toByteArray())
        if (!MessageDigest.isEqual(expected, hash)) return null
        return aesCtr(key, iv, rest)
    }

    private fun saltPosAfter(bytes: ByteArray, start: Int): Int {
        val consumed = tlStringSize(bytes, start) ?: return bytes.size
        return start + consumed
    }

    private fun fetchKeyValue(data: ByteArray): Pair<String, ByteArray>? {
        val key = fetchTlString(data, 0) ?: return null
        val keySize = tlStringSize(data, 0) ?: return null
        val value = fetchTlString(data, keySize) ?: return null
        if (key.isEmpty()) return null
        return key.toString(Charsets.UTF_8) to value
    }

    private fun fetchTlString(bytes: ByteArray, start: Int): ByteArray? {
        if (start >= bytes.size) return null
        val first = bytes[start].toInt() and 0xff
        val (len, payloadStart, total) = when {
            first < 254 -> {
                val aligned = first and 0x7c
                Triple(first, start + 1, 4 + aligned)
            }
            first == 254 -> {
                if (start + 4 > bytes.size) return null
                val len = (bytes[start + 1].toInt() and 0xff) or
                    ((bytes[start + 2].toInt() and 0xff) shl 8) or
                    ((bytes[start + 3].toInt() and 0xff) shl 16)
                Triple(len, start + 4, 4 + ((len + 3) and 0x7ffffffc))
            }
            else -> return null
        }
        if (len < 0 || payloadStart + len > bytes.size || start + total > bytes.size) return null
        return bytes.copyOfRange(payloadStart, payloadStart + len)
    }

    private fun tlStringSize(bytes: ByteArray, start: Int): Int? {
        if (start >= bytes.size) return null
        val first = bytes[start].toInt() and 0xff
        return when {
            first < 254 -> 4 + (first and 0x7c)
            first == 254 -> {
                if (start + 4 > bytes.size) return null
                val len = (bytes[start + 1].toInt() and 0xff) or
                    ((bytes[start + 2].toInt() and 0xff) shl 8) or
                    ((bytes[start + 3].toInt() and 0xff) shl 16)
                4 + ((len + 3) and 0x7ffffffc)
            }
            else -> null
        }
    }

    internal fun storeTlString(data: ByteArray): ByteArray {
        val len = data.size
        return if (len < 254) {
            val total = 4 + (len and 0x7c)
            val out = ByteArray(total)
            out[0] = len.toByte()
            data.copyInto(out, 1)
            out
        } else {
            val total = 4 + ((len + 3) and 0x7ffffffc)
            val out = ByteArray(total)
            out[0] = 254.toByte()
            out[1] = (len and 0xff).toByte()
            out[2] = ((len shr 8) and 0xff).toByte()
            out[3] = ((len shr 16) and 0xff).toByte()
            data.copyInto(out, 4)
            out
        }
    }

    internal fun wrapEvent(type: Int, data: ByteArray): ByteArray {
        val size = ((HEADER + data.size + TAIL + 3) / 4) * 4
        val out = ByteArray(size)
        putLeInt(out, 0, size)
        putLeInt(out, 12, type)
        data.copyInto(out, HEADER)
        return out
    }

    internal fun encryptBinlog(plainEvents: ByteArray, password: ByteArray = EMPTY_KEY.toByteArray()): ByteArray {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = pbkdf2Sha256(password, salt, 2, 32)
        val hash = hmacSha256(key, HASH_MSG.toByteArray())
        val payload = ByteArray(4) + storeTlString(salt) + storeTlString(iv) + storeTlString(hash)
        val header = wrapEvent(AES_CTR, payload)
        return header + aesCtr(key, iv, plainEvents)
    }

    internal fun pmcEvent(key: String, value: ByteArray): ByteArray =
        wrapEvent(PMC, storeTlString(key.toByteArray()) + storeTlString(value))

    internal fun authKeyBlob(key: ByteArray): ByteArray {
        val id = authKeyId(key)
        val flags = AUTH_FLAG
        val out = ByteArray(12) + storeTlString(key)
        putLeLong(out, 0, id)
        putLeInt(out, 8, flags)
        return out
    }

    private fun findAuthKey(bytes: ByteArray): ByteArray? {
        var offset = 0
        while (offset + 264 <= bytes.size) {
            val storedId = leLong(bytes, offset)
            if (storedId != 0L) {
                val key = bytes.copyOfRange(offset + 8, offset + 264)
                if (authKeyId(key) == storedId) return key
            }
            offset += 8
        }
        return null
    }

    internal fun authKeyId(key: ByteArray): Long {
        val sha = MessageDigest.getInstance("SHA-1").digest(key)
        return leLong(sha, 12)
    }

    private fun sessionHasUser(sessionFile: File): Boolean {
        if (!sessionFile.isFile || sessionFile.length() <= 0L) return false
        val text = runCatching { sessionFile.readText() }.getOrNull() ?: return false
        return runCatching {
            val obj = JSONObject(text)
            !obj.isNull("user_id") && obj.optLong("user_id") != 0L
        }.getOrDefault(false)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((bytes[offset + i].toInt() and 0xff) shl (8 * i))
        }
        return value
    }

    private fun leLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
        }
        return value
    }

    private fun putLeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) bytes[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }

    private fun putLeLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (8 * i)) and 0xffL).toByte()
    }

    private fun findDcId(bytes: ByteArray): Int? {
        var i = 0
        while (i + 4 <= bytes.size) {
            val v = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            if (bytes[i + 2] == 0.toByte() && bytes[i + 3] == 0.toByte() && v in 1..5) return v
            i += 4
        }
        return null
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hashLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            val blockBytes = ByteArray(4)
            putLeInt(blockBytes, 0, java.lang.Integer.reverseBytes(block))
            var u = mac.doFinal(salt + blockBytes)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val copy = minOf(hashLen, dkLen - offset)
            t.copyInto(out, offset, 0, copy)
            offset += copy
            block++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun sessionJson(dcId: Int, authKey: ByteArray, userId: Long?): String {
        val keyJson = JSONArray()
        authKey.forEach { keyJson.put(it.toInt() and 0xff) }
        val snapshot = JSONObject()
            .put("dc_id", dcId)
            .put("auth_key", keyJson)
            .put("server_salt", 0)
            .put("session_id", Random.nextLong() or 1L)
            .put("last_message_id", 0)
            .put("content_sequence", 0)
            .put("time_offset_micros", 0)
            .put("pending_acknowledgements", JSONArray())
            .put("received_message_ids", JSONObject())
            .put("reconnect", JSONObject().put("attempts", 0).put("retry_at_micros", JSONObject.NULL))
        return JSONObject()
            .put("snapshot", snapshot)
            .put("user_id", if (userId == null) JSONObject.NULL else userId)
            .put("peers", JSONObject())
            .put("media", JSONObject())
            .put("channel_pts", JSONObject())
            .put("seen_messages", JSONArray())
            .put("session_dead", false)
            .put("logout_tokens", JSONArray())
            .put("test_dc", false)
            .toString()
    }
}
