package org.monogram.tools.tl.codegen.emit.codec

import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile

class TlCodecEmitter {
    fun emit(plan: TlCodecGenerationPlan): List<GeneratedKotlinFile> {
        val resultsByMethod = plan.methodResultCodecs.associateBy { it.schemaKey to it.methodTlName }
        return buildList {
            plan.declarationCodecs.forEach { declaration ->
                val result = if (declaration.declarationKind.name == "FUNCTION") {
                    resultsByMethod.getValue(declaration.schemaKey to declaration.tlName)
                } else {
                    null
                }
                add(
                    GeneratedKotlinFile(
                        relativePath = declaration.relativePath,
                        packageName = declaration.packageName,
                        declarations = buildList {
                            add(declaration.codecName)
                            result?.let { add(it.kotlinName) }
                        },
                        content = emitDeclaration(declaration, result),
                    ),
                )
            }
            plan.familyCodecs.forEach { family ->
                add(
                    GeneratedKotlinFile(
                        relativePath = family.relativePath,
                        packageName = family.packageName,
                        declarations = listOf(family.contract.objectName),
                        content = emitFamilyCodec(family),
                    ),
                )
            }
        }.sortedBy(GeneratedKotlinFile::relativePath).also { files ->
            require(files.map(GeneratedKotlinFile::relativePath).distinct().size == files.size) {
                "Generated codec paths must be unique"
            }
        }
    }

    private fun emitFamilyCodec(plan: TlFamilyCodecPlan): String = buildString {
        appendLine("package ${plan.packageName}")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.runtime.TlCodec")
        appendLine("import org.monogram.mtproto.tl.runtime.TlDecodeContext")
        appendLine("import org.monogram.mtproto.tl.runtime.TlReader")
        appendLine("import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException")
        appendLine("import org.monogram.mtproto.tl.runtime.TlWriter")
        appendLine()
        appendLine("object ${plan.contract.objectName} : TlCodec<${plan.kotlinType}> {")
        appendLine("    override fun read(reader: TlReader, context: TlDecodeContext): ${plan.kotlinType} {")
        appendLine("        val _constructorOffset = reader.absoluteOffset")
        appendLine("        val _constructorId = reader.readInt().toUInt()")
        appendLine("        return when (_constructorId) {")
        plan.constructors.forEach { constructor ->
            appendLine("            ${hex(constructor.constructorId)} -> ${constructor.qualifiedCodecName}.readBare(reader, context)")
        }
        appendLine("            else -> throw TlUnknownConstructorException(context.schema, _constructorId, _constructorOffset)")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    override fun write(writer: TlWriter, value: ${plan.kotlinType}) {")
        appendLine("        when (value) {")
        plan.constructors.forEach { constructor ->
            val type = "${constructor.packageName}.${constructor.kotlinType.substringBefore('<')}"
            appendLine("            is $type -> {")
            appendLine("                writer.writeInt($type.CONSTRUCTOR_ID.toInt())")
            appendLine("                ${constructor.qualifiedCodecName}.writeBare(writer, value)")
            appendLine("            }")
        }
        appendLine("            else -> throw IllegalArgumentException(" +
            quote("No generated constructor codec for family ${plan.tlName} and ID \${value.constructorId}") + ")")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
    }

    private fun emitDeclaration(
        declaration: TlDeclarationCodecPlan,
        result: TlMethodResultCodecPlan?,
    ): String = buildString {
        appendLine("package ${declaration.packageName}")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.runtime.TlBytes")
        appendLine("import org.monogram.mtproto.tl.runtime.TlCodec")
        appendLine("import org.monogram.mtproto.tl.runtime.TlDecodeContext")
        appendLine("import org.monogram.mtproto.tl.runtime.TlDeferredObject")
        appendLine("import org.monogram.mtproto.tl.runtime.TlInt128")
        appendLine("import org.monogram.mtproto.tl.runtime.TlInt256")
        appendLine("import org.monogram.mtproto.tl.runtime.TlMethod")
        appendLine("import org.monogram.mtproto.tl.runtime.TlObject")
        appendLine("import org.monogram.mtproto.tl.runtime.TlReader")
        appendLine("import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException")
        appendLine("import org.monogram.mtproto.tl.runtime.TlWriter")
        appendLine()
        appendDeclarationCodec(declaration)
        result?.let {
            appendLine()
            appendResultCodec(it)
        }
    }

    private fun StringBuilder.appendDeclarationCodec(plan: TlDeclarationCodecPlan) {
        if (plan.typeParameters.isEmpty()) {
            appendLine("object ${plan.codecName} : TlCodec<${plan.kotlinType}> {")
            appendBoxedOverrides(plan, "    ")
            appendLine()
            appendReadBare(plan, "    ", emptyMap())
            appendLine()
            appendWriteBare(plan, "    ", emptyMap())
            appendLine("}")
            return
        }

        val typeParameters = plan.typeParameters.joinToString()
        val parameters = plan.codecParameters.joinToString { "${it.parameterName}: TlCodec<${it.typeParameter}>" }
        val arguments = plan.codecParameters.joinToString { it.parameterName }
        appendLine("object ${plan.codecName} {")
        appendLine("    fun <$typeParameters> bind($parameters): TlCodec<${plan.kotlinType}> =")
        appendLine("        object : TlCodec<${plan.kotlinType}> {")
        appendLine("            override fun read(reader: TlReader, context: TlDecodeContext): ${plan.kotlinType} =")
        appendLine("                readBoxed(reader, context, $arguments)")
        appendLine()
        appendLine("            override fun write(writer: TlWriter, value: ${plan.kotlinType}) =")
        appendLine("                writeBoxed(writer, value, $arguments)")
        appendLine("        }")
        appendLine()
        appendLine("    fun <$typeParameters> readBoxed(")
        appendLine("        reader: TlReader,")
        appendLine("        context: TlDecodeContext,")
        plan.codecParameters.forEach { appendLine("        ${it.parameterName}: TlCodec<${it.typeParameter}>,") }
        appendLine("    ): ${plan.kotlinType} {")
        appendConstructorCheck(plan, "        ")
        appendLine("        return readBare(reader, context, $arguments)")
        appendLine("    }")
        appendLine()
        appendLine("    fun <$typeParameters> writeBoxed(")
        appendLine("        writer: TlWriter,")
        appendLine("        value: ${plan.kotlinType},")
        plan.codecParameters.forEach { appendLine("        ${it.parameterName}: TlCodec<${it.typeParameter}>,") }
        appendLine("    ) {")
        appendLine("        writer.writeInt(${plan.kotlinType.substringBefore('<')}.CONSTRUCTOR_ID.toInt())")
        appendLine("        writeBare(writer, value, $arguments)")
        appendLine("    }")
        appendLine()
        appendReadBare(plan, "    ", plan.codecParameters.associate { it.typeParameter to it.parameterName })
        appendLine()
        appendWriteBare(plan, "    ", plan.codecParameters.associate { it.typeParameter to it.parameterName })
        appendLine("}")
    }

    private fun StringBuilder.appendBoxedOverrides(plan: TlDeclarationCodecPlan, indent: String) {
        appendLine("${indent}override fun read(reader: TlReader, context: TlDecodeContext): ${plan.kotlinType} {")
        appendConstructorCheck(plan, "$indent    ")
        appendLine("$indent    return readBare(reader, context)")
        appendLine("$indent}")
        appendLine()
        appendLine("${indent}override fun write(writer: TlWriter, value: ${plan.kotlinType}) {")
        appendLine("$indent    writer.writeInt(${plan.kotlinType}.CONSTRUCTOR_ID.toInt())")
        appendLine("$indent    writeBare(writer, value)")
        appendLine("$indent}")
    }

    private fun StringBuilder.appendConstructorCheck(plan: TlDeclarationCodecPlan, indent: String) {
        appendLine("${indent}val _constructorOffset = reader.absoluteOffset")
        appendLine("${indent}val _constructorId = reader.readInt().toUInt()")
        appendLine("${indent}if (_constructorId != ${plan.kotlinType.substringBefore('<')}.CONSTRUCTOR_ID) {")
        appendLine("$indent    throw TlUnknownConstructorException(context.schema, _constructorId, _constructorOffset)")
        appendLine("$indent}")
    }

    private fun StringBuilder.appendReadBare(
        plan: TlDeclarationCodecPlan,
        indent: String,
        genericCodecs: Map<String, String>,
    ) {
        val genericPrefix = if (plan.typeParameters.isEmpty()) "" else "<${plan.typeParameters.joinToString()}> "
        val codecParameters = if (plan.codecParameters.isEmpty()) "" else plan.codecParameters.joinToString(
            prefix = ",\n",
            postfix = ",",
        ) { "$indent    ${it.parameterName}: TlCodec<${it.typeParameter}>" }
        appendLine("${indent}fun ${genericPrefix}readBare(")
        appendLine("$indent    reader: TlReader,")
        appendLine("$indent    context: TlDecodeContext$codecParameters")
        appendLine("$indent): ${plan.kotlinType} {")

        val fieldLocals = plan.fields.associate { it.kotlinName to "_field${it.sourceOrder}" }
        val flagsByName = plan.flagWords.associateBy(TlFlagWordPlan::tlName)
        plan.wireMembers.forEach { member ->
            when (member) {
                is TlWireMemberPlan.FlagWord ->
                    appendLine("$indent    val ${member.flag.localName} = reader.readInt().toUInt()")
                is TlWireMemberPlan.Field -> {
                    val field = member.field
                    val local = fieldLocals.getValue(field.kotlinName)
                    val condition = field.condition
                    when {
                        condition == null -> appendLine(
                            "$indent    val $local = ${readExpression(field.codec, "reader", "context", fieldLocals, genericCodecs)}",
                        )
                        field.independentFlag -> {
                            val flag = flagsByName.getValue(condition.flagName)
                            appendLine("$indent    val $local = (${flag.localName} and ${hex(condition.mask)}) != 0u")
                        }
                        else -> {
                            val flag = flagsByName.getValue(condition.flagName)
                            appendLine("$indent    val $local = if ((${flag.localName} and ${hex(condition.mask)}) != 0u) {")
                            appendLine("$indent        ${readExpression(field.codec, "reader", "context", fieldLocals, genericCodecs)}")
                            appendLine("$indent    } else null")
                        }
                    }
                }
            }
        }

        if (plan.fields.isEmpty()) {
            appendLine("$indent    return ${plan.kotlinType.substringBefore('<')}")
        } else {
            appendLine("$indent    return ${plan.kotlinType.substringBefore('<')}(")
            plan.fields.forEach { field ->
                appendLine("$indent        ${field.kotlinName} = ${fieldLocals.getValue(field.kotlinName)},")
            }
            appendLine("$indent    )")
        }
        appendLine("$indent}")
    }

    private fun StringBuilder.appendWriteBare(
        plan: TlDeclarationCodecPlan,
        indent: String,
        genericCodecs: Map<String, String>,
    ) {
        val genericPrefix = if (plan.typeParameters.isEmpty()) "" else "<${plan.typeParameters.joinToString()}> "
        val codecParameters = if (plan.codecParameters.isEmpty()) "" else plan.codecParameters.joinToString(
            prefix = ",\n",
            postfix = ",",
        ) { "$indent    ${it.parameterName}: TlCodec<${it.typeParameter}>" }
        appendLine("${indent}fun ${genericPrefix}writeBare(")
        appendLine("$indent    writer: TlWriter,")
        appendLine("$indent    value: ${plan.kotlinType}$codecParameters")
        appendLine("$indent) {")

        plan.transportChecks.forEach { check ->
            when (check) {
                is TlTransportWriteCheck.ExactDeferredLength -> appendLine(
                    "$indent    require(value.${check.byteCountField} == value.${check.deferredField}.size) { " +
                        quote("Deferred object size must match ${check.byteCountField}") + " }",
                )
            }
        }

        val bitLocals = plan.sharedFlagBits.mapIndexed { index, bit -> bit to "_flagBit$index" }.toMap()
        plan.sharedFlagBits.forEach { bit ->
            val local = bitLocals.getValue(bit)
            val first = bit.fields.first()
            appendLine("$indent    val $local = ${presenceExpression(first)}")
            bit.fields.drop(1).forEach { field ->
                appendLine(
                    "$indent    require(${presenceExpression(field)} == $local) { " +
                        quote("Fields sharing ${bit.flagName}.${bit.bit} must have coherent presence") + " }",
                )
            }
        }
        plan.flagWords.forEach { flag ->
            appendLine("$indent    var ${flag.localName} = 0u")
            plan.sharedFlagBits.filter { it.flagName == flag.tlName }.forEach { bit ->
                appendLine("$indent    if (${bitLocals.getValue(bit)}) ${flag.localName} = ${flag.localName} or ${hex(bit.mask)}")
            }
        }

        val bitByCondition = plan.sharedFlagBits.associateBy { it.flagName to it.bit }
        plan.wireMembers.forEach { member ->
            when (member) {
                is TlWireMemberPlan.FlagWord -> appendLine("$indent    writer.writeInt(${member.flag.localName}.toInt())")
                is TlWireMemberPlan.Field -> {
                    val field = member.field
                    when {
                        field.independentFlag -> Unit
                        field.condition == null -> appendLine(
                            "$indent    ${writeStatement(field.codec, "writer", "value.${field.kotlinName}", genericCodecs)}",
                        )
                        else -> {
                            val bit = bitByCondition.getValue(field.condition.flagName to field.condition.bit)
                            appendLine("$indent    if (${bitLocals.getValue(bit)}) {")
                            appendLine(
                                "$indent        ${writeStatement(field.codec, "writer", "value.${field.kotlinName}!!", genericCodecs)}",
                            )
                            appendLine("$indent    }")
                        }
                    }
                }
            }
        }
        appendLine("$indent}")
    }

    private fun StringBuilder.appendResultCodec(plan: TlMethodResultCodecPlan) {
        if (plan.typeParameters.isEmpty()) {
            appendLine("object ${plan.kotlinName} : TlCodec<${plan.resultType}> {")
            appendLine("    override fun read(reader: TlReader, context: TlDecodeContext): ${plan.resultType} =")
            appendLine("        ${readExpression(plan.codec, "reader", "context", emptyMap(), emptyMap())}")
            appendLine()
            appendLine("    override fun write(writer: TlWriter, value: ${plan.resultType}) {")
            appendLine("        ${writeStatement(plan.codec, "writer", "value", emptyMap())}")
            appendLine("    }")
            appendLine("}")
            return
        }

        val typeParameters = plan.typeParameters.joinToString()
        val parameters = plan.codecParameters.joinToString { "${it.parameterName}: TlCodec<${it.typeParameter}>" }
        val genericCodecs = plan.codecParameters.associate { it.typeParameter to it.parameterName }
        appendLine("object ${plan.kotlinName} {")
        appendLine("    fun <$typeParameters> bind($parameters): TlCodec<${plan.resultType}> =")
        appendLine("        object : TlCodec<${plan.resultType}> {")
        appendLine("            override fun read(reader: TlReader, context: TlDecodeContext): ${plan.resultType} =")
        appendLine("                ${readExpression(plan.codec, "reader", "context", emptyMap(), genericCodecs)}")
        appendLine()
        appendLine("            override fun write(writer: TlWriter, value: ${plan.resultType}) {")
        appendLine("                ${writeStatement(plan.codec, "writer", "value", genericCodecs)}")
        appendLine("            }")
        appendLine("        }")
        appendLine("}")
    }

    private fun readExpression(
        codec: TlValueCodecPlan,
        reader: String,
        context: String,
        fieldLocals: Map<String, String>,
        genericCodecs: Map<String, String>,
    ): String = when (codec) {
        is TlValueCodecPlan.Primitive -> when (codec.kind) {
            TlPrimitiveCodecKind.INT -> "$reader.readInt()"
            TlPrimitiveCodecKind.UINT -> "$reader.readInt().toUInt()"
            TlPrimitiveCodecKind.LONG -> "$reader.readLong()"
            TlPrimitiveCodecKind.DOUBLE -> "$reader.readDouble()"
            TlPrimitiveCodecKind.BOOL -> "$reader.readBool($context)"
            TlPrimitiveCodecKind.BYTES -> "$reader.readBytes($context)"
            TlPrimitiveCodecKind.STRING -> "$reader.readString($context)"
            TlPrimitiveCodecKind.INT128 -> "$reader.readInt128()"
            TlPrimitiveCodecKind.INT256 -> "$reader.readInt256()"
        }
        is TlValueCodecPlan.Generic ->
            "${genericCodecs[codec.typeParameter] ?: codec.codecParameterName}.read($reader, $context.nested())"
        is TlValueCodecPlan.Vector ->
            if (codec.boxed) {
                "$reader.readVector(${codecExpression(codec.element, genericCodecs)}, $context)"
            } else {
                "org.monogram.mtproto.tl.runtime.readBareVector($reader, ${codecExpression(codec.element, genericCodecs)}, $context)"
            }
        is TlValueCodecPlan.NamedBoxed ->
            "${codec.familyCodec.qualifiedName}.read($reader, $context.nested())"
        is TlValueCodecPlan.UnconstrainedObject ->
            "${codec.registry.qualifiedName}.decode($reader.readInt().toUInt(), $reader, $context)"
        is TlValueCodecPlan.NamedBare -> {
            val arguments = codec.codecArguments.joinToString(prefix = if (codec.codecArguments.isEmpty()) "" else ", ") {
                codecExpression(it, genericCodecs)
            }
            "${codec.codecQualifiedName}.readBare($reader, $context.nested()$arguments)"
        }
        is TlValueCodecPlan.Method -> {
            val resultCodec = codec.resultCodecParameterName?.let { name ->
                genericCodecs.values.firstOrNull { it == name } ?: name
            }
            val branches = codec.exactResultBranches.joinToString(" ") { branch ->
                val argument = if (branch.requiresResultCodec) ", $resultCodec" else ""
                val decoded = "${branch.qualifiedCodecName}.readBare($reader, $context.nested()$argument)"
                // JVM erases TlMethod's result type; the exhaustive constructor-ID branch fixes the concrete method.
                val typed = if (resultCodec != null && !branch.requiresResultCodec) {
                    "($decoded as ${codec.kotlinType})"
                } else {
                    decoded
                }
                "${hex(branch.constructorId)} -> $typed;"
            }
            "run { val _methodOffset = $reader.absoluteOffset; val _methodId = $reader.readInt().toUInt(); " +
                "when (_methodId) { $branches else -> throw TlUnknownConstructorException(" +
                "$context.schema, _methodId, _methodOffset) } }"
        }
        is TlValueCodecPlan.DeferredExact -> {
            val byteCount = fieldLocals[codec.byteCountField]
                ?: error("Missing deferred byte-count local ${codec.byteCountField}")
            "$reader.readDeferredObject($byteCount, $context)"
        }
        is TlValueCodecPlan.DeferredRemaining -> "$reader.readRemainingDeferredObject($context)"
    }

    private fun writeStatement(
        codec: TlValueCodecPlan,
        writer: String,
        value: String,
        genericCodecs: Map<String, String>,
    ): String = when (codec) {
        is TlValueCodecPlan.Primitive -> when (codec.kind) {
            TlPrimitiveCodecKind.INT -> "$writer.writeInt($value)"
            TlPrimitiveCodecKind.UINT -> "$writer.writeInt($value.toInt())"
            TlPrimitiveCodecKind.LONG -> "$writer.writeLong($value)"
            TlPrimitiveCodecKind.DOUBLE -> "$writer.writeDouble($value)"
            TlPrimitiveCodecKind.BOOL -> "$writer.writeBool($value)"
            TlPrimitiveCodecKind.BYTES -> "$writer.writeBytes($value)"
            TlPrimitiveCodecKind.STRING -> "$writer.writeString($value)"
            TlPrimitiveCodecKind.INT128 -> "$writer.writeInt128($value)"
            TlPrimitiveCodecKind.INT256 -> "$writer.writeInt256($value)"
        }
        is TlValueCodecPlan.Generic ->
            "${genericCodecs[codec.typeParameter] ?: codec.codecParameterName}.write($writer, $value)"
        is TlValueCodecPlan.Vector -> if (codec.boxed) {
            "$writer.writeVector($value, ${codecExpression(codec.element, genericCodecs)})"
        } else {
            "org.monogram.mtproto.tl.runtime.writeBareVector($writer, $value, ${codecExpression(codec.element, genericCodecs)})"
        }
        is TlValueCodecPlan.NamedBoxed -> "${codec.familyCodec.qualifiedName}.write($writer, $value)"
        is TlValueCodecPlan.UnconstrainedObject -> "${codec.registry.qualifiedName}.encode($writer, $value)"
        is TlValueCodecPlan.NamedBare -> {
            val arguments = codec.codecArguments.joinToString(prefix = if (codec.codecArguments.isEmpty()) "" else ", ") {
                codecExpression(it, genericCodecs)
            }
            if (codec.kotlinType == codec.codecKotlinType || codec.kotlinType == codec.codecKotlinType.substringAfterLast('.')) {
                "${codec.codecQualifiedName}.writeBare($writer, $value$arguments)"
            } else {
                "when ($value) { is ${codec.codecKotlinType} -> ${codec.codecQualifiedName}.writeBare($writer, $value$arguments); " +
                    "else -> throw IllegalArgumentException(${quote("Expected bare ${codec.codecKotlinType}")}) }"
            }
        }
        is TlValueCodecPlan.Method -> {
            val helpers = codec.exactResultBranches.mapIndexedNotNull { index, branch ->
                if (!branch.requiresResultCodec) return@mapIndexedNotNull null
                "fun <R> _writeMethod$index(method: ${branch.qualifiedType}<R>) { " +
                    "${branch.qualifiedCodecName}.writeBare($writer, method, method.resultCodec) }"
            }.joinToString("\n")
            val branches = codec.exactResultBranches.mapIndexed { index, branch ->
                val type = branch.qualifiedType + if (branch.requiresResultCodec) "<*>" else ""
                val write = if (branch.requiresResultCodec) {
                    "_writeMethod$index($value)"
                } else {
                    "${branch.qualifiedCodecName}.writeBare($writer, $value)"
                }
                "is $type -> { $writer.writeInt(${branch.qualifiedType}.CONSTRUCTOR_ID.toInt()); $write };"
            }.joinToString(" ")
            "run {\n$helpers\nwhen ($value) { $branches else -> throw IllegalArgumentException(" +
                quote("Method does not have the required result type") + ") } }"
        }
        is TlValueCodecPlan.DeferredExact,
        is TlValueCodecPlan.DeferredRemaining,
        -> "$writer.writeDeferredObject($value)"
    }

    private fun codecExpression(codec: TlValueCodecPlan, genericCodecs: Map<String, String>): String {
        if (codec is TlValueCodecPlan.Generic) {
            return genericCodecs[codec.typeParameter] ?: codec.codecParameterName
        }
        val read = readExpression(codec, "reader", "context", emptyMap(), genericCodecs)
        val write = writeStatement(codec, "writer", "value", genericCodecs)
        return "object : TlCodec<${codec.kotlinType}> { " +
            "override fun read(reader: TlReader, context: TlDecodeContext): ${codec.kotlinType} = $read; " +
            "override fun write(writer: TlWriter, value: ${codec.kotlinType}) { $write } }"
    }

    private fun presenceExpression(field: TlFlagPresencePlan): String =
        if (field.independentFlag) "value.${field.kotlinName}" else "value.${field.kotlinName} != null"

    private fun hex(value: UInt): String = "0x${value.toString(16).padStart(8, '0')}u"

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
