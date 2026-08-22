package org.monogram.mtproto.crypto

import org.monogram.mtproto.tl.generated.transport.PQInnerDataDc
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import java.math.BigInteger

internal object PqInnerDataBuilder {
    fun build(
        factors: PqFactors,
        pq: ByteArray,
        nonce: TlInt128,
        serverNonce: TlInt128,
        newNonce: TlInt256,
        dcId: Int,
    ): PQInnerDataDc {
        require(dcId > 0) { "dcId must be positive" }
        val normalizedPq = unsigned(pq)
        val normalizedP = unsigned(factors.p)
        val normalizedQ = unsigned(factors.q)
        require(BigInteger(1, normalizedPq) == BigInteger.valueOf(factors.p)
            .multiply(BigInteger.valueOf(factors.q))) {
            "pq does not match factors"
        }
        return PQInnerDataDc(
            pq = TlBytes.copyOf(normalizedPq),
            p = TlBytes.copyOf(normalizedP),
            q = TlBytes.copyOf(normalizedQ),
            nonce = nonce,
            serverNonce = serverNonce,
            newNonce = newNonce,
            dc = dcId,
        )
    }

    private fun unsigned(value: Long): ByteArray = unsigned(BigInteger.valueOf(value).toByteArray())

    private fun unsigned(value: ByteArray): ByteArray {
        require(value.isNotEmpty()) { "unsigned value must not be empty" }
        var first = 0
        while (first < value.lastIndex && value[first] == 0.toByte()) first++
        val result = value.copyOfRange(first, value.size)
        require(result[0].toInt() and 0x80 == 0) { "value exceeds signed Long range" }
        return result
    }
}
