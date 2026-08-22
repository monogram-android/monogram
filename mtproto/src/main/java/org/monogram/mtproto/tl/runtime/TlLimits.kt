package org.monogram.mtproto.tl.runtime

class TlLimits(
    val maxObjectBytes: Int = MAX_OBJECT_BYTES,
    val maxDecompressedBytes: Int = MAX_DECOMPRESSED_BYTES,
    val maxVectorElements: Int = MAX_VECTOR_ELEMENTS,
    val maxDepth: Int = MAX_DEPTH,
    val maxGzipRatio: Int = MAX_GZIP_RATIO,
) {
    init {
        requireInDefaultRange("maxObjectBytes", maxObjectBytes, MAX_OBJECT_BYTES)
        requireInDefaultRange("maxDecompressedBytes", maxDecompressedBytes, MAX_DECOMPRESSED_BYTES)
        requireInDefaultRange("maxVectorElements", maxVectorElements, MAX_VECTOR_ELEMENTS)
        requireInDefaultRange("maxDepth", maxDepth, MAX_DEPTH)
        requireInDefaultRange("maxGzipRatio", maxGzipRatio, MAX_GZIP_RATIO)
    }

    fun lowered(
        maxObjectBytes: Int = this.maxObjectBytes,
        maxDecompressedBytes: Int = this.maxDecompressedBytes,
        maxVectorElements: Int = this.maxVectorElements,
        maxDepth: Int = this.maxDepth,
        maxGzipRatio: Int = this.maxGzipRatio,
    ): TlLimits {
        requireNotRaised("maxObjectBytes", maxObjectBytes, this.maxObjectBytes)
        requireNotRaised("maxDecompressedBytes", maxDecompressedBytes, this.maxDecompressedBytes)
        requireNotRaised("maxVectorElements", maxVectorElements, this.maxVectorElements)
        requireNotRaised("maxDepth", maxDepth, this.maxDepth)
        requireNotRaised("maxGzipRatio", maxGzipRatio, this.maxGzipRatio)
        return TlLimits(
            maxObjectBytes = maxObjectBytes,
            maxDecompressedBytes = maxDecompressedBytes,
            maxVectorElements = maxVectorElements,
            maxDepth = maxDepth,
            maxGzipRatio = maxGzipRatio,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TlLimits &&
            maxObjectBytes == other.maxObjectBytes &&
            maxDecompressedBytes == other.maxDecompressedBytes &&
            maxVectorElements == other.maxVectorElements &&
            maxDepth == other.maxDepth &&
            maxGzipRatio == other.maxGzipRatio

    override fun hashCode(): Int {
        var result = maxObjectBytes
        result = 31 * result + maxDecompressedBytes
        result = 31 * result + maxVectorElements
        result = 31 * result + maxDepth
        result = 31 * result + maxGzipRatio
        return result
    }

    override fun toString(): String =
        "TlLimits(maxObjectBytes=<redacted>, maxDecompressedBytes=<redacted>, " +
            "maxVectorElements=<redacted>, maxDepth=<redacted>, maxGzipRatio=<redacted>)"

    companion object {
        private const val MAX_OBJECT_BYTES: Int = 16 * 1024 * 1024
        private const val MAX_DECOMPRESSED_BYTES: Int = 16 * 1024 * 1024
        private const val MAX_VECTOR_ELEMENTS: Int = 100_000
        private const val MAX_DEPTH: Int = 128
        private const val MAX_GZIP_RATIO: Int = 100

        val DEFAULT: TlLimits = TlLimits()
    }
}

private fun requireInDefaultRange(name: String, value: Int, defaultMaximum: Int) {
    require(value in 1..defaultMaximum) {
        "$name must be positive and no greater than its default maximum"
    }
}

private fun requireNotRaised(name: String, value: Int, currentMaximum: Int) {
    require(value <= currentMaximum) { "$name cannot be raised" }
}
