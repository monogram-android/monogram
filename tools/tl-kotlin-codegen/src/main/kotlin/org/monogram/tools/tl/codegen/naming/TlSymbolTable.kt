package org.monogram.tools.tl.codegen.naming

import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlGenericParameter
import org.monogram.tools.tl.codegen.model.TlParameter
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema

const val GENERATED_PACKAGE_ROOT: String = "org.monogram.mtproto.tl.generated"

data class TlSchemaPartition(
    val schemaKey: TlSchemaKey,
    val packageName: String,
    val relativeDirectory: String,
    val registryPackageName: String = "$packageName.registry",
)

data class TlResultFamilyKey(
    val schemaKey: TlSchemaKey,
    val tlName: String,
    val genericArity: Int,
)

data class TlResultFamilySymbol(
    val key: TlResultFamilyKey,
    val packageName: String,
    val kotlinName: String,
    val typeParameters: List<String>,
    val constructors: List<TlDeclaration>,
    val schema: ValidatedTlSchema,
    val sealed: Boolean,
    val relativePath: String,
    val partitionRelativePath: String,
)

data class TlFieldSymbol(
    val source: TlParameter,
    val kotlinName: String,
    val expression: TlExpression?,
    val repetition: TlRepetitionSymbol?,
    val optional: Boolean,
    val independentFlag: Boolean,
    val flagVariable: String?,
    val flagBit: Int?,
    val optionalMask: UInt?,
    val transportPolicy: TlTransportPolicy,
    val implicit: Boolean,
    val functional: Boolean,
)

data class TlRepetitionSymbol(
    val multiplicity: TlExpression?,
    val fields: List<TlFieldSymbol>,
)

data class TlResultCodecBindingSymbol(
    val packageName: String,
    val kotlinName: String,
    val resultExpression: TlExpression,
    val genericTypeParameters: List<String>,
    val codecArgumentExpressions: List<String>,
) {
    val qualifiedName: String get() = "$packageName.$kotlinName"
    val accessExpression: String
        get() = if (genericTypeParameters.isEmpty()) {
            kotlinName
        } else {
            "$kotlinName.bind(${codecArgumentExpressions.joinToString()})"
        }
}

data class TlDeclarationSymbol(
    val schema: ValidatedTlSchema,
    val source: TlDeclaration,
    val partition: TlSchemaPartition,
    val packageName: String,
    val kotlinName: String,
    val typeParameters: Map<String, String>,
    val fields: List<TlFieldSymbol>,
    val resultFamily: TlResultFamilySymbol?,
    val resultCodecBinding: TlResultCodecBindingSymbol?,
    val relativePath: String,
    val partitionRelativePath: String,
)

data class TlSchemaSymbols(
    val schema: ValidatedTlSchema,
    val partition: TlSchemaPartition,
    val resultFamilies: List<TlResultFamilySymbol>,
    val declarations: List<TlDeclarationSymbol>,
)

data class TlSymbolTable(
    val schemas: List<TlSchemaSymbols>,
    val collisionReport: List<KotlinNameCollision>,
) {
    val declarations: List<TlDeclarationSymbol> get() = schemas.flatMap(TlSchemaSymbols::declarations)
    val resultFamilies: List<TlResultFamilySymbol> get() = schemas.flatMap(TlSchemaSymbols::resultFamilies)

    fun schema(key: TlSchemaKey): TlSchemaSymbols? = schemas.singleOrNull { it.schema.key == key }

    fun resultFamily(key: TlSchemaKey, tlName: String, genericArity: Int = 0): TlResultFamilySymbol? =
        resultFamilies.singleOrNull { it.key == TlResultFamilyKey(key, tlName, genericArity) }

    fun constructor(key: TlSchemaKey, tlName: String): TlDeclarationSymbol? =
        declarations.singleOrNull {
            it.schema.key == key && it.source.kind == TlDeclarationKind.CONSTRUCTOR && it.source.name == tlName
        }
}

class TlSymbolTableBuilder(
    private val nameAllocator: DeterministicKotlinNameAllocator = DeterministicKotlinNameAllocator(),
) {
    fun build(input: Collection<ValidatedTlSchema>): TlSymbolTable {
        val duplicateKey = input.groupingBy(ValidatedTlSchema::key).eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicateKey != null) {
            throw TlGenerationException(
                TlGenerationFailure.DUPLICATE_SCHEMA_KEY,
                duplicateKey.key,
                null,
                null,
                "A schema key may be generated only once",
            )
        }

        val schemas = input.sortedWith(schemaComparator)
        val partitions = schemas.associate { it.key to partition(it.key) }
        val namespaceResult = allocateNamespaces(schemas, partitions)
        val requests = mutableListOf<KotlinNameRequest>()
        val familySpecs = linkedMapOf<TlResultFamilyKey, MutableList<Pair<ValidatedTlSchema, TlDeclaration>>>()

        schemas.forEach { schema ->
            schema.constructors.forEach { declaration ->
                resultFamilyKey(schema.key, declaration.result)?.let { key ->
                    familySpecs.getOrPut(key) { mutableListOf() } += schema to declaration
                }
            }
            schema.declarations.forEach { declaration ->
                val pkg = declarationPackage(schema, declaration, partitions.getValue(schema.key), namespaceResult.packages)
                requests += KotlinNameRequest(
                    identity = declarationIdentity(schema.key, declaration),
                    sourceName = declaration.localName,
                    scope = "${schema.key}|$pkg|types",
                    style = KotlinNameStyle.TYPE,
                    role = declaration.kind.name.lowercase(),
                )
            }
        }

        familySpecs.forEach { (key, entries) ->
            val schema = entries.first().first
            val namespace = key.tlName.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty)
            val pkg = packageForNamespace(partitions.getValue(key.schemaKey), key.schemaKey, namespace, namespaceResult.packages)
            requests += KotlinNameRequest(
                identity = familyIdentity(key),
                sourceName = key.tlName.substringAfterLast('.'),
                scope = "${key.schemaKey}|$pkg|types",
                style = KotlinNameStyle.TYPE,
                role = "result-family-${key.genericArity}",
            )
        }

        val typeNames = nameAllocator.allocate(requests)
        val families = familySpecs.mapValues { (key, entries) ->
            val schema = entries.first().first
            val partition = partitions.getValue(key.schemaKey)
            val namespace = key.tlName.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty)
            val pkg = packageForNamespace(partition, key.schemaKey, namespace, namespaceResult.packages)
            val kotlinName = typeNames[familyIdentity(key)].allocatedName
            val localPath = namespacePath(pkg, partition.packageName, kotlinName)
            TlResultFamilySymbol(
                key = key,
                packageName = pkg,
                kotlinName = kotlinName,
                typeParameters = genericTypeParameters(key.genericArity),
                constructors = entries.map { it.second }.sortedBy(TlDeclaration::sourceOrder),
                schema = schema,
                sealed = entries.all { (_, declaration) ->
                    declarationPackage(schema, declaration, partition, namespaceResult.packages) == pkg
                },
                relativePath = fullRelativePath(partition, localPath),
                partitionRelativePath = localPath,
            )
        }

        val schemaSymbols = schemas.map { schema ->
            val partition = partitions.getValue(schema.key)
            val declarations = schema.declarations.map { declaration ->
                buildDeclarationSymbol(
                    schema,
                    declaration,
                    partition,
                    namespaceResult.packages,
                    typeNames,
                    families,
                )
            }
            val duplicatePath = (declarations.map(TlDeclarationSymbol::relativePath) +
                families.values.filter { it.key.schemaKey == schema.key }.map(TlResultFamilySymbol::relativePath))
                .groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            if (duplicatePath != null) {
                throw TlGenerationException(
                    TlGenerationFailure.DUPLICATE_OUTPUT_PATH,
                    schema.key,
                    null,
                    duplicatePath.key,
                    "Multiple symbols resolve to the same output path",
                )
            }
            TlSchemaSymbols(
                schema = schema,
                partition = partition,
                resultFamilies = families.values.filter { it.key.schemaKey == schema.key }.sortedBy { it.relativePath },
                declarations = declarations,
            )
        }

        return TlSymbolTable(
            schemas = schemaSymbols,
            collisionReport = (namespaceResult.collisions + typeNames.collisions)
                .sortedWith(compareBy(KotlinNameCollision::scope, KotlinNameCollision::preferredName)),
        )
    }

    private fun buildDeclarationSymbol(
        schema: ValidatedTlSchema,
        declaration: TlDeclaration,
        partition: TlSchemaPartition,
        packages: Map<NamespaceKey, String>,
        names: KotlinNameAllocationResult,
        families: Map<TlResultFamilyKey, TlResultFamilySymbol>,
    ): TlDeclarationSymbol {
        val pkg = declarationPackage(schema, declaration, partition, packages)
        val kotlinName = names[declarationIdentity(schema.key, declaration)].allocatedName
        val orderedGenericParameters = declaration.genericParameters.sortedBy(TlGenericParameter::sourceOrder)
        val genericRequests = orderedGenericParameters.map { generic ->
            KotlinNameRequest(
                identity = "${declarationIdentity(schema.key, declaration)}|generic|${generic.sourceOrder}|${generic.name}",
                sourceName = generic.name,
                scope = "${declarationIdentity(schema.key, declaration)}|generics",
                style = KotlinNameStyle.TYPE,
                role = "generic",
            )
        }
        val genericNames = nameAllocator.allocate(genericRequests)
        val typeParameters = orderedGenericParameters.associate { generic ->
            generic.name to genericNames["${declarationIdentity(schema.key, declaration)}|generic|${generic.sourceOrder}|${generic.name}"].allocatedName
        }
        val fields = buildFields(schema.key, declaration, declaration.parameters, "root")
        val localPath = namespacePath(pkg, partition.packageName, kotlinName)
        val family = resultFamilyKey(schema.key, declaration.result)?.let(families::get)
        val resultBinding = if (declaration.kind == TlDeclarationKind.FUNCTION) {
            val codecArguments = orderedGenericParameters.map { generic ->
                val sources = fields.filter { field ->
                    field.functional && field.expression?.let { expression ->
                        expressionProvidesGenericCodec(expression, generic.name)
                    } == true
                }
                if (sources.size != 1) {
                    throw TlGenerationException(
                        TlGenerationFailure.UNRESOLVED_GENERIC_CODEC,
                        schema.key,
                        declaration.name,
                        generic.name,
                        "Expected exactly one visible functional codec source, found ${sources.size}",
                    )
                }
                "${sources.single().kotlinName}.resultCodec"
            }
            TlResultCodecBindingSymbol(
                packageName = pkg,
                kotlinName = "${kotlinName}ResultCodec",
                resultExpression = declaration.result,
                genericTypeParameters = typeParameters.values.toList(),
                codecArgumentExpressions = codecArguments,
            )
        } else {
            null
        }
        return TlDeclarationSymbol(
            schema = schema,
            source = declaration,
            partition = partition,
            packageName = pkg,
            kotlinName = kotlinName,
            typeParameters = typeParameters,
            fields = fields,
            resultFamily = family,
            resultCodecBinding = resultBinding,
            relativePath = fullRelativePath(partition, localPath),
            partitionRelativePath = localPath,
        )
    }

    private fun buildFields(
        key: TlSchemaKey,
        declaration: TlDeclaration,
        parameters: List<TlParameter>,
        fieldPath: String,
    ): List<TlFieldSymbol> {
        val visible = parameters.filterNot(TlParameter::implicit).filterNot { parameter ->
            val expression = (parameter.value as? TlArgumentValue.Type)?.expression
            expression == TlExpression.Hash ||
                (expression is TlExpression.Identifier && expression.referenceKind == TlReferenceKind.NATURAL_PARAMETER)
        }
        val requests = visible.mapIndexed { index, parameter ->
            val raw = parameter.name ?: if (parameter.value is TlArgumentValue.Repetition) "items$index" else "value$index"
            KotlinNameRequest(
                identity = "${declarationIdentity(key, declaration)}|field|$fieldPath|${parameter.sourceOrder}|$raw",
                sourceName = raw,
                scope = "${declarationIdentity(key, declaration)}|fields",
                style = KotlinNameStyle.VALUE,
                role = "field",
            )
        }
        val allocated = nameAllocator.allocate(requests)
        return visible.mapIndexed { index, parameter ->
            val raw = parameter.name ?: if (parameter.value is TlArgumentValue.Repetition) "items$index" else "value$index"
            val identity = "${declarationIdentity(key, declaration)}|field|$fieldPath|${parameter.sourceOrder}|$raw"
            val expression = (parameter.value as? TlArgumentValue.Type)?.expression
            val repetition = (parameter.value as? TlArgumentValue.Repetition)?.let { repeated ->
                TlRepetitionSymbol(
                    repeated.multiplicity,
                    buildFields(key, declaration, repeated.parameters, "$fieldPath.${parameter.sourceOrder}"),
                )
            }
            val independentFlag = expression is TlExpression.Identifier && expression.name == "true"
            TlFieldSymbol(
                source = parameter,
                kotlinName = allocated[identity].allocatedName,
                expression = expression,
                repetition = repetition,
                optional = parameter.condition != null,
                independentFlag = independentFlag,
                flagVariable = parameter.condition?.variable,
                flagBit = parameter.condition?.bit,
                optionalMask = parameter.optionalMask,
                transportPolicy = parameter.transportPolicy,
                implicit = parameter.implicit,
                functional = parameter.functional,
            )
        }
    }

    private fun expressionProvidesGenericCodec(expression: TlExpression, genericName: String): Boolean {
        val unwrapped = if (expression is TlExpression.Bang) expression.inner else expression
        return unwrapped is TlExpression.Identifier &&
            unwrapped.referenceKind == TlReferenceKind.TYPE_PARAMETER &&
            unwrapped.name == genericName
    }

    private data class NamespaceKey(val schemaKey: TlSchemaKey, val rawPath: List<String>)
    private data class NamespaceAllocation(
        val packages: Map<NamespaceKey, String>,
        val collisions: List<KotlinNameCollision>,
    )

    private fun allocateNamespaces(
        schemas: List<ValidatedTlSchema>,
        partitions: Map<TlSchemaKey, TlSchemaPartition>,
    ): NamespaceAllocation {
        val paths = schemas.flatMap { schema ->
            buildList {
                schema.declarations.forEach { declaration -> addAll(prefixes(declaration.namespace).map { NamespaceKey(schema.key, it) }) }
                schema.constructors.mapNotNull { resultFamilyKey(schema.key, it.result) }.forEach { family ->
                    val namespace = family.tlName.substringBeforeLast('.', "").split('.').filter(String::isNotEmpty)
                    addAll(prefixes(namespace).map { NamespaceKey(schema.key, it) })
                }
            }
        }.distinct().sortedWith(compareBy({ it.schemaKey.kind.ordinal }, { it.schemaKey.layer ?: -1 }, { it.rawPath.joinToString(".") }))

        val packages = mutableMapOf<NamespaceKey, String>()
        val collisions = mutableListOf<KotlinNameCollision>()
        val maxDepth = paths.maxOfOrNull { it.rawPath.size } ?: 0
        for (depth in 1..maxDepth) {
            val atDepth = paths.filter { it.rawPath.size == depth }
            val requests = atDepth.map { key ->
                val parent = if (depth == 1) partitions.getValue(key.schemaKey).packageName
                else packages.getValue(NamespaceKey(key.schemaKey, key.rawPath.dropLast(1)))
                KotlinNameRequest(
                    identity = "namespace|${key.schemaKey}|${key.rawPath.joinToString(".")}",
                    sourceName = key.rawPath.last(),
                    scope = "${key.schemaKey}|$parent|packages",
                    style = KotlinNameStyle.PACKAGE,
                    role = "namespace",
                )
            }
            val reservedNamesByScope = if (depth == 1) {
                partitions.values.associate { partition ->
                    "${partition.schemaKey}|${partition.packageName}|packages" to setOf("registry")
                }
            } else {
                emptyMap()
            }
            val result = nameAllocator.allocate(requests, reservedNamesByScope)
            collisions += result.collisions
            atDepth.forEach { key ->
                val parent = if (depth == 1) partitions.getValue(key.schemaKey).packageName
                else packages.getValue(NamespaceKey(key.schemaKey, key.rawPath.dropLast(1)))
                val identity = "namespace|${key.schemaKey}|${key.rawPath.joinToString(".")}"
                packages[key] = "$parent.${result[identity].allocatedName}"
            }
        }
        return NamespaceAllocation(packages, collisions)
    }

    private fun resultFamilyKey(key: TlSchemaKey, expression: TlExpression): TlResultFamilyKey? {
        val unwrapped = if (expression is TlExpression.Bare) expression.inner else expression
        return when (unwrapped) {
            is TlExpression.Identifier -> when (unwrapped.referenceKind) {
                TlReferenceKind.NAMED_BOXED, TlReferenceKind.NAMED_BARE -> TlResultFamilyKey(key, unwrapped.name, 0)
                TlReferenceKind.OBJECT, TlReferenceKind.PRIMITIVE, TlReferenceKind.TYPE_PARAMETER,
                TlReferenceKind.NATURAL_PARAMETER -> null
            }
            is TlExpression.Application -> {
                if (unwrapped.applicationKind == TlApplicationKind.VECTOR) return null
                val constructor = unwrapped.constructor as? TlExpression.Identifier ?: return null
                if (constructor.referenceKind == TlReferenceKind.TYPE_PARAMETER) return null
                TlResultFamilyKey(key, constructor.name, unwrapped.arguments.size)
            }
            else -> null
        }
    }

    companion object {
        private val schemaComparator = compareBy<ValidatedTlSchema>(
            { it.key.kind.ordinal },
            { it.key.layer ?: -1 },
        )

        fun partition(key: TlSchemaKey): TlSchemaPartition {
            val suffix = when (key.kind) {
                TlSchemaKind.CLOUD -> "cloud.layer${key.layer}"
                TlSchemaKind.TRANSPORT -> "transport"
                TlSchemaKind.SECRET -> "secret.layer${key.layer}"
            }
            return TlSchemaPartition(
                schemaKey = key,
                packageName = "$GENERATED_PACKAGE_ROOT.$suffix",
                relativeDirectory = suffix.replace('.', '/'),
            )
        }

        private fun declarationIdentity(key: TlSchemaKey, declaration: TlDeclaration): String =
            "declaration|$key|${declaration.kind}|${declaration.sourceOrder}|${declaration.name}|${declaration.id}"

        private fun familyIdentity(key: TlResultFamilyKey): String =
            "family|${key.schemaKey}|${key.tlName}|${key.genericArity}"

        private fun declarationPackage(
            schema: ValidatedTlSchema,
            declaration: TlDeclaration,
            partition: TlSchemaPartition,
            packages: Map<NamespaceKey, String>,
        ): String = packageForNamespace(partition, schema.key, declaration.namespace, packages)

        private fun packageForNamespace(
            partition: TlSchemaPartition,
            key: TlSchemaKey,
            namespace: List<String>,
            packages: Map<NamespaceKey, String>,
        ): String = if (namespace.isEmpty()) partition.packageName else packages.getValue(NamespaceKey(key, namespace))

        private fun prefixes(namespace: List<String>): List<List<String>> = namespace.indices.map { namespace.take(it + 1) }

        private fun namespacePath(packageName: String, partitionPackage: String, kotlinName: String): String {
            val suffix = packageName.removePrefix(partitionPackage).trim('.').replace('.', '/')
            return if (suffix.isEmpty()) "$kotlinName.kt" else "$suffix/$kotlinName.kt"
        }

        private fun fullRelativePath(partition: TlSchemaPartition, partitionRelativePath: String): String =
            "${GENERATED_PACKAGE_ROOT.replace('.', '/')}/${partition.relativeDirectory}/$partitionRelativePath"

        private fun genericTypeParameters(arity: Int): List<String> = when (arity) {
            0 -> emptyList()
            1 -> listOf("T")
            else -> (1..arity).map { "T$it" }
        }
    }
}
