package org.monogram.data.mtproto

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_a29444d0dd
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa
import org.monogram.mtproto.tl.generated.cloud.layer223.upload.SaveFilePart
import org.monogram.mtproto.tl.runtime.TlBytes

internal fun interface MtProtoFileUploader {
    suspend fun upload(path: String): InputFile_a29444d0dd
}

internal class TelegramMtProtoFileUploader(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = "default",
) : MtProtoFileUploader {
    override suspend fun upload(path: String): InputFile_a29444d0dd {
        val file = File(path)
        require(file.isFile) { "Upload source is not a regular file" }
        require(file.length() <= MAX_UPLOAD_BYTES) { "MTProto upload exceeds supported size" }
        val fileId = Random.nextLong()
        val digest = MessageDigest.getInstance("MD5")
        var part = 0
        transportFactory.open(accountSlot).use { transport ->
            file.inputStream().use { input ->
                val buffer = ByteArray(PART_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "MTProto upload produced an empty part" }
                    digest.update(buffer, 0, count)
                    val bytes = TlBytes.copyOf(buffer.copyOf(count))
                    check(transport.execute(SaveFilePart(fileId, part, bytes))) {
                        "upload.saveFilePart rejected part $part"
                    }
                    part++
                }
            }
        }
        require(part > 0) { "Upload source is empty" }
        return InputFile_ef0db4e0fa(fileId, part, file.name, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val PART_SIZE = 512 * 1024
        const val MAX_UPLOAD_BYTES = 10L * 1024L * 1024L
    }
}
