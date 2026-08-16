package org.monogram.mtproto.tl.runtime

enum class TlSchemaKind {
    CLOUD,
    TRANSPORT,
    SECRET,
}

data class TlSchemaIdentity(
    val kind: TlSchemaKind,
    val layer: Int?,
) {
    init {
        require(
            when (kind) {
                TlSchemaKind.TRANSPORT -> layer == null
                TlSchemaKind.CLOUD,
                TlSchemaKind.SECRET,
                -> layer != null
            },
        ) { "Schema layer nullability does not match schema kind" }
    }
}

data class TlDecodeContext(
    val schema: TlSchemaIdentity,
    val depth: Int,
    val limits: TlLimits,
) {
    init {
        require(depth in 0..limits.maxDepth) { "depth must be within the configured depth limit" }
    }

    fun nested(): TlDecodeContext {
        if (depth >= limits.maxDepth) {
            throw TlLimitExceededException.depth(this)
        }
        return copy(depth = depth + 1)
    }
}
