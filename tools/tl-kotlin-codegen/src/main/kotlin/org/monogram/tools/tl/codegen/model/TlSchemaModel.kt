package org.monogram.tools.tl.codegen.model

import java.nio.file.Path

enum class TlSchemaKind {
    CLOUD,
    TRANSPORT,
    SECRET,
}

data class TlSchemaKey(
    val kind: TlSchemaKind,
    val layer: Int?,
) {
    init {
        require((kind == TlSchemaKind.TRANSPORT) == (layer == null)) {
            "Only the transport schema has a null layer"
        }
    }

    override fun toString(): String = "(${kind.name.lowercase()}, ${layer ?: "null"})"
}

data class TlSourceMetadata(
    val name: String,
    val url: String,
    val artifactPath: Path,
    val provenance: TlManifestProvenance?,
)

data class TlManifestProvenance(
    val manifestPath: Path,
    val artifactRelativePath: String,
    val sourceTlPath: String,
    val sourceSha256: String,
    val exportedJsonSha256: String,
    val tellersCommit: String,
    val exporterPackage: String,
    val exporterVersion: String,
    val exportCommand: String,
    val generatedAt: String,
)

data class ValidatedTlSchema(
    val formatVersion: Int,
    val key: TlSchemaKey,
    val source: TlSourceMetadata,
    val constructors: List<TlDeclaration>,
    val functions: List<TlDeclaration>,
    val finalizations: List<TlFinalization>,
    val partialApplications: List<TlExpression>,
) {
    val declarations: List<TlDeclaration> get() = constructors + functions
    val resultTypes: List<TlExpression> get() = declarations.map(TlDeclaration::result)
}

enum class TlDeclarationKind {
    CONSTRUCTOR,
    FUNCTION,
}

enum class TlIdOrigin {
    EXPLICIT,
    COMPUTED,
}

data class TlDeclaration(
    val name: String,
    val id: UInt,
    val idHex: String,
    val idOrigin: TlIdOrigin,
    val kind: TlDeclarationKind,
    val parameters: List<TlParameter>,
    val result: TlExpression,
    val documentation: TlDocumentation,
    val schemaLayer: Int?,
    val introducedLayer: Int?,
    val builtin: Boolean,
    val sourceOrder: Int,
    val genericParameters: List<TlGenericParameter>,
    val flagWords: List<TlFlagWord>,
) {
    val namespace: List<String> get() = name.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty)
    val localName: String get() = name.substringAfterLast('.')
}

data class TlGenericParameter(
    val name: String,
    val sourceOrder: Int,
)

data class TlFlagWord(
    val name: String,
    val sourceOrder: Int,
    val optionalMask: UInt,
)

data class TlParameter(
    val name: String?,
    val value: TlArgumentValue,
    val implicit: Boolean,
    val functional: Boolean,
    val condition: TlCondition?,
    val description: String?,
    val sourceOrder: Int,
    val transportPolicy: TlTransportPolicy = TlTransportPolicy.NONE,
) {
    val optionalMask: UInt? get() = condition?.bit?.let { 1u shl it }
}

sealed interface TlArgumentValue {
    data class Type(
        val expression: TlExpression,
    ) : TlArgumentValue

    data class Repetition(
        val multiplicity: TlExpression?,
        val parameters: List<TlParameter>,
    ) : TlArgumentValue
}

data class TlCondition(
    val variable: String,
    val bit: Int?,
)

enum class TlReferenceKind {
    PRIMITIVE,
    NAMED_BOXED,
    NAMED_BARE,
    TYPE_PARAMETER,
    NATURAL_PARAMETER,
    OBJECT,
}

enum class TlApplicationKind {
    VECTOR,
    GENERIC,
}

sealed interface TlExpression {
    data class Identifier(
        val name: String,
        val referenceKind: TlReferenceKind,
    ) : TlExpression {
        val namespace: List<String> get() = name.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty)
        val localName: String get() = name.substringAfterLast('.')
    }

    data class Natural(
        val value: ULong,
    ) : TlExpression

    data object Hash : TlExpression

    data class Add(
        val left: TlExpression,
        val right: TlExpression,
    ) : TlExpression

    data class Application(
        val constructor: TlExpression,
        val arguments: List<TlExpression>,
        val applicationKind: TlApplicationKind,
    ) : TlExpression

    data class Bare(
        val inner: TlExpression,
    ) : TlExpression

    data class Bang(
        val inner: TlExpression,
    ) : TlExpression
}

data class TlDocumentation(
    val description: String?,
    val parameters: Map<String, String>,
    val officialUrl: String?,
    val links: List<String>,
)

data class TlFinalization(
    val mode: TlFinalizationMode,
    val type: TlExpression,
    val sourceOrder: Int,
)

enum class TlFinalizationMode {
    NEW,
    FINAL,
    EMPTY,
}

sealed interface TlTransportPolicy {
    data object None : TlTransportPolicy

    data class ExactLengthDeferred(
        val byteCountParameter: String,
    ) : TlTransportPolicy

    data object RemainingDeferred : TlTransportPolicy
    data object GzipPackedBytes : TlTransportPolicy

    companion object {
        val NONE: TlTransportPolicy = None
    }
}
