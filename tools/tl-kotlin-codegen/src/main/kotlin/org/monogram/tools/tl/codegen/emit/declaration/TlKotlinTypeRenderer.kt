package org.monogram.tools.tl.codegen.emit.declaration

import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.naming.TlDeclarationSymbol
import org.monogram.tools.tl.codegen.naming.TlFieldSymbol
import org.monogram.tools.tl.codegen.naming.TlGenerationException
import org.monogram.tools.tl.codegen.naming.TlGenerationFailure
import org.monogram.tools.tl.codegen.naming.TlResultFamilyKey
import org.monogram.tools.tl.codegen.naming.TlResultFamilySymbol
import org.monogram.tools.tl.codegen.naming.TlSymbolTable

class TlKotlinTypeRenderer(
    private val symbols: TlSymbolTable,
) {
    fun renderField(field: TlFieldSymbol, declaration: TlDeclarationSymbol): String {
        val base = when (field.transportPolicy) {
            is TlTransportPolicy.ExactLengthDeferred,
            TlTransportPolicy.RemainingDeferred,
            -> "TlDeferredObject"
            TlTransportPolicy.GzipPackedBytes -> {
                val rendered = field.expression?.let { render(it, declaration) }
                if (rendered != "TlBytes") unsupportedObject(declaration, field.kotlinName, "gzip policy requires bytes")
                "TlBytes"
            }
            TlTransportPolicy.None -> when {
                field.independentFlag -> "Boolean"
                field.repetition != null -> renderRepetition(field, declaration)
                field.functional && field.expression != null -> {
                    val result = if (field.expression is TlExpression.Bang) field.expression.inner else field.expression
                    "TlMethod<${render(result, declaration)}>"
                }
                field.expression != null -> render(field.expression, declaration)
                else -> unsupportedProjection(declaration, field.kotlinName, "field has no type expression")
            }
        }
        return if (field.optional && !field.independentFlag) "$base?" else base
    }

    fun render(expression: TlExpression, declaration: TlDeclarationSymbol): String =
        render(expression, declaration, RenderContext.FIELD)

    fun renderResult(expression: TlExpression, declaration: TlDeclarationSymbol): String =
        render(expression, declaration, RenderContext.DECLARATION_RESULT)

    private fun render(
        expression: TlExpression,
        declaration: TlDeclarationSymbol,
        context: RenderContext,
    ): String = when (expression) {
        is TlExpression.Identifier -> renderIdentifier(expression, declaration, 0, context)
        is TlExpression.Application -> renderApplication(expression, declaration, context)
        is TlExpression.Bare -> render(expression.inner, declaration, context)
        is TlExpression.Bang -> "TlMethod<${render(expression.inner, declaration, context)}>"
        is TlExpression.Hash -> "UInt"
        is TlExpression.Natural -> "ULong"
        is TlExpression.Add -> "ULong"
    }

    fun renderFamilySupertype(declaration: TlDeclarationSymbol): String? {
        val family = declaration.resultFamily ?: return null
        val expression = if (declaration.source.result is TlExpression.Bare) declaration.source.result.inner else declaration.source.result
        val arguments = (expression as? TlExpression.Application)?.arguments.orEmpty()
        return qualify(family, declaration.packageName) + if (arguments.isEmpty()) "" else
            arguments.joinToString(prefix = "<", postfix = ">") { renderResult(it, declaration) }
    }

    private fun renderIdentifier(
        expression: TlExpression.Identifier,
        declaration: TlDeclarationSymbol,
        genericArity: Int,
        context: RenderContext,
    ): String = when (expression.referenceKind) {
        TlReferenceKind.PRIMITIVE -> primitive(expression.name, declaration)
        TlReferenceKind.TYPE_PARAMETER -> declaration.typeParameters[expression.name]
            ?: unsupportedProjection(declaration, expression.name, "unresolved generated type parameter")
        TlReferenceKind.NATURAL_PARAMETER -> "UInt"
        TlReferenceKind.OBJECT -> if (context == RenderContext.DECLARATION_RESULT) {
            "TlObject"
        } else {
            unsupportedObject(declaration, expression.name, "Object is valid only as a declaration result or in tagged transport fields")
        }
        TlReferenceKind.NAMED_BARE -> {
            val constructor = symbols.constructor(declaration.schema.key, expression.name)
            if (constructor != null) qualify(constructor, declaration.packageName) else {
                val family = symbols.resultFamily(declaration.schema.key, expression.name, genericArity)
                    ?: unresolvedFamily(declaration, expression.name, genericArity)
                qualify(family, declaration.packageName)
            }
        }
        TlReferenceKind.NAMED_BOXED -> {
            val family = symbols.resultFamily(declaration.schema.key, expression.name, genericArity)
                ?: unresolvedFamily(declaration, expression.name, genericArity)
            qualify(family, declaration.packageName)
        }
    }

    private fun renderApplication(
        expression: TlExpression.Application,
        declaration: TlDeclarationSymbol,
        context: RenderContext,
    ): String {
        if (expression.applicationKind == TlApplicationKind.VECTOR) {
            if (expression.arguments.size != 1) unsupportedProjection(declaration, "result", "Vector requires one argument")
            return "List<${render(expression.arguments.single(), declaration, context)}>"
        }
        val constructor = expression.constructor as? TlExpression.Identifier
            ?: unsupportedProjection(declaration, "result", "generic constructor must be an identifier")
        if (constructor.referenceKind == TlReferenceKind.TYPE_PARAMETER) {
            unsupportedProjection(declaration, constructor.name, "Kotlin cannot apply type arguments to a type parameter")
        }
        val base = renderIdentifier(constructor, declaration, expression.arguments.size, context)
        return expression.arguments.joinToString(prefix = "$base<", postfix = ">") {
            render(it, declaration, context)
        }
    }

    private fun renderRepetition(field: TlFieldSymbol, declaration: TlDeclarationSymbol): String {
        val fields = field.repetition?.fields.orEmpty()
        if (fields.isEmpty()) unsupportedProjection(declaration, field.kotlinName, "empty repetition")
        val element = if (fields.size == 1 && fields.single().repetition == null) {
            renderField(fields.single(), declaration)
        } else {
            repetitionItemName(field)
        }
        return "List<$element>"
    }

    fun repetitionItemName(field: TlFieldSymbol): String =
        org.monogram.tools.tl.codegen.naming.KotlinNames.type(field.kotlinName) + "Item"

    private fun primitive(name: String, declaration: TlDeclarationSymbol): String = when (name) {
        "int" -> "Int"
        "long" -> "Long"
        "double" -> "Double"
        "string" -> "String"
        "bytes" -> "TlBytes"
        "int128" -> "TlInt128"
        "int256" -> "TlInt256"
        "Bool", "true" -> "Boolean"
        else -> unsupportedProjection(declaration, name, "unsupported primitive")
    }

    private fun qualify(family: TlResultFamilySymbol, currentPackage: String): String =
        if (family.packageName == currentPackage) family.kotlinName else "${family.packageName}.${family.kotlinName}"

    private fun qualify(constructor: TlDeclarationSymbol, currentPackage: String): String =
        if (constructor.packageName == currentPackage) constructor.kotlinName else "${constructor.packageName}.${constructor.kotlinName}"

    private fun unresolvedFamily(declaration: TlDeclarationSymbol, name: String, arity: Int): Nothing =
        throw TlGenerationException(
            TlGenerationFailure.UNRESOLVED_RESULT_FAMILY,
            declaration.schema.key,
            declaration.source.name,
            "type",
            "No result family for ${TlResultFamilyKey(declaration.schema.key, name, arity)}",
        )

    private fun unsupportedObject(declaration: TlDeclarationSymbol, path: String, detail: String): Nothing =
        throw TlGenerationException(
            TlGenerationFailure.UNSUPPORTED_OBJECT_POSITION,
            declaration.schema.key,
            declaration.source.name,
            path,
            detail,
        )

    private enum class RenderContext {
        FIELD,
        DECLARATION_RESULT,
    }

    private fun unsupportedProjection(declaration: TlDeclarationSymbol, path: String, detail: String): Nothing =
        throw TlGenerationException(
            TlGenerationFailure.UNSUPPORTED_TYPE_PROJECTION,
            declaration.schema.key,
            declaration.source.name,
            path,
            detail,
        )
}
