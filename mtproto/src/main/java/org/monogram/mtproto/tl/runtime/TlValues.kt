package org.monogram.mtproto.tl.runtime

class TlBytes private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is TlBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TlBytes(size=${bytes.size})"

    companion object {
        fun copyOf(bytes: ByteArray): TlBytes = TlBytes(bytes.copyOf())
    }
}

class TlInt128 private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is TlInt128 && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TlInt128(size=$BYTE_COUNT)"

    companion object {
        private const val BYTE_COUNT: Int = 16

        fun copyOf(bytes: ByteArray): TlInt128 {
            require(bytes.size == BYTE_COUNT) { "TlInt128 requires exactly $BYTE_COUNT bytes" }
            return TlInt128(bytes.copyOf())
        }
    }
}

class TlInt256 private constructor(private val bytes: ByteArray) {
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is TlInt256 && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TlInt256(size=$BYTE_COUNT)"

    companion object {
        private const val BYTE_COUNT: Int = 32

        fun copyOf(bytes: ByteArray): TlInt256 {
            require(bytes.size == BYTE_COUNT) { "TlInt256 requires exactly $BYTE_COUNT bytes" }
            return TlInt256(bytes.copyOf())
        }
    }
}

class TlDeferredObject private constructor(private val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is TlDeferredObject && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TlDeferredObject(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray, maxBytes: Int): TlDeferredObject {
            require(maxBytes in 1..TlLimits.DEFAULT.maxObjectBytes) {
                "maxBytes must be positive and no greater than the default object-byte limit"
            }
            require(bytes.size <= maxBytes) { "Deferred object exceeds maxBytes" }
            return TlDeferredObject(bytes.copyOf())
        }
    }
}
