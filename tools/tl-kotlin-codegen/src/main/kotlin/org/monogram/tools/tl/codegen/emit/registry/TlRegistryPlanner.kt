package org.monogram.tools.tl.codegen.emit.registry

import org.monogram.tools.tl.codegen.emit.codec.TlCodecGenerationResult
import org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan
import org.monogram.tools.tl.codegen.emit.codec.TlSchemaCodecPlan

class TlRegistryPlanner {
    fun plan(codecs: TlCodecGenerationResult): TlRegistryGenerationPlan {
        val schemas = codecs.registryPlans.map(::planSchema)
            .sortedWith(compareBy({ it.schemaKey.kind.ordinal }, { it.schemaKey.layer ?: -1 }))
        val duplicateRegistryPath = duplicate(schemas.map(TlSchemaRegistryPlan::relativePath))
        if (duplicateRegistryPath != null) {
            throw TlRegistryGenerationException(
                TlRegistryGenerationFailure.DUPLICATE_REGISTRY_PATH,
                null,
                duplicateRegistryPath,
                "Multiple schema registries resolve to one generated output path",
            )
        }
        return TlRegistryGenerationPlan(schemas.toList())
    }

    private fun planSchema(schema: TlSchemaCodecPlan): TlSchemaRegistryPlan {
        val declarations = schema.constructors + schema.methods
        declarations.firstOrNull { it.schemaKey != schema.schemaKey }?.let { declaration ->
            throw TlRegistryGenerationException(
                TlRegistryGenerationFailure.SCHEMA_MISMATCH,
                schema.schemaKey,
                declaration.tlName,
                "Registry entries must belong to their containing schema",
            )
        }
        rejectDuplicate(
            schema,
            declarations,
            TlRegistryGenerationFailure.DUPLICATE_ID,
            declarations.map { it.constructorId.toString() },
            "constructor/function ID",
        )
        rejectDuplicate(
            schema,
            declarations,
            TlRegistryGenerationFailure.DUPLICATE_NAME,
            declarations.map(TlDeclarationCodecPlan::tlName),
            "TL declaration name",
        )
        rejectDuplicate(
            schema,
            declarations,
            TlRegistryGenerationFailure.DUPLICATE_PATH,
            declarations.map(TlDeclarationCodecPlan::relativePath),
            "codec output path",
        )
        schema.methods.filter { it.typeParameters.isNotEmpty() }.forEach { method ->
            if (method.typeParameters.size != 1 || method.codecParameters.size != 1) {
                throw TlRegistryGenerationException(
                    TlRegistryGenerationFailure.UNSUPPORTED_GENERIC_METHOD,
                    schema.schemaKey,
                    method.tlName,
                    "The frozen registry ABI supports exactly one explicit generic result codec",
                )
            }
        }
        return TlSchemaRegistryPlan(
            schemaKey = schema.schemaKey,
            contract = schema.registry,
            relativePath = schema.registryRelativePath(),
            constructors = schema.constructors.sortedWith(dispatchComparator).toList(),
            methods = schema.methods.sortedWith(dispatchComparator).toList(),
        )
    }

    private fun rejectDuplicate(
        schema: TlSchemaCodecPlan,
        declarations: List<TlDeclarationCodecPlan>,
        reason: TlRegistryGenerationFailure,
        values: List<String>,
        label: String,
    ) {
        val value = duplicate(values) ?: return
        val names = declarations.zip(values).filter { it.second == value }.map { it.first.tlName }
        throw TlRegistryGenerationException(
            reason,
            schema.schemaKey,
            value,
            "Duplicate $label within one schema: ${names.joinToString()}",
        )
    }

    private fun duplicate(values: List<String>): String? = values.groupingBy { it }
        .eachCount()
        .entries
        .filter { it.value > 1 }
        .map { it.key }
        .sorted()
        .firstOrNull()

    private val dispatchComparator = compareBy<TlDeclarationCodecPlan>(
        { it.constructorId.toLong() },
        TlDeclarationCodecPlan::tlName,
        TlDeclarationCodecPlan::relativePath,
    )
}
