package org.monogram.data.mtproto

import java.io.File

/** Reads the app-owned Room database size without depending on TDLib. */
internal fun interface MtProtoDatabaseSizeReader {
    fun sizeBytes(): Long
}

internal class FileMtProtoDatabaseSizeReader(
    private val databaseFile: File,
) : MtProtoDatabaseSizeReader {
    override fun sizeBytes(): Long = databaseFile.takeIf(File::isFile)?.length() ?: 0L
}
