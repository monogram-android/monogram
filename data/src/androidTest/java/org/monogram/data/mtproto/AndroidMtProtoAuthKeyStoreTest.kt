package org.monogram.data.mtproto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMtProtoAuthKeyStoreTest {
    @Test
    fun persistsLoadsAndDeletesScopedAuthKey() = runBlocking {
        val store = AndroidMtProtoAuthKeyStore(ApplicationProvider.getApplicationContext())
        val scope = MtProtoAuthKeyScope("instrumentation", MtProtoEnvironment.TEST, 2)
        store.delete(scope)
        val material = ByteArray(StoredMtProtoAuthKey.MATERIAL_BYTES) { (it * 3).toByte() }
        val record = StoredMtProtoAuthKey.create(
            material,
            StoredMtProtoAuthKey.calculateId(material),
            serverSalt = 73L,
            createdAt = 1_783_001_185,
        )
        try {
            store.save(scope, record)
            val loaded = store.load(scope) as MtProtoAuthKeyLoadResult.Found
            loaded.authKey.use { restored ->
                val restoredMaterial = restored.copyMaterial()
                try {
                    assertArrayEquals(material, restoredMaterial)
                } finally {
                    restoredMaterial.fill(0)
                }
            }
            val file = File(
                ApplicationProvider.getApplicationContext<android.content.Context>().noBackupFilesDir,
                "mtproto-auth/test/instrumentation/dc2.bin",
            )
            val corrupted = file.readBytes()
            try {
                corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
                file.writeBytes(corrupted)
            } finally {
                corrupted.fill(0)
            }
            assertSame(MtProtoAuthKeyLoadResult.Corrupt, store.load(scope))
            assertSame(MtProtoAuthKeyLoadResult.Missing, store.load(scope))

            store.save(scope, record)
            store.delete(scope)
            assertSame(MtProtoAuthKeyLoadResult.Missing, store.load(scope))
        } finally {
            record.close()
            material.fill(0)
            store.delete(scope)
        }
    }
}
