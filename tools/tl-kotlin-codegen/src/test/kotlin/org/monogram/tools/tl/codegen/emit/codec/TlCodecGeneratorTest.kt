package org.monogram.tools.tl.codegen.emit.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlDocumentation
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlIdOrigin
import org.monogram.tools.tl.codegen.model.TlParameter
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader
import java.nio.file.Files
import java.nio.file.Path

class TlCodecGeneratorTest {
    @Test
    fun `plans complete corpus coverage with D-023 exclusions`() {
        val result = generated

        assertEquals(14, result.registryPlans.size)
        assertEquals(2_220, result.coverage.concreteConstructorCount)
        assertEquals(766, result.coverage.methodResultCount)
        assertEquals(8, result.coverage.excludedCount)
        assertEquals(4, result.plan.exclusions.count { it.reason == TlCodecExclusionReason.BUILTIN_PRIMITIVE })
        assertEquals(2, result.plan.exclusions.count { it.reason == TlCodecExclusionReason.BUILTIN_VECTOR })
        assertEquals(2, result.plan.exclusions.count { it.reason == TlCodecExclusionReason.FORMAL_REPETITION })
        assertEquals(8, result.plan.exclusions.size)
        assertEquals(
            setOf(
                "CLOUD:223:CONSTRUCTOR:vector:1cb5c415:BUILTIN_VECTOR",
                "TRANSPORT:null:CONSTRUCTOR:int:a8509bda:BUILTIN_PRIMITIVE",
                "TRANSPORT:null:CONSTRUCTOR:long:22076cba:BUILTIN_PRIMITIVE",
                "TRANSPORT:null:CONSTRUCTOR:double:2210c154:BUILTIN_PRIMITIVE",
                "TRANSPORT:null:CONSTRUCTOR:string:b5286e24:BUILTIN_PRIMITIVE",
                "TRANSPORT:null:CONSTRUCTOR:vector:1cb5c415:BUILTIN_VECTOR",
                "TRANSPORT:null:CONSTRUCTOR:int128:84ccf7b7:FORMAL_REPETITION",
                "TRANSPORT:null:CONSTRUCTOR:int256:7bedeb5b:FORMAL_REPETITION",
            ),
            result.plan.exclusions.mapTo(mutableSetOf()) {
                "${it.schemaKey.kind}:${it.schemaKey.layer}:${it.declarationKind}:${it.tlName}:" +
                    "${it.constructorId.toString(16).padStart(8, '0')}:${it.reason}"
            },
        )
    }

    @Test
    fun `emits sorted deterministic codec files and registry-consumable plans`() {
        val first = generated
        val second = TlCodecGenerator().generate(declarations)

        assertEquals(first.files.map { it.relativePath }.sorted(), first.files.map { it.relativePath })
        assertEquals(first.files.map { it.relativePath }, second.files.map { it.relativePath })
        first.files.zip(second.files).forEach { (left, right) -> assertEquals(left.content, right.content) }
        assertEquals(first.plan, second.plan)
        assertTrue(first.registryPlans.all { schema ->
            schema.constructors.all { it.schemaKey == schema.schemaKey } &&
                schema.methods.all { it.schemaKey == schema.schemaKey } &&
                schema.methodResults.all { it.schemaKey == schema.schemaKey }
        })
        assertEquals(
            "org.monogram.mtproto.tl.generated.transport.registry.TransportConstructorRegistry",
            first.registryPlans.single { it.schemaKey.kind == TlSchemaKind.TRANSPORT }.registry.qualifiedName,
        )
    }

    @Test
    fun `emits exact transport policies and ordinary gzip bytes`() {
        val message = sourceFor("message")
        val rpcResult = sourceFor("rpc_result")
        val gzip = sourceFor("gzip_packed")

        assertTrue(message.contains("reader.readDeferredObject(_field2, context)"))
        assertTrue(message.contains("require(value.bytes == value.body.size)"))
        assertTrue(message.contains("writer.writeDeferredObject(value.body)"))
        assertTrue(rpcResult.contains("reader.readRemainingDeferredObject(context)"))
        assertTrue(rpcResult.contains("writer.writeDeferredObject(value.result)"))
        assertTrue(gzip.contains("reader.readBytes(context)"))
        assertTrue(gzip.contains("writer.writeBytes(value.packedData)"))
        assertFalse(gzip.contains("readDeferredObject"))
        assertFalse(gzip.contains("readRemainingDeferredObject"))
    }

    @Test
    fun `unconstrained object delegates current context to registry`() {
        val objectMethod = declaration("fixture.getObject", 2u).copy(
            kind = TlDeclarationKind.FUNCTION,
            result = TlExpression.Identifier("Object", TlReferenceKind.OBJECT),
        )
        val fixture = fixtureSchema(
            constructors = listOf(declaration("fixture.value", 1u)),
            functions = listOf(objectMethod),
        )
        val result = TlCodecGenerator().generate(TlDeclarationGenerator().generate(listOf(fixture)))
        val resultPlan = result.plan.methodResultCodecs.single()
        val registry = (resultPlan.codec as TlValueCodecPlan.UnconstrainedObject).registry.qualifiedName
        val declarationPlan = result.plan.declarationCodecs.single { it.tlName == objectMethod.name }
        val source = result.files.single { it.relativePath == declarationPlan.relativePath }.content

        assertTrue(source.contains("$registry.decode(reader.readInt().toUInt(), reader, context)"))
        assertFalse(source.contains("$registry.decode(reader.readInt().toUInt(), reader, context.nested())"))
    }

    @Test
    fun `emits primitives wrappers vectors generics boxed bare and UInt boundaries`() {
        val allSource = generated.files.joinToString("\n") { it.content }
        val generic = generated.plan.declarationCodecs.single { it.tlName == "invokeAfterMsg" }
        val genericSource = generated.files.single { it.relativePath == generic.relativePath }.content
        val container = sourceFor("msg_container")

        assertTrue(allSource.contains("reader.readInt128()"))
        assertTrue(allSource.contains("reader.readInt256()"))
        assertTrue(allSource.contains("reader.readVector("))
        assertTrue(generated.plan.familyCodecs.isNotEmpty())
        assertTrue(allSource.contains("BoxedCodec.read(reader, context.nested())"))
        assertFalse(allSource.contains("BoxedCodec.read(reader, context.nested().nested())"))
        assertTrue(allSource.contains("else -> throw TlUnknownConstructorException(context.schema, _constructorId, _constructorOffset)"))
        assertTrue(genericSource.contains(" as TlMethod<X>"))
        assertFalse(allSource.contains(".decode(reader.readInt().toUInt(), reader, context.nested()) as"))
        assertTrue(allSource.contains("writer.writeInt(") && allSource.contains(".CONSTRUCTOR_ID.toInt())"))
        assertTrue(genericSource.contains("fun <X> bind(codec0: TlCodec<X>)"))
        assertTrue(genericSource.contains("val _methodId = reader.readInt().toUInt()"))
        assertTrue(genericSource.contains("readBare(reader, context.nested(), codec0)"))
        assertTrue(genericSource.contains("codec0.read(reader, context.nested())"))
        val bareMessage = ((generated.plan.declarationCodecs.single { it.tlName == "msg_container" }
            .fields.single().codec as TlValueCodecPlan.Vector).element as TlValueCodecPlan.NamedBare)
        assertTrue(container.contains("${bareMessage.codecQualifiedName}.readBare(reader, context.nested())"))
        assertFalse(allSource.contains("context.copy("))
        assertFalse(allSource.contains("TlDecodeContext("))
        assertFalse(allSource.contains("java.lang.reflect"))
        assertFalse(allSource.contains("kotlinx.serialization"))
    }

    @Test
    fun `derives flags preserves source order and emits shared-bit coherence`() {
        val shared = generated.plan.declarationCodecs.first { plan ->
            plan.sharedFlagBits.any { it.fields.size > 1 }
        }
        val source = generated.files.single { it.relativePath == shared.relativePath }.content
        val sourceOrders = shared.wireMembers.map(TlWireMemberPlan::sourceOrder)

        assertEquals(sourceOrders.sorted(), sourceOrders)
        assertTrue(source.contains("reader.readInt().toUInt()"))
        assertTrue(source.contains("must have coherent presence"))
        assertTrue(source.contains("var _flags0 = 0u"))
        assertTrue(source.contains("writer.writeInt(_flags0.toInt())"))
        shared.fields.filter(TlFieldCodecPlan::independentFlag).forEach { field ->
            assertFalse(source.contains("writer.writeBool(value.${field.kotlinName})"))
        }
    }

    @Test
    fun `fails codec name collision without reallocating`() {
        val fixture = fixtureSchema(
            constructors = listOf(
                declaration("fixture.foo", 1u),
                declaration("fixture.foo_codec", 2u),
            ),
        )
        val generatedDeclarations = TlDeclarationGenerator().generate(listOf(fixture))

        val failure = assertThrows(TlCodecPlanningException::class.java) {
            TlCodecGenerator().generate(generatedDeclarations)
        }
        assertEquals(TlCodecPlanningFailure.NAME_COLLISION, failure.reason)
        assertTrue(failure.message.orEmpty().contains("does not reallocate"))
    }

    @Test
    fun `rejects builtin and repetition lookalikes outside exact D-023 identities`() {
        val builtin = declaration("int", 1u, builtin = true)
        val builtinFailure = assertThrows(TlCodecPlanningException::class.java) {
            TlCodecGenerator().generate(TlDeclarationGenerator().generate(listOf(fixtureSchema(listOf(builtin)))))
        }
        assertEquals(TlCodecPlanningFailure.UNSUPPORTED_BUILTIN, builtinFailure.reason)
        assertTrue(builtinFailure.message.orEmpty().contains("not an approved pinned identity"))

        val repeated = repetitionParameter()
        val vector = declaration("vector", 0x1cb5c414u, listOf(repeated))
        val vectorFailure = assertThrows(TlCodecPlanningException::class.java) {
            TlCodecGenerator().generate(TlDeclarationGenerator().generate(listOf(fixtureSchema(listOf(vector)))))
        }
        assertEquals(TlCodecPlanningFailure.UNSUPPORTED_BUILTIN, vectorFailure.reason)
        assertTrue(vectorFailure.message.orEmpty().contains("not an approved pinned identity"))
    }

    @Test
    fun `fails unsupported non-builtin repetition`() {
        val repeated = repetitionParameter()
        val fixture = fixtureSchema(constructors = listOf(declaration("fixture.repeat", 3u, listOf(repeated))))
        val generatedDeclarations = TlDeclarationGenerator().generate(listOf(fixture))

        val failure = assertThrows(TlCodecPlanningException::class.java) {
            TlCodecGenerator().generate(generatedDeclarations)
        }
        assertEquals(TlCodecPlanningFailure.UNSUPPORTED_REPETITION, failure.reason)
    }

    private fun sourceFor(tlName: String): String {
        val plan = generated.plan.declarationCodecs.single {
            it.schemaKey.kind == TlSchemaKind.TRANSPORT && it.tlName == tlName
        }
        return generated.files.single { it.relativePath == plan.relativePath }.content
    }

    private fun fixtureSchema(
        constructors: List<TlDeclaration>,
        functions: List<TlDeclaration> = emptyList(),
    ): ValidatedTlSchema = ValidatedTlSchema(
        formatVersion = 1,
        key = TlSchemaKey(TlSchemaKind.CLOUD, 223),
        source = TlSourceMetadata("fixture", "", Path.of("fixture.json"), null),
        constructors = constructors,
        functions = functions,
        finalizations = emptyList(),
        partialApplications = emptyList(),
    )

    private fun declaration(
        name: String,
        id: UInt,
        parameters: List<TlParameter> = emptyList(),
        builtin: Boolean = false,
    ): TlDeclaration = TlDeclaration(
        name = name,
        id = id,
        idHex = id.toString(16).padStart(8, '0'),
        idOrigin = TlIdOrigin.EXPLICIT,
        kind = TlDeclarationKind.CONSTRUCTOR,
        parameters = parameters,
        result = TlExpression.Identifier("fixture.Result", TlReferenceKind.NAMED_BOXED),
        documentation = TlDocumentation(null, emptyMap(), null, emptyList()),
        schemaLayer = 223,
        introducedLayer = null,
        builtin = builtin,
        sourceOrder = id.toInt(),
        genericParameters = emptyList(),
        flagWords = emptyList(),
    )

    private fun repetitionParameter(): TlParameter = TlParameter(
        name = "items",
        value = TlArgumentValue.Repetition(
            multiplicity = TlExpression.Natural(1uL),
            parameters = listOf(
                TlParameter(
                    name = "item",
                    value = TlArgumentValue.Type(primitive("int")),
                    implicit = false,
                    functional = false,
                    condition = null,
                    description = null,
                    sourceOrder = 0,
                ),
            ),
        ),
        implicit = false,
        functional = false,
        condition = null,
        description = null,
        sourceOrder = 0,
    )

    private fun primitive(name: String): TlExpression.Identifier =
        TlExpression.Identifier(name, TlReferenceKind.PRIMITIVE)

    companion object {
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val schemas by lazy {
            TlSchemaDocumentReader.readManifest(repositoryRoot.resolve("protocol/schema/manifest.json"))
        }
        private val declarations by lazy { TlDeclarationGenerator().generate(schemas) }
        private val generated by lazy { TlCodecGenerator().generate(declarations) }
    }
}
