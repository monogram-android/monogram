package org.monogram.tools.tl.codegen.validation

import org.monogram.tools.tl.codegen.model.TlSchemaKey
import java.nio.file.Path

enum class SchemaValidationReason {
    FILE_TOO_LARGE,
    INVALID_UTF8,
    UTF8_BOM_NOT_ALLOWED,
    INVALID_LINE_ENDINGS,
    MISSING_FINAL_LINE_FEED,
    MALFORMED_JSON,
    DUPLICATE_JSON_KEY,
    JSON_DEPTH_EXCEEDED,
    JSON_ARRAY_LIMIT_EXCEEDED,
    JSON_MEMBER_LIMIT_EXCEEDED,
    JSON_STRING_LIMIT_EXCEEDED,
    NON_FINITE_NUMBER,
    UNKNOWN_FIELD,
    WRONG_TOP_LEVEL_KEYS,
    EMBEDDED_SCHEMA_MISMATCH,
    UNSUPPORTED_FORMAT_VERSION,
    INVALID_SCHEMA_IDENTITY,
    SOURCE_MISMATCH,
    INVALID_UNSIGNED_NUMBER,
    ID_HEX_MISMATCH,
    DECLARATION_KIND_MISMATCH,
    DUPLICATE_DECLARATION_NAME,
    DUPLICATE_DECLARATION_ID,
    DUPLICATE_PARAMETER_NAME,
    INVALID_LAYER,
    INVALID_FLAG_REFERENCE,
    INVALID_FLAG_BIT,
    UNKNOWN_ARGUMENT_VALUE_KIND,
    UNKNOWN_EXPRESSION_KIND,
    MALFORMED_EXPRESSION,
    UNRESOLVED_REFERENCE,
    INVALID_RESULT_EXPRESSION,
    INVALID_FINALIZATION,
    UNSUPPORTED_OBJECT_POSITION,
    TRANSPORT_POLICY_MISMATCH,
    MANIFEST_MALFORMED,
    MANIFEST_DUPLICATE_PATH,
    MANIFEST_DUPLICATE_IDENTITY,
    MANIFEST_IDENTITY_MISMATCH,
    MANIFEST_ARTIFACT_MISMATCH,
    MANIFEST_HASH_MISMATCH,
    MANIFEST_PROVENANCE_MISMATCH,
}

class SchemaValidationException(
    val artifactPath: Path,
    val reason: SchemaValidationReason,
    val location: String,
    val declarationName: String? = null,
    val schemaKey: TlSchemaKey? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(
    buildString {
        append(reason.name)
        append(" at ")
        append(artifactPath.normalize())
        append(':')
        append(location)
        if (schemaKey != null) {
            append(" schema=")
            append(schemaKey)
        }
        if (declarationName != null) {
            append(" declaration=")
            append(declarationName)
        }
    },
    cause,
)
