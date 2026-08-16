package org.monogram.tools.tl.codegen.emit.registry

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlReader
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind
import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException
import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException
import org.monogram.mtproto.tl.runtime.TlWriter
import org.monogram.tools.tl.codegen.emit.codec.TlCodecGenerator
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlDocumentation
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlGenericParameter
import org.monogram.tools.tl.codegen.model.TlIdOrigin
import org.monogram.tools.tl.codegen.model.TlParameter
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema

class TlRegistryCompileFixtureTest {
    @Test
    fun `generated registry compiles against exact ABI and rejects schema before consumption`() {
        val declarations = TlDeclarationGenerator().generate(listOf(fixtureSchema()))
        val codecs = TlCodecGenerator().generate(declarations)
        val registries = TlRegistryGenerator().generate(codecs)
        val sourceRoot = Files.createTempDirectory("wp004-registry-compile")
        (declarations.files + codecs.files + registries.files).forEach { file ->
            val output = sourceRoot.resolve(file.relativePath)
            Files.createDirectories(output.parent)
            Files.writeString(output, file.content)
        }

        val output = compile(sourceRoot)
        val registryPlan = registries.plan.schemas.single()
        URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
            val registryClass = loader.loadClass(registryPlan.contract.qualifiedName)
            val registry = registryClass.getField("INSTANCE").get(null) as TlConstructorRegistry
            val cloud = TlSchemaIdentity(TlSchemaKind.CLOUD, 223)
            val transport = TlSchemaIdentity(TlSchemaKind.TRANSPORT, null)

            val mismatchReader = NoConsumptionReader(73L)
            val mismatch = assertThrows(TlSchemaMismatchException::class.java) {
                registry.decode(1u, mismatchReader, TlDecodeContext(transport, 0, TlLimits.DEFAULT))
            }
            assertEquals(transport, mismatch.expectedSchema)
            assertEquals(cloud, mismatch.actualSchema)
            assertEquals(73L, mismatch.absoluteOffset)
            assertEquals(0, mismatchReader.readAttempts)

            val unknownReader = NoConsumptionReader(92L)
            val unknown = assertThrows(TlUnknownConstructorException::class.java) {
                registry.decode(UInt.MAX_VALUE, unknownReader, TlDecodeContext(cloud, 0, TlLimits.DEFAULT))
            }
            assertEquals(UInt.MAX_VALUE, unknown.constructorId)
            assertEquals(cloud, unknown.schema)
            assertEquals(88L, unknown.absoluteOffset)
            assertEquals(0, unknownReader.readAttempts)

            val knownReader = NoConsumptionReader(91L)
            val known = registry.decode(1u, knownReader, TlDecodeContext(cloud, 0, TlLimits.DEFAULT))
            assertEquals(1u, known.constructorId)
            assertEquals(0, knownReader.readAttempts)

            val encode = registryClass.methods.single { it.name == "encode" }
            val writer = RecordingWriter()
            encode.invoke(registry, writer, known)
            assertEquals(listOf(1), writer.ints)

            val forged = object : TlObject { override val constructorId: UInt = 1u }
            val forgedFailure = assertThrows(InvocationTargetException::class.java) {
                encode.invoke(registry, RecordingWriter(), forged)
            }
            assertTrue(forgedFailure.cause is IllegalArgumentException)
        }
    }

    private fun compile(sourceRoot: Path): Path {
        val runtimeSources = kotlinSources(repositoryRoot.resolve("mtproto/src/main/java/org/monogram/mtproto/tl/runtime"))
        val generatedSources = kotlinSources(sourceRoot)
        val output = Files.createTempDirectory("wp004-registry-classes")
        val messages = BoundedMessages()
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = (runtimeSources + generatedSources).map(Path::toString)
            destination = output.toString()
            classpath = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
            jdkHome = System.getProperty("java.home")
            jvmTarget = "17"
            noStdlib = true
            noReflect = true
        }
        val exit = K2JVMCompiler().exec(messages, Services.EMPTY, arguments)
        assertEquals(messages.render(), ExitCode.OK, exit)
        return output
    }

    private fun fixtureSchema(): ValidatedTlSchema {
        val result = identifier("fixture.Result", TlReferenceKind.NAMED_BOXED)
        val generic = identifier("X", TlReferenceKind.TYPE_PARAMETER)
        return ValidatedTlSchema(
            formatVersion = 1,
            key = TlSchemaKey(org.monogram.tools.tl.codegen.model.TlSchemaKind.CLOUD, 223),
            source = TlSourceMetadata("fixture", "", Path.of("fixture.json"), null),
            constructors = listOf(declaration("fixture.value", 1u, TlDeclarationKind.CONSTRUCTOR, result)),
            functions = listOf(
                declaration("fixture.get_int", 2u, TlDeclarationKind.FUNCTION, primitive("int")),
                declaration(
                    "fixture.invoke",
                    3u,
                    TlDeclarationKind.FUNCTION,
                    generic,
                    parameters = listOf(parameter("query", generic, functional = true)),
                    genericParameters = listOf(TlGenericParameter("X", 0)),
                ),
            ),
            finalizations = emptyList(),
            partialApplications = emptyList(),
        )
    }

    private fun declaration(
        name: String,
        id: UInt,
        kind: TlDeclarationKind,
        result: TlExpression,
        parameters: List<TlParameter> = emptyList(),
        genericParameters: List<TlGenericParameter> = emptyList(),
    ): TlDeclaration = TlDeclaration(
        name = name,
        id = id,
        idHex = id.toString(16).padStart(8, '0'),
        idOrigin = TlIdOrigin.EXPLICIT,
        kind = kind,
        parameters = parameters,
        result = result,
        documentation = TlDocumentation(null, emptyMap(), null, emptyList()),
        schemaLayer = 223,
        introducedLayer = null,
        builtin = false,
        sourceOrder = id.toInt(),
        genericParameters = genericParameters,
        flagWords = emptyList(),
    )

    private fun parameter(name: String, expression: TlExpression, functional: Boolean): TlParameter = TlParameter(
        name = name,
        value = TlArgumentValue.Type(expression),
        implicit = false,
        functional = functional,
        condition = null,
        description = null,
        sourceOrder = 0,
    )

    private fun primitive(name: String): TlExpression.Identifier = identifier(name, TlReferenceKind.PRIMITIVE)

    private fun identifier(name: String, kind: TlReferenceKind): TlExpression.Identifier =
        TlExpression.Identifier(name, kind)

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.sorted().toList()
    }

    private class NoConsumptionReader(
        override val absoluteOffset: Long,
    ) : TlReader {
        var readAttempts: Int = 0
            private set
        override val size: Long = 0

        private fun consumed(): Nothing {
            readAttempts += 1
            fail("Registry consumed payload before rejecting")
            error("unreachable")
        }

        override fun readInt(): Int = consumed()
        override fun readLong(): Long = consumed()
        override fun readDouble(): Double = consumed()
        override fun readBool(context: TlDecodeContext): Boolean = consumed()
        override fun readBytes(context: TlDecodeContext): TlBytes = consumed()
        override fun readString(context: TlDecodeContext): String = consumed()
        override fun readInt128(): TlInt128 = consumed()
        override fun readInt256(): TlInt256 = consumed()
        override fun readDeferredObject(byteCount: Int, context: TlDecodeContext): TlDeferredObject = consumed()
        override fun readRemainingDeferredObject(context: TlDecodeContext): TlDeferredObject = consumed()
        override fun <T> readVector(codec: TlCodec<T>, context: TlDecodeContext): List<T> = consumed()
    }

    private class RecordingWriter : TlWriter {
        val ints = mutableListOf<Int>()
        override val absoluteOffset: Long get() = ints.size.toLong() * Int.SIZE_BYTES
        override val size: Long get() = absoluteOffset
        override fun writeInt(value: Int) { ints += value }
        override fun writeLong(value: Long) = unsupported()
        override fun writeDouble(value: Double) = unsupported()
        override fun writeBool(value: Boolean) = unsupported()
        override fun writeBytes(value: TlBytes) = unsupported()
        override fun writeString(value: String) = unsupported()
        override fun writeInt128(value: TlInt128) = unsupported()
        override fun writeInt256(value: TlInt256) = unsupported()
        override fun writeDeferredObject(value: TlDeferredObject) = unsupported()
        override fun <T> writeVector(values: List<T>, codec: TlCodec<T>) = unsupported()
        private fun unsupported(): Nothing = error("Unexpected fixture write")
    }

    private class BoundedMessages : MessageCollector {
        private val output = StringBuilder()
        private var errors = false
        override fun clear() { output.clear(); errors = false }
        override fun hasErrors(): Boolean = errors
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            if (severity.isError) errors = true
            if (output.length < 32_000) {
                output.append(severity).append(": ").append(message)
                location?.let { output.append(" at ").append(it.path).append(':').append(it.line) }
                output.appendLine()
            }
        }
        fun render(): String = output.toString()
    }

    companion object {
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
    }
}
