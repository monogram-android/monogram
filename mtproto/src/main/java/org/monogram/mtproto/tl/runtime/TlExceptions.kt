package org.monogram.mtproto.tl.runtime

sealed class TlCodecException protected constructor(
    val schema: TlSchemaIdentity,
    open val absoluteOffset: Long?,
    open val constructorId: UInt?,
) : RuntimeException(null, null) {
    final override val message: String
        get() = when (this) {
            is TlUnknownConstructorException ->
                "Unknown TL constructor $constructorId at offset $absoluteOffset for $schema"
            is TlLimitExceededException ->
                "TL limit $limitKind exceeded: configured maximum $configuredMaximum, " +
                    "observed $observedValue, offset ${absoluteOffset ?: "unavailable"}, schema $schema"
            is TlSchemaMismatchException ->
                "TL schema mismatch at offset $absoluteOffset: expected $expectedSchema, actual $actualSchema"
        }
}

class TlUnknownConstructorException(
    schema: TlSchemaIdentity,
    override val constructorId: UInt,
    override val absoluteOffset: Long,
) : TlCodecException(
        schema = schema,
        absoluteOffset = absoluteOffset,
        constructorId = constructorId,
    )

enum class TlLimitKind {
    OBJECT_BYTES,
    DECOMPRESSED_BYTES,
    VECTOR_ELEMENTS,
    DEPTH,
    GZIP_RATIO,
}

class TlLimitExceededException(
    schema: TlSchemaIdentity,
    val limitKind: TlLimitKind,
    val configuredMaximum: Int,
    val observedValue: Int,
    absoluteOffset: Long?,
) : TlCodecException(
        schema = schema,
        absoluteOffset = absoluteOffset,
        constructorId = null,
    ) {
    companion object {
        fun depth(context: TlDecodeContext): TlLimitExceededException =
            TlLimitExceededException(
                schema = context.schema,
                limitKind = TlLimitKind.DEPTH,
                configuredMaximum = context.limits.maxDepth,
                observedValue = context.depth + 1,
                absoluteOffset = null,
            )
    }
}

class TlSchemaMismatchException(
    val expectedSchema: TlSchemaIdentity,
    val actualSchema: TlSchemaIdentity,
    override val absoluteOffset: Long,
) : TlCodecException(
        schema = expectedSchema,
        absoluteOffset = absoluteOffset,
        constructorId = null,
    )
