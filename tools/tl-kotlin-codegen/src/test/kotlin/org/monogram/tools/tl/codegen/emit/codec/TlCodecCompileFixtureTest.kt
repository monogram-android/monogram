package org.monogram.tools.tl.codegen.emit.codec

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.model.TlApplicationKind
import org.monogram.tools.tl.codegen.model.TlArgumentValue
import org.monogram.tools.tl.codegen.model.TlCondition
import org.monogram.tools.tl.codegen.model.TlDeclaration
import org.monogram.tools.tl.codegen.model.TlDeclarationKind
import org.monogram.tools.tl.codegen.model.TlDocumentation
import org.monogram.tools.tl.codegen.model.TlExpression
import org.monogram.tools.tl.codegen.model.TlFlagWord
import org.monogram.tools.tl.codegen.model.TlGenericParameter
import org.monogram.tools.tl.codegen.model.TlIdOrigin
import org.monogram.tools.tl.codegen.model.TlParameter
import org.monogram.tools.tl.codegen.model.TlReferenceKind
import org.monogram.tools.tl.codegen.model.TlSchemaKey
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import java.nio.file.Files
import java.nio.file.Path

class TlCodecCompileFixtureTest {
    @Test
    fun `generated declaration and codec fixture compiles against exact ABI and S2 contract`() {
        val declarations = TlDeclarationGenerator().generate(listOf(fixtureSchema()))
        val codecs = TlCodecGenerator().generate(declarations)
        val sourceRoot = Files.createTempDirectory("wp004-codec-compile")
        (declarations.files + codecs.files).forEach { file ->
            val output = sourceRoot.resolve(file.relativePath)
            Files.createDirectories(output.parent)
            Files.writeString(output, file.content)
        }
        val registry = codecs.registryPlans.single().registry
        val registryPath = sourceRoot.resolve(registry.packageName.replace('.', '/') + "/${registry.objectName}.kt")
        Files.createDirectories(registryPath.parent)
        Files.writeString(registryPath, registryStub(registry.packageName, registry.objectName))

        val runtimeSources = kotlinSources(repositoryRoot.resolve("mtproto/src/main/java/org/monogram/mtproto/tl/runtime"))
        val generatedSources = kotlinSources(sourceRoot)
        val output = Files.createTempDirectory("wp004-codec-classes")
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
    }

    private fun fixtureSchema(): ValidatedTlSchema {
        val result = identifier("fixture.Result", TlReferenceKind.NAMED_BOXED)
        val wrapper = identifier("fixture.Wrapper", TlReferenceKind.NAMED_BOXED)
        val generic = identifier("X", TlReferenceKind.TYPE_PARAMETER)
        val flags = TlParameter(
            name = "flags",
            value = TlArgumentValue.Type(TlExpression.Hash),
            implicit = false,
            functional = false,
            condition = null,
            description = null,
            sourceOrder = 0,
        )
        return ValidatedTlSchema(
            formatVersion = 1,
            key = TlSchemaKey(TlSchemaKind.CLOUD, 223),
            source = TlSourceMetadata("fixture", "", Path.of("fixture.json"), null),
            constructors = listOf(
                declaration("fixture.value", 1u, TlDeclarationKind.CONSTRUCTOR, result),
                declaration(
                    "fixture.wrapper",
                    2u,
                    TlDeclarationKind.CONSTRUCTOR,
                    wrapper,
                    parameters = listOf(
                        flags,
                        parameter(
                            "item",
                            TlExpression.Bare(result),
                            1,
                            condition = TlCondition("flags", 0),
                        ),
                        parameter(
                            "enabled",
                            primitive("true"),
                            2,
                            condition = TlCondition("flags", 1),
                        ),
                    ),
                    flagWords = listOf(TlFlagWord("flags", 0, 3u)),
                ),
            ),
            functions = listOf(
                declaration("fixture.get_int", 3u, TlDeclarationKind.FUNCTION, primitive("int")),
                declaration(
                    "fixture.get_vector",
                    4u,
                    TlDeclarationKind.FUNCTION,
                    TlExpression.Application(
                        constructor = identifier("Vector", TlReferenceKind.NAMED_BOXED),
                        arguments = listOf(primitive("string")),
                        applicationKind = TlApplicationKind.VECTOR,
                    ),
                ),
                declaration("fixture.get_object", 5u, TlDeclarationKind.FUNCTION, identifier("Object", TlReferenceKind.OBJECT)),
                declaration(
                    "fixture.invoke",
                    6u,
                    TlDeclarationKind.FUNCTION,
                    generic,
                    parameters = listOf(parameter("query", generic, 1, functional = true)),
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
        flagWords: List<TlFlagWord> = emptyList(),
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
        flagWords = flagWords,
    )

    private fun parameter(
        name: String,
        expression: TlExpression,
        order: Int,
        condition: TlCondition? = null,
        functional: Boolean = false,
    ): TlParameter = TlParameter(
        name = name,
        value = TlArgumentValue.Type(expression),
        implicit = false,
        functional = functional,
        condition = condition,
        description = null,
        sourceOrder = order,
    )

    private fun primitive(name: String): TlExpression.Identifier = identifier(name, TlReferenceKind.PRIMITIVE)

    private fun identifier(name: String, kind: TlReferenceKind): TlExpression.Identifier = TlExpression.Identifier(name, kind)

    private fun registryStub(packageName: String, objectName: String): String = """
        package $packageName

        import org.monogram.mtproto.tl.runtime.TlCodec
        import org.monogram.mtproto.tl.runtime.TlConstructorRegistry
        import org.monogram.mtproto.tl.runtime.TlDecodeContext
        import org.monogram.mtproto.tl.runtime.TlObject
        import org.monogram.mtproto.tl.runtime.TlReader
        import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
        import org.monogram.mtproto.tl.runtime.TlSchemaKind
        import org.monogram.mtproto.tl.runtime.TlWriter

        object $objectName : TlConstructorRegistry {
            override val schema = TlSchemaIdentity(TlSchemaKind.CLOUD, 223)
            override fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject =
                throw UnsupportedOperationException()
            fun encode(writer: TlWriter, value: TlObject) = Unit
            fun decodeMethod(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject =
                throw UnsupportedOperationException()
            fun <R> decodeMethod(
                id: UInt,
                reader: TlReader,
                context: TlDecodeContext,
                resultCodec: TlCodec<R>,
            ): TlObject = throw UnsupportedOperationException()
            fun encodeMethod(writer: TlWriter, value: TlObject) = Unit
        }
    """.trimIndent()

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.sorted().toList()
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
