package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoStickerRepositoryTest {
    @Test
    fun `unscoped sticker state fails closed`() = runBlocking {
        val repository = MtProtoStickerRepository()

        val failure = runCatching { repository.getStickerSet(7L) }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }
}
