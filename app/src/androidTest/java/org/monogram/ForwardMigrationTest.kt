package org.monogram

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.database.DatabaseProvider
import org.monogram.core.database.SessionMetadataStore

class ForwardMigrationTest {
    @Test
    fun staleSchemaDropsCacheAndRestoresIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseProvider.closeAndReset()
        context.deleteDatabase("monogram.db")
        val file = context.getDatabasePath("monogram.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 32
            db.execSQL(
                "CREATE TABLE meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO meta (`key`, value) VALUES ('${SessionMetadataStore.KEY_USER_ID}', '42')",
            )
            db.execSQL(
                "INSERT INTO meta (`key`, value) VALUES ('${SessionMetadataStore.KEY_DC_ID}', '2')",
            )
            db.execSQL(
                "INSERT INTO meta (`key`, value) VALUES ('${SessionMetadataStore.KEY_AUTHORIZED}', '1')",
            )
            db.execSQL("CREATE TABLE leftover (id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO leftover (id) VALUES (1)")
        }
        try {
            val db = DatabaseProvider.get(context)
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("SELECT value FROM meta WHERE `key` = ?", arrayOf(SessionMetadataStore.KEY_USER_ID)).use {
                assertTrue(it.moveToFirst())
                assertEquals("42", it.getString(0))
            }
            sqlite.query("SELECT value FROM meta WHERE `key` = ?", arrayOf(SessionMetadataStore.KEY_AUTHORIZED)).use {
                assertTrue(it.moveToFirst())
                assertEquals("1", it.getString(0))
            }
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'leftover'").use {
                assertFalse(it.moveToFirst())
            }
        } finally {
            DatabaseProvider.closeAndReset()
            context.deleteDatabase("monogram.db")
        }
    }
}
