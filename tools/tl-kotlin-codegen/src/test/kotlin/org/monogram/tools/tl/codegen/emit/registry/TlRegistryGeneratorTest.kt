package org.monogram.tools.tl.codegen.emit.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.tools.tl.codegen.emit.codec.TlCodecGenerationResult
import org.monogram.tools.tl.codegen.emit.codec.TlCodecGenerator
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader
import java.nio.file.Files
import java.nio.file.Path

class TlRegistryGeneratorTest {
    @Test
    fun `emits one registry for all 14 schema identities with exact coverage`() {
        val result = registries
        val identities = result.plan.schemas.map { it.schemaKey }.toSet()

        assertEquals(14, result.files.size)
        assertEquals(14, identities.size)
        assertEquals(2_220, result.constructorCount)
        assertEquals(766, result.methodCount)
        assertTrue(TlSchemaKey(TlSchemaKind.CLOUD, 223) in identities)
        assertTrue(TlSchemaKey(TlSchemaKind.TRANSPORT, null) in identities)
        assertEquals(
            setOf(8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, 216),
            identities.filter { it.kind == TlSchemaKind.SECRET }.mapNotNull { it.layer }.toSet(),
        )
        assertEquals(
            "org/monogram/mtproto/tl/generated/cloud/layer223/registry/CloudLayer223ConstructorRegistry.kt",
            result.plan.schemas.single { it.schemaKey.kind == TlSchemaKind.CLOUD }.relativePath,
        )
        assertEquals(
            "org/monogram/mtproto/tl/generated/transport/registry/TransportConstructorRegistry.kt",
            result.plan.schemas.single { it.schemaKey.kind == TlSchemaKind.TRANSPORT }.relativePath,
        )
    }

    @Test
    fun `source and paths are deterministic and contain no global dispatch map`() {
        val second = TlRegistryGenerator().generate(codecs)

        assertEquals(registries.plan, second.plan)
        assertEquals(registries.files.map { it.relativePath }, second.files.map { it.relativePath })
        registries.files.zip(second.files).forEach { (first, repeated) -> assertEquals(first.content, repeated.content) }
        assertEquals(registries.files.map { it.relativePath }.sorted(), registries.files.map { it.relativePath })
        val source = registries.files.joinToString("\n") { it.content }
        assertFalse(source.contains("mapOf("))
        assertFalse(source.contains("HashMap"))
        assertFalse(source.contains("Map<"))
        assertFalse(source.contains("java.lang.reflect"))
        assertFalse(source.contains("kotlinx.serialization"))
        assertFalse(source.contains("Json.decode"))
        assertFalse(source.contains("Json.parse"))
    }

    @Test
    fun `schema validation is the first decode operation and unknown dispatch preserves UInt`() {
        registries.files.forEach { file ->
            val source = file.content
            assertBodyStartsWithSchemaCheck(
                source,
                "override fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject {",
            )
            assertBodyStartsWithSchemaCheck(
                source,
                "fun decodeMethod(id: UInt, reader: TlReader, context: TlDecodeContext): TlMethod<*> {",
            )
            val genericStart = "    fun <R> decodeMethod("
            val genericBody = source.substring(source.indexOf(genericStart)).substringAfter("): TlMethod<R> {")
            assertTrue(genericBody.trimStart().startsWith("if (schema != context.schema)"))
            assertTrue(source.contains("TlUnknownConstructorException(context.schema, id, reader.absoluteOffset)"))
        }
        val unsigned = codecs.plan.declarationCodecs.first { it.constructorId > Int.MAX_VALUE.toUInt() }
        val schemaSource = registries.files.single { file ->
            registries.plan.schemas.single { it.relativePath == file.relativePath }.schemaKey == unsigned.schemaKey
        }.content
        assertTrue(schemaSource.contains("${hex(unsigned.constructorId)} ->"))
        assertFalse(schemaSource.contains("${unsigned.constructorId.toInt()} ->"))
    }

    @Test
    fun `known dispatch uses one nested context and encode verifies concrete types`() {
        registries.plan.schemas.forEach { schema ->
            val source = registries.files.single { it.relativePath == schema.relativePath }.content
            (schema.constructors + schema.methods.filter { it.typeParameters.isEmpty() }).forEach { declaration ->
                val branch = "${hex(declaration.constructorId)} -> ${declaration.qualifiedCodecName}.readBare(reader, context.nested())"
                assertEquals("Missing or repeated branch for ${declaration.tlName}", 1, source.countOccurrences(branch))
            }
            schema.constructors.forEach { declaration ->
                val type = "${declaration.packageName}.${declaration.kotlinType.substringBefore('<')}"
                assertTrue(source.contains("is $type -> {"))
                assertTrue(source.contains("writer.writeInt($type.CONSTRUCTOR_ID.toInt())"))
            }
        }
        val source = registries.files.joinToString("\n") { it.content }
        assertTrue(source.contains("when (value)"))
        assertTrue(source.contains("No generated constructor codec"))
        assertTrue(source.contains("No generated method codec"))
    }

    @Test
    fun `D-023 pseudo constructors are absent from every registry`() {
        assertEquals(8, codecs.plan.exclusions.size)
        codecs.plan.exclusions.forEach { exclusion ->
            val schema = registries.plan.schemas.single { it.schemaKey == exclusion.schemaKey }
            val source = registries.files.single { it.relativePath == schema.relativePath }.content
            assertFalse(source.contains("${hex(exclusion.constructorId)} ->"))
            assertFalse(schema.constructors.any { it.tlName == exclusion.tlName })
            assertFalse(schema.methods.any { it.tlName == exclusion.tlName })
        }
    }

    @Test
    fun `cross schema repeated IDs are valid`() {
        val repeated = registries.plan.schemas
            .flatMap { schema -> (schema.constructors + schema.methods).map { it.constructorId to schema.schemaKey } }
            .groupBy({ it.first }, { it.second })
            .filterValues { keys -> keys.distinct().size > 1 }

        assertFalse(repeated.isEmpty())
        assertEquals(14, TlRegistryGenerator().generate(codecs).files.size)
    }

    @Test
    fun `within schema duplicate IDs names and paths are rejected`() {
        assertDuplicate(
            TlRegistryGenerationFailure.DUPLICATE_ID,
        ) { first, second -> second.copy(constructorId = first.constructorId) }
        assertDuplicate(
            TlRegistryGenerationFailure.DUPLICATE_NAME,
        ) { first, second -> second.copy(tlName = first.tlName) }
        assertDuplicate(
            TlRegistryGenerationFailure.DUPLICATE_PATH,
        ) { first, second -> second.copy(relativePath = first.relativePath) }
    }

    @Test
    fun `generic methods require explicit result codec and use typed bindings`() {
        val cloud = registries.plan.schemas.single { it.schemaKey.kind == TlSchemaKind.CLOUD }
        val generic = cloud.methods.first { it.typeParameters.isNotEmpty() }
        val source = registries.files.single { it.relativePath == cloud.relativePath }.content
        val type = "${generic.packageName}.${generic.kotlinType.substringBefore('<')}"

        assertEquals(1, generic.typeParameters.size)
        assertEquals(1, generic.codecParameters.size)
        assertTrue(source.contains("Method ${generic.tlName} requires an explicit result codec"))
        assertTrue(source.contains("fun <R> decodeMethod("))
        assertTrue(
            source.contains(
                "${hex(generic.constructorId)} -> ${generic.qualifiedCodecName}.readBare(reader, context.nested(), resultCodec)",
            ),
        )
        assertTrue(source.contains("is $type<*> -> encodeGenericMethod"))
        assertTrue(source.contains("${generic.qualifiedCodecName}.writeBare(writer, value, value.resultCodec)"))
        assertFalse(source.contains(" as "))
    }

    private fun assertDuplicate(
        expected: TlRegistryGenerationFailure,
        mutate: (org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan,
            org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan) ->
            org.monogram.tools.tl.codegen.emit.codec.TlDeclarationCodecPlan,
    ) {
        val cloud = codecs.plan.schemas.single { it.schemaKey.kind == TlSchemaKind.CLOUD }
        val first = cloud.constructors[0]
        val second = cloud.constructors[1]
        val changed = cloud.copy(
            constructors = cloud.constructors.map { if (it === second) mutate(first, second) else it },
        )
        val malformed = codecs.copy(
            plan = codecs.plan.copy(
                schemas = codecs.plan.schemas.map { if (it.schemaKey == cloud.schemaKey) changed else it },
            ),
        )

        val failure = assertThrows(TlRegistryGenerationException::class.java) {
            TlRegistryGenerator().generate(malformed)
        }
        assertEquals(expected, failure.reason)
        assertEquals(cloud.schemaKey, failure.schemaKey)
        assertNotNull(failure.message)
    }

    private fun assertBodyStartsWithSchemaCheck(source: String, signature: String) {
        val start = source.indexOf(signature)
        assertTrue("Missing $signature", start >= 0)
        val body = source.substring(start + signature.length)
        assertTrue(body.trimStart().startsWith("if (schema != context.schema)"))
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var index = indexOf(value)
        while (index >= 0) {
            count += 1
            index = indexOf(value, index + value.length)
        }
        return count
    }

    private fun hex(value: UInt): String = "0x${value.toString(16).padStart(8, '0')}u"

    companion object {
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val schemas by lazy {
            TlSchemaDocumentReader.readManifest(repositoryRoot.resolve("protocol/schema/manifest.json"))
        }
        private val declarations by lazy { TlDeclarationGenerator().generate(schemas) }
        private val codecs by lazy { TlCodecGenerator().generate(declarations) }
        private val registries by lazy { TlRegistryGenerator().generate(codecs) }
    }
}
