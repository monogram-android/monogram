package org.monogram.tools.tl.codegen.emit.declaration

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.naming.GENERATED_PACKAGE_ROOT
import org.monogram.tools.tl.codegen.naming.TlGenerationException
import org.monogram.tools.tl.codegen.naming.TlGenerationFailure
import org.monogram.tools.tl.codegen.naming.TlSymbolTableBuilder
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class TlDeclarationGeneratorTest {
    private data class ManifestIdentity(
        val schemaKey: TlSchemaKey,
        val kind: TlDeclarationKind,
        val tlName: String,
        val constructorId: UInt,
    )
    @Test
    fun `generates every committed declaration exactly once in isolated partitions`() {
        val result = generated

        val expectedIdentities = schemas.flatMap { schema ->
            schema.declarations.map { declaration ->
                ManifestIdentity(schema.key, declaration.kind, declaration.name, declaration.id)
            }
        }.toSet()
        val actualIdentities = result.manifest.declarations.map { entry ->
            ManifestIdentity(entry.schemaKey, entry.kind, entry.tlName, entry.constructorId)
        }.toSet()

        assertEquals(2_994, expectedIdentities.size)
        assertEquals(expectedIdentities, actualIdentities)
        assertEquals(expectedIdentities.size, result.manifest.declarations.size)
        assertEquals(14, result.symbolTable.schemas.size)
        assertEquals(14, result.symbolTable.schemas.map { it.partition.packageName }.distinct().size)
        assertTrue(result.files.all { it.relativePath.startsWith(GENERATED_PACKAGE_ROOT.replace('.', '/') + "/") })
        assertTrue(result.symbolTable.schemas.all { it.partition.registryPackageName == it.partition.packageName + ".registry" })
        assertEquals(
            setOf(
                "$GENERATED_PACKAGE_ROOT.cloud.layer223",
                "$GENERATED_PACKAGE_ROOT.transport",
                "$GENERATED_PACKAGE_ROOT.secret.layer8",
                "$GENERATED_PACKAGE_ROOT.secret.layer17",
                "$GENERATED_PACKAGE_ROOT.secret.layer20",
                "$GENERATED_PACKAGE_ROOT.secret.layer23",
                "$GENERATED_PACKAGE_ROOT.secret.layer45",
                "$GENERATED_PACKAGE_ROOT.secret.layer46",
                "$GENERATED_PACKAGE_ROOT.secret.layer66",
                "$GENERATED_PACKAGE_ROOT.secret.layer73",
                "$GENERATED_PACKAGE_ROOT.secret.layer101",
                "$GENERATED_PACKAGE_ROOT.secret.layer143",
                "$GENERATED_PACKAGE_ROOT.secret.layer144",
                "$GENERATED_PACKAGE_ROOT.secret.layer216",
            ),
            result.symbolTable.schemas.map { it.partition.packageName }.toSet(),
        )
        assertTrue(result.manifest.declarations.all { entry ->
            val schema = schemas.single { it.key == entry.schemaKey }
            entry.relativePath.isNotBlank() &&
                entry.partitionRelativePath.isNotBlank() &&
                entry.sourceSchemaHash == schema.source.provenance?.sourceSha256 &&
                entry.sourceSchemaHash?.length == 64
        })
    }

    @Test
    fun `retains source generics flags repetitions policies and result binding symbols`() {
        val declarations = generated.symbolTable.declarations
        assertTrue(declarations.any { it.typeParameters.isNotEmpty() })
        assertTrue(declarations.any { it.source.flagWords.isNotEmpty() })
        assertTrue(declarations.any { declaration -> declaration.fields.any { it.repetition != null } })
        assertTrue(declarations.any { declaration -> declaration.fields.any { it.functional } })
        assertTrue(declarations.filter { it.source.kind == TlDeclarationKind.FUNCTION }.all { it.resultCodecBinding != null })
        val genericMethods = declarations.filter {
            it.source.kind == TlDeclarationKind.FUNCTION && it.typeParameters.isNotEmpty()
        }
        assertEquals(11, genericMethods.size)
        assertTrue(genericMethods.all { it.resultCodecBinding?.codecArgumentExpressions == listOf("query.resultCodec") })
        genericMethods.forEach { method ->
            val source = generated.files.single { it.relativePath == method.relativePath }.content
            assertTrue(source.contains("${method.resultCodecBinding!!.kotlinName}.bind(query.resultCodec)"))
            assertFalse(source.contains(".bind()"))
        }

        val optional = declarations.flatMap { it.fields }.first { it.optionalMask != null }
        assertNotNull(optional.flagVariable)
        assertNotNull(optional.flagBit)
        assertEquals(1u shl optional.flagBit!!, optional.optionalMask)
    }

    @Test
    fun `emits exact transport policies and standalone Object result constructors`() {
        val message = sourceFor("message")
        val rpcResult = sourceFor("rpc_result")
        val gzip = sourceFor("gzip_packed")
        val gzipSymbol = generated.symbolTable.declarations.single {
            it.schema.key.kind == TlSchemaKind.TRANSPORT && it.source.name == "gzip_packed"
        }

        assertTrue(message.contains("val body: TlDeferredObject"))
        assertTrue(rpcResult.contains("val result: TlDeferredObject"))
        assertTrue(gzip.contains("val packedData: TlBytes"))
        assertFalse(gzip.contains("val packedData: TlDeferredObject"))
        assertTrue(gzip.contains(") : TlObject {"))
        assertEquals(TlExpression.Identifier("Object", TlReferenceKind.OBJECT), gzipSymbol.source.result)
        assertEquals(null, gzipSymbol.resultFamily)
        assertFalse(generated.symbolTable.resultFamilies.any { it.key.tlName == "Object" })
    }

    @Test
    fun `emits immutable wrappers flags families methods metadata and safe KDoc`() {
        val allSource = generated.files.joinToString("\n", transform = GeneratedKotlinFile::content)
        assertTrue(allSource.contains("sealed interface "))
        assertTrue(allSource.contains("data class "))
        assertTrue(allSource.contains("data object "))
        assertTrue(allSource.contains("List<"))
        assertTrue(allSource.contains("TlBytes"))
        assertTrue(allSource.contains("TlInt128"))
        assertTrue(allSource.contains("TlInt256"))
        assertTrue(allSource.contains("override val resultCodec: TlCodec<"))
        assertTrue(allSource.contains("TlMethod<"))
        assertTrue(allSource.contains("const val CONSTRUCTOR_ID: UInt = 0x"))
        assertTrue(allSource.contains("const val TL_NAME: String"))
        assertFalse(allSource.contains("*/\n * /"))
        generated.symbolTable.declarations.forEach { declaration ->
            val hashFields = declaration.fields.filter { it.expression is TlExpression.Hash }
            assertTrue(
                "visible # field emitted for ${declaration.source.name}: ${hashFields.joinToString { it.source.name ?: "<unnamed>" }}",
                hashFields.isEmpty(),
            )
        }
    }

    @Test
    fun `manifest and collision report are sorted deterministic JSON`() {
        val result = generated
        Json.parseToJsonElement(result.manifestJson)
        Json.parseToJsonElement(result.collisionReportJson)
        assertEquals(result.files.map { it.relativePath }.sorted(), result.files.map { it.relativePath })
        assertEquals(result.manifest.files.sorted(), result.manifest.files)
        assertTrue(result.manifestJson.endsWith("\n"))
        assertTrue(result.collisionReportJson.endsWith("\n"))
    }

    @Test
    fun `shuffled schema and declaration traversal returns identical paths and bytes`() {
        val random = Random(0x57_50_30_30_33)
        val shuffled = schemas.shuffled(random).map { schema ->
            schema.copy(
                constructors = schema.constructors.shuffled(random),
                functions = schema.functions.shuffled(random),
            )
        }
        val second = TlDeclarationGenerator().generate(shuffled)
        val firstBytes = generated.allOutputBytes()
        val secondBytes = second.allOutputBytes()

        assertEquals(firstBytes.keys, secondBytes.keys)
        firstBytes.forEach { (path, bytes) ->
            assertTrue("different output for $path", bytes.contentEquals(secondBytes.getValue(path)))
        }
    }

    @Test
    fun `reserves registry as a direct partition child package`() {
        val cloud = schemas.single { it.key.kind == TlSchemaKind.CLOUD }
        val source = cloud.constructors.first()
        val registryDeclaration = source.copy(name = "registry.${source.localName}")
        val fixture = cloud.copy(constructors = listOf(registryDeclaration), functions = emptyList())

        val symbols = TlSymbolTableBuilder().build(listOf(fixture))
        val declaration = symbols.declarations.single()
        val reservedPackage = "${declaration.partition.packageName}.registry"

        assertFalse(declaration.packageName == reservedPackage)
        assertTrue(declaration.packageName.startsWith("${reservedPackage}_"))
        assertTrue(symbols.collisionReport.any { collision ->
            collision.scope == "${fixture.key}|${declaration.partition.packageName}|packages" &&
                collision.preferredName == "registry" &&
                collision.allocations.single().allocatedName == declaration.packageName.substringAfterLast('.')
        })
    }

    @Test
    fun `rejects missing or duplicate generic codec sources`() {
        val cloud = schemas.single { it.key.kind == TlSchemaKind.CLOUD }
        val genericMethod = cloud.functions.first { it.genericParameters.isNotEmpty() }
        val query = genericMethod.parameters.single { it.functional }
        val invalidMethods = listOf(
            genericMethod.copy(
                parameters = genericMethod.parameters.map { parameter ->
                    if (parameter === query) parameter.copy(functional = false) else parameter
                },
            ),
            genericMethod.copy(
                parameters = genericMethod.parameters + query.copy(
                    name = "other_query",
                    sourceOrder = genericMethod.parameters.maxOf { it.sourceOrder } + 1,
                ),
            ),
        )

        invalidMethods.forEach { invalidMethod ->
            val failure = try {
                TlDeclarationGenerator().generate(
                    listOf(cloud.copy(constructors = emptyList(), functions = listOf(invalidMethod))),
                )
                throw AssertionError("Expected unresolved generic codec failure")
            } catch (failure: TlGenerationException) {
                failure
            }
            assertEquals(TlGenerationFailure.UNRESOLVED_GENERIC_CODEC, failure.reason)
        }
    }

    @Test
    fun `rejects unsupported Object when the validated transport tag is absent`() {
        val transport = schemas.single { it.key.kind == TlSchemaKind.TRANSPORT }
        val message = transport.constructors.single { it.name == "message" }
        val badMessage = message.copy(
            parameters = message.parameters.map { parameter ->
                if (parameter.name == "body") parameter.copy(transportPolicy = TlTransportPolicy.None) else parameter
            },
        )
        val badSchema = transport.copy(constructors = transport.constructors.map { if (it.name == "message") badMessage else it })

        val failure = try {
            TlDeclarationGenerator().generate(listOf(badSchema))
            throw AssertionError("Expected unsupported Object failure")
        } catch (failure: TlGenerationException) {
            failure
        }
        assertEquals(TlGenerationFailure.UNSUPPORTED_OBJECT_POSITION, failure.reason)
        assertEquals("message", failure.declarationName)
        assertEquals("Object", failure.expressionPath)
        assertTrue(failure.message.orEmpty().contains("Object is valid only as a declaration result or in tagged transport fields"))
    }

    @Test
    fun `schema key permits repeated TL identities without shared output paths`() {
        val repeatedNames = generated.manifest.declarations.groupBy { it.tlName }.values.first { entries ->
            entries.map { it.schemaKey }.distinct().size > 1
        }
        assertTrue(repeatedNames.map { it.constructorId }.distinct().size <= repeatedNames.size)
        assertEquals(repeatedNames.size, repeatedNames.map { it.relativePath }.distinct().size)
        assertTrue(repeatedNames.all { entry ->
            when (entry.schemaKey.kind) {
                TlSchemaKind.CLOUD -> entry.relativePath.contains("/cloud/layer223/")
                TlSchemaKind.TRANSPORT -> entry.relativePath.contains("/transport/")
                TlSchemaKind.SECRET -> entry.relativePath.contains("/secret/layer${entry.schemaKey.layer}/")
            }
        })
    }

    private fun sourceFor(tlName: String): String {
        val entry = generated.manifest.declarations.single {
            it.schemaKey.kind == TlSchemaKind.TRANSPORT && it.tlName == tlName
        }
        return generated.files.single { it.relativePath == entry.relativePath }.content
    }

    companion object {
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val schemas by lazy {
            TlSchemaDocumentReader.readManifest(repositoryRoot.resolve("protocol/schema/manifest.json"))
        }
        private val generated by lazy { TlDeclarationGenerator().generate(schemas) }
    }
}
