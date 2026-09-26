package org.monogram.core.ui

import java.io.File

fun gzipFile(file: File?): Boolean {
    val header = fileHeader(file, 2) ?: return false
    return header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
}

fun webmFile(file: File?): Boolean {
    val header = fileHeader(file, 4) ?: return false
    return header.size >= 4 &&
        header[0] == 0x1a.toByte() &&
        header[1] == 0x45.toByte() &&
        header[2] == 0xdf.toByte() &&
        header[3] == 0xa3.toByte()
}

fun videoFile(file: File?): Boolean = webmFile(file) || mp4File(file)

fun mp4File(file: File?): Boolean {
    val header = fileHeader(file, 12) ?: return false
    if (header.size < 8) return false
    val ascii = header.toString(Charsets.ISO_8859_1)
    return ascii.contains("ftyp")
}

private fun fileHeader(file: File?, n: Int): ByteArray? {
    if (file == null || !file.isFile) return null
    return runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(n)
            val read = stream.read(header)
            if (read <= 0) null else header.copyOf(read)
        }
    }.getOrNull()
}
