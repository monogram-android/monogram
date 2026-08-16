package org.monogram.tools.tl.codegen.emit.declaration

import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.naming.TlDeclarationSymbol
import org.monogram.tools.tl.codegen.naming.TlFieldSymbol
import org.monogram.tools.tl.codegen.naming.TlResultFamilySymbol
import org.monogram.tools.tl.codegen.naming.TlSymbolTable

class TlDeclarationEmitter(
    symbols: TlSymbolTable,
) {
    private val types = TlKotlinTypeRenderer(symbols)

    fun emitFamily(symbol: TlResultFamilySymbol): String = buildString {
        appendLine("package ${symbol.packageName}")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.runtime.TlObject")
        appendLine()
        appendKDoc(
            description = "Result family for TL type `${symbol.key.tlName}`.",
            metadata = listOf("Schema: ${schemaLabel(symbol.schema.key.kind, symbol.schema.key.layer)}"),
        )
        append(if (symbol.sealed) "sealed interface " else "interface ")
        append(symbol.kotlinName)
        append(symbol.typeParameters.joinToString(prefix = if (symbol.typeParameters.isEmpty()) "" else "<", postfix = if (symbol.typeParameters.isEmpty()) "" else ">"))
        appendLine(" : TlObject")
    }

    fun emitDeclaration(symbol: TlDeclarationSymbol): String = buildString {
        appendLine("package ${symbol.packageName}")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.runtime.TlBytes")
        appendLine("import org.monogram.mtproto.tl.runtime.TlCodec")
        appendLine("import org.monogram.mtproto.tl.runtime.TlDeferredObject")
        appendLine("import org.monogram.mtproto.tl.runtime.TlInt128")
        appendLine("import org.monogram.mtproto.tl.runtime.TlInt256")
        appendLine("import org.monogram.mtproto.tl.runtime.TlMethod")
        appendLine("import org.monogram.mtproto.tl.runtime.TlObject")
        appendLine("import org.monogram.mtproto.tl.runtime.TlSchemaKind")
        appendLine()
        appendDeclarationKDoc(symbol)
        appendDeclarationBody(symbol)
    }

    private fun StringBuilder.appendDeclarationBody(symbol: TlDeclarationSymbol) {
        val typeParameters = symbol.typeParameters.values.joinToString(
            prefix = if (symbol.typeParameters.isEmpty()) "" else "<",
            postfix = if (symbol.typeParameters.isEmpty()) "" else ">",
        )
        val objectDeclaration = symbol.fields.isEmpty() && symbol.typeParameters.isEmpty()
        if (objectDeclaration) {
            append("data object ${symbol.kotlinName}")
        } else {
            append("data class ${symbol.kotlinName}$typeParameters(")
            if (symbol.fields.isNotEmpty()) {
                appendLine()
                symbol.fields.forEachIndexed { index, field ->
                    append("    val ${field.kotlinName}: ${types.renderField(field, symbol)}")
                    if (index < symbol.fields.lastIndex) append(',')
                    appendLine()
                }
            }
            append(')')
        }

        val supertype = when (symbol.source.kind) {
            TlDeclarationKind.CONSTRUCTOR -> types.renderFamilySupertype(symbol) ?: "TlObject"
            TlDeclarationKind.FUNCTION -> "TlMethod<${types.renderResult(symbol.source.result, symbol)}>"
        }
        appendLine(" : $supertype {")
        appendLine("    override val constructorId: UInt")
        appendLine("        get() = CONSTRUCTOR_ID")

        if (symbol.source.kind == TlDeclarationKind.FUNCTION) {
            appendLine()
            appendLine("    override val resultCodec: TlCodec<${types.renderResult(symbol.source.result, symbol)}>")
            appendLine("        get() = ${symbol.resultCodecBinding!!.accessExpression}")
        }

        symbol.fields.filter { it.repetition != null && (it.repetition.fields.size != 1 || it.repetition.fields.single().repetition != null) }
            .forEach { field ->
                appendLine()
                appendRepetitionItem(symbol, field, "    ")
            }

        appendLine()
        if (objectDeclaration) {
            appendMetadata(symbol, "    ")
        } else {
            appendLine("    companion object {")
            appendMetadata(symbol, "        ")
            appendLine("    }")
        }
        appendLine("}")
    }

    private fun StringBuilder.appendMetadata(symbol: TlDeclarationSymbol, indent: String) {
        appendLine("${indent}const val CONSTRUCTOR_ID: UInt = 0x${symbol.source.idHex.lowercase()}u")
        appendLine("${indent}const val TL_NAME: String = ${quote(symbol.source.name)}")
        appendLine("${indent}val SCHEMA_KIND: TlSchemaKind = TlSchemaKind.${symbol.schema.key.kind.name}")
        val schemaLayer = symbol.schema.key.layer?.toString() ?: "null"
        appendLine("${indent}val SCHEMA_LAYER: Int? = $schemaLayer")
        val introduced = symbol.source.introducedLayer?.toString() ?: "null"
        appendLine("${indent}val INTRODUCED_LAYER: Int? = $introduced")
        val sourceUrl = symbol.schema.source.url.takeIf(String::isNotBlank)
        appendLine("${indent}val SOURCE_URL: String? = ${sourceUrl?.let(::quote) ?: "null"}")
        append("${indent}val OPTIONAL_MASKS: Map<String, UInt> = ")
        if (symbol.source.flagWords.isEmpty()) {
            appendLine("emptyMap()")
        } else {
            appendLine("mapOf(")
            symbol.source.flagWords.sortedBy { it.sourceOrder }.forEach { flag ->
                appendLine("$indent    ${quote(flag.name)} to 0x${flag.optionalMask.toString(16).padStart(8, '0')}u,")
            }
            appendLine("$indent)")
        }
    }

    private fun StringBuilder.appendRepetitionItem(
        declaration: TlDeclarationSymbol,
        field: TlFieldSymbol,
        indent: String,
    ) {
        val fields = field.repetition!!.fields
        appendLine("${indent}data class ${types.repetitionItemName(field)}(")
        fields.forEachIndexed { index, nested ->
            append("$indent    val ${nested.kotlinName}: ${types.renderField(nested, declaration)}")
            if (index < fields.lastIndex) append(',')
            appendLine()
        }
        val complexChildren = fields.filter { it.repetition != null && (it.repetition.fields.size != 1 || it.repetition.fields.single().repetition != null) }
        if (complexChildren.isEmpty()) {
            appendLine("$indent)")
        } else {
            appendLine("$indent) {")
            complexChildren.forEachIndexed { index, child ->
                if (index > 0) appendLine()
                appendRepetitionItem(declaration, child, "$indent    ")
            }
            appendLine("$indent}")
        }
    }

    private fun StringBuilder.appendDeclarationKDoc(symbol: TlDeclarationSymbol) {
        val metadata = buildList {
            add("TL name: `${symbol.source.name}`")
            add("Schema: ${schemaLabel(symbol.schema.key.kind, symbol.schema.key.layer)}")
            add("Constructor ID: `0x${symbol.source.idHex.lowercase()}u` (${symbol.source.id})")
            symbol.source.introducedLayer?.let { add("Introduced in layer: $it") }
            symbol.source.documentation.officialUrl?.let { add("Official reference: $it") }
        }
        appendKDoc(symbol.source.documentation.description, metadata)
    }

    private fun StringBuilder.appendKDoc(description: String?, metadata: List<String>) {
        appendLine("/**")
        description?.takeIf(String::isNotBlank)?.let { text ->
            safeKDoc(text).lines().forEach { appendLine(" * $it") }
            appendLine(" *")
        }
        metadata.forEach { value ->
            safeKDoc(value).lines().forEach { appendLine(" * $it") }
        }
        appendLine(" */")
    }

    private fun safeKDoc(value: String): String = value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("/*", "/ *")
        .replace("*/", "* /")
        .replace("\u0000", "")

    private fun schemaLabel(kind: TlSchemaKind, layer: Int?): String =
        "${kind.name.lowercase()}${layer?.let { " layer $it" }.orEmpty()}"

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
