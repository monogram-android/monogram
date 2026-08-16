package org.monogram.tools.tl.codegen.emit.registry

import org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan
import org.monogram.tools.tl.codegen.emit.declaration.GeneratedKotlinFile

class TlRegistryEmitter {
    fun emit(plan: TlRegistryGenerationPlan): List<GeneratedKotlinFile> = plan.schemas.map { schema ->
        GeneratedKotlinFile(
            relativePath = schema.relativePath,
            packageName = schema.contract.packageName,
            declarations = listOf(schema.contract.objectName),
            content = emitSchema(schema),
        )
    }.sortedBy(GeneratedKotlinFile::relativePath).also { files ->
        require(files.map(GeneratedKotlinFile::relativePath).distinct().size == files.size) {
            "Generated registry paths must be unique"
        }
    }

    private fun emitSchema(plan: TlSchemaRegistryPlan): String = buildString {
        appendLine("package ${plan.contract.packageName}")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.runtime.TlCodec")
        appendLine("import org.monogram.mtproto.tl.runtime.TlConstructorRegistry")
        appendLine("import org.monogram.mtproto.tl.runtime.TlDecodeContext")
        appendLine("import org.monogram.mtproto.tl.runtime.TlMethod")
        appendLine("import org.monogram.mtproto.tl.runtime.TlObject")
        appendLine("import org.monogram.mtproto.tl.runtime.TlReader")
        appendLine("import org.monogram.mtproto.tl.runtime.TlSchemaIdentity")
        appendLine("import org.monogram.mtproto.tl.runtime.TlSchemaKind")
        appendLine("import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException")
        appendLine("import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException")
        appendLine("import org.monogram.mtproto.tl.runtime.TlWriter")
        appendLine()
        appendLine("object ${plan.contract.objectName} : TlConstructorRegistry {")
        appendLine("    override val schema: TlSchemaIdentity = ${schemaIdentity(plan)}")
        appendLine()
        appendDecodeConstructors(plan)
        appendLine()
        appendEncodeConstructors(plan)
        appendLine()
        appendDecodeMethods(plan)
        appendLine()
        appendDecodeGenericMethods(plan)
        appendLine()
        appendEncodeMethods(plan)
        appendConstructorDecodeBuckets(plan)
        appendConstructorEncodeBuckets(plan)
        appendMethodDecodeBuckets(plan)
        appendGenericMethodDecodeBuckets(plan)
        appendMethodEncodeBuckets(plan)
        appendGenericMethodEncoders(plan)
        appendLine("}")
    }

    private fun StringBuilder.appendDecodeConstructors(plan: TlSchemaRegistryPlan) {
        appendLine("    override fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject {")
        appendSchemaCheck("        ")
        appendBucketDispatch(
            declarations = plan.constructors,
            valueExpression = "id shr 24",
            functionPrefix = "decodeConstructors",
            arguments = "id, reader, context",
            indent = "        ",
            fallback = unknownExpression("id"),
        )
        appendLine("    }")
    }

    private fun StringBuilder.appendEncodeConstructors(plan: TlSchemaRegistryPlan) {
        appendLine("    fun encode(writer: TlWriter, value: TlObject) {")
        appendBucketDispatch(
            declarations = plan.constructors,
            valueExpression = "value.constructorId shr 24",
            functionPrefix = "encodeConstructors",
            arguments = "writer, value",
            indent = "        ",
            fallback = unsupportedEncodeExpression("constructor", "value"),
        )
        appendLine("    }")
    }

    private fun StringBuilder.appendDecodeMethods(plan: TlSchemaRegistryPlan) {
        appendLine("    fun decodeMethod(id: UInt, reader: TlReader, context: TlDecodeContext): TlMethod<*> {")
        appendSchemaCheck("        ")
        appendBucketDispatch(
            declarations = plan.methods,
            valueExpression = "id shr 24",
            functionPrefix = "decodeMethods",
            arguments = "id, reader, context",
            indent = "        ",
            fallback = unknownExpression("id"),
        )
        appendLine("    }")
    }

    private fun StringBuilder.appendDecodeGenericMethods(plan: TlSchemaRegistryPlan) {
        val genericMethods = plan.methods.filter { it.typeParameters.isNotEmpty() }
        appendLine("    fun <R> decodeMethod(")
        appendLine("        id: UInt,")
        appendLine("        reader: TlReader,")
        appendLine("        context: TlDecodeContext,")
        appendLine("        resultCodec: TlCodec<R>,")
        appendLine("    ): TlMethod<R> {")
        appendSchemaCheck("        ")
        appendBucketDispatch(
            declarations = genericMethods,
            valueExpression = "id shr 24",
            functionPrefix = "decodeGenericMethods",
            arguments = "id, reader, context, resultCodec",
            indent = "        ",
            fallback = unknownExpression("id"),
        )
        appendLine("    }")
    }

    private fun StringBuilder.appendEncodeMethods(plan: TlSchemaRegistryPlan) {
        appendLine("    fun encodeMethod(writer: TlWriter, value: TlMethod<*>) {")
        appendBucketDispatch(
            declarations = plan.methods,
            valueExpression = "value.constructorId shr 24",
            functionPrefix = "encodeMethods",
            arguments = "writer, value",
            indent = "        ",
            fallback = unsupportedEncodeExpression("method", "value"),
        )
        appendLine("    }")
    }

    private fun StringBuilder.appendConstructorDecodeBuckets(plan: TlSchemaRegistryPlan) {
        buckets(plan.constructors).forEach { (bucket, declarations) ->
            appendLine()
            appendLine("    private fun decodeConstructors${bucketName(bucket)}(")
            appendLine("        id: UInt,")
            appendLine("        reader: TlReader,")
            appendLine("        context: TlDecodeContext,")
            appendLine("    ): TlObject = when (id) {")
            declarations.forEach { declaration ->
                appendLine("        ${hex(declaration.constructorId)} -> ${declaration.qualifiedCodecName}.readBare(reader, context.nested())")
            }
            appendLine("        else -> ${unknownExpression("id")}")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendConstructorEncodeBuckets(plan: TlSchemaRegistryPlan) {
        buckets(plan.constructors).forEach { (bucket, declarations) ->
            appendLine()
            appendLine("    private fun encodeConstructors${bucketName(bucket)}(writer: TlWriter, value: TlObject) {")
            appendLine("        when (value) {")
            declarations.forEach { declaration -> appendEncodeBranch(declaration, genericMethodIndex = null) }
            appendLine("            else -> ${unsupportedEncodeExpression("constructor", "value")}")
            appendLine("        }")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendMethodDecodeBuckets(plan: TlSchemaRegistryPlan) {
        buckets(plan.methods).forEach { (bucket, declarations) ->
            appendLine()
            appendLine("    private fun decodeMethods${bucketName(bucket)}(")
            appendLine("        id: UInt,")
            appendLine("        reader: TlReader,")
            appendLine("        context: TlDecodeContext,")
            appendLine("    ): TlMethod<*> = when (id) {")
            declarations.forEach { declaration ->
                if (declaration.typeParameters.isEmpty()) {
                    appendLine("        ${hex(declaration.constructorId)} -> ${declaration.qualifiedCodecName}.readBare(reader, context.nested())")
                } else {
                    appendLine(
                        "        ${hex(declaration.constructorId)} -> throw IllegalArgumentException(" +
                            quote("Method ${declaration.tlName} requires an explicit result codec") + ")",
                    )
                }
            }
            appendLine("        else -> ${unknownExpression("id")}")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendGenericMethodDecodeBuckets(plan: TlSchemaRegistryPlan) {
        val genericMethods = plan.methods.filter { it.typeParameters.isNotEmpty() }
        buckets(genericMethods).forEach { (bucket, declarations) ->
            appendLine()
            appendLine("    private fun <R> decodeGenericMethods${bucketName(bucket)}(")
            appendLine("        id: UInt,")
            appendLine("        reader: TlReader,")
            appendLine("        context: TlDecodeContext,")
            appendLine("        resultCodec: TlCodec<R>,")
            appendLine("    ): TlMethod<R> = when (id) {")
            declarations.forEach { declaration ->
                appendLine(
                    "        ${hex(declaration.constructorId)} -> " +
                        "${declaration.qualifiedCodecName}.readBare(reader, context.nested(), resultCodec)",
                )
            }
            appendLine("        else -> ${unknownExpression("id")}")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendMethodEncodeBuckets(plan: TlSchemaRegistryPlan) {
        val genericIndexes = plan.methods.filter { it.typeParameters.isNotEmpty() }
            .mapIndexed { index, declaration -> declaration to index }
            .toMap()
        buckets(plan.methods).forEach { (bucket, declarations) ->
            appendLine()
            appendLine("    private fun encodeMethods${bucketName(bucket)}(writer: TlWriter, value: TlMethod<*>) {")
            appendLine("        when (value) {")
            declarations.forEach { declaration -> appendEncodeBranch(declaration, genericIndexes[declaration]) }
            appendLine("            else -> ${unsupportedEncodeExpression("method", "value")}")
            appendLine("        }")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendEncodeBranch(
        declaration: TlDeclarationCodecPlan,
        genericMethodIndex: Int?,
    ) {
        val type = declaration.qualifiedRawType
        if (genericMethodIndex != null) {
            appendLine("            is $type<*> -> encodeGenericMethod$genericMethodIndex(writer, value)")
            return
        }
        appendLine("            is $type -> {")
        appendLine("                writer.writeInt($type.CONSTRUCTOR_ID.toInt())")
        appendLine("                ${declaration.qualifiedCodecName}.writeBare(writer, value)")
        appendLine("            }")
    }

    private fun StringBuilder.appendGenericMethodEncoders(plan: TlSchemaRegistryPlan) {
        plan.methods.filter { it.typeParameters.isNotEmpty() }.forEachIndexed { index, declaration ->
            val type = declaration.qualifiedRawType
            appendLine()
            appendLine("    private fun <R> encodeGenericMethod$index(writer: TlWriter, value: $type<R>) {")
            appendLine("        writer.writeInt($type.CONSTRUCTOR_ID.toInt())")
            appendLine("        ${declaration.qualifiedCodecName}.writeBare(writer, value, value.resultCodec)")
            appendLine("    }")
        }
    }

    private fun StringBuilder.appendBucketDispatch(
        declarations: List<TlDeclarationCodecPlan>,
        valueExpression: String,
        functionPrefix: String,
        arguments: String,
        indent: String,
        fallback: String,
    ) {
        appendLine("${indent}return when ($valueExpression) {")
        buckets(declarations).keys.forEach { bucket ->
            appendLine("$indent    ${hexBucket(bucket)} -> $functionPrefix${bucketName(bucket)}($arguments)")
        }
        appendLine("$indent    else -> $fallback")
        appendLine("$indent}")
    }

    private fun StringBuilder.appendSchemaCheck(indent: String) {
        appendLine("${indent}if (schema != context.schema) {")
        appendLine("$indent    throw TlSchemaMismatchException(")
        appendLine("$indent        expectedSchema = context.schema,")
        appendLine("$indent        actualSchema = schema,")
        appendLine("$indent        absoluteOffset = reader.absoluteOffset,")
        appendLine("$indent    )")
        appendLine("$indent}")
    }

    private fun buckets(declarations: List<TlDeclarationCodecPlan>): Map<UInt, List<TlDeclarationCodecPlan>> =
        declarations.groupBy { it.constructorId shr 24 }.toSortedMap()

    private fun schemaIdentity(plan: TlSchemaRegistryPlan): String =
        "TlSchemaIdentity(TlSchemaKind.${plan.schemaKey.kind.name}, ${plan.schemaKey.layer ?: "null"})"

    private fun unknownExpression(id: String): String =
        "throw TlUnknownConstructorException(context.schema, $id, reader.absoluteOffset)"

    private fun unsupportedEncodeExpression(kind: String, value: String): String =
        "throw IllegalArgumentException(\"No generated $kind codec for schema \$schema and ID \${$value.constructorId}\")"

    private val TlDeclarationCodecPlan.qualifiedRawType: String
        get() = "$packageName.${kotlinType.substringBefore('<')}"

    private fun bucketName(bucket: UInt): String = "_${bucket.toString(16).padStart(2, '0')}"

    private fun hexBucket(bucket: UInt): String = "0x${bucket.toString(16).padStart(2, '0')}u"

    private fun hex(value: UInt): String = "0x${value.toString(16).padStart(8, '0')}u"

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
