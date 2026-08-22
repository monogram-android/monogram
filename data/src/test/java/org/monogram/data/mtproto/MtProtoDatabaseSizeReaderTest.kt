package org.monogram.data.mtproto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MtProtoDatabaseSizeReaderTest {
    @Test
    fun `reports a regular database file size`() {
        val database = File.createTempFile("monogram-db", ".sqlite")
        database.writeBytes(ByteArray(42))
        try {
            assertEquals(42L, FileMtProtoDatabaseSizeReader(database).sizeBytes())
        } finally {
            database.delete()
        }
    }

    @Test
    fun `reports zero when the database file is absent`() {
        val database = File.createTempFile("monogram-db", ".sqlite").also(File::delete)

        assertEquals(0L, FileMtProtoDatabaseSizeReader(database).sizeBytes())
    }
}
