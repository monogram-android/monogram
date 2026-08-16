package org.monogram.mtproto.crypto

import java.security.SecureRandom

internal fun interface EntropySource {
    fun nextBytes(destination: ByteArray)
}

internal object SecureEntropySource : EntropySource {
    private val random = SecureRandom()

    override fun nextBytes(destination: ByteArray) = random.nextBytes(destination)
}
