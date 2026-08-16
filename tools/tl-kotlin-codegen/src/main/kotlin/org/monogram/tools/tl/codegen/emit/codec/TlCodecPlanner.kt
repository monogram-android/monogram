package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerationResult
import org.monogram.tools.tl.codegen.emit.declaration.TlKotlinTypeRenderer
import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.naming.TlDeclarationSymbol
import org.monogram.tools.tl.codegen.naming.TlFieldSymbol
import org.monogram.tools.tl.codegen.naming.TlResultFamilyKey
import org.monogram.tools.tl.codegen.naming.TlResultFamilySymbol
import org.monogram.tools.tl.codegen.naming.TlSchemaSymbols
import org.monogram.tools.tl.codegen.naming.TlSymbolTable

class TlCodecPlanner {
    fun plan(generation: TlDeclarationGenerationResult): TlCodecGenerationPlan {
        val symbols = generation.symbolTable
        val types = TlKotlinTypeRenderer(symbols)
        val exclusions = symbols.declarations.mapNotNull(::classifyExclusion)
            .sortedWith(exclusionComparator)
        val excludedIdentities = exclusions.map { exclusionIdentity(it) }.toSet()
        val familyContracts = planFamilyContracts(symbols, excludedIdentities)

        val declarations = symbols.declarations
            .filterNot { exclusionIdentity(it) in excludedIdentities }
            .map { declaration -> planDeclaration(declaration, symbols, types, excludedIdentities, familyContracts) }
            .sortedWith(declarationComparator)
        val declarationByIdentity = declarations.associateBy { planIdentity(it) }
        val familyCodecs = symbols.resultFamilies.mapNotNull { family ->
            val contract = familyContracts[family.key] ?: return@mapNotNull null
            TlFamilyCodecPlan(
                schemaKey = family.key.schemaKey,
                tlName = family.key.tlName,
                packageName = family.packageName,
                kotlinType = family.kotlinName,
                contract = contract,
                relativePath = family.relativePath.substringBeforeLast('/') + "/${contract.objectName}.kt",
                constructors = family.constructors.mapNotNull { constructor ->
                    declarationByIdentity[planIdentity(family.key.schemaKey, constructor)]
                }.sortedWith(declarationComparator),
            )
        }.sortedWith(familyComparator)
        val results = symbols.declarations
            .filter { it.source.kind == TlDeclarationKind.FUNCTION }
            .map { declaration -> planResult(declaration, symbols, types, excludedIdentities, familyContracts) }
            .sortedWith(resultComparator)

        validateNamesAndPaths(symbols, declarations, familyCodecs, results)
        validateCoverage(symbols, declarations, results, exclusions)

        val schemaPlans = symbols.schemas.map { schema ->
            val registry = registryContract(schema)
            TlSchemaCodecPlan(
                schemaKey = schema.schema.key,
                registry = registry,
                constructors = schema.declarations
                    .filter { it.source.kind == TlDeclarationKind.CONSTRUCTOR }
                    .mapNotNull { declarationByIdentity[planIdentity(it)] }
                    .sortedWith(declarationComparator),
                methods = schema.declarations
                    .filter { it.source.kind == TlDeclarationKind.FUNCTION }
                    .map { declarationByIdentity.getValue(planIdentity(it)) }
                    .sortedWith(declarationComparator),
                methodResults = results.filter { it.schemaKey == schema.schema.key }.sortedWith(resultComparator),
                exclusions = exclusions.filter { it.schemaKey == schema.schema.key }.sortedWith(exclusionComparator),
            )
        }.sortedWith(compareBy({ it.schemaKey.kind.ordinal }, { it.schemaKey.layer ?: -1 }))

        val coverage = TlCodecCoverageMetadata(
            schemas = schemaPlans.map { schema ->
                TlSchemaCodecCoverage(
                    schemaKey = schema.schemaKey,
                    concreteConstructors = schema.constructors.map {
                        TlCodecCoverageEntry(it.tlName, it.constructorId, it.qualifiedCodecName)
                    },
                    methodResults = schema.methodResults.map {
                        val method = schema.methods.single { method -> method.tlName == it.methodTlName }
                        TlCodecCoverageEntry(it.methodTlName, method.constructorId, it.qualifiedName)
                    },
                    exclusions = schema.exclusions,
                )
            },
        )
        return TlCodecGenerationPlan(
            schemas = schemaPlans.toList(),
            declarationCodecs = declarations.toList(),
            familyCodecs = familyCodecs.toList(),
            methodResultCodecs = results.toList(),
            exclusions = exclusions.toList(),
            coverage = coverage,
        )
    }

    private fun planDeclaration(
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        types: TlKotlinTypeRenderer,
        excludedIdentities: Set<String>,
        familyContracts: Map<TlResultFamilyKey, TlFamilyCodecContract>,
    ): TlDeclarationCodecPlan {
        if (declaration.source.kind == TlDeclarationKind.CONSTRUCTOR && declaration.typeParameters.isNotEmpty()) {
            fail(
                TlCodecPlanningFailure.UNRESOLVED_GENERIC_CODEC,
                declaration,
                "generics",
                "A concrete registry constructor cannot require unbound generic codecs",
            )
        }
        val registry = registryContract(symbols.schema(declaration.schema.key)!!)
        val codecParameters = declaration.typeParameters.values.mapIndexed { index, type ->
            TlGenericCodecParameter(type, "codec$index")
        }
        val genericCodecs = codecParameters.associate { it.typeParameter to it.parameterName }
        val fields = declaration.fields.map { field ->
            planField(field, declaration, symbols, types, registry, genericCodecs, excludedIdentities, familyContracts)
        }.sortedBy(TlFieldCodecPlan::sourceOrder)
        val fieldsByOrder = fields.associateBy(TlFieldCodecPlan::sourceOrder)
        val flags = planFlags(declaration)
        val flagsByOrder = flags.associateBy(TlFlagWordPlan::sourceOrder)
        val flagByName = flags.associateBy(TlFlagWordPlan::tlName)

        fields.forEach { field ->
            field.condition?.let { condition ->
                val flag = flagByName[condition.flagName] ?: fail(
                    TlCodecPlanningFailure.INVALID_FLAG_LAYOUT,
                    declaration,
                    field.kotlinName,
                    "Condition references absent flag word ${condition.flagName}",
                )
                if (condition.mask and flag.optionalMask != condition.mask) {
                    fail(
                        TlCodecPlanningFailure.INVALID_FLAG_LAYOUT,
                        declaration,
                        field.kotlinName,
                        "Condition mask is absent from flag metadata",
                    )
                }
            }
        }
        flags.forEach { flag ->
            val calculated = fields.mapNotNull { field ->
                field.condition?.takeIf { it.flagName == flag.tlName }?.mask
            }.fold(0u, UInt::or)
            if (calculated != flag.optionalMask) {
                fail(
                    TlCodecPlanningFailure.INVALID_FLAG_LAYOUT,
                    declaration,
                    flag.tlName,
                    "Flag metadata mask ${flag.optionalMask} differs from field mask $calculated",
                )
            }
        }

        val members = declaration.source.parameters.mapNotNull { parameter ->
            when (val value = parameter.value) {
                is TlArgumentValue.Repetition -> fail(
                    TlCodecPlanningFailure.UNSUPPORTED_REPETITION,
                    declaration,
                    parameter.name ?: "parameter${parameter.sourceOrder}",
                    "Non-builtin repetition cannot be emitted through the frozen runtime ABI",
                )
                is TlArgumentValue.Type -> when {
                    value.expression == TlExpression.Hash -> TlWireMemberPlan.FlagWord(
                        flagsByOrder[parameter.sourceOrder] ?: fail(
                            TlCodecPlanningFailure.INVALID_FLAG_LAYOUT,
                            declaration,
                            parameter.name,
                            "Missing flag plan",
                        ),
                        parameter.sourceOrder,
                    )
                    parameter.implicit || isNaturalBinding(value.expression) -> null
                    else -> TlWireMemberPlan.Field(
                        fieldsByOrder[parameter.sourceOrder] ?: fail(
                            TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
                            declaration,
                            parameter.name,
                            "Visible field has no codec plan",
                        ),
                        parameter.sourceOrder,
                    )
                }
            }
        }
        val sharedBits = fields.mapNotNull { field -> field.condition?.let { it to field } }
            .groupBy { (condition, _) -> condition.flagName to condition.bit }
            .map { (key, entries) ->
                TlSharedFlagBitPlan(
                    flagName = key.first,
                    bit = key.second,
                    mask = entries.first().first.mask,
                    fields = entries.sortedBy { (_, field) -> field.sourceOrder }.map { (_, field) ->
                        TlFlagPresencePlan(field.kotlinName, field.independentFlag)
                    },
                )
            }.sortedWith(compareBy(TlSharedFlagBitPlan::flagName, TlSharedFlagBitPlan::bit))
        val transportChecks = fields.mapNotNull { field ->
            val codec = field.codec as? TlValueCodecPlan.DeferredExact ?: return@mapNotNull null
            val count = fields.singleOrNull { it.kotlinName == codec.byteCountField }
                ?: fail(
                    TlCodecPlanningFailure.INVALID_TRANSPORT_POLICY,
                    declaration,
                    field.kotlinName,
                    "Exact deferred byte-count field ${codec.byteCountField} is absent",
                )
            if (count.kotlinType != "Int" || count.sourceOrder >= field.sourceOrder) {
                fail(
                    TlCodecPlanningFailure.INVALID_TRANSPORT_POLICY,
                    declaration,
                    field.kotlinName,
                    "Exact deferred byte-count must be a preceding Int field",
                )
            }
            TlTransportWriteCheck.ExactDeferredLength(count.kotlinName, field.kotlinName)
        }
        val codecName = "${declaration.kotlinName}Codec"
        return TlDeclarationCodecPlan(
            schemaKey = declaration.schema.key,
            declarationKind = declaration.source.kind,
            tlName = declaration.source.name,
            constructorId = declaration.source.id,
            constructorIdHex = declaration.source.idHex,
            packageName = declaration.packageName,
            kotlinType = declaration.kotlinName + declaration.typeParameters.values.joinToString(
                prefix = if (declaration.typeParameters.isEmpty()) "" else "<",
                postfix = if (declaration.typeParameters.isEmpty()) "" else ">",
            ),
            codecName = codecName,
            relativePath = declaration.relativePath.substringBeforeLast('/') + "/$codecName.kt",
            typeParameters = declaration.typeParameters.values.toList(),
            codecParameters = codecParameters,
            registry = registry,
            wireMembers = members.sortedBy(TlWireMemberPlan::sourceOrder),
            fields = fields,
            flagWords = flags,
            sharedFlagBits = sharedBits,
            transportChecks = transportChecks,
        )
    }

    private fun planResult(
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        types: TlKotlinTypeRenderer,
        excludedIdentities: Set<String>,
        familyContracts: Map<TlResultFamilyKey, TlFamilyCodecContract>,
    ): TlMethodResultCodecPlan {
        val binding = declaration.resultCodecBinding ?: fail(
            TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
            declaration,
            "result",
            "Method has no result codec binding symbol",
        )
        val codecParameters = binding.genericTypeParameters.mapIndexed { index, type ->
            TlGenericCodecParameter(type, "codec$index")
        }
        val genericCodecs = codecParameters.associate { it.typeParameter to it.parameterName }
        return TlMethodResultCodecPlan(
            schemaKey = declaration.schema.key,
            methodTlName = declaration.source.name,
            packageName = binding.packageName,
            kotlinName = binding.kotlinName,
            resultType = types.renderResult(binding.resultExpression, declaration),
            typeParameters = binding.genericTypeParameters.toList(),
            codecParameters = codecParameters,
            codec = planExpression(
                binding.resultExpression,
                declaration,
                symbols,
                types,
                registryContract(symbols.schema(declaration.schema.key)!!),
                genericCodecs,
                excludedIdentities,
                familyContracts,
                "result",
                allowObject = true,
            ),
        )
    }

    private fun planField(
        field: TlFieldSymbol,
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        types: TlKotlinTypeRenderer,
        registry: TlRegistryContract,
        genericCodecs: Map<String, String>,
        excludedIdentities: Set<String>,
        familyContracts: Map<TlResultFamilyKey, TlFamilyCodecContract>,
    ): TlFieldCodecPlan {
        if (field.repetition != null) {
            fail(
                TlCodecPlanningFailure.UNSUPPORTED_REPETITION,
                declaration,
                field.kotlinName,
                "Non-builtin repetition cannot be emitted through the frozen runtime ABI",
            )
        }
        val codec = when (val policy = field.transportPolicy) {
            is TlTransportPolicy.ExactLengthDeferred -> {
                val countField = declaration.fields.singleOrNull { it.source.name == policy.byteCountParameter }
                    ?: fail(
                        TlCodecPlanningFailure.INVALID_TRANSPORT_POLICY,
                        declaration,
                        field.kotlinName,
                        "Unknown exact deferred byte-count parameter ${policy.byteCountParameter}",
                    )
                TlValueCodecPlan.DeferredExact(countField.kotlinName)
            }
            TlTransportPolicy.RemainingDeferred -> TlValueCodecPlan.DeferredRemaining()
            TlTransportPolicy.GzipPackedBytes -> {
                val expression = field.expression
                    ?: fail(TlCodecPlanningFailure.INVALID_TRANSPORT_POLICY, declaration, field.kotlinName, "Missing gzip bytes expression")
                val planned = planExpression(
                    expression,
                    declaration,
                    symbols,
                    types,
                    registry,
                    genericCodecs,
                    excludedIdentities,
                    familyContracts,
                    field.kotlinName,
                    allowObject = false,
                )
                if (planned !is TlValueCodecPlan.Primitive || planned.kind != TlPrimitiveCodecKind.BYTES) {
                    fail(TlCodecPlanningFailure.INVALID_TRANSPORT_POLICY, declaration, field.kotlinName, "gzip_packed data must use ordinary bytes")
                }
                planned
            }
            TlTransportPolicy.None -> {
                val expression = field.expression
                    ?: fail(TlCodecPlanningFailure.UNSUPPORTED_EXPRESSION, declaration, field.kotlinName, "Field has no expression")
                if (field.independentFlag) {
                    TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.BOOL, "Boolean")
                } else if (field.functional) {
                    val unwrapped = if (expression is TlExpression.Bang) expression.inner else expression
                    val typeParameter = unwrapped as? TlExpression.Identifier
                    val resultCodec = typeParameter
                        ?.takeIf { it.referenceKind == TlReferenceKind.TYPE_PARAMETER }
                        ?.let { declaration.typeParameters[it.name] }
                        ?.let(genericCodecs::get)
                    TlValueCodecPlan.Method(
                        registry = registry,
                        resultCodecParameterName = resultCodec,
                        exactResultBranches = if (resultCodec == null) {
                            exactMethodBranches(unwrapped, declaration, symbols)
                        } else {
                            genericMethodBranches(declaration, symbols)
                        },
                        kotlinType = types.renderField(field, declaration).removeSuffix("?"),
                    )
                } else {
                    planExpression(
                        expression,
                        declaration,
                        symbols,
                        types,
                        registry,
                        genericCodecs,
                        excludedIdentities,
                        familyContracts,
                        field.kotlinName,
                        allowObject = false,
                    )
                }
            }
        }
        val condition = field.flagVariable?.let { variable ->
            val bit = field.flagBit ?: fail(
                TlCodecPlanningFailure.INVALID_FLAG_LAYOUT,
                declaration,
                field.kotlinName,
                "Conditional field has no flag bit",
            )
            TlFlagConditionPlan(variable, bit, 1u shl bit)
        }
        return TlFieldCodecPlan(
            sourceOrder = field.source.sourceOrder,
            kotlinName = field.kotlinName,
            kotlinType = types.renderField(field, declaration).removeSuffix("?"),
            codec = codec,
            condition = condition,
            independentFlag = field.independentFlag,
        )
    }

    private fun planExpression(
        expression: TlExpression,
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        types: TlKotlinTypeRenderer,
        registry: TlRegistryContract,
        genericCodecs: Map<String, String>,
        excludedIdentities: Set<String>,
        familyContracts: Map<TlResultFamilyKey, TlFamilyCodecContract>,
        path: String,
        allowObject: Boolean,
    ): TlValueCodecPlan = when (expression) {
        is TlExpression.Identifier -> when (expression.referenceKind) {
            TlReferenceKind.PRIMITIVE -> primitive(expression.name, declaration, path)
            TlReferenceKind.TYPE_PARAMETER -> {
                val type = declaration.typeParameters[expression.name]
                    ?: fail(TlCodecPlanningFailure.UNRESOLVED_GENERIC_CODEC, declaration, path, "Unknown type parameter ${expression.name}")
                val codec = genericCodecs[type]
                    ?: fail(TlCodecPlanningFailure.UNRESOLVED_GENERIC_CODEC, declaration, path, "No codec binding for $type")
                TlValueCodecPlan.Generic(type, codec, type)
            }
            TlReferenceKind.NATURAL_PARAMETER -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.UINT, "UInt")
            TlReferenceKind.OBJECT -> if (allowObject) {
                TlValueCodecPlan.UnconstrainedObject(registry)
            } else {
                fail(TlCodecPlanningFailure.UNSUPPORTED_OBJECT_POSITION, declaration, path, "Object requires an exact transport policy")
            }
            TlReferenceKind.NAMED_BOXED -> TlValueCodecPlan.NamedBoxed(
                familyCodec(expression.name, 0, declaration, familyContracts, path),
                types.render(expression, declaration),
            )
            TlReferenceKind.NAMED_BARE -> namedBare(
                expression,
                emptyList(),
                declaration,
                symbols,
                types.render(expression, declaration),
                excludedIdentities,
                path,
            )
        }
        is TlExpression.Application -> if (expression.applicationKind == TlApplicationKind.VECTOR) {
            val element = expression.arguments.singleOrNull()
                ?: fail(TlCodecPlanningFailure.UNSUPPORTED_EXPRESSION, declaration, path, "Vector requires exactly one element")
            TlValueCodecPlan.Vector(
                planExpression(
                    element,
                    declaration,
                    symbols,
                    types,
                    registry,
                    genericCodecs,
                    excludedIdentities,
                    familyContracts,
                    "$path.element",
                    allowObject = false,
                ),
                types.render(expression, declaration),
            )
        } else {
            val head = expression.constructor as? TlExpression.Identifier
                ?: fail(TlCodecPlanningFailure.UNSUPPORTED_EXPRESSION, declaration, path, "Boxed application has no named head")
            TlValueCodecPlan.NamedBoxed(
                familyCodec(head.name, expression.arguments.size, declaration, familyContracts, path),
                types.render(expression, declaration),
            )
        }
        is TlExpression.Bare -> {
            val inner = expression.inner
            val arguments = (inner as? TlExpression.Application)?.arguments.orEmpty().mapIndexed { index, argument ->
                planExpression(
                    argument,
                    declaration,
                    symbols,
                    types,
                    registry,
                    genericCodecs,
                    excludedIdentities,
                    familyContracts,
                    "$path.argument$index",
                    allowObject = false,
                )
            }
            val head = when (inner) {
                is TlExpression.Identifier -> inner
                is TlExpression.Application -> inner.constructor as? TlExpression.Identifier
                else -> null
            } ?: fail(TlCodecPlanningFailure.UNRESOLVED_BARE_CODEC, declaration, path, "Bare expression has no named head")
            namedBare(head, arguments, declaration, symbols, types.render(expression, declaration), excludedIdentities, path)
        }
        is TlExpression.Bang -> {
            val resultCodec = when (val inner = expression.inner) {
                is TlExpression.Identifier -> if (inner.referenceKind == TlReferenceKind.TYPE_PARAMETER) {
                    val type = declaration.typeParameters[inner.name]
                        ?: fail(TlCodecPlanningFailure.UNRESOLVED_GENERIC_CODEC, declaration, path, "Unknown method result type ${inner.name}")
                    genericCodecs[type]
                        ?: fail(TlCodecPlanningFailure.UNRESOLVED_GENERIC_CODEC, declaration, path, "No method result codec for $type")
                } else {
                    null
                }
                else -> null
            }
            TlValueCodecPlan.Method(
                registry = registry,
                resultCodecParameterName = resultCodec,
                exactResultBranches = if (resultCodec == null) {
                    exactMethodBranches(expression.inner, declaration, symbols)
                } else {
                    genericMethodBranches(declaration, symbols)
                },
                kotlinType = types.render(expression, declaration),
            )
        }
        TlExpression.Hash -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.UINT, "UInt")
        is TlExpression.Natural,
        is TlExpression.Add,
        -> fail(TlCodecPlanningFailure.UNSUPPORTED_EXPRESSION, declaration, path, "Natural expressions are not standalone wire values")
    }

    private fun namedBare(
        head: TlExpression.Identifier,
        arguments: List<TlValueCodecPlan>,
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        kotlinType: String,
        excludedIdentities: Set<String>,
        path: String,
    ): TlValueCodecPlan.NamedBare {
        val target = when (head.referenceKind) {
            TlReferenceKind.NAMED_BARE -> symbols.constructor(declaration.schema.key, head.name)
            TlReferenceKind.NAMED_BOXED -> {
                val family = symbols.resultFamily(declaration.schema.key, head.name, arguments.size)
                resolveSingleFamilyConstructor(family, declaration, symbols, path)
            }
            else -> null
        } ?: fail(
            TlCodecPlanningFailure.UNRESOLVED_BARE_CODEC,
            declaration,
            path,
            "No concrete constructor for bare type ${head.name}",
        )
        if (exclusionIdentity(target) in excludedIdentities) {
            fail(
                TlCodecPlanningFailure.UNRESOLVED_BARE_CODEC,
                declaration,
                path,
                "Bare type ${head.name} resolves to a D-023 runtime pseudo-constructor",
            )
        }
        val codecTypeArguments = arguments.joinToString(
            prefix = if (arguments.isEmpty()) "" else "<",
            postfix = if (arguments.isEmpty()) "" else ">",
        ) { it.kotlinType }
        return TlValueCodecPlan.NamedBare(
            codecQualifiedName = "${target.packageName}.${target.kotlinName}Codec",
            codecKotlinType = "${target.packageName}.${target.kotlinName}$codecTypeArguments",
            codecArguments = arguments.toList(),
            kotlinType = kotlinType,
        )
    }

    private fun resolveSingleFamilyConstructor(
        family: TlResultFamilySymbol?,
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
        path: String,
    ): TlDeclarationSymbol? {
        family ?: return null
        if (family.constructors.size != 1) {
            fail(
                TlCodecPlanningFailure.UNRESOLVED_BARE_CODEC,
                declaration,
                path,
                "Bare family ${family.key.tlName} has ${family.constructors.size} constructors",
            )
        }
        val source = family.constructors.single()
        return symbols.declarations.singleOrNull {
            it.schema.key == declaration.schema.key &&
                it.source.kind == TlDeclarationKind.CONSTRUCTOR &&
                it.source.name == source.name &&
                it.source.id == source.id
        }
    }

    private fun exactMethodBranches(
        resultExpression: TlExpression,
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
    ): List<TlMethodDispatchBranch> {
        val branches = symbols.declarations.filter {
            it.schema.key == declaration.schema.key &&
                it.source.kind == TlDeclarationKind.FUNCTION &&
                it.source.result == resultExpression &&
                it.typeParameters.isEmpty()
        }.map {
            TlMethodDispatchBranch(
                constructorId = it.source.id,
                qualifiedType = "${it.packageName}.${it.kotlinName}",
                qualifiedCodecName = "${it.packageName}.${it.kotlinName}Codec",
                requiresResultCodec = false,
            )
        }.sortedBy { it.constructorId.toLong() }
        if (branches.isEmpty()) {
            fail(
                TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
                declaration,
                "method",
                "No methods have the required exact result expression",
            )
        }
        return branches
    }

    private fun genericMethodBranches(
        declaration: TlDeclarationSymbol,
        symbols: TlSymbolTable,
    ): List<TlMethodDispatchBranch> {
        val branches = symbols.declarations.filter {
            it.schema.key == declaration.schema.key &&
                it.source.kind == TlDeclarationKind.FUNCTION
        }.map {
            TlMethodDispatchBranch(
                constructorId = it.source.id,
                qualifiedType = "${it.packageName}.${it.kotlinName}",
                qualifiedCodecName = "${it.packageName}.${it.kotlinName}Codec",
                requiresResultCodec = it.typeParameters.isNotEmpty(),
            )
        }.sortedBy { it.constructorId.toLong() }
        if (branches.isEmpty()) {
            fail(
                TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
                declaration,
                "method",
                "Schema has no generic methods for the explicit result codec",
            )
        }
        return branches
    }

    private fun planFamilyContracts(
        symbols: TlSymbolTable,
        excludedIdentities: Set<String>,
    ): Map<TlResultFamilyKey, TlFamilyCodecContract> = symbols.resultFamilies.mapNotNull { family ->
        val concrete = family.constructors.filterNot { planIdentity(family.key.schemaKey, it) in excludedIdentities }
        if (concrete.isEmpty()) return@mapNotNull null
        family.key to TlFamilyCodecContract(family.packageName, "${family.kotlinName}BoxedCodec")
    }.toMap()

    private fun familyCodec(
        tlName: String,
        genericArity: Int,
        declaration: TlDeclarationSymbol,
        familyContracts: Map<TlResultFamilyKey, TlFamilyCodecContract>,
        path: String,
    ): TlFamilyCodecContract = familyContracts[TlResultFamilyKey(declaration.schema.key, tlName, genericArity)]
        ?: fail(
            TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
            declaration,
            path,
            "No typed boxed codec for result family $tlName/$genericArity",
        )

    private fun planFlags(declaration: TlDeclarationSymbol): List<TlFlagWordPlan> =
        declaration.source.flagWords.sortedBy { it.sourceOrder }.mapIndexed { index, flag ->
            TlFlagWordPlan(flag.sourceOrder, flag.name, "_flags$index", flag.optionalMask)
        }

    private fun classifyExclusion(declaration: TlDeclarationSymbol): TlCodecExclusion? {
        val source = declaration.source
        val identity = D023ExclusionIdentity(declaration.schema.key, source.kind, source.name, source.id)
        D023_EXCLUSIONS[identity]?.let { reason ->
            return TlCodecExclusion(declaration.schema.key, source.kind, source.name, source.id, reason)
        }
        if (source.name in D023_EXCLUSION_NAMES) {
            fail(
                TlCodecPlanningFailure.UNSUPPORTED_BUILTIN,
                declaration,
                null,
                "Declaration resembles a D-023 pseudo-constructor but is not an approved pinned identity",
            )
        }
        if (source.builtin) {
            fail(
                TlCodecPlanningFailure.UNSUPPORTED_BUILTIN,
                declaration,
                null,
                "Builtin declaration is not an approved pinned D-023 identity",
            )
        }
        if (source.parameters.any { containsRepetition(it.value) }) {
            fail(
                TlCodecPlanningFailure.UNSUPPORTED_REPETITION,
                declaration,
                null,
                "Non-builtin repetition cannot be emitted through the frozen runtime ABI",
            )
        }
        return null
    }

    private fun validateNamesAndPaths(
        symbols: TlSymbolTable,
        declarations: List<TlDeclarationCodecPlan>,
        families: List<TlFamilyCodecPlan>,
        results: List<TlMethodResultCodecPlan>,
    ) {
        val existing = buildMap<String, MutableSet<String>> {
            symbols.declarations.forEach { getOrPut(it.packageName) { mutableSetOf() }.add(it.kotlinName) }
            symbols.resultFamilies.forEach { getOrPut(it.packageName) { mutableSetOf() }.add(it.kotlinName) }
        }
        val emitted = mutableMapOf<String, MutableSet<String>>()
        fun claim(packageName: String, name: String, declarationName: String) {
            if (name in existing[packageName].orEmpty() || !emitted.getOrPut(packageName) { mutableSetOf() }.add(name)) {
                throw TlCodecPlanningException(
                    TlCodecPlanningFailure.NAME_COLLISION,
                    null,
                    declarationName,
                    "$packageName.$name",
                    "Codec emission names collide; the codec lane does not reallocate declaration names",
                )
            }
        }
        declarations.forEach { claim(it.packageName, it.codecName, it.tlName) }
        families.forEach { claim(it.packageName, it.contract.objectName, it.tlName) }
        results.forEach { claim(it.packageName, it.kotlinName, it.methodTlName) }
        val outputPaths = declarations.map(TlDeclarationCodecPlan::relativePath) + families.map(TlFamilyCodecPlan::relativePath)
        val duplicatePath = outputPaths.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicatePath != null) {
            throw TlCodecPlanningException(
                TlCodecPlanningFailure.DUPLICATE_OUTPUT_PATH,
                null,
                null,
                duplicatePath.key,
                "Multiple declaration codecs resolve to one output path",
            )
        }
    }

    private fun validateCoverage(
        symbols: TlSymbolTable,
        declarations: List<TlDeclarationCodecPlan>,
        results: List<TlMethodResultCodecPlan>,
        exclusions: List<TlCodecExclusion>,
    ) {
        val constructors = symbols.declarations.count { it.source.kind == TlDeclarationKind.CONSTRUCTOR }
        val methods = symbols.declarations.count { it.source.kind == TlDeclarationKind.FUNCTION }
        val plannedConstructors = declarations.count { it.declarationKind == TlDeclarationKind.CONSTRUCTOR }
        val plannedMethods = declarations.count { it.declarationKind == TlDeclarationKind.FUNCTION }
        if (plannedConstructors + exclusions.size != constructors || plannedMethods != methods || results.size != methods) {
            throw TlCodecPlanningException(
                TlCodecPlanningFailure.INCOMPLETE_COVERAGE,
                null,
                null,
                null,
                "Expected constructors=$constructors methods=$methods; planned constructors=$plannedConstructors, " +
                    "excluded=${exclusions.size}, methods=$plannedMethods, results=${results.size}",
            )
        }
    }

    private fun primitive(name: String, declaration: TlDeclarationSymbol, path: String): TlValueCodecPlan.Primitive = when (name) {
        "int" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.INT, "Int")
        "long" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.LONG, "Long")
        "double" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.DOUBLE, "Double")
        "Bool" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.BOOL, "Boolean")
        "bytes" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.BYTES, "TlBytes")
        "string" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.STRING, "String")
        "int128" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.INT128, "TlInt128")
        "int256" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.INT256, "TlInt256")
        "true" -> TlValueCodecPlan.Primitive(TlPrimitiveCodecKind.BOOL, "Boolean")
        else -> fail(TlCodecPlanningFailure.UNSUPPORTED_EXPRESSION, declaration, path, "Unsupported primitive $name")
    }

    private fun registryContract(schema: TlSchemaSymbols): TlRegistryContract {
        val objectName = when (schema.schema.key.kind) {
            TlSchemaKind.CLOUD -> "CloudLayer${schema.schema.key.layer}ConstructorRegistry"
            TlSchemaKind.TRANSPORT -> "TransportConstructorRegistry"
            TlSchemaKind.SECRET -> "SecretLayer${schema.schema.key.layer}ConstructorRegistry"
        }
        return TlRegistryContract(schema.partition.registryPackageName, objectName)
    }

    private fun containsRepetition(value: TlArgumentValue): Boolean = when (value) {
        is TlArgumentValue.Type -> false
        is TlArgumentValue.Repetition -> true
    }

    private fun isNaturalBinding(expression: TlExpression): Boolean =
        expression is TlExpression.Identifier && expression.referenceKind == TlReferenceKind.NATURAL_PARAMETER

    private fun exclusionIdentity(exclusion: TlCodecExclusion): String =
        "${exclusion.schemaKey}|${exclusion.declarationKind}|${exclusion.tlName}|${exclusion.constructorId}"

    private fun exclusionIdentity(declaration: TlDeclarationSymbol): String =
        "${declaration.schema.key}|${declaration.source.kind}|${declaration.source.name}|${declaration.source.id}"

    private fun planIdentity(declaration: TlDeclarationSymbol): String =
        "${declaration.schema.key}|${declaration.source.kind}|${declaration.source.name}|${declaration.source.id}"

    private fun planIdentity(declaration: TlDeclarationCodecPlan): String =
        "${declaration.schemaKey}|${declaration.declarationKind}|${declaration.tlName}|${declaration.constructorId}"

    private fun planIdentity(schemaKey: TlSchemaKey, declaration: TlDeclaration): String =
        "$schemaKey|${declaration.kind}|${declaration.name}|${declaration.id}"

    private fun fail(
        reason: TlCodecPlanningFailure,
        declaration: TlDeclarationSymbol,
        path: String?,
        detail: String,
    ): Nothing = throw TlCodecPlanningException(reason, declaration.schema.key, declaration.source.name, path, detail)

    private val declarationComparator = compareBy<TlDeclarationCodecPlan>(
        { it.schemaKey.kind.ordinal },
        { it.schemaKey.layer ?: -1 },
        TlDeclarationCodecPlan::relativePath,
        { it.declarationKind.ordinal },
        TlDeclarationCodecPlan::tlName,
        { it.constructorId.toLong() },
    )
    private val familyComparator = compareBy<TlFamilyCodecPlan>(
        { it.schemaKey.kind.ordinal },
        { it.schemaKey.layer ?: -1 },
        TlFamilyCodecPlan::relativePath,
        TlFamilyCodecPlan::tlName,
    )
    private val resultComparator = compareBy<TlMethodResultCodecPlan>(
        { it.schemaKey.kind.ordinal },
        { it.schemaKey.layer ?: -1 },
        TlMethodResultCodecPlan::packageName,
        TlMethodResultCodecPlan::kotlinName,
        TlMethodResultCodecPlan::methodTlName,
    )
    private val exclusionComparator = compareBy<TlCodecExclusion>(
        { it.schemaKey.kind.ordinal },
        { it.schemaKey.layer ?: -1 },
        { it.declarationKind.ordinal },
        TlCodecExclusion::tlName,
        { it.constructorId.toLong() },
    )
}

private data class D023ExclusionIdentity(
    val schemaKey: TlSchemaKey,
    val declarationKind: TlDeclarationKind,
    val tlName: String,
    val constructorId: UInt,
)

private val D023_EXCLUSIONS: Map<D023ExclusionIdentity, TlCodecExclusionReason> = mapOf(
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.CLOUD, 223), TlDeclarationKind.CONSTRUCTOR, "vector", 0x1cb5c415u) to
        TlCodecExclusionReason.BUILTIN_VECTOR,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "int", 0xa8509bdau) to
        TlCodecExclusionReason.BUILTIN_PRIMITIVE,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "long", 0x22076cbau) to
        TlCodecExclusionReason.BUILTIN_PRIMITIVE,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "double", 0x2210c154u) to
        TlCodecExclusionReason.BUILTIN_PRIMITIVE,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "string", 0xb5286e24u) to
        TlCodecExclusionReason.BUILTIN_PRIMITIVE,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "vector", 0x1cb5c415u) to
        TlCodecExclusionReason.BUILTIN_VECTOR,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "int128", 0x84ccf7b7u) to
        TlCodecExclusionReason.FORMAL_REPETITION,
    D023ExclusionIdentity(TlSchemaKey(TlSchemaKind.TRANSPORT, null), TlDeclarationKind.CONSTRUCTOR, "int256", 0x7bedeb5bu) to
        TlCodecExclusionReason.FORMAL_REPETITION,
)

private val D023_EXCLUSION_NAMES: Set<String> = D023_EXCLUSIONS.keys.mapTo(mutableSetOf()) { it.tlName }
