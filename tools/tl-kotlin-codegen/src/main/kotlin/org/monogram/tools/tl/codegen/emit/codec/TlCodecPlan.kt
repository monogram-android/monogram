package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey

/** Immutable codec and dispatch metadata shared by codec and registry emission. */
data class TlCodecGenerationPlan(
    val schemas: List<TlSchemaCodecPlan>,
    val declarationCodecs: List<TlDeclarationCodecPlan>,
    val familyCodecs: List<TlFamilyCodecPlan>,
    val methodResultCodecs: List<TlMethodResultCodecPlan>,
    val exclusions: List<TlCodecExclusion>,
    val coverage: TlCodecCoverageMetadata,
)

data class TlSchemaCodecPlan(
    val schemaKey: TlSchemaKey,
    val registry: TlRegistryContract,
    val constructors: List<TlDeclarationCodecPlan>,
    val methods: List<TlDeclarationCodecPlan>,
    val methodResults: List<TlMethodResultCodecPlan>,
    val exclusions: List<TlCodecExclusion>,
)

data class TlRegistryContract(
    val packageName: String,
    val objectName: String,
) {
    val qualifiedName: String get() = "$packageName.$objectName"
}

data class TlFamilyCodecContract(
    val packageName: String,
    val objectName: String,
) {
    val qualifiedName: String get() = "$packageName.$objectName"
}

data class TlFamilyCodecPlan(
    val schemaKey: TlSchemaKey,
    val tlName: String,
    val packageName: String,
    val kotlinType: String,
    val contract: TlFamilyCodecContract,
    val relativePath: String,
    val constructors: List<TlDeclarationCodecPlan>,
)

data class TlDeclarationCodecPlan(
    val schemaKey: TlSchemaKey,
    val declarationKind: TlDeclarationKind,
    val tlName: String,
    val constructorId: UInt,
    val constructorIdHex: String,
    val packageName: String,
    val kotlinType: String,
    val codecName: String,
    val relativePath: String,
    val typeParameters: List<String>,
    val codecParameters: List<TlGenericCodecParameter>,
    val registry: TlRegistryContract,
    val wireMembers: List<TlWireMemberPlan>,
    val fields: List<TlFieldCodecPlan>,
    val flagWords: List<TlFlagWordPlan>,
    val sharedFlagBits: List<TlSharedFlagBitPlan>,
    val transportChecks: List<TlTransportWriteCheck>,
) {
    val qualifiedCodecName: String get() = "$packageName.$codecName"
    val bareReadExpression: String get() = "$qualifiedCodecName.readBare"
    val bareWriteExpression: String get() = "$qualifiedCodecName.writeBare"
}

data class TlMethodResultCodecPlan(
    val schemaKey: TlSchemaKey,
    val methodTlName: String,
    val packageName: String,
    val kotlinName: String,
    val resultType: String,
    val typeParameters: List<String>,
    val codecParameters: List<TlGenericCodecParameter>,
    val codec: TlValueCodecPlan,
) {
    val qualifiedName: String get() = "$packageName.$kotlinName"
}

data class TlGenericCodecParameter(
    val typeParameter: String,
    val parameterName: String,
)

data class TlMethodDispatchBranch(
    val constructorId: UInt,
    val qualifiedType: String,
    val qualifiedCodecName: String,
    val requiresResultCodec: Boolean,
)

sealed interface TlWireMemberPlan {
    val sourceOrder: Int

    data class FlagWord(
        val flag: TlFlagWordPlan,
        override val sourceOrder: Int,
    ) : TlWireMemberPlan

    data class Field(
        val field: TlFieldCodecPlan,
        override val sourceOrder: Int,
    ) : TlWireMemberPlan
}

data class TlFieldCodecPlan(
    val sourceOrder: Int,
    val kotlinName: String,
    val kotlinType: String,
    val codec: TlValueCodecPlan,
    val condition: TlFlagConditionPlan?,
    val independentFlag: Boolean,
)

data class TlFlagConditionPlan(
    val flagName: String,
    val bit: Int,
    val mask: UInt,
)

data class TlFlagWordPlan(
    val sourceOrder: Int,
    val tlName: String,
    val localName: String,
    val optionalMask: UInt,
)

data class TlSharedFlagBitPlan(
    val flagName: String,
    val bit: Int,
    val mask: UInt,
    val fields: List<TlFlagPresencePlan>,
)

data class TlFlagPresencePlan(
    val kotlinName: String,
    val independentFlag: Boolean,
)

sealed interface TlTransportWriteCheck {
    data class ExactDeferredLength(
        val byteCountField: String,
        val deferredField: String,
    ) : TlTransportWriteCheck
}

sealed interface TlValueCodecPlan {
    val kotlinType: String

    data class Primitive(
        val kind: TlPrimitiveCodecKind,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class Generic(
        val typeParameter: String,
        val codecParameterName: String,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class Vector(
        val element: TlValueCodecPlan,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class NamedBoxed(
        val familyCodec: TlFamilyCodecContract,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class UnconstrainedObject(
        val registry: TlRegistryContract,
        override val kotlinType: String = "TlObject",
    ) : TlValueCodecPlan

    data class NamedBare(
        val codecQualifiedName: String,
        val codecKotlinType: String,
        val codecArguments: List<TlValueCodecPlan>,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class Method(
        val registry: TlRegistryContract,
        val resultCodecParameterName: String?,
        val exactResultBranches: List<TlMethodDispatchBranch>,
        override val kotlinType: String,
    ) : TlValueCodecPlan

    data class DeferredExact(
        val byteCountField: String,
        override val kotlinType: String = "TlDeferredObject",
    ) : TlValueCodecPlan

    data class DeferredRemaining(
        override val kotlinType: String = "TlDeferredObject",
    ) : TlValueCodecPlan
}

enum class TlPrimitiveCodecKind {
    INT,
    UINT,
    LONG,
    DOUBLE,
    BOOL,
    BYTES,
    STRING,
    INT128,
    INT256,
}

data class TlCodecExclusion(
    val schemaKey: TlSchemaKey,
    val declarationKind: TlDeclarationKind,
    val tlName: String,
    val constructorId: UInt,
    val reason: TlCodecExclusionReason,
)

enum class TlCodecExclusionReason {
    BUILTIN_PRIMITIVE,
    BUILTIN_VECTOR,
    FORMAL_REPETITION,
}

data class TlSchemaCodecCoverage(
    val schemaKey: TlSchemaKey,
    val concreteConstructors: List<TlCodecCoverageEntry>,
    val methodResults: List<TlCodecCoverageEntry>,
    val exclusions: List<TlCodecExclusion>,
)

data class TlCodecCoverageEntry(
    val tlName: String,
    val constructorId: UInt,
    val qualifiedCodecName: String,
)

data class TlCodecCoverageMetadata(
    val schemas: List<TlSchemaCodecCoverage>,
) {
    val concreteConstructorCount: Int get() = schemas.sumOf { it.concreteConstructors.size }
    val methodResultCount: Int get() = schemas.sumOf { it.methodResults.size }
    val excludedCount: Int get() = schemas.sumOf { it.exclusions.size }
}

data class TlCodecGenerationResult(
    val files: List<GeneratedKotlinFile>,
    val plan: TlCodecGenerationPlan,
) {
    val registryPlans: List<TlSchemaCodecPlan> get() = plan.schemas
    val coverage: TlCodecCoverageMetadata get() = plan.coverage
}

class TlCodecPlanningException(
    val reason: TlCodecPlanningFailure,
    val schemaKey: TlSchemaKey?,
    val declarationName: String?,
    val expressionPath: String?,
    detail: String,
) : IllegalArgumentException(
    buildString {
        append(reason.name)
        schemaKey?.let { append(" schema=").append(it) }
        declarationName?.let { append(" declaration=").append(it) }
        expressionPath?.let { append(" path=").append(it) }
        append(": ").append(detail)
    },
)

enum class TlCodecPlanningFailure {
    NAME_COLLISION,
    DUPLICATE_OUTPUT_PATH,
    UNSUPPORTED_BUILTIN,
    UNSUPPORTED_REPETITION,
    UNSUPPORTED_EXPRESSION,
    UNSUPPORTED_OBJECT_POSITION,
    UNRESOLVED_BARE_CODEC,
    UNRESOLVED_GENERIC_CODEC,
    INVALID_FLAG_LAYOUT,
    INVALID_TRANSPORT_POLICY,
    INCOMPLETE_COVERAGE,
}
