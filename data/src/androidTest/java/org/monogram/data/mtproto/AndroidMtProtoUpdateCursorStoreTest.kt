package org.monogram.data.mtproto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.mtproto.updates.MtProtoUpdateCursor

@RunWith(AndroidJUnit4::class)
class AndroidMtProtoUpdateCursorStoreTest {
    @Test
    fun persistsCursorAndKeepsCorruptionStickyUntilReset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidMtProtoUpdateCursorStore(context)
        val scope = MtProtoAuthKeyScope("cursor_instrumentation", MtProtoEnvironment.TEST, 2)
        val cursor = MtProtoUpdateCursor(11, 22, 33, 44)
        store.delete(scope)
        try {
            store.save(scope, cursor)
            assertEquals(cursor, (store.load(scope) as MtProtoUpdateCursorLoadResult.Found).cursor)

            File(
                context.noBackupFilesDir,
                "mtproto-updates/test/cursor_instrumentation/dc2.cursor",
            ).writeBytes(byteArrayOf(1, 2, 3))

            assertSame(MtProtoUpdateCursorLoadResult.Corrupt, store.load(scope))
            assertSame(MtProtoUpdateCursorLoadResult.Corrupt, store.load(scope))

            store.delete(scope)
            assertSame(MtProtoUpdateCursorLoadResult.Missing, store.load(scope))
        } finally {
            store.delete(scope)
        }
    }
}
