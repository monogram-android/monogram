package org.monogram.tools.tl.codegen.validation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.monogram.tools.tl.codegen.model.ArgumentDto
import org.monogram.tools.tl.codegen.model.ArgumentValueDto
import org.monogram.tools.tl.codegen.model.CombinatorDto
import org.monogram.tools.tl.codegen.model.CombinatorKindDto
import org.monogram.tools.tl.codegen.model.ExpressionDto
import org.monogram.tools.tl.codegen.model.InterchangeDocumentDto
import org.monogram.tools.tl.codegen.model.InterchangeSchemaDto
import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlCondition
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlDocumentation
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlFinalization
import org.monogram.tools.tl.codegen.model.TlFinalizationMode
import org.monogram.tools.tl.codegen.model.TlFlagWord
import org.monogram.tools.tl.codegen.model.TlGenericParameter
import org.monogram.tools.tl.codegen.model.TlIdOrigin
import org.monogram.tools.tl.codegen.model.TlManifestProvenance
import org.monogram.tools.tl.codegen.model.TlParameter
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

private const val FORMAT_VERSION = 1L
private const val CLOUD_LAYER = 223
private const val TELLERS_COMMIT = "31ed4e03e74188e342160951493ae75e25efccc6"
private const val EXPORTER_PACKAGE = "tellers-tl-json"
private const val EXPORTER_VERSION = "0.1.0"
private const val CLOUD_SOURCE = "schemas/layers/223/api.tl"
private const val CLOUD_URL = "https://core.telegram.org/schema?raw=1"
private const val TRANSPORT_SOURCE = "schemas/upstream/mtproto.tl"
private const val TRANSPORT_URL = "https://core.telegram.org/schema/mtproto?raw=1"
private const val SECRET_URL = "https://core.telegram.org/schema/end-to-end?raw=1"
private const val JSON_DEFS = "\$defs"
private const val EMBEDDED_SCHEMA_SHA256 = "63e2804dd5194d2bf25b05831db7ba3ccfdb3f90bcb922a88a82291325e6ba91"
private val SECRET_LAYERS = setOf(8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, 216)
private val EXPECTED_SOURCE_SHA256 = mapOf(
    TlSchemaKey(TlSchemaKind.CLOUD, CLOUD_LAYER) to "07a1808c20ef37db861f9d44a5167cbe3e202cf574b5c9e68dc9dd8e68de33b4",
    TlSchemaKey(TlSchemaKind.TRANSPORT, null) to "844ee9be2060808424654d08e5804f7f8f2fb5522587821c7ba2736265632bad",
    TlSchemaKey(TlSchemaKind.SECRET, 8) to "64a117bcf7f166b1bc5faf1b8ce8cb7eab9ec6527410861528a3912f2549acfe",
    TlSchemaKey(TlSchemaKind.SECRET, 17) to "54cb6cf30c4d6f9ae7661102bf5ac662a6a33900fc3ba22fa87e18f6c87825ed",
    TlSchemaKey(TlSchemaKind.SECRET, 20) to "6f3f5f32c20c59ba8b715c88d8af07c6f4ddc01bfaf22f7af33e331a8560188b",
    TlSchemaKey(TlSchemaKind.SECRET, 23) to "dd83c1eac0ce48fcec7bc02ba23bc3650c6d40160e1defa4823718b42ed59b72",
    TlSchemaKey(TlSchemaKind.SECRET, 45) to "3a4e712c64d6bb974057732368bda06c6d40742aa85ade7740c5af86767cbdd0",
    TlSchemaKey(TlSchemaKind.SECRET, 46) to "ed3fa8938426d5c369f3a5a95520b1b62c6733bbb97efdbdd9f10cc94307aa4f",
    TlSchemaKey(TlSchemaKind.SECRET, 66) to "76bac47c42f0da2fde337f1b0271799ddf53058932585b2bbc17d2616e52358c",
    TlSchemaKey(TlSchemaKind.SECRET, 73) to "2cea38cd31bae44ae440544823e32dc2b3b3ce08f91bb8be098335da27226ba7",
    TlSchemaKey(TlSchemaKind.SECRET, 101) to "de9935e15a3a5d95cf72c00c45c02d33949708383f7ac67874390b8cc2bb1676",
    TlSchemaKey(TlSchemaKind.SECRET, 143) to "5ec73880f41c6e80becc8a22e5fda77dcc43e37247076d25485caa0b1addc500",
    TlSchemaKey(TlSchemaKind.SECRET, 144) to "7a65ba8df7c38ada0e5e38262a387fdb355141dc3eebf15ce262cf736ac71a26",
    TlSchemaKey(TlSchemaKind.SECRET, 216) to "1f10089a570c4fcf84f0a9cdb5aeba2d5f24f40351d748f27d13133407069ae0",
)
private val PRIMITIVES = setOf("int", "long", "double", "string", "bytes", "int128", "int256", "Bool", "true")
private val EXPRESSION_KINDS = setOf("ident", "nat", "hash", "add", "apply", "bare", "bang")
private val ARGUMENT_VALUE_KINDS = setOf("type", "repetition")
private val HEX_32 = Regex("[0-9a-f]{8}")
private val SHA_256 = Regex("[0-9a-f]{64}")
private val json = Json {
    ignoreUnknownKeys = false
    classDiscriminator = "kind"
    isLenient = false
    allowSpecialFloatingPointValues = false
}

object TlSchemaDocumentReader {
    @JvmStatic
    fun read(path: Path): ValidatedTlSchema = read(path, JsonReaderLimits())

    @JvmStatic
    fun read(path: Path, limits: JsonReaderLimits): ValidatedTlSchema {
        val normalizedPath = path.toAbsolutePath().normalize()
        val input = StrictJsonInput.read(normalizedPath, limits)
        val root = parseRoot(normalizedPath, input.text)
        validateDocumentShape(normalizedPath, root)
        val dto = decodeDocument(normalizedPath, input.text)
        val key = identify(normalizedPath, dto.schema)
        val manifest = findManifest(normalizedPath)?.let { manifestPath ->
            validateManifest(manifestPath, normalizedPath, input.bytes, key, dto.schema.source.name, dto.schema.source.url)
        }
        return normalize(normalizedPath, dto.schema, key, manifest)
    }

    internal fun readManifest(path: Path): List<ValidatedTlSchema> {
        val manifestPath = path.toAbsolutePath().normalize()
        val manifest = decodeAndValidateManifest(manifestPath)
        val root = manifestPath.parent
        return manifest.schemas.map { entry -> read(root.resolve(entry.path)) }
    }
}

private fun parseRoot(path: Path, text: String): JsonObject {
    val element = try {
        json.parseToJsonElement(text)
    } catch (error: SerializationException) {
        fail(path, SchemaValidationReason.MALFORMED_JSON, "$", error)
    }
    return element as? JsonObject ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$")
}

private fun decodeDocument(path: Path, text: String): InterchangeDocumentDto = try {
    json.decodeFromString(text)
} catch (error: SerializationException) {
    val reason = if (error.message?.contains("unknown key", ignoreCase = true) == true) {
        SchemaValidationReason.UNKNOWN_FIELD
    } else {
        SchemaValidationReason.MALFORMED_JSON
    }
    fail(path, reason, "$", error)
}

private fun validateDocumentShape(path: Path, root: JsonObject) {
    if (root.keys.toList() != listOf("json_schema", "schema")) {
        fail(path, SchemaValidationReason.WRONG_TOP_LEVEL_KEYS, "$")
    }
    validateEmbeddedSchema(path, root.getValue("json_schema"))
    val schema = root.getValue("schema") as? JsonObject
        ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$.schema")
    if (schema.keys.toList() != listOf(
            "format_version",
            "layer",
            "source",
            "constructors",
            "functions",
            "finalizations",
            "partial_applications",
        )
    ) {
        fail(path, SchemaValidationReason.UNKNOWN_FIELD, "$.schema")
    }
    listOf("constructors", "functions").forEach { section ->
        val declarations = schema[section] as? JsonArray
            ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$.schema.$section")
        declarations.forEachIndexed { index, declaration ->
            validateDeclarationShape(path, declaration, "$.schema.$section[$index]")
        }
    }
    val finalizations = schema["finalizations"] as? JsonArray
        ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$.schema.finalizations")
    finalizations.forEachIndexed { index, value ->
        val item = value as? JsonObject
            ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$.schema.finalizations[$index]")
        requireExactKeys(path, item, setOf("mode", "type"), "$.schema.finalizations[$index]")
        validateExpressionShape(path, item["type"], "$.schema.finalizations[$index].type")
    }
    val partials = schema["partial_applications"] as? JsonArray
        ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$.schema.partial_applications")
    partials.forEachIndexed { index, expression ->
        validateExpressionShape(path, expression, "$.schema.partial_applications[$index]")
    }
}

private fun validateEmbeddedSchema(path: Path, value: JsonElement) {
    val schema = value as? JsonObject
        ?: fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema")
    if (sha256(schema.toString().toByteArray(StandardCharsets.UTF_8)) != EMBEDDED_SCHEMA_SHA256) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema")
    }
    val expectedKeys = listOf("$" + "schema", "title", "description", "type", "properties", "required", "$" + "defs")
    if (schema.keys.toList() != expectedKeys ||
        schema["$" + "schema"]?.jsonPrimitive?.contentOrNull != "https://json-schema.org/draft/2020-12/schema" ||
        schema["title"]?.jsonPrimitive?.contentOrNull != "Schema" ||
        schema["type"]?.jsonPrimitive?.contentOrNull != "object"
    ) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema")
    }
    val properties = schema["properties"] as? JsonObject
        ?: fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.properties")
    if (properties.keys != setOf(
            "constructors",
            "finalizations",
            "format_version",
            "functions",
            "layer",
            "partial_applications",
            "source",
        )
    ) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.properties")
    }
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
    if (required != setOf("format_version", "source", "constructors", "functions", "finalizations", "partial_applications")) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.required")
    }
    val definitions = schema[JSON_DEFS] as? JsonObject
        ?: fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.$JSON_DEFS")
    if (definitions.keys != setOf(
            "Argument",
            "ArgumentValue",
            "Combinator",
            "CombinatorKind",
            "Condition",
            "Documentation",
            "Expression",
            "Finalization",
            "LayerInfo",
            "Source",
        )
    ) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.$JSON_DEFS")
    }
    val expressionKinds = constValues(definitions["Expression"]?.jsonObject?.get("oneOf"))
    val argumentKinds = constValues(definitions["ArgumentValue"]?.jsonObject?.get("oneOf"))
    val combinatorKinds = constValues(definitions["CombinatorKind"]?.jsonObject?.get("oneOf"))
    if (expressionKinds != EXPRESSION_KINDS ||
        argumentKinds != ARGUMENT_VALUE_KINDS ||
        combinatorKinds != setOf("constructor", "function")
    ) {
        fail(path, SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH, "$.json_schema.$JSON_DEFS")
    }
}

private fun constValues(value: JsonElement?): Set<String> = (value as? JsonArray).orEmpty().mapNotNull { variant ->
    val objectValue = variant as? JsonObject ?: return@mapNotNull null
    val direct = (objectValue["const"] as? JsonPrimitive)?.contentOrNull
    val properties = objectValue["properties"] as? JsonObject
    val kind = properties?.get("kind") as? JsonObject
    direct ?: (kind?.get("const") as? JsonPrimitive)?.contentOrNull
}.toSet()

private fun validateDeclarationShape(path: Path, value: JsonElement, location: String) {
    val declaration = value as? JsonObject ?: fail(path, SchemaValidationReason.MALFORMED_JSON, location)
    requireExactKeys(
        path,
        declaration,
        setOf("name", "id", "id_hex", "id_explicit", "kind", "arguments", "result", "documentation", "layer", "builtin"),
        location,
    )
    val kind = declaration["kind"]?.jsonPrimitive?.contentOrNull
    if (kind != "constructor" && kind != "function") {
        fail(path, SchemaValidationReason.DECLARATION_KIND_MISMATCH, "$location.kind")
    }
    val arguments = declaration["arguments"] as? JsonArray
        ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$location.arguments")
    arguments.forEachIndexed { index, argument -> validateArgumentShape(path, argument, "$location.arguments[$index]") }
    validateExpressionShape(path, declaration["result"], "$location.result")
}

private fun validateArgumentShape(path: Path, value: JsonElement, location: String) {
    val argument = value as? JsonObject ?: fail(path, SchemaValidationReason.MALFORMED_JSON, location)
    requireExactKeys(path, argument, setOf("name", "value", "implicit", "functional", "condition", "description"), location)
    val argumentValue = argument["value"] as? JsonObject
        ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$location.value")
    when (argumentValue["kind"]?.jsonPrimitive?.contentOrNull) {
        "type" -> {
            requireExactKeys(path, argumentValue, setOf("kind", "expression"), "$location.value")
            validateExpressionShape(path, argumentValue["expression"], "$location.value.expression")
        }
        "repetition" -> {
            requireExactKeys(path, argumentValue, setOf("kind", "multiplicity", "arguments"), "$location.value")
            argumentValue["multiplicity"]?.takeUnless { it.toString() == "null" }?.let {
                validateExpressionShape(path, it, "$location.value.multiplicity")
            }
            val nested = argumentValue["arguments"] as? JsonArray
                ?: fail(path, SchemaValidationReason.MALFORMED_JSON, "$location.value.arguments")
            nested.forEachIndexed { index, item -> validateArgumentShape(path, item, "$location.value.arguments[$index]") }
        }
        else -> fail(path, SchemaValidationReason.UNKNOWN_ARGUMENT_VALUE_KIND, "$location.value.kind")
    }
}

private fun validateExpressionShape(path: Path, value: JsonElement?, location: String) {
    val expression = value as? JsonObject ?: fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location)
    when (expression["kind"]?.jsonPrimitive?.contentOrNull) {
        "ident" -> requireExactKeys(path, expression, setOf("kind", "name"), location)
        "nat" -> requireExactKeys(path, expression, setOf("kind", "value"), location)
        "hash" -> requireExactKeys(path, expression, setOf("kind"), location)
        "add" -> {
            requireExactKeys(path, expression, setOf("kind", "left", "right"), location)
            validateExpressionShape(path, expression["left"], "$location.left")
            validateExpressionShape(path, expression["right"], "$location.right")
        }
        "apply" -> {
            requireExactKeys(path, expression, setOf("kind", "constructor", "arguments"), location)
            validateExpressionShape(path, expression["constructor"], "$location.constructor")
            val arguments = expression["arguments"] as? JsonArray
                ?: fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, "$location.arguments")
            arguments.forEachIndexed { index, item -> validateExpressionShape(path, item, "$location.arguments[$index]") }
        }
        "bare", "bang" -> {
            requireExactKeys(path, expression, setOf("kind", "inner"), location)
            validateExpressionShape(path, expression["inner"], "$location.inner")
        }
        else -> fail(path, SchemaValidationReason.UNKNOWN_EXPRESSION_KIND, "$location.kind")
    }
}

private fun requireExactKeys(path: Path, value: JsonObject, expected: Set<String>, location: String) {
    if (value.keys == expected) return
    val reason = if ((value.keys - expected).isNotEmpty()) {
        SchemaValidationReason.UNKNOWN_FIELD
    } else {
        SchemaValidationReason.MALFORMED_JSON
    }
    fail(path, reason, location)
}

private fun identify(path: Path, schema: InterchangeSchemaDto): TlSchemaKey {
    if (schema.formatVersion != FORMAT_VERSION) {
        fail(path, SchemaValidationReason.UNSUPPORTED_FORMAT_VERSION, "$.schema.format_version")
    }
    val layer = checkedInt(path, schema.layer, "$.schema.layer", nullable = true)
    val key = when {
        layer == CLOUD_LAYER && schema.source.name == CLOUD_SOURCE && schema.source.url == CLOUD_URL ->
            TlSchemaKey(TlSchemaKind.CLOUD, CLOUD_LAYER)
        layer == null && schema.source.name == TRANSPORT_SOURCE && schema.source.url == TRANSPORT_URL ->
            TlSchemaKey(TlSchemaKind.TRANSPORT, null)
        layer in SECRET_LAYERS && schema.source.name == "schemas/secret-chat/layers/$layer.tl" && schema.source.url == SECRET_URL ->
            TlSchemaKey(TlSchemaKind.SECRET, layer)
        else -> fail(path, SchemaValidationReason.INVALID_SCHEMA_IDENTITY, "$.schema.source")
    }
    if (schema.constructors.isEmpty() && schema.functions.isEmpty()) {
        fail(path, SchemaValidationReason.INVALID_SCHEMA_IDENTITY, "$.schema.constructors", schemaKey = key)
    }
    return key
}

private fun normalize(
    path: Path,
    schema: InterchangeSchemaDto,
    key: TlSchemaKey,
    manifest: TlManifestProvenance?,
): ValidatedTlSchema {
    val allDtos = schema.constructors + schema.functions
    val names = HashSet<String>()
    val ids = HashSet<UInt>()
    allDtos.forEachIndexed { index, declaration ->
        if (!names.add(declaration.name)) {
            fail(path, SchemaValidationReason.DUPLICATE_DECLARATION_NAME, declarationLocation(declaration, index), declarationName = declaration.name, schemaKey = key)
        }
        val id = checkedUInt(path, declaration.id, declaration.name, key)
        if (!ids.add(id)) {
            fail(path, SchemaValidationReason.DUPLICATE_DECLARATION_ID, declarationLocation(declaration, index), declarationName = declaration.name, schemaKey = key)
        }
    }
    val knownReferences = buildMap {
        schema.constructors.forEach { put(it.name, TlReferenceKind.NAMED_BARE) }
        allDtos.mapNotNull { headName(it.result) }.forEach { put(it, TlReferenceKind.NAMED_BOXED) }
        put("Vector", TlReferenceKind.NAMED_BOXED)
        put("vector", TlReferenceKind.NAMED_BARE)
    }
    val constructors = schema.constructors.mapIndexed { index, declaration ->
        normalizeDeclaration(path, declaration, TlDeclarationKind.CONSTRUCTOR, index, key, knownReferences)
    }
    val functions = schema.functions.mapIndexed { index, declaration ->
        normalizeDeclaration(path, declaration, TlDeclarationKind.FUNCTION, index, key, knownReferences)
    }
    val finalizations = schema.finalizations.mapIndexed { index, finalization ->
        val mode = when (finalization.mode) {
            "new" -> TlFinalizationMode.NEW
            "final" -> TlFinalizationMode.FINAL
            "empty" -> TlFinalizationMode.EMPTY
            else -> fail(path, SchemaValidationReason.INVALID_FINALIZATION, "$.schema.finalizations[$index].mode", schemaKey = key)
        }
        TlFinalization(
            mode = mode,
            type = normalizeExpression(path, finalization.type, emptySet(), LexicalScope(emptyMap(), false), knownReferences, "$.schema.finalizations[$index].type", null, key)
                .also { type ->
                    if (!isFinalizationType(type)) {
                        fail(path, SchemaValidationReason.INVALID_FINALIZATION, "$.schema.finalizations[$index].type", schemaKey = key)
                    }
                },
            sourceOrder = index,
        )
    }
    val partials = schema.partialApplications.mapIndexed { index, expression ->
        normalizeExpression(path, expression, emptySet(), LexicalScope(emptyMap(), false), knownReferences, "$.schema.partial_applications[$index]", null, key)
    }
    val normalized = ValidatedTlSchema(
        formatVersion = FORMAT_VERSION.toInt(),
        key = key,
        source = TlSourceMetadata(
            name = schema.source.name,
            url = schema.source.url ?: fail(path, SchemaValidationReason.SOURCE_MISMATCH, "$.schema.source.url", schemaKey = key),
            artifactPath = path,
            provenance = manifest,
        ),
        constructors = constructors,
        functions = functions,
        finalizations = finalizations,
        partialApplications = partials,
    )
    validateTransportPolicies(path, normalized)
    return normalized
}

private fun normalizeDeclaration(
    path: Path,
    dto: CombinatorDto,
    expectedKind: TlDeclarationKind,
    sourceOrder: Int,
    key: TlSchemaKey,
    knownReferences: Map<String, TlReferenceKind>,
): TlDeclaration {
    val declarationLocation = "$.schema.${section(expectedKind)}[$sourceOrder]"
    val actualKind = when (dto.kind) {
        CombinatorKindDto.CONSTRUCTOR -> TlDeclarationKind.CONSTRUCTOR
        CombinatorKindDto.FUNCTION -> TlDeclarationKind.FUNCTION
    }
    if (actualKind != expectedKind) {
        fail(path, SchemaValidationReason.DECLARATION_KIND_MISMATCH, "$declarationLocation.kind", declarationName = dto.name, schemaKey = key)
    }
    val id = checkedUInt(path, dto.id, dto.name, key)
    if (!HEX_32.matches(dto.idHex) || dto.idHex.toULong(16).toUInt() != id) {
        fail(path, SchemaValidationReason.ID_HEX_MISMATCH, "$declarationLocation.id_hex", declarationName = dto.name, schemaKey = key)
    }
    val schemaLayer = checkedInt(path, dto.layer.schema, "$declarationLocation.layer.schema", nullable = true)
    val introduced = checkedInt(path, dto.layer.introduced, "$declarationLocation.layer.introduced", nullable = true)
    if (schemaLayer != key.layer || (introduced != null && (schemaLayer == null || introduced > schemaLayer))) {
        fail(path, SchemaValidationReason.INVALID_LAYER, "$declarationLocation.layer", declarationName = dto.name, schemaKey = key)
    }

    validateArgumentSequence(path, dto.arguments, "$declarationLocation.arguments", dto.name, key, emptyMap())
    val typeParameters = LinkedHashMap<String, Int>()
    dto.arguments.forEachIndexed { index, argument ->
        if (!argument.implicit) return@forEachIndexed
        val name = argument.name
            ?: fail(path, SchemaValidationReason.UNRESOLVED_REFERENCE, "$declarationLocation.arguments[$index]", declarationName = dto.name, schemaKey = key)
        val expression = (argument.value as? ArgumentValueDto.Type)?.expression
        when {
            expression is ExpressionDto.Ident && expression.name == "Type" -> typeParameters[name] = index
            expression is ExpressionDto.Hash -> Unit
            else -> fail(path, SchemaValidationReason.UNRESOLVED_REFERENCE, "$declarationLocation.arguments[$index]", declarationName = dto.name, schemaKey = key)
        }
    }
    val normalizedArguments = normalizeArgumentSequence(
        path = path,
        arguments = dto.arguments,
        location = "$declarationLocation.arguments",
        declarationName = dto.name,
        key = key,
        typeParameters = typeParameters.keys,
        inheritedLexicalScope = LexicalScope(emptyMap(), false),
        knownReferences = knownReferences,
        tagTransportPolicy = true,
    )
    val result = normalizeExpression(
        path,
        dto.result,
        typeParameters.keys,
        normalizedArguments.lexicalScope,
        knownReferences,
        "$declarationLocation.result",
        dto.name,
        key,
    )
    if (!isResultExpression(result)) {
        fail(path, SchemaValidationReason.INVALID_RESULT_EXPRESSION, "$declarationLocation.result", declarationName = dto.name, schemaKey = key)
    }
    val flagWords = dto.arguments.mapIndexedNotNull { index, argument ->
        val name = argument.name ?: return@mapIndexedNotNull null
        if ((argument.value as? ArgumentValueDto.Type)?.expression !is ExpressionDto.Hash) return@mapIndexedNotNull null
        TlFlagWord(
            name = name,
            sourceOrder = index,
            optionalMask = collectConditionMask(dto.arguments, name),
        )
    }
    return TlDeclaration(
        name = dto.name,
        id = id,
        idHex = dto.idHex,
        idOrigin = if (dto.idExplicit) TlIdOrigin.EXPLICIT else TlIdOrigin.COMPUTED,
        kind = expectedKind,
        parameters = normalizedArguments.parameters,
        result = result,
        documentation = TlDocumentation(
            description = dto.documentation.description,
            parameters = dto.documentation.parameters.toMap(LinkedHashMap()),
            officialUrl = dto.documentation.officialUrl,
            links = dto.documentation.links.toList(),
        ),
        schemaLayer = schemaLayer,
        introducedLayer = introduced,
        builtin = dto.builtin,
        sourceOrder = sourceOrder,
        genericParameters = typeParameters.map { (name, order) -> TlGenericParameter(name, order) },
        flagWords = flagWords,
    )
}

private fun validateArgumentSequence(
    path: Path,
    arguments: List<ArgumentDto>,
    location: String,
    declarationName: String,
    key: TlSchemaKey,
    inheritedFlags: Map<String, ArgumentDto>,
) {
    val seenNames = HashSet<String>()
    val availableFlags = LinkedHashMap(inheritedFlags)
    arguments.forEachIndexed { index, argument ->
        val argumentLocation = "$location[$index]"
        argument.name?.let { name ->
            if (!seenNames.add(name)) {
                fail(path, SchemaValidationReason.DUPLICATE_PARAMETER_NAME, argumentLocation, declarationName = declarationName, schemaKey = key)
            }
        }
        argument.condition?.let { condition ->
            if ((availableFlags[condition.variable]?.value as? ArgumentValueDto.Type)?.expression !is ExpressionDto.Hash) {
                fail(path, SchemaValidationReason.INVALID_FLAG_REFERENCE, argumentLocation, declarationName = declarationName, schemaKey = key)
            }
            if (condition.bit == null || condition.bit !in 0L..31L) {
                fail(path, SchemaValidationReason.INVALID_FLAG_BIT, argumentLocation, declarationName = declarationName, schemaKey = key)
            }
        }
        (argument.value as? ArgumentValueDto.Repetition)?.let { repetition ->
            validateArgumentSequence(
                path,
                repetition.arguments,
                "$argumentLocation.value.arguments",
                declarationName,
                key,
                availableFlags,
            )
        }
        argument.name?.let { availableFlags[it] = argument }
    }
}

private enum class LexicalBindingKind {
    NATURAL,
    NON_NATURAL,
}

private data class LexicalScope(
    val bindings: Map<String, LexicalBindingKind>,
    val hasAnonymousNatural: Boolean,
) {
    fun hasNatural(): Boolean = hasAnonymousNatural || bindings.values.any { it == LexicalBindingKind.NATURAL }
}

private data class NormalizedArgumentSequence(
    val parameters: List<TlParameter>,
    val lexicalScope: LexicalScope,
)

private fun normalizeArgumentSequence(
    path: Path,
    arguments: List<ArgumentDto>,
    location: String,
    declarationName: String,
    key: TlSchemaKey,
    typeParameters: Set<String>,
    inheritedLexicalScope: LexicalScope,
    knownReferences: Map<String, TlReferenceKind>,
    tagTransportPolicy: Boolean,
): NormalizedArgumentSequence {
    val bindings = LinkedHashMap(inheritedLexicalScope.bindings)
    var hasAnonymousNatural = inheritedLexicalScope.hasAnonymousNatural
    val parameters = arguments.mapIndexed { index, argument ->
        normalizeParameter(
            path = path,
            dto = argument,
            sourceOrder = index,
            location = "$location[$index]",
            declarationName = declarationName,
            key = key,
            typeParameters = typeParameters,
            lexicalScope = LexicalScope(bindings, hasAnonymousNatural),
            knownReferences = knownReferences,
            tagTransportPolicy = tagTransportPolicy,
        ).also {
            val bindingKind = if ((argument.value as? ArgumentValueDto.Type)?.expression is ExpressionDto.Hash) {
                LexicalBindingKind.NATURAL
            } else {
                LexicalBindingKind.NON_NATURAL
            }
            argument.name?.let { bindings[it] = bindingKind }
                ?: run { hasAnonymousNatural = hasAnonymousNatural || bindingKind == LexicalBindingKind.NATURAL }
        }
    }
    return NormalizedArgumentSequence(parameters, LexicalScope(bindings.toMap(), hasAnonymousNatural))
}

private fun collectConditionMask(arguments: List<ArgumentDto>, flagName: String): UInt {
    var mask = 0u
    arguments.forEach { argument ->
        if (argument.condition?.variable == flagName && argument.condition.bit != null) {
            mask = mask or (1u shl argument.condition.bit.toInt())
        }
        (argument.value as? ArgumentValueDto.Repetition)?.arguments?.let {
            mask = mask or collectConditionMask(it, flagName)
        }
    }
    return mask
}

private fun normalizeParameter(
    path: Path,
    dto: ArgumentDto,
    sourceOrder: Int,
    location: String,
    declarationName: String,
    key: TlSchemaKey,
    typeParameters: Set<String>,
    lexicalScope: LexicalScope,
    knownReferences: Map<String, TlReferenceKind>,
    tagTransportPolicy: Boolean,
): TlParameter {
    val value = when (val argumentValue = dto.value) {
        is ArgumentValueDto.Type -> TlArgumentValue.Type(
            normalizeExpression(path, argumentValue.expression, typeParameters, lexicalScope, knownReferences, "$location.value.expression", declarationName, key),
        )
        is ArgumentValueDto.Repetition -> {
            if (argumentValue.multiplicity == null && !lexicalScope.hasNatural()) {
                fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, "$location.value.multiplicity", declarationName = declarationName, schemaKey = key)
            }
            val multiplicity = argumentValue.multiplicity?.let {
                normalizeExpression(path, it, typeParameters, lexicalScope, knownReferences, "$location.value.multiplicity", declarationName, key)
            }
            if (multiplicity != null && !isNaturalExpression(multiplicity)) {
                fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, "$location.value.multiplicity", declarationName = declarationName, schemaKey = key)
            }
            val nested = normalizeArgumentSequence(
                path = path,
                arguments = argumentValue.arguments,
                location = "$location.value.arguments",
                declarationName = declarationName,
                key = key,
                typeParameters = typeParameters,
                inheritedLexicalScope = lexicalScope,
                knownReferences = knownReferences,
                tagTransportPolicy = false,
            ).parameters
            TlArgumentValue.Repetition(multiplicity, nested)
        }
    }
    if (dto.functional) {
        val expression = (value as? TlArgumentValue.Type)?.expression
        val reference = when (expression) {
            is TlExpression.Identifier -> expression
            is TlExpression.Bang -> expression.inner as? TlExpression.Identifier
            else -> null
        }
        if (reference?.referenceKind != TlReferenceKind.TYPE_PARAMETER) {
            fail(path, SchemaValidationReason.UNRESOLVED_REFERENCE, location, declarationName = declarationName, schemaKey = key)
        }
    }
    return TlParameter(
        name = dto.name,
        value = value,
        implicit = dto.implicit,
        functional = dto.functional,
        condition = dto.condition?.let { TlCondition(it.variable, it.bit?.toInt()) },
        description = dto.description,
        sourceOrder = sourceOrder,
        transportPolicy = if (tagTransportPolicy) {
            transportPolicyCandidate(key, declarationName, dto.name)
        } else {
            TlTransportPolicy.None
        },
    )
}

private fun normalizeExpression(
    path: Path,
    dto: ExpressionDto,
    typeParameters: Set<String>,
    lexicalScope: LexicalScope,
    knownReferences: Map<String, TlReferenceKind>,
    location: String,
    declarationName: String?,
    key: TlSchemaKey,
): TlExpression = when (dto) {
    is ExpressionDto.Ident -> {
        val kind = when {
            dto.name in typeParameters -> TlReferenceKind.TYPE_PARAMETER
            lexicalScope.bindings[dto.name] == LexicalBindingKind.NATURAL -> TlReferenceKind.NATURAL_PARAMETER
            dto.name in PRIMITIVES || dto.name == "Type" -> TlReferenceKind.PRIMITIVE
            lexicalScope.bindings[dto.name] == LexicalBindingKind.NON_NATURAL ->
                fail(path, SchemaValidationReason.UNRESOLVED_REFERENCE, location, declarationName = declarationName, schemaKey = key)
            dto.name == "Object" -> TlReferenceKind.OBJECT
            dto.name in knownReferences -> knownReferences.getValue(dto.name)
            else -> fail(path, SchemaValidationReason.UNRESOLVED_REFERENCE, location, declarationName = declarationName, schemaKey = key)
        }
        TlExpression.Identifier(dto.name, kind)
    }
    is ExpressionDto.Nat -> TlExpression.Natural(dto.value)
    is ExpressionDto.Hash -> TlExpression.Hash
    is ExpressionDto.Add -> {
        val left = normalizeExpression(path, dto.left, typeParameters, lexicalScope, knownReferences, "$location.left", declarationName, key)
        val right = normalizeExpression(path, dto.right, typeParameters, lexicalScope, knownReferences, "$location.right", declarationName, key)
        if (!isNaturalExpression(left) || !isNaturalExpression(right)) {
            fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location, declarationName = declarationName, schemaKey = key)
        }
        TlExpression.Add(left, right)
    }
    is ExpressionDto.Apply -> {
        val constructor = normalizeExpression(path, dto.constructor, typeParameters, lexicalScope, knownReferences, "$location.constructor", declarationName, key)
        if (constructor !is TlExpression.Identifier) {
            fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, "$location.constructor", declarationName = declarationName, schemaKey = key)
        }
        val arguments = dto.arguments.mapIndexed { index, expression ->
            normalizeExpression(path, expression, typeParameters, lexicalScope, knownReferences, "$location.arguments[$index]", declarationName, key)
        }
        val applicationKind = if (constructor.name == "Vector" || constructor.name == "vector") {
            if (arguments.size != 1) fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location, declarationName = declarationName, schemaKey = key)
            TlApplicationKind.VECTOR
        } else {
            if (constructor.referenceKind !in setOf(TlReferenceKind.NAMED_BOXED, TlReferenceKind.TYPE_PARAMETER) || arguments.isEmpty()) {
                fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location, declarationName = declarationName, schemaKey = key)
            }
            TlApplicationKind.GENERIC
        }
        TlExpression.Application(constructor, arguments, applicationKind)
    }
    is ExpressionDto.Bare -> {
        val inner = normalizeExpression(path, dto.inner, typeParameters, lexicalScope, knownReferences, "$location.inner", declarationName, key)
        val valid = inner is TlExpression.Application ||
            (inner is TlExpression.Identifier && inner.referenceKind == TlReferenceKind.NAMED_BOXED)
        if (!valid) fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location, declarationName = declarationName, schemaKey = key)
        TlExpression.Bare(inner)
    }
    is ExpressionDto.Bang -> {
        val inner = normalizeExpression(path, dto.inner, typeParameters, lexicalScope, knownReferences, "$location.inner", declarationName, key)
        if (inner !is TlExpression.Identifier && inner !is TlExpression.Application) {
            fail(path, SchemaValidationReason.MALFORMED_EXPRESSION, location, declarationName = declarationName, schemaKey = key)
        }
        TlExpression.Bang(inner)
    }
}

private fun validateTransportPolicies(path: Path, schema: ValidatedTlSchema) {
    val objectLocations = mutableListOf<Pair<TlDeclaration, TlParameter?>>()
    schema.declarations.forEach { declaration ->
        if (containsObject(declaration.result)) objectLocations += declaration to null
        declaration.parameters.forEach { parameter ->
            if (containsObject(parameter.value)) objectLocations += declaration to parameter
        }
    }
    if (schema.key.kind != TlSchemaKind.TRANSPORT) {
        objectLocations.firstOrNull()?.let { (declaration, _) ->
            fail(path, SchemaValidationReason.UNSUPPORTED_OBJECT_POSITION, "$.schema", declarationName = declaration.name, schemaKey = schema.key)
        }
        return
    }
    val message = schema.constructors.singleOrNull { it.name == "message" }
        ?: fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors", declarationName = "message", schemaKey = schema.key)
    val rpcResult = schema.constructors.singleOrNull { it.name == "rpc_result" }
        ?: fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors", declarationName = "rpc_result", schemaKey = schema.key)
    val gzip = schema.constructors.singleOrNull { it.name == "gzip_packed" }
        ?: fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors", declarationName = "gzip_packed", schemaKey = schema.key)
    if (message.id != 0x5bb8e511u || !matchesFields(message, listOf("msg_id" to "long", "seqno" to "int", "bytes" to "int", "body" to "Object"), "Message") ||
        message.parameters.last().transportPolicy !is TlTransportPolicy.ExactLengthDeferred
    ) {
        fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors.message", declarationName = "message", schemaKey = schema.key)
    }
    if (rpcResult.id != 0xf35c6d01u || !matchesFields(rpcResult, listOf("req_msg_id" to "long", "result" to "Object"), "RpcResult") ||
        rpcResult.parameters.last().transportPolicy !is TlTransportPolicy.RemainingDeferred
    ) {
        fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors.rpc_result", declarationName = "rpc_result", schemaKey = schema.key)
    }
    if (gzip.id != 0x3072cfa1u || !matchesFields(gzip, listOf("packed_data" to "bytes"), "Object") ||
        gzip.parameters.single().transportPolicy !is TlTransportPolicy.GzipPackedBytes
    ) {
        fail(path, SchemaValidationReason.TRANSPORT_POLICY_MISMATCH, "$.schema.constructors.gzip_packed", declarationName = "gzip_packed", schemaKey = schema.key)
    }
    objectLocations.forEach { (declaration, parameter) ->
        val allowed = (declaration.name == "message" && parameter?.name == "body") ||
            (declaration.name == "rpc_result" && parameter?.name == "result") ||
            (declaration.name == "gzip_packed" && parameter == null)
        if (!allowed) {
            fail(path, SchemaValidationReason.UNSUPPORTED_OBJECT_POSITION, "$.schema", declarationName = declaration.name, schemaKey = schema.key)
        }
    }
}

private fun matchesFields(declaration: TlDeclaration, fields: List<Pair<String, String>>, result: String): Boolean {
    if (declaration.parameters.size != fields.size || expressionName(declaration.result) != result) return false
    return declaration.parameters.zip(fields).all { (parameter, expected) ->
        parameter.name == expected.first &&
            !parameter.implicit && !parameter.functional && parameter.condition == null &&
            expressionName((parameter.value as? TlArgumentValue.Type)?.expression) == expected.second
    }
}

private fun expressionName(expression: TlExpression?): String? = (expression as? TlExpression.Identifier)?.name

private fun containsObject(value: TlArgumentValue): Boolean = when (value) {
    is TlArgumentValue.Type -> containsObject(value.expression)
    is TlArgumentValue.Repetition -> value.multiplicity?.let(::containsObject) == true || value.parameters.any { containsObject(it.value) }
}

private fun containsObject(expression: TlExpression): Boolean = when (expression) {
    is TlExpression.Identifier -> expression.referenceKind == TlReferenceKind.OBJECT
    is TlExpression.Natural, TlExpression.Hash -> false
    is TlExpression.Add -> containsObject(expression.left) || containsObject(expression.right)
    is TlExpression.Application -> containsObject(expression.constructor) || expression.arguments.any(::containsObject)
    is TlExpression.Bare -> containsObject(expression.inner)
    is TlExpression.Bang -> containsObject(expression.inner)
}

private fun transportPolicyCandidate(key: TlSchemaKey, declaration: String, parameter: String?): TlTransportPolicy = when {
    key.kind == TlSchemaKind.TRANSPORT && declaration == "message" && parameter == "body" -> TlTransportPolicy.ExactLengthDeferred("bytes")
    key.kind == TlSchemaKind.TRANSPORT && declaration == "rpc_result" && parameter == "result" -> TlTransportPolicy.RemainingDeferred
    key.kind == TlSchemaKind.TRANSPORT && declaration == "gzip_packed" && parameter == "packed_data" -> TlTransportPolicy.GzipPackedBytes
    else -> TlTransportPolicy.None
}

private fun headName(expression: ExpressionDto): String? = when (expression) {
    is ExpressionDto.Ident -> expression.name
    is ExpressionDto.Apply -> headName(expression.constructor)
    is ExpressionDto.Bare -> headName(expression.inner)
    is ExpressionDto.Bang -> headName(expression.inner)
    else -> null
}

private fun isNaturalExpression(expression: TlExpression): Boolean = when (expression) {
    is TlExpression.Natural -> true
    is TlExpression.Identifier -> expression.referenceKind == TlReferenceKind.NATURAL_PARAMETER
    is TlExpression.Add -> isNaturalExpression(expression.left) && isNaturalExpression(expression.right)
    else -> false
}

private fun isResultExpression(expression: TlExpression): Boolean = when (expression) {
    is TlExpression.Identifier -> expression.referenceKind != TlReferenceKind.NATURAL_PARAMETER
    is TlExpression.Application, is TlExpression.Bare -> true
    is TlExpression.Natural, TlExpression.Hash, is TlExpression.Add, is TlExpression.Bang -> false
}

private fun isFinalizationType(expression: TlExpression): Boolean = when (expression) {
    is TlExpression.Identifier -> expression.referenceKind == TlReferenceKind.NAMED_BOXED
    is TlExpression.Application, is TlExpression.Bare -> true
    else -> false
}

private fun checkedUInt(path: Path, value: Long, declaration: String, key: TlSchemaKey): UInt {
    if (value !in 0..UInt.MAX_VALUE.toLong()) {
        fail(path, SchemaValidationReason.INVALID_UNSIGNED_NUMBER, "$.schema", declarationName = declaration, schemaKey = key)
    }
    return value.toUInt()
}

private fun checkedInt(path: Path, value: Long?, location: String, nullable: Boolean): Int? {
    if (value == null) {
        if (!nullable) fail(path, SchemaValidationReason.INVALID_UNSIGNED_NUMBER, location)
        return null
    }
    if (value !in 0..Int.MAX_VALUE.toLong()) fail(path, SchemaValidationReason.INVALID_UNSIGNED_NUMBER, location)
    return value.toInt()
}

private fun section(kind: TlDeclarationKind): String = if (kind == TlDeclarationKind.CONSTRUCTOR) "constructors" else "functions"
private fun parameterLocation(kind: TlDeclarationKind, declarationOrder: Int, parameterOrder: Int): String =
    "$.schema.${section(kind)}[$declarationOrder].arguments[$parameterOrder]"
private fun declarationLocation(declaration: CombinatorDto, index: Int): String = "$.schema.${if (declaration.kind == CombinatorKindDto.CONSTRUCTOR) "constructors" else "functions"}[$index]"

@Serializable
private data class ManifestDto(
    @SerialName("format_version") val formatVersion: Long,
    val schemas: List<ManifestEntryDto>,
)

@Serializable
private data class ManifestEntryDto(
    val path: String,
    val kind: String,
    val layer: Long?,
    @SerialName("source_tl_path") val sourceTlPath: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_sha256") val sourceSha256: String,
    @SerialName("exported_json_sha256") val exportedJsonSha256: String,
    @SerialName("tellers_commit") val tellersCommit: String,
    @SerialName("exporter_package") val exporterPackage: String,
    @SerialName("exporter_version") val exporterVersion: String,
    @SerialName("interchange_format_version") val interchangeFormatVersion: Long,
    @SerialName("export_command") val exportCommand: String,
    @SerialName("generated_at") val generatedAt: String,
)

private fun findManifest(path: Path): Path? {
    var directory = path.parent
    repeat(4) {
        if (directory == null) return null
        val candidate = directory.resolve("manifest.json")
        if (Files.isRegularFile(candidate)) return candidate
        directory = directory.parent
    }
    return null
}

private fun decodeAndValidateManifest(path: Path): ManifestDto {
    val input = StrictJsonInput.read(path, JsonReaderLimits(maxFileBytes = 2 * 1024 * 1024))
    val manifest = try {
        json.decodeFromString<ManifestDto>(input.text)
    } catch (error: SerializationException) {
        fail(path, SchemaValidationReason.MANIFEST_MALFORMED, "$", error)
    }
    if (manifest.formatVersion != FORMAT_VERSION || manifest.schemas.isEmpty()) {
        fail(path, SchemaValidationReason.MANIFEST_MALFORMED, "$.format_version")
    }
    val expectedKeys = expectedManifestPaths().keys
    val seenPaths = HashSet<String>()
    val seenKeys = HashSet<TlSchemaKey>()
    if (manifest.schemas.map { it.path } != manifest.schemas.map { it.path }.sorted()) {
        fail(path, SchemaValidationReason.MANIFEST_MALFORMED, "$.schemas")
    }
    manifest.schemas.forEachIndexed { index, entry ->
        if (!seenPaths.add(entry.path)) fail(path, SchemaValidationReason.MANIFEST_DUPLICATE_PATH, "$.schemas[$index].path")
        val key = manifestKey(path, entry, index)
        if (!seenKeys.add(key)) fail(path, SchemaValidationReason.MANIFEST_DUPLICATE_IDENTITY, "$.schemas[$index]", schemaKey = key)
        if (entry.path != expectedManifestPaths()[key]) {
            fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas[$index].path", schemaKey = key)
        }
        validateManifestProvenance(path, entry, key, index)
    }
    if (seenKeys != expectedKeys || manifest.schemas.size != expectedKeys.size) {
        fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas")
    }
    val root = path.parent
    val listed = manifest.schemas.map { root.resolve(it.path).normalize() }.toSet()
    val actual = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") && it != path }
            .map { it.normalize() }
            .toList()
            .toSet()
    }
    if (actual != listed) fail(path, SchemaValidationReason.MANIFEST_ARTIFACT_MISMATCH, "$.schemas")
    manifest.schemas.forEachIndexed { index, entry ->
        val artifact = root.resolve(entry.path).normalize()
        if (!Files.isRegularFile(artifact) || sha256(Files.readAllBytes(artifact)) != entry.exportedJsonSha256) {
            fail(path, SchemaValidationReason.MANIFEST_HASH_MISMATCH, "$.schemas[$index].exported_json_sha256")
        }
    }
    return manifest
}

private fun validateManifest(
    manifestPath: Path,
    artifactPath: Path,
    artifactBytes: ByteArray,
    key: TlSchemaKey,
    sourceName: String,
    sourceUrl: String?,
): TlManifestProvenance {
    val manifest = decodeAndValidateManifest(manifestPath)
    val relative = manifestPath.parent.relativize(artifactPath).joinToString("/") { it.toString() }
    val entry = manifest.schemas.singleOrNull { it.path == relative }
        ?: fail(artifactPath, SchemaValidationReason.MANIFEST_ARTIFACT_MISMATCH, "$", schemaKey = key)
    val entryKey = manifestKey(manifestPath, entry, manifest.schemas.indexOf(entry))
    if (entryKey != key || entry.sourceTlPath != sourceName || entry.sourceUrl != sourceUrl) {
        fail(artifactPath, SchemaValidationReason.MANIFEST_PROVENANCE_MISMATCH, "$.schema.source", schemaKey = key)
    }
    if (sha256(artifactBytes) != entry.exportedJsonSha256) {
        fail(artifactPath, SchemaValidationReason.MANIFEST_HASH_MISMATCH, "$", schemaKey = key)
    }
    return TlManifestProvenance(
        manifestPath = manifestPath,
        artifactRelativePath = entry.path,
        sourceTlPath = entry.sourceTlPath,
        sourceSha256 = entry.sourceSha256,
        exportedJsonSha256 = entry.exportedJsonSha256,
        tellersCommit = entry.tellersCommit,
        exporterPackage = entry.exporterPackage,
        exporterVersion = entry.exporterVersion,
        exportCommand = entry.exportCommand,
        generatedAt = entry.generatedAt,
    )
}

private fun manifestKey(path: Path, entry: ManifestEntryDto, index: Int): TlSchemaKey {
    val layer = checkedInt(path, entry.layer, "$.schemas[$index].layer", nullable = true)
    return when (entry.kind) {
        "cloud" -> if (layer == CLOUD_LAYER) TlSchemaKey(TlSchemaKind.CLOUD, layer) else fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas[$index]")
        "transport" -> if (layer == null) TlSchemaKey(TlSchemaKind.TRANSPORT, null) else fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas[$index]")
        "secret" -> if (layer in SECRET_LAYERS) TlSchemaKey(TlSchemaKind.SECRET, layer) else fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas[$index]")
        else -> fail(path, SchemaValidationReason.MANIFEST_IDENTITY_MISMATCH, "$.schemas[$index].kind")
    }
}

private fun validateManifestProvenance(path: Path, entry: ManifestEntryDto, key: TlSchemaKey, index: Int) {
    val expectedSource = when (key.kind) {
        TlSchemaKind.CLOUD -> CLOUD_SOURCE to CLOUD_URL
        TlSchemaKind.TRANSPORT -> TRANSPORT_SOURCE to TRANSPORT_URL
        TlSchemaKind.SECRET -> "schemas/secret-chat/layers/${key.layer}.tl" to SECRET_URL
    }
    val expectedCommand = "cargo run --locked --offline -p tellers-tl-json -- ${expectedSource.first} --source-url ${expectedSource.second}"
    val validTimestamp = runCatching { Instant.parse(entry.generatedAt) }.isSuccess
    if (entry.sourceTlPath != expectedSource.first || entry.sourceUrl != expectedSource.second ||
        !SHA_256.matches(entry.sourceSha256) || entry.sourceSha256 != EXPECTED_SOURCE_SHA256[key] ||
        !SHA_256.matches(entry.exportedJsonSha256) ||
        entry.tellersCommit != TELLERS_COMMIT || entry.exporterPackage != EXPORTER_PACKAGE ||
        entry.exporterVersion != EXPORTER_VERSION || entry.interchangeFormatVersion != FORMAT_VERSION ||
        entry.exportCommand != expectedCommand || !validTimestamp
    ) {
        fail(path, SchemaValidationReason.MANIFEST_PROVENANCE_MISMATCH, "$.schemas[$index]", schemaKey = key)
    }
}

private fun expectedManifestPaths(): Map<TlSchemaKey, String> = buildMap {
    put(TlSchemaKey(TlSchemaKind.CLOUD, CLOUD_LAYER), "cloud/layer-223.json")
    put(TlSchemaKey(TlSchemaKind.TRANSPORT, null), "transport/mtproto.json")
    SECRET_LAYERS.forEach { layer -> put(TlSchemaKey(TlSchemaKind.SECRET, layer), "secret-chat/layer-$layer.json") }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
