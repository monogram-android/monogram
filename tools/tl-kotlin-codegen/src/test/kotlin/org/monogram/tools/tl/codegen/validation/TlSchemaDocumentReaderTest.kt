package org.monogram.tools.tl.codegen.validation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlFinalizationMode
import org.monogram.tools.tl.codegen.model.TlIdOrigin
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

class TlSchemaDocumentReaderTest {
    private val temporaryFolder = TemporaryFolder.builder().assureDeletion().build().also { it.create() }

    @Test
    fun `reads every committed snapshot with expected corpus counts and provenance`() {
        val schemas = TlSchemaDocumentReader.readManifest(schemaRoot.resolve("manifest.json"))

        assertEquals(14, schemas.size)
        assertEquals(2_228, schemas.sumOf { it.constructors.size })
        assertEquals(766, schemas.sumOf { it.functions.size })
        assertEquals(TlSchemaKey(TlSchemaKind.CLOUD, 223), schemas.first { it.key.kind == TlSchemaKind.CLOUD }.key)
        assertEquals(TlSchemaKey(TlSchemaKind.TRANSPORT, null), schemas.first { it.key.kind == TlSchemaKind.TRANSPORT }.key)
        assertEquals(
            setOf(8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, 216),
            schemas.filter { it.key.kind == TlSchemaKind.SECRET }.map { it.key.layer }.toSet(),
        )
        schemas.forEach { schema ->
            assertNotNull(schema.source.provenance)
            assertEquals(schema.constructors.indices.toList(), schema.constructors.map { it.sourceOrder })
            assertEquals(schema.functions.indices.toList(), schema.functions.map { it.sourceOrder })
            schema.declarations.forEach { declaration ->
                assertEquals(declaration.id, declaration.idHex.toUInt(16))
                assertEquals(schema.key.layer, declaration.schemaLayer)
            }
        }
    }

    @Test
    fun `committed transport policies are exact and gzip bytes remain ordinary`() {
        val schema = TlSchemaDocumentReader.read(schemaRoot.resolve("transport/mtproto.json"))
        val message = schema.constructors.single { it.name == "message" }
        val rpcResult = schema.constructors.single { it.name == "rpc_result" }
        val gzip = schema.constructors.single { it.name == "gzip_packed" }
        val futureSalts = schema.constructors.single { it.name == "future_salts" }

        assertEquals(TlTransportPolicy.ExactLengthDeferred("bytes"), message.parameters.single { it.name == "body" }.transportPolicy)
        assertEquals(TlTransportPolicy.RemainingDeferred, rpcResult.parameters.single { it.name == "result" }.transportPolicy)
        assertEquals(TlTransportPolicy.GzipPackedBytes, gzip.parameters.single().transportPolicy)
        assertEquals(
            TlReferenceKind.PRIMITIVE,
            ((gzip.parameters.single().value as TlArgumentValue.Type).expression as TlExpression.Identifier).referenceKind,
        )
        assertEquals(TlReferenceKind.OBJECT, (gzip.result as TlExpression.Identifier).referenceKind)
        val salts = (futureSalts.parameters.single { it.name == "salts" }.value as TlArgumentValue.Type).expression as TlExpression.Application
        assertEquals(TlReferenceKind.NAMED_BARE, (salts.constructor as TlExpression.Identifier).referenceKind)
        assertEquals(TlReferenceKind.NAMED_BARE, (salts.arguments.single() as TlExpression.Identifier).referenceKind)
    }

    @Test
    fun `normalizes every format v1 expression and preserves flags generics docs layers and order`() {
        val path = writeDocument("all-expressions.json", completeSyntheticDocument())

        val schema = TlSchemaDocumentReader.read(path)
        val constructor = schema.constructors.single()
        val method = schema.functions.single()
        val repeated = constructor.parameters.single { it.name == "items" }.value as TlArgumentValue.Repetition
        val vector = (constructor.parameters.single { it.name == "boxed" }.value as TlArgumentValue.Type).expression as TlExpression.Application

        assertEquals(TlSchemaKey(TlSchemaKind.CLOUD, 223), schema.key)
        assertEquals("fixture.item", constructor.name)
        assertEquals(listOf("fixture"), constructor.namespace)
        assertEquals("item", constructor.localName)
        assertEquals(UInt.MAX_VALUE, constructor.id)
        assertEquals(TlIdOrigin.EXPLICIT, constructor.idOrigin)
        assertEquals(TlIdOrigin.COMPUTED, method.idOrigin)
        assertEquals(listOf("T"), constructor.genericParameters.map { it.name })
        assertEquals(0x80000000u, constructor.flagWords.single { it.name == "flags" }.optionalMask)
        assertEquals(0x80000000u, constructor.parameters.single { it.name == "optional" }.optionalMask)
        assertEquals(TlApplicationKind.VECTOR, vector.applicationKind)
        assertEquals(TlReferenceKind.NAMED_BOXED, (vector.constructor as TlExpression.Identifier).referenceKind)
        assertTrue(vector.arguments.single() is TlExpression.Bare)
        assertTrue(repeated.multiplicity is TlExpression.Add)
        assertEquals("Fixture documentation", constructor.documentation.description)
        assertEquals("Optional value", constructor.documentation.parameters["optional"])
        assertEquals(100, constructor.introducedLayer)
        assertEquals(listOf(TlFinalizationMode.NEW, TlFinalizationMode.FINAL, TlFinalizationMode.EMPTY), schema.finalizations.map { it.mode })
        assertEquals(
            setOf(
                TlExpression.Identifier::class,
                TlExpression.Natural::class,
                TlExpression.Hash::class,
                TlExpression.Add::class,
                TlExpression.Application::class,
                TlExpression.Bare::class,
                TlExpression.Bang::class,
            ),
            schema.partialApplications.map { it::class }.toSet(),
        )
        assertEquals(listOf(0), schema.constructors.map { it.sourceOrder })
        assertEquals(listOf(0), schema.functions.map { it.sourceOrder })

        val primitiveShadowDocument = completeSyntheticDocument { syntheticSchema ->
            syntheticSchema.withFirstConstructor { declaration ->
                declaration.withArguments {
                    JsonArray(
                        listOf(
                            argument("flags", hash()),
                            argument("long", ident("double")),
                            argument("lat", ident("double")),
                            argument("access_hash", ident("long")),
                            argument("accuracy_radius", ident("int"), condition = condition("flags", 0)),
                        ),
                    )
                }
            }
        }
        val primitiveShadowSchema = TlSchemaDocumentReader.read(writeDocument("primitive-shadow.json", primitiveShadowDocument))
        val accessHash = primitiveShadowSchema.constructors.single().parameters.single { it.name == "access_hash" }
        assertEquals(
            TlReferenceKind.PRIMITIVE,
            ((accessHash.value as TlArgumentValue.Type).expression as TlExpression.Identifier).referenceKind,
        )
    }

    @Test
    fun `rejects natural parameter references outside lexical argument scope`() {
        fun withArguments(arguments: List<JsonObject>): JsonObject = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration -> declaration.withArguments { JsonArray(arguments) } }
        }

        val topLevelForward = withArguments(
            listOf(
                repetitionArgument("items", ident("count"), listOf(argument("item", ident("string")))),
                argument("count", hash()),
            ),
        )
        val repetitionForward = withArguments(
            listOf(
                argument("available", hash()),
                repetitionArgument(
                    "outer",
                    null,
                    listOf(
                        repetitionArgument("items", ident("count"), listOf(argument("item", ident("string")))),
                        argument("count", hash()),
                    ),
                ),
            ),
        )
        val siblingLeakage = withArguments(
            listOf(
                argument("available", hash()),
                repetitionArgument(
                    "outer",
                    null,
                    listOf(
                        repetitionArgument("first", null, listOf(argument("count", hash()))),
                        repetitionArgument("second", ident("count"), listOf(argument("item", ident("string")))),
                    ),
                ),
            ),
        )
        val nestedToParentLeakage = withArguments(
            listOf(
                argument("available", hash()),
                repetitionArgument("outer", null, listOf(argument("count", hash()))),
                repetitionArgument("items", ident("count"), listOf(argument("item", ident("string")))),
            ),
        )

        listOf(
            "top-level-forward.json" to topLevelForward,
            "repetition-forward.json" to repetitionForward,
            "sibling-leakage.json" to siblingLeakage,
            "nested-parent-leakage.json" to nestedToParentLeakage,
        ).forEach { (name, document) ->
            assertReason(SchemaValidationReason.UNRESOLVED_REFERENCE) {
                TlSchemaDocumentReader.read(writeDocument(name, document))
            }
        }
    }

    @Test
    fun `rejects omitted repetition multiplicity without a preceding natural`() {
        val document = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments {
                    JsonArray(listOf(repetitionArgument("items", null, listOf(argument("item", ident("string"))))))
                }
            }
        }

        assertReason(SchemaValidationReason.MALFORMED_EXPRESSION) {
            TlSchemaDocumentReader.read(writeDocument("missing-implicit-multiplicity.json", document))
        }
    }

    @Test
    fun `accepts omitted repetition multiplicity with a preceding natural`() {
        val document = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments {
                    JsonArray(
                        listOf(
                            argument("count", hash()),
                            repetitionArgument("items", null, listOf(argument("item", ident("string")))),
                        ),
                    )
                }
            }
        }

        val schema = TlSchemaDocumentReader.read(writeDocument("implicit-multiplicity.json", document))
        val repetition = schema.constructors.single().parameters[1].value as TlArgumentValue.Repetition
        assertEquals(null, repetition.multiplicity)
    }

    @Test
    fun `nested non natural binding shadows an inherited natural`() {
        val document = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments {
                    JsonArray(
                        listOf(
                            argument("count", hash()),
                            repetitionArgument(
                                "outer",
                                null,
                                listOf(
                                    argument("count", ident("string")),
                                    repetitionArgument("items", null, listOf(argument("item", ident("string")))),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        assertReason(SchemaValidationReason.MALFORMED_EXPRESSION) {
            TlSchemaDocumentReader.read(writeDocument("shadowed-implicit-multiplicity.json", document))
        }
    }

    @Test
    fun `accepts parent natural parameters in children and top level parameters in results`() {
        val parentToChild = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments {
                    JsonArray(
                        listOf(
                            argument("count", hash()),
                            repetitionArgument(
                                "outer",
                                ident("count"),
                                listOf(
                                    repetitionArgument(
                                        "inner",
                                        ident("count"),
                                        listOf(argument("item", ident("string"))),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        val topLevelResult = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration
                    .withArguments { JsonArray(listOf(argument("count", hash()))) }
                    .with("result", apply(ident("fixture.Item"), ident("count")))
            }
        }

        val parentSchema = TlSchemaDocumentReader.read(writeDocument("parent-child-natural.json", parentToChild))
        val outer = parentSchema.constructors.single().parameters[1].value as TlArgumentValue.Repetition
        val inner = outer.parameters.single().value as TlArgumentValue.Repetition
        assertEquals(TlReferenceKind.NATURAL_PARAMETER, (outer.multiplicity as TlExpression.Identifier).referenceKind)
        assertEquals(TlReferenceKind.NATURAL_PARAMETER, (inner.multiplicity as TlExpression.Identifier).referenceKind)

        val resultSchema = TlSchemaDocumentReader.read(writeDocument("top-level-result-natural.json", topLevelResult))
        val result = resultSchema.constructors.single().result as TlExpression.Application
        assertEquals(TlReferenceKind.NATURAL_PARAMETER, (result.arguments.single() as TlExpression.Identifier).referenceKind)
    }

    @Test
    fun `accepts repeated names and ids across schema keys`() {
        val cloud = writeDocument(
            "cloud.json",
            schemaDocument(TlSchemaKind.CLOUD, 223, listOf(simpleDeclaration("shared", 7, "00000007", 223))),
        )
        val secret = writeDocument(
            "secret.json",
            schemaDocument(TlSchemaKind.SECRET, 8, listOf(simpleDeclaration("shared", 7, "00000007", 8))),
        )

        val cloudSchema = TlSchemaDocumentReader.read(cloud)
        val secretSchema = TlSchemaDocumentReader.read(secret)

        assertEquals(cloudSchema.constructors.single().name, secretSchema.constructors.single().name)
        assertEquals(cloudSchema.constructors.single().id, secretSchema.constructors.single().id)
        assertFalse(cloudSchema.key == secretSchema.key)
    }

    @Test
    fun `rejects duplicate declaration names and ids only inside one key`() {
        val duplicateName = schemaDocument(
            TlSchemaKind.CLOUD,
            223,
            listOf(
                simpleDeclaration("same", 1, "00000001", 223, resultName = "One"),
                simpleDeclaration("same", 2, "00000002", 223, resultName = "Two"),
            ),
        )
        val duplicateId = schemaDocument(
            TlSchemaKind.CLOUD,
            223,
            listOf(
                simpleDeclaration("one", 1, "00000001", 223, resultName = "One"),
                simpleDeclaration("two", 1, "00000001", 223, resultName = "Two"),
            ),
        )

        assertReason(SchemaValidationReason.DUPLICATE_DECLARATION_NAME) {
            TlSchemaDocumentReader.read(writeDocument("duplicate-name.json", duplicateName))
        }
        val error = assertReason(SchemaValidationReason.DUPLICATE_DECLARATION_ID) {
            TlSchemaDocumentReader.read(writeDocument("duplicate-id.json", duplicateId))
        }
        assertEquals(TlSchemaKey(TlSchemaKind.CLOUD, 223), error.schemaKey)
    }

    @Test
    fun `rejects bad ids flags references results fields and expression kinds`() {
        val badHex = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration -> declaration.with("id_hex", JsonPrimitive("00000000")) }
        }
        val badFlag = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments { arguments ->
                    arguments.replaceNamed("optional") { argument ->
                        argument.with("condition", condition("missing", 0))
                    }
                }
            }
        }
        val badBit = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments { arguments ->
                    arguments.replaceNamed("optional") { argument -> argument.with("condition", condition("flags", 32)) }
                }
            }
        }
        val badResult = completeSyntheticDocument { schema ->
            schema.withFirstFunction { it.with("result", bang(ident("Vector"))) }
        }
        val unknownField = completeSyntheticDocument { schema -> schema.with("future", JsonPrimitive(true)) }
        val unknownExpression = completeSyntheticDocument { schema ->
            schema.with("partial_applications", JsonArray(listOf(buildJsonObject { put("kind", "future") })))
        }

        assertReason(SchemaValidationReason.ID_HEX_MISMATCH) { TlSchemaDocumentReader.read(writeDocument("bad-hex.json", badHex)) }
        assertReason(SchemaValidationReason.INVALID_FLAG_REFERENCE) { TlSchemaDocumentReader.read(writeDocument("bad-flag.json", badFlag)) }
        assertReason(SchemaValidationReason.INVALID_FLAG_BIT) { TlSchemaDocumentReader.read(writeDocument("bad-bit.json", badBit)) }
        assertReason(SchemaValidationReason.INVALID_RESULT_EXPRESSION) { TlSchemaDocumentReader.read(writeDocument("bad-result.json", badResult)) }
        assertReason(SchemaValidationReason.UNKNOWN_FIELD) { TlSchemaDocumentReader.read(writeDocument("unknown-field.json", unknownField)) }
        assertReason(SchemaValidationReason.UNKNOWN_EXPRESSION_KIND) { TlSchemaDocumentReader.read(writeDocument("unknown-expression.json", unknownExpression)) }
    }

    @Test
    fun `rejects malformed unresolved wrong schema and unsupported Object`() {
        val unresolved = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments { arguments ->
                    arguments.replaceNamed("boxed") { argument -> argument.withType(ident("Missing")) }
                }
            }
        }
        val wrongEmbeddedSchema = JsonObject(completeSyntheticDocument().toMutableMap().apply {
            val embedded = getValue("json_schema").jsonObject
            put("json_schema", embedded.with("title", JsonPrimitive("Other")))
        })
        val unsupportedObject = completeSyntheticDocument { schema ->
            schema.withFirstConstructor { declaration ->
                declaration.withArguments { arguments ->
                    JsonArray(arguments + argument("opaque", ident("Object")))
                }
            }
        }

        assertReason(SchemaValidationReason.MALFORMED_JSON) {
            TlSchemaDocumentReader.read(writeRaw("malformed.json", "{\"json_schema\":\n", appendLf = false))
        }
        assertReason(SchemaValidationReason.UNRESOLVED_REFERENCE) {
            TlSchemaDocumentReader.read(writeDocument("unresolved.json", unresolved))
        }
        assertReason(SchemaValidationReason.EMBEDDED_SCHEMA_MISMATCH) {
            TlSchemaDocumentReader.read(writeDocument("wrong-schema.json", wrongEmbeddedSchema))
        }
        assertReason(SchemaValidationReason.UNSUPPORTED_OBJECT_POSITION) {
            TlSchemaDocumentReader.read(writeDocument("unsupported-object.json", unsupportedObject))
        }
    }

    @Test
    fun `strict preflight rejects BOM CRLF duplicate keys non finite values and byte limits`() {
        val valid = completeSyntheticDocument().toString() + "\n"
        val duplicate = valid.replaceFirst("\"format_version\":1", "\"format_version\":1,\"format_version\":1")
        val nonFinite = valid.replaceFirst("\"format_version\":1", "\"format_version\":NaN")

        assertReason(SchemaValidationReason.UTF8_BOM_NOT_ALLOWED) {
            val path = temporaryPath("bom.json")
            Files.write(path, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + valid.toByteArray())
            TlSchemaDocumentReader.read(path)
        }
        assertReason(SchemaValidationReason.INVALID_LINE_ENDINGS) {
            TlSchemaDocumentReader.read(writeRaw("crlf.json", valid.replace("\n", "\r\n"), appendLf = false))
        }
        assertReason(SchemaValidationReason.MISSING_FINAL_LINE_FEED) {
            TlSchemaDocumentReader.read(writeRaw("no-lf.json", valid.dropLast(1), appendLf = false))
        }
        assertReason(SchemaValidationReason.MISSING_FINAL_LINE_FEED) {
            TlSchemaDocumentReader.read(writeRaw("two-lf.json", valid + "\n", appendLf = false))
        }
        assertReason(SchemaValidationReason.DUPLICATE_JSON_KEY) {
            TlSchemaDocumentReader.read(writeRaw("duplicate-key.json", duplicate, appendLf = false))
        }
        assertReason(SchemaValidationReason.NON_FINITE_NUMBER) {
            TlSchemaDocumentReader.read(writeRaw("non-finite.json", nonFinite, appendLf = false))
        }
        assertReason(SchemaValidationReason.FILE_TOO_LARGE) {
            val path = writeRaw("too-large.json", valid, appendLf = false)
            TlSchemaDocumentReader.read(path, JsonReaderLimits(maxFileBytes = valid.toByteArray().size - 1))
        }
        assertReason(SchemaValidationReason.JSON_DEPTH_EXCEEDED) {
            TlSchemaDocumentReader.read(writeRaw("deep.json", valid, appendLf = false), JsonReaderLimits(maxDepth = 4))
        }
        assertReason(SchemaValidationReason.JSON_ARRAY_LIMIT_EXCEEDED) {
            TlSchemaDocumentReader.read(writeRaw("array-limit.json", valid, appendLf = false), JsonReaderLimits(maxArrayElements = 1))
        }
        assertReason(SchemaValidationReason.JSON_MEMBER_LIMIT_EXCEEDED) {
            TlSchemaDocumentReader.read(writeRaw("member-limit.json", valid, appendLf = false), JsonReaderLimits(maxObjectMembers = 2))
        }
        assertReason(SchemaValidationReason.JSON_STRING_LIMIT_EXCEEDED) {
            TlSchemaDocumentReader.read(writeRaw("string-limit.json", valid, appendLf = false), JsonReaderLimits(maxStringChars = 5))
        }
    }

    @Test
    fun `manifest rejects duplicate path duplicate identity and altered artifact hash`() {
        val duplicatePathRoot = copySchemaTree("duplicate-path")
        mutateManifest(duplicatePathRoot) { manifest ->
            val schemas = manifest.getValue("schemas") as JsonArray
            manifest.with("schemas", JsonArray(listOf(schemas.first()) + schemas))
        }
        val duplicateIdentityRoot = copySchemaTree("duplicate-identity")
        mutateManifest(duplicateIdentityRoot) { manifest ->
            val schemas = (manifest.getValue("schemas") as JsonArray).toMutableList()
            val second = schemas[1].jsonObject
                .with("kind", JsonPrimitive("cloud"))
                .with("layer", JsonPrimitive(223))
            schemas[1] = second
            manifest.with("schemas", JsonArray(schemas))
        }
        val hashRoot = copySchemaTree("bad-hash")
        val cloudPath = hashRoot.resolve("cloud/layer-223.json")
        val cloudText = Files.readString(cloudPath)
        Files.writeString(cloudPath, cloudText.dropLast(1) + " \n", StandardCharsets.UTF_8)

        assertReason(SchemaValidationReason.MANIFEST_DUPLICATE_PATH) {
            TlSchemaDocumentReader.readManifest(duplicatePathRoot.resolve("manifest.json"))
        }
        assertReason(SchemaValidationReason.MANIFEST_DUPLICATE_IDENTITY) {
            TlSchemaDocumentReader.readManifest(duplicateIdentityRoot.resolve("manifest.json"))
        }
        assertReason(SchemaValidationReason.MANIFEST_HASH_MISMATCH) {
            TlSchemaDocumentReader.readManifest(hashRoot.resolve("manifest.json"))
        }
    }

    @Test
    fun `manifest rejects source hash that differs from pinned provenance`() {
        val root = copySchemaTree("bad-source-hash")
        mutateManifest(root) { manifest ->
            val schemas = (manifest.getValue("schemas") as JsonArray).toMutableList()
            schemas[0] = schemas[0].jsonObject.with("source_sha256", JsonPrimitive("0".repeat(64)))
            manifest.with("schemas", JsonArray(schemas))
        }

        assertReason(SchemaValidationReason.MANIFEST_PROVENANCE_MISMATCH) {
            TlSchemaDocumentReader.readManifest(root.resolve("manifest.json"))
        }
    }

    @Test
    fun `altered transport shape and extra Object position fail deterministically`() {
        val source = Json.parseToJsonElement(Files.readString(schemaRoot.resolve("transport/mtproto.json"))).jsonObject
        val alteredMessage = source.withSchema { schema ->
            schema.withConstructors { constructors ->
                constructors.replaceNamed("message") { declaration ->
                    declaration.withArguments { arguments -> arguments.replaceNamed("bytes") { it.withType(ident("long")) } }
                }
            }
        }
        val extraObject = source.withSchema { schema ->
            schema.withConstructors { constructors ->
                JsonArray(constructors + simpleDeclaration("unexpected", 9, "00000009", null, resultName = "Object", argumentList = listOf(argument("value", ident("Object")))))
            }
        }

        assertReason(SchemaValidationReason.TRANSPORT_POLICY_MISMATCH) {
            TlSchemaDocumentReader.read(writeDocument("altered-message.json", alteredMessage))
        }
        val error = assertReason(SchemaValidationReason.UNSUPPORTED_OBJECT_POSITION) {
            TlSchemaDocumentReader.read(writeDocument("extra-object.json", extraObject))
        }
        assertEquals("unexpected", error.declarationName)
        assertEquals(TlSchemaKey(TlSchemaKind.TRANSPORT, null), error.schemaKey)
    }

    @Test
    fun `CLI validates one document or the manifest and returns nonzero for errors`() {
        assertEquals(0, TlSchemaValidationCli.run(arrayOf(schemaRoot.resolve("transport/mtproto.json").toString())))
        assertEquals(0, TlSchemaValidationCli.run(arrayOf(schemaRoot.resolve("manifest.json").toString())))
        assertEquals(1, TlSchemaValidationCli.run(arrayOf(writeRaw("cli-bad.json", "{}\n", appendLf = false).toString())))
        assertEquals(2, TlSchemaValidationCli.run(emptyArray()))
    }

    private fun completeSyntheticDocument(transform: (JsonObject) -> JsonObject = { it }): JsonObject {
        val constructor = declaration(
            name = "fixture.item",
            id = UInt.MAX_VALUE.toLong(),
            idHex = "ffffffff",
            explicit = true,
            kind = "constructor",
            arguments = listOf(
                argument("T", ident("Type"), implicit = true),
                argument("flags", hash()),
                argument("count", hash()),
                argument("optional", ident("string"), condition = condition("flags", 31), description = "Optional value"),
                repetitionArgument("items", add(ident("count"), nat(1)), listOf(argument("item", ident("T")))),
                argument("boxed", apply(ident("Vector"), bare(ident("fixture.Item")))),
                argument("callback", bang(ident("T")), functional = true),
            ),
            result = ident("fixture.Item"),
            layer = 223,
            introduced = 100,
            description = "Fixture documentation",
            parameterDocumentation = mapOf("optional" to "Optional value"),
        )
        val method = declaration(
            name = "fixture.get",
            id = 2,
            idHex = "00000002",
            explicit = false,
            kind = "function",
            arguments = emptyList(),
            result = apply(ident("Vector"), ident("fixture.Item")),
            layer = 223,
        )
        val schema = buildSchema(
            kind = TlSchemaKind.CLOUD,
            layer = 223,
            constructors = listOf(constructor),
            functions = listOf(method),
            finalizations = listOf("new", "final", "empty").map { mode ->
                buildJsonObject {
                    put("mode", mode)
                    put("type", ident("fixture.Item"))
                }
            },
            partialApplications = listOf(
                ident("fixture.Item"),
                nat(2),
                hash(),
                add(nat(1), nat(2)),
                apply(ident("Vector"), bare(ident("fixture.Item"))),
                bare(ident("fixture.Item")),
                bang(ident("fixture.Item")),
            ),
        )
        return document(transform(schema))
    }

    private fun schemaDocument(
        kind: TlSchemaKind,
        layer: Int?,
        constructors: List<JsonObject>,
    ): JsonObject = document(buildSchema(kind, layer, constructors, emptyList(), emptyList(), emptyList()))

    private fun document(schema: JsonObject): JsonObject = buildJsonObject {
        put("json_schema", embeddedSchema)
        put("schema", schema)
    }

    private fun buildSchema(
        kind: TlSchemaKind,
        layer: Int?,
        constructors: List<JsonObject>,
        functions: List<JsonObject>,
        finalizations: List<JsonObject>,
        partialApplications: List<JsonElement>,
    ): JsonObject = buildJsonObject {
        put("format_version", 1)
        put("layer", layer?.let(::JsonPrimitive) ?: JsonNull)
        put("source", source(kind, layer))
        put("constructors", JsonArray(constructors))
        put("functions", JsonArray(functions))
        put("finalizations", JsonArray(finalizations))
        put("partial_applications", JsonArray(partialApplications))
    }

    private fun source(kind: TlSchemaKind, layer: Int?): JsonObject = buildJsonObject {
        when (kind) {
            TlSchemaKind.CLOUD -> {
                put("name", "schemas/layers/223/api.tl")
                put("url", "https://core.telegram.org/schema?raw=1")
            }
            TlSchemaKind.TRANSPORT -> {
                put("name", "schemas/upstream/mtproto.tl")
                put("url", "https://core.telegram.org/schema/mtproto?raw=1")
            }
            TlSchemaKind.SECRET -> {
                put("name", "schemas/secret-chat/layers/$layer.tl")
                put("url", "https://core.telegram.org/schema/end-to-end?raw=1")
            }
        }
    }

    private fun simpleDeclaration(
        name: String,
        id: Long,
        idHex: String,
        layer: Int?,
        resultName: String = "Shared",
        argumentList: List<JsonObject> = emptyList(),
    ): JsonObject = declaration(name, id, idHex, true, "constructor", argumentList, ident(resultName), layer)

    private fun declaration(
        name: String,
        id: Long,
        idHex: String,
        explicit: Boolean,
        kind: String,
        arguments: List<JsonObject>,
        result: JsonObject,
        layer: Int?,
        introduced: Int? = null,
        description: String? = null,
        parameterDocumentation: Map<String, String> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("id", id)
        put("id_hex", idHex)
        put("id_explicit", explicit)
        put("kind", kind)
        put("arguments", JsonArray(arguments))
        put("result", result)
        put("documentation", buildJsonObject {
            put("description", description?.let(::JsonPrimitive) ?: JsonNull)
            put("parameters", buildJsonObject { parameterDocumentation.forEach { (key, value) -> put(key, value) } })
            put("official_url", JsonPrimitive("https://core.telegram.org/$kind/$name"))
            put("links", buildJsonArray { add(JsonPrimitive("https://core.telegram.org/type/$name")) })
        })
        put("layer", buildJsonObject {
            put("schema", layer?.let(::JsonPrimitive) ?: JsonNull)
            put("introduced", introduced?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("builtin", false)
    }

    private fun argument(
        name: String?,
        expression: JsonObject,
        implicit: Boolean = false,
        functional: Boolean = false,
        condition: JsonObject? = null,
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
        put("value", buildJsonObject {
            put("kind", "type")
            put("expression", expression)
        })
        put("implicit", implicit)
        put("functional", functional)
        put("condition", condition ?: JsonNull)
        put("description", description?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun repetitionArgument(name: String?, multiplicity: JsonObject?, arguments: List<JsonObject>): JsonObject = buildJsonObject {
        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
        put("value", buildJsonObject {
            put("kind", "repetition")
            put("multiplicity", multiplicity ?: JsonNull)
            put("arguments", JsonArray(arguments))
        })
        put("implicit", false)
        put("functional", false)
        put("condition", JsonNull)
        put("description", JsonNull)
    }

    private fun condition(variable: String, bit: Int?): JsonObject = buildJsonObject {
        put("variable", variable)
        put("bit", bit?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun ident(name: String): JsonObject = buildJsonObject { put("kind", "ident"); put("name", name) }
    private fun nat(value: ULong): JsonObject = buildJsonObject { put("kind", "nat"); put("value", value.toLong()) }
    private fun nat(value: Int): JsonObject = nat(value.toULong())
    private fun hash(): JsonObject = buildJsonObject { put("kind", "hash") }
    private fun add(left: JsonObject, right: JsonObject): JsonObject = buildJsonObject { put("kind", "add"); put("left", left); put("right", right) }
    private fun apply(constructor: JsonObject, vararg arguments: JsonObject): JsonObject = buildJsonObject {
        put("kind", "apply")
        put("constructor", constructor)
        put("arguments", JsonArray(arguments.toList()))
    }
    private fun bare(inner: JsonObject): JsonObject = buildJsonObject { put("kind", "bare"); put("inner", inner) }
    private fun bang(inner: JsonObject): JsonObject = buildJsonObject { put("kind", "bang"); put("inner", inner) }

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(key, value) })

    private fun JsonObject.withSchema(transform: (JsonObject) -> JsonObject): JsonObject =
        with("schema", transform(getValue("schema").jsonObject))

    private fun JsonObject.withFirstConstructor(transform: (JsonObject) -> JsonObject): JsonObject = withConstructors { constructors ->
        JsonArray(listOf(transform(constructors.first().jsonObject)) + constructors.drop(1))
    }

    private fun JsonObject.withConstructors(transform: (JsonArray) -> JsonArray): JsonObject =
        with("constructors", transform(getValue("constructors") as JsonArray))

    private fun JsonObject.withFirstFunction(transform: (JsonObject) -> JsonObject): JsonObject {
        val functions = getValue("functions") as JsonArray
        return with("functions", JsonArray(listOf(transform(functions.first().jsonObject)) + functions.drop(1)))
    }

    private fun JsonObject.withArguments(transform: (JsonArray) -> JsonArray): JsonObject =
        with("arguments", transform(getValue("arguments") as JsonArray))

    private fun JsonObject.withType(expression: JsonObject): JsonObject {
        val value = getValue("value").jsonObject.with("expression", expression)
        return with("value", value)
    }

    private fun JsonArray.replaceNamed(name: String, transform: (JsonObject) -> JsonObject): JsonArray = JsonArray(map { element ->
        val item = element.jsonObject
        if ((item["name"] as? JsonPrimitive)?.content == name) transform(item) else item
    })

    private fun writeDocument(name: String, document: JsonObject): Path = writeRaw(name, document.toString() + "\n", appendLf = false)

    private fun writeRaw(name: String, text: String, appendLf: Boolean = true): Path {
        val path = temporaryPath(name)
        Files.writeString(path, if (appendLf) "$text\n" else text, StandardCharsets.UTF_8)
        return path
    }

    private fun temporaryPath(name: String): Path = temporaryFolder.root.toPath().resolve(name)

    private fun copySchemaTree(name: String): Path {
        val target = temporaryPath(name).createDirectories()
        val manifest = Json.parseToJsonElement(Files.readString(schemaRoot.resolve("manifest.json"))).jsonObject
        Files.copy(schemaRoot.resolve("manifest.json"), target.resolve("manifest.json"))
        (manifest.getValue("schemas") as JsonArray).forEach { element ->
            val relative = element.jsonObject.getValue("path").let { (it as JsonPrimitive).content }
            val destination = target.resolve(relative)
            destination.parent.createDirectories()
            Files.copy(schemaRoot.resolve(relative), destination)
        }
        return target
    }

    private fun mutateManifest(root: Path, transform: (JsonObject) -> JsonObject) {
        val path = root.resolve("manifest.json")
        val manifest = Json.parseToJsonElement(Files.readString(path)).jsonObject
        Files.writeString(path, transform(manifest).toString() + "\n", StandardCharsets.UTF_8)
    }

    private fun assertReason(reason: SchemaValidationReason, block: () -> Unit): SchemaValidationException {
        val error = try {
            block()
            throw AssertionError("Expected $reason")
        } catch (error: SchemaValidationException) {
            error
        }
        assertEquals(reason, error.reason)
        assertTrue(error.location.isNotBlank())
        return error
    }

    companion object {
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val schemaRoot: Path = repositoryRoot.resolve("protocol/schema")
        private val embeddedSchema: JsonObject by lazy {
            Json.parseToJsonElement(Files.readString(schemaRoot.resolve("transport/mtproto.json")))
                .jsonObject
                .getValue("json_schema")
                .jsonObject
        }
    }
}
