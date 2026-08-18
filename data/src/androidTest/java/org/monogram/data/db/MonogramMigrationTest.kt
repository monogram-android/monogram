package org.monogram.data.db

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonogramMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = MonogramDatabase::class.java
    )

    @Test
    fun migration37To38PreservesMessagesAndStartsWithUnknownCoverage() {
        helper.createDatabase(TEST_DATABASE, 37).use { database ->
            database.insertMessageRow(chatId = 100L, messageId = 200L, content = "preserved")
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            38,
            true,
            MonogramMigrations.MIGRATION_37_38
        ).use { database ->
            database.query(
                "SELECT content FROM messages WHERE chatId = 100 AND id = 200"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("preserved", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            database.query("SELECT COUNT(*) FROM message_windows").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration38To39CreatesScopedMtProtoUpdateState() {
        helper.createDatabase(TEST_DATABASE_38, 38).use { }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_38,
            39,
            true,
            MonogramMigrations.MIGRATION_38_39,
        ).use { database ->
            database.query("PRAGMA table_info(mtproto_update_state)").use { cursor ->
                val columns = buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertEquals(
                    setOf("accountSlot", "environment", "dcId", "pts", "qts", "date", "seq", "channelPtsData"),
                    columns,
                )
            }
        }
    }

    @Test
    fun migration39To40PreservesUpdateStateAndCreatesPendingEnvelopeQueue() {
        helper.createDatabase(TEST_DATABASE_39, 39).use { database ->
            database.execSQL(
                "INSERT INTO mtproto_update_state " +
                    "(accountSlot, environment, dcId, pts, qts, date, seq, channelPtsData) " +
                    "VALUES ('account-1', 'production', 2, 10, 20, 30, 40, NULL)"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_39,
            40,
            true,
            MonogramMigrations.MIGRATION_39_40,
        ).use { database ->
            database.query("SELECT pts, qts, date, seq FROM mtproto_update_state").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(10, cursor.getInt(0))
                assertEquals(20, cursor.getInt(1))
                assertEquals(30, cursor.getInt(2))
                assertEquals(40, cursor.getInt(3))
            }
            database.query("SELECT COUNT(*) FROM mtproto_pending_envelopes").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration40To41PreservesPendingEnvelopesAndCreatesCloudObjectStaging() {
        helper.createDatabase(TEST_DATABASE_40, 40).use { database ->
            database.execSQL(
                "INSERT INTO mtproto_pending_envelopes " +
                    "(accountSlot, environment, dcId, payloadHash, payload, createdAt) " +
                    "VALUES ('account-1', 'prod', 2, 'hash-1', X'0102', 1234)"
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_40,
            41,
            true,
            MonogramMigrations.MIGRATION_40_41,
        ).use { database ->
            database.query("SELECT payloadHash FROM mtproto_pending_envelopes").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("hash-1", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM mtproto_cloud_objects").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertMessageRow(
        chatId: Long,
        messageId: Long,
        content: String
    ) {
        val values = ContentValues()
        query("PRAGMA table_info(messages)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                when (name) {
                    "chatId" -> values.put(name, chatId)
                    "id" -> values.put(name, messageId)
                    "content" -> values.put(name, content)
                    else -> if (cursor.getInt(notNullIndex) != 0 && cursor.isNull(defaultIndex)) {
                        when (cursor.getString(typeIndex).uppercase()) {
                            "TEXT" -> values.put(name, "")
                            "BLOB" -> values.put(name, ByteArray(0))
                            "REAL" -> values.put(name, 0.0)
                            else -> values.put(name, 0L)
                        }
                    }
                }
            }
        }
        insert("messages", 0, values)
    }

    private companion object {
        const val TEST_DATABASE = "migration-37-38"
        const val TEST_DATABASE_38 = "migration-38-39"
        const val TEST_DATABASE_39 = "migration-39-40"
        const val TEST_DATABASE_40 = "migration-40-41"
    }
}
