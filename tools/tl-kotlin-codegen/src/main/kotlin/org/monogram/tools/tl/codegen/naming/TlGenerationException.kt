package org.monogram.tools.tl.codegen.naming

import org.monogram.tools.tl.codegen.model.TlSchemaKey

open class TlGenerationException(
    val reason: TlGenerationFailure,
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

enum class TlGenerationFailure {
    DUPLICATE_SCHEMA_KEY,
    DUPLICATE_OUTPUT_PATH,
    UNRESOLVED_RESULT_FAMILY,
    UNRESOLVED_GENERIC_CODEC,
    UNSUPPORTED_OBJECT_POSITION,
    UNSUPPORTED_TYPE_PROJECTION,
    INHERITANCE_CYCLE,
    UNRESOLVABLE_NAME_COLLISION,
}
