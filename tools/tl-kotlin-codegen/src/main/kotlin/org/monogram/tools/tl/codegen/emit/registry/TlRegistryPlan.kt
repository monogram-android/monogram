package org.monogram.tools.tl.codegen.emit.registry

import org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan
import org.monogram.tools.tl.codegen.emit.codec.TlRegistryContract
import org.monogram.tools.tl.codegen.emit.codec.TlSchemaCodecPlan
import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile
import org.monogram.tools.tl.codegen.model.TlSchemaKey

/** Immutable per-schema inputs for constructor and method registry emission. */
data class TlRegistryGenerationPlan(
    val schemas: List<TlSchemaRegistryPlan>,
)

data class TlSchemaRegistryPlan(
    val schemaKey: TlSchemaKey,
    val contract: TlRegistryContract,
    val relativePath: String,
    val constructors: List<TlDeclarationCodecPlan>,
    val methods: List<TlDeclarationCodecPlan>,
)

data class TlRegistryGenerationResult(
    val files: List<GeneratedKotlinFile>,
    val plan: TlRegistryGenerationPlan,
) {
    val constructorCount: Int get() = plan.schemas.sumOf { it.constructors.size }
    val methodCount: Int get() = plan.schemas.sumOf { it.methods.size }
}

class TlRegistryGenerationException(
    val reason: TlRegistryGenerationFailure,
    val schemaKey: TlSchemaKey?,
    val value: String,
    detail: String,
) : IllegalArgumentException(
    buildString {
        append(reason.name)
        schemaKey?.let { append(" schema=").append(it) }
        append(" value=").append(value)
        append(": ").append(detail)
    },
)

enum class TlRegistryGenerationFailure {
    DUPLICATE_ID,
    DUPLICATE_NAME,
    DUPLICATE_PATH,
    DUPLICATE_REGISTRY_PATH,
    SCHEMA_MISMATCH,
    UNSUPPORTED_GENERIC_METHOD,
}

internal fun TlSchemaCodecPlan.registryRelativePath(): String =
    registry.packageName.replace('.', '/') + "/${registry.objectName}.kt"
