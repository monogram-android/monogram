package org.monogram.tools.tl.codegen.emit.declaration

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.tools.tl.codegen.model.TlApplicationKind
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
import org.monogram.tools.tl.codegen.model.TlSchemaKind
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import org.monogram.tools.tl.codegen.naming.TlDeclarationSymbol
import org.monogram.tools.tl.codegen.validation.TlSchemaDocumentReader
import java.nio.file.Files
import java.nio.file.Path

class GeneratedAbiCompileFixtureTest {
    private data class DeclarationIdentity(
        val schemaKey: TlSchemaKey,
        val kind: TlDeclarationKind,
        val tlName: String,
        val constructorId: UInt,
    )

    @Test
    fun `all generated declarations and hostile KDoc compile against exact frozen ABI sources`() {
        val corpus = TlDeclarationGenerator().generate(
            TlSchemaDocumentReader.readManifest(repositoryRoot.resolve("protocol/schema/manifest.json")),
        )
        assertEquals(14, corpus.symbolTable.schemas.size)
        assertEquals(2_994, corpus.manifest.declarations.size)

        val hostile = TlDeclarationGenerator().generate(listOf(fixtureSchema()))
        assertHostileKDoc(hostile)
        assertZeroFieldObjects(hostile)

        val generations = listOf(corpus, hostile)
        val emittedFiles = generations.flatMap(TlDeclarationGenerationResult::files)
        assertEquals(emittedFiles.size, emittedFiles.map(GeneratedKotlinFile::relativePath).distinct().size)

        val expectedIdentities = corpus.manifest.declarations.map { entry ->
            DeclarationIdentity(entry.schemaKey, entry.kind, entry.tlName, entry.constructorId)
        }.toSet()
        val expectedFiles = corpus.files.map(GeneratedKotlinFile::relativePath).toSet()
        assertEquals(2_994, expectedIdentities.size)
        assertEquals(corpus.files.size, expectedFiles.size)

        val runtimeRoot = repositoryRoot.resolve("mtproto/src/main/java/org/monogram/mtproto/tl/runtime")
        val runtimeSources = kotlinSources(runtimeRoot)
        assertEquals(6, runtimeSources.size)
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val compileRoot = Files.createTempDirectory("wp003-generated-compile")
        val compiledIdentities = mutableSetOf<DeclarationIdentity>()
        val compiledFiles = mutableSetOf<String>()
        val hostileSchemaKey = hostile.symbolTable.schemas.single().schema.key

        corpus.symbolTable.schemas.forEachIndexed { index, schemaSymbols ->
            val schemaKey = schemaSymbols.schema.key
            val partitionPrefix = schemaSymbols.partition.packageName.replace('.', '/') + "/"
            val partitionFiles = corpus.files.filter { it.relativePath.startsWith(partitionPrefix) }
            val partitionEntries = corpus.manifest.declarations.filter { it.schemaKey == schemaKey }
            assertTrue("No generated files for $schemaKey", partitionFiles.isNotEmpty())
            assertTrue("No declarations for $schemaKey", partitionEntries.isNotEmpty())

            val sourceRoot = compileRoot.resolve("partition-$index-sources")
            val filesToCompile = if (schemaKey == hostileSchemaKey) partitionFiles + hostile.files else partitionFiles
            filesToCompile.forEach { file ->
                val output = sourceRoot.resolve(file.relativePath)
                Files.createDirectories(output.parent)
                Files.writeString(output, file.content)
            }
            writeBindingPlaceholders(sourceRoot, generations, setOf(schemaKey))

            if (schemaKey == hostileSchemaKey) {
                val objectMethod = hostile.symbolTable.declarations.single { it.source.name == "fixture.object_method" }
                val objectBinding = requireNotNull(objectMethod.resultCodecBinding)
                val objectBindingSource = Files.readString(
                    sourceRoot.resolve(
                        objectBinding.packageName.replace('.', '/') + "/__Wp004ResultCodecBindings.kt",
                    ),
                )
                assertTrue(
                    objectBindingSource.contains(
                        "object ${objectBinding.kotlinName} : Wp004BindingPlaceholder<TlObject>()",
                    ),
                )
            }

            val generatedSources = kotlinSources(sourceRoot)
            assertTrue(generatedSources.size >= filesToCompile.size)
            val classes = compileRoot.resolve("partition-$index-classes")
            Files.createDirectories(classes)
            val messages = BoundedMessageCollector()
            val arguments = K2JVMCompilerArguments().apply {
                freeArgs = (runtimeSources + generatedSources).map(Path::toString)
                destination = classes.toString()
                classpath = stdlib.toString()
                jdkHome = System.getProperty("java.home")
                jvmTarget = "17"
                noStdlib = true
                noReflect = true
            }

            val exitCode = K2JVMCompiler().exec(messages, Services.EMPTY, arguments)
            assertEquals("Partition $schemaKey\n${messages.render()}", ExitCode.OK, exitCode)

            compiledIdentities += partitionEntries.map { entry ->
                DeclarationIdentity(entry.schemaKey, entry.kind, entry.tlName, entry.constructorId)
            }
            compiledFiles += partitionFiles.map(GeneratedKotlinFile::relativePath)
            println(
                "WP-003 compiled $schemaKey: ${partitionEntries.size} declarations, " +
                    "${partitionFiles.size} generated files",
            )
        }

        assertEquals(expectedIdentities, compiledIdentities)
        assertEquals(expectedFiles, compiledFiles)
        assertEquals(2_994, compiledIdentities.size)
        assertEquals(corpus.files.size, compiledFiles.size)
    }

    private fun assertHostileKDoc(generated: TlDeclarationGenerationResult) {
        val hostileSource = generated.files.single { it.content.contains("class SourceInjection") }.content
        assertTrue(hostileSource.contains("Close * /"))
        assertTrue(hostileSource.contains("@param injected"))
        assertTrue(hostileSource.contains("[hostile](https://example.invalid/link)"))
        assertFalse(hostileSource.contains("*/\nclass SourceInjection"))
        assertEquals(1, Regex("/\\*").findAll(hostileSource).count())
        assertEquals(1, Regex("\\*/").findAll(hostileSource).count())

        val generic = generated.symbolTable.declarations.single { it.source.name == "fixture.generic_method" }
        assertEquals(listOf("query.resultCodec"), generic.resultCodecBinding!!.codecArgumentExpressions)
        assertTrue(
            generated.files.single { it.relativePath == generic.relativePath }.content
                .contains("GenericMethodResultCodec.bind(query.resultCodec)"),
        )
    }

    private fun assertZeroFieldObjects(generated: TlDeclarationGenerationResult) {
        val constructor = generated.symbolTable.declarations.single { it.source.name == "fixture.value" }
        val constructorSource = generated.files.single { it.relativePath == constructor.relativePath }.content
        assertTrue(constructorSource.contains("data object ${constructor.kotlinName} : Result {"))
        assertTrue(constructorSource.contains("override val constructorId: UInt"))
        assertTrue(constructorSource.contains("    const val CONSTRUCTOR_ID: UInt = 0xfffffff0u"))
        assertTrue(constructorSource.contains("    const val TL_NAME: String = \"fixture.value\""))
        assertFalse(constructorSource.contains("companion object"))

        val method = generated.symbolTable.declarations.single { it.source.name == "fixture.primitive_method" }
        val methodSource = generated.files.single { it.relativePath == method.relativePath }.content
        assertTrue(methodSource.contains("data object ${method.kotlinName} : TlMethod<Int> {"))
        assertTrue(methodSource.contains("override val resultCodec: TlCodec<Int>"))
        assertTrue(methodSource.contains("get() = PrimitiveMethodResultCodec"))
        assertTrue(methodSource.contains("    const val CONSTRUCTOR_ID: UInt = 0x00000001u"))
        assertFalse(methodSource.contains("companion object"))

        val objectMethod = generated.symbolTable.declarations.single { it.source.name == "fixture.object_method" }
        assertEquals(
            identifier("Object", TlReferenceKind.OBJECT),
            objectMethod.source.result,
        )
        val objectMethodSource = generated.files.single { it.relativePath == objectMethod.relativePath }.content
        assertTrue(objectMethodSource.contains("data object ${objectMethod.kotlinName} : TlMethod<TlObject> {"))
        assertTrue(objectMethodSource.contains("override val resultCodec: TlCodec<TlObject>"))
        assertTrue(objectMethodSource.contains("get() = ObjectMethodResultCodec"))

        val generic = generated.symbolTable.declarations.single { it.source.name == "fixture.generic_method" }
        val genericSource = generated.files.single { it.relativePath == generic.relativePath }.content
        assertTrue(genericSource.contains("data class ${generic.kotlinName}<X>("))
        assertTrue(genericSource.contains("    companion object {"))
    }

    private fun writeBindingPlaceholders(
        sourceRoot: Path,
        generations: List<TlDeclarationGenerationResult>,
        schemaKeys: Set<TlSchemaKey>,
    ) {
        val helper = sourceRoot.resolve("org/monogram/mtproto/tl/generated/fixture/Wp004BindingPlaceholder.kt")
        check(Files.notExists(helper)) { "Binding helper collides with emitted source: $helper" }
        Files.createDirectories(helper.parent)
        Files.writeString(
            helper,
            """
                package org.monogram.mtproto.tl.generated.fixture

                import org.monogram.mtproto.tl.runtime.TlCodec
                import org.monogram.mtproto.tl.runtime.TlDecodeContext
                import org.monogram.mtproto.tl.runtime.TlReader
                import org.monogram.mtproto.tl.runtime.TlWriter

                open class Wp004BindingPlaceholder<T> : TlCodec<T> {
                    override fun read(reader: TlReader, context: TlDecodeContext): T =
                        throw UnsupportedOperationException()

                    override fun write(writer: TlWriter, value: T) = throw UnsupportedOperationException()
                }
            """.trimIndent(),
        )

        val bindingFixtures = generations.flatMap { generation ->
            val renderer = TlKotlinTypeRenderer(generation.symbolTable)
            generation.symbolTable.declarations
                .filter { it.schema.key in schemaKeys }
                .mapNotNull { declaration ->
                    declaration.resultCodecBinding?.let { BindingFixture(declaration, renderer) }
                }
        }
        assertEquals(
            generations.sumOf { generation ->
                generation.symbolTable.declarations.count {
                    it.schema.key in schemaKeys && it.source.kind == TlDeclarationKind.FUNCTION
                }
            },
            bindingFixtures.size,
        )

        bindingFixtures.groupBy { it.declaration.resultCodecBinding!!.packageName }
            .toSortedMap()
            .forEach { (packageName, fixtures) ->
                val output = sourceRoot.resolve(
                    packageName.replace('.', '/') + "/__Wp004ResultCodecBindings.kt",
                )
                check(Files.notExists(output)) { "Binding placeholders collide with emitted source: $output" }
                Files.createDirectories(output.parent)
                Files.writeString(output, renderBindingFile(packageName, fixtures.sortedBy { it.binding.kotlinName }))
            }
    }

    private fun renderBindingFile(packageName: String, fixtures: List<BindingFixture>): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import org.monogram.mtproto.tl.generated.fixture.Wp004BindingPlaceholder")
        appendLine("import org.monogram.mtproto.tl.runtime.TlCodec")
        appendLine("import org.monogram.mtproto.tl.runtime.TlObject")
        fixtures.forEach { fixture ->
            val binding = fixture.binding
            val resultType = fixture.renderer.renderResult(binding.resultExpression, fixture.declaration)
            appendLine()
            if (binding.genericTypeParameters.isEmpty()) {
                appendLine("object ${binding.kotlinName} : Wp004BindingPlaceholder<$resultType>()")
            } else {
                assertEquals(binding.genericTypeParameters.size, binding.codecArgumentExpressions.size)
                val typeParameters = binding.genericTypeParameters.joinToString()
                val codecParameters = binding.genericTypeParameters.mapIndexed { index, typeParameter ->
                    "codec$index: TlCodec<$typeParameter>"
                }.joinToString()
                appendLine("object ${binding.kotlinName} {")
                appendLine("    @Suppress(\"UNUSED_PARAMETER\")")
                appendLine("    fun <$typeParameters> bind($codecParameters): TlCodec<$resultType> =")
                appendLine("        Wp004BindingPlaceholder()")
                appendLine("}")
            }
        }
    }

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .sorted()
            .toList()
    }

    private fun fixtureSchema(): ValidatedTlSchema {
        val resultType = identifier("fixture.Result", TlReferenceKind.NAMED_BOXED)
        val genericType = identifier("X", TlReferenceKind.TYPE_PARAMETER)
        return ValidatedTlSchema(
            formatVersion = 1,
            key = TlSchemaKey(TlSchemaKind.CLOUD, 223),
            source = TlSourceMetadata("compile-fixture", "", Path.of("fixture.json"), null),
            constructors = listOf(
                declaration(
                    name = "fixture.value",
                    id = 0xfffffff0u,
                    kind = TlDeclarationKind.CONSTRUCTOR,
                    result = resultType,
                    documentation = TlDocumentation(
                        description = "Close */\n@param injected class SourceInjection\n[hostile](https://example.invalid/link)",
                        parameters = emptyMap(),
                        officialUrl = "https://example.invalid/reference/*/still-commented",
                        links = listOf("https://example.invalid/link"),
                    ),
                ),
            ),
            functions = listOf(
                declaration("fixture.primitive_method", 1u, TlDeclarationKind.FUNCTION, primitive("int")),
                declaration(
                    "fixture.vector_method",
                    2u,
                    TlDeclarationKind.FUNCTION,
                    TlExpression.Application(
                        constructor = identifier("vector", TlReferenceKind.NAMED_BOXED),
                        arguments = listOf(primitive("string")),
                        applicationKind = TlApplicationKind.VECTOR,
                    ),
                ),
                declaration(
                    "fixture.object_method",
                    3u,
                    TlDeclarationKind.FUNCTION,
                    identifier("Object", TlReferenceKind.OBJECT),
                ),
                declaration(
                    name = "fixture.generic_method",
                    id = 4u,
                    kind = TlDeclarationKind.FUNCTION,
                    result = genericType,
                    parameters = listOf(
                        TlParameter(
                            name = "query",
                            value = TlArgumentValue.Type(genericType),
                            implicit = false,
                            functional = true,
                            condition = null,
                            description = null,
                            sourceOrder = 0,
                        ),
                    ),
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
        documentation: TlDocumentation = TlDocumentation(null, emptyMap(), null, emptyList()),
    ): TlDeclaration = TlDeclaration(
        name = name,
        id = id,
        idHex = id.toString(16).padStart(8, '0'),
        idOrigin = TlIdOrigin.EXPLICIT,
        kind = kind,
        parameters = parameters,
        result = result,
        documentation = documentation,
        schemaLayer = 223,
        introducedLayer = null,
        builtin = false,
        sourceOrder = id.toInt(),
        genericParameters = genericParameters,
        flagWords = emptyList(),
    )

    private fun primitive(name: String): TlExpression = identifier(name, TlReferenceKind.PRIMITIVE)

    private fun identifier(name: String, kind: TlReferenceKind): TlExpression.Identifier =
        TlExpression.Identifier(name, kind)

    private data class BindingFixture(
        val declaration: TlDeclarationSymbol,
        val renderer: TlKotlinTypeRenderer,
    ) {
        val binding get() = requireNotNull(declaration.resultCodecBinding)
    }

    private class BoundedMessageCollector : MessageCollector {
        private val output = StringBuilder()
        private var errors = false
        private var messageCount = 0
        private var omittedCharacters = 0

        override fun clear() {
            output.clear()
            errors = false
            messageCount = 0
            omittedCharacters = 0
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) errors = true
            messageCount++
            val rendered = buildString {
                append(severity).append(": ").append(message)
                location?.let { append(" at ").append(it.path).append(':').append(it.line) }
                appendLine()
            }
            val remaining = MAX_DIAGNOSTIC_CHARACTERS - output.length
            if (remaining > 0) output.append(rendered.take(remaining))
            omittedCharacters += (rendered.length - remaining.coerceAtLeast(0)).coerceAtLeast(0)
        }

        fun render(): String = buildString {
            append("Compiler diagnostics: ").append(messageCount).appendLine()
            append(output)
            if (omittedCharacters > 0) {
                appendLine()
                append("Diagnostics bounded at ").append(MAX_DIAGNOSTIC_CHARACTERS)
                    .append(" characters; omitted ").append(omittedCharacters).appendLine(" characters.")
            }
        }
    }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARACTERS = 256 * 1024

        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
    }
}
