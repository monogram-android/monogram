package org.monogram.tools.tl.codegen.emit.codec

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
import org.junit.Test
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlCodec
import org.monogram.mtproto.tl.runtime.TlConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlDeferredObject
import org.monogram.mtproto.tl.runtime.TlInt128
import org.monogram.mtproto.tl.runtime.TlInt256
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlReader
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind
import org.monogram.mtproto.tl.runtime.TlSchemaMismatchException
import org.monogram.mtproto.tl.runtime.TlUnknownConstructorException
import org.monogram.mtproto.tl.runtime.TlWriter
import org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerator
import org.monogram.tools.tl.codegen.emit.registry.TlRegistryGenerator
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
import org.monogram.tools.tl.codegen.model.TlSchemaKind as ModelSchemaKind
import org.monogram.tools.tl.codegen.model.TlSourceMetadata
import org.monogram.tools.tl.codegen.model.TlTransportPolicy
import org.monogram.tools.tl.codegen.model.ValidatedTlSchema
import java.lang.reflect.InvocationTargetException

class TlGeneratedCodecRuntimeTest {
    @Test
    fun `generated declarations codecs and registries execute against exact six source ABI`() {
        val declarations = TlDeclarationGenerator().generate(listOf(fixtureSchema()))
        val codecs = TlCodecGenerator().generate(declarations)
        val registries = TlRegistryGenerator().generate(codecs)
        val sourceRoot = Files.createTempDirectory("wp004-runtime-sources")
        (declarations.files + codecs.files + registries.files).forEach { file ->
            val output = sourceRoot.resolve(file.relativePath)
            Files.createDirectories(output.parent)
            Files.writeString(output, file.content)
        }

        val runtimeSources = exactRuntimeSources()
        val generatedSources = kotlinSources(sourceRoot)
        val classes = Files.createTempDirectory("wp004-runtime-classes")
        val messages = BoundedMessages()
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = (runtimeSources + generatedSources).map(Path::toString)
            destination = classes.toString()
            classpath = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
            jdkHome = System.getProperty("java.home")
            jvmTarget = "17"
            noStdlib = true
            noReflect = true
        }
        val exit = K2JVMCompiler().exec(messages, Services.EMPTY, arguments)
        assertEquals(messages.render(), ExitCode.OK, exit)

        val schema = TlSchemaIdentity(TlSchemaKind.CLOUD, 223)
        val context = TlDecodeContext(schema, 0, TlLimits.DEFAULT)
        val registryPlan = registries.plan.schemas.single()
        val registryName = registryPlan.contract.qualifiedName
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val registryClass = loader.loadClass(registryName)
            val registry = registryClass.getField("INSTANCE").get(null) as TlConstructorRegistry

            val wrapper = symbol(declarations, "fixture.wrapper")
            val wrapperClass = generatedClass(loader, wrapper)
            val boxA = symbol(declarations, "fixture.box_a")
            val boxB = symbol(declarations, "fixture.box_b")
            val bareValue = symbol(declarations, "fixture.bare_value")
            val boxAClass = generatedClass(loader, boxA)
            val boxBClass = generatedClass(loader, boxB)
            val bareClass = generatedClass(loader, bareValue)
            val boxAValue = boxAClass.getDeclaredConstructor(String::class.java).newInstance("boxed")
            val bareValueObject = bareClass.getDeclaredConstructor(String::class.java).newInstance("bare")
            val wrapperValue = wrapperClass.declaredConstructors.single().newInstance(
                boxAValue,
                bareValueObject,
                true,
                listOf(7, 8),
                "label",
            )

            val writer = RecordingWriter()
            registryClass.getMethod("encode", TlWriter::class.java, TlObject::class.java)
                .invoke(registry, writer, wrapperValue)
            assertEquals(
                listOf(
                    "int:${WRAPPER_ID.toInt()}",
                    "int:3",
                    "int:${BOX_A_ID.toInt()}",
                    "string:boxed",
                    "string:bare",
                    "int:2",
                    "int:7",
                    "int:8",
                    "string:label",
                ),
                writer.events,
            )

            val reader = RecordingReader(
                ints = listOf(3, BOX_A_ID.toInt(), 2, 7, 8),
                strings = listOf("boxed", "bare", "label"),
            )
            val decoded = registry.decode(WRAPPER_ID, reader, context)
            assertEquals(WRAPPER_ID, decoded.constructorId)
            assertEquals("boxed", getter(getter(decoded, "getBoxed")!!, "getPayload"))
            assertEquals("bare", getter(getter(decoded, "getBare")!!, "getPayload"))
            assertEquals(true, getter(decoded, "getEnabled"))
            assertEquals(listOf(7, 8), getter(decoded, "getNumbers"))
            assertEquals("label", getter(decoded, "getLabel"))
            assertEquals(listOf(3, BOX_A_ID.toInt(), 2, 7, 8), reader.readInts)
            assertEquals(emptyList<Int>(), reader.vectorContexts)
            assertEquals(listOf(2, 2, 1), reader.stringContexts)

            val incoherent = wrapperClass.declaredConstructors.single().newInstance(
                boxAValue,
                null,
                true,
                emptyList<Int>(),
                "label",
            )
            val incoherentFailure = assertThrows(InvocationTargetException::class.java) {
                registryClass.getMethod("encode", TlWriter::class.java, TlObject::class.java)
                    .invoke(registry, RecordingWriter(), incoherent)
            }
            assertTrue(incoherentFailure.cause is IllegalArgumentException)

            val boxBReader = RecordingReader()
            val decodedB = registry.decode(BOX_B_ID, boxBReader, context)
            assertEquals(boxBClass.name, decodedB.javaClass.name)
            assertEquals(0, boxBReader.readAttempts)

            val boxFamily = codecs.plan.familyCodecs.single { it.tlName == "fixture.Box" }
            val boxFamilyCodec = codecInstance(loader, boxFamily.contract.objectName, boxFamily.packageName)
            val wrongFamilyReader = RecordingReader(
                ints = listOf(OTHER_ID.toInt()),
                strings = listOf("must-not-be-read"),
            )
            val wrongFamilyFailure = assertThrows(InvocationTargetException::class.java) {
                boxFamilyCodec.javaClass.getMethod("read", TlReader::class.java, TlDecodeContext::class.java)
                    .invoke(boxFamilyCodec, wrongFamilyReader, context)
            }
            assertTrue(wrongFamilyFailure.cause is TlUnknownConstructorException)
            assertEquals(1, wrongFamilyReader.readAttempts)
            assertTrue(wrongFamilyReader.readStrings.isEmpty())

            val packet = symbol(declarations, "fixture.packet")
            val packetClass = generatedClass(loader, packet)
            val packetValue = packetClass.declaredConstructors.single().newInstance(
                4,
                TlDeferredObject.copyOf(byteArrayOf(1, 2, 3, 4), 32),
            )
            val packetReader = RecordingReader(
                ints = listOf(4),
                deferred = listOf(TlDeferredObject.copyOf(byteArrayOf(1, 2, 3, 4), 32)),
            )
            registry.decode(PACKET_ID, packetReader, context)
            assertEquals(listOf(4 to 1), packetReader.exactDeferred)
            val packetWriter = RecordingWriter()
            registryClass.getMethod("encode", TlWriter::class.java, TlObject::class.java)
                .invoke(registry, packetWriter, packetValue)
            assertEquals(
                listOf("int:${PACKET_ID.toInt()}", "int:4", "deferred:4"),
                packetWriter.events,
            )

            val remainder = symbol(declarations, "fixture.remainder")
            val remainderClass = generatedClass(loader, remainder)
            val remainderValue = remainderClass.declaredConstructors.single().newInstance(
                TlDeferredObject.copyOf(byteArrayOf(9, 8), 32),
            )
            val remainderReader = RecordingReader(
                remaining = listOf(TlDeferredObject.copyOf(byteArrayOf(9, 8), 32)),
            )
            registry.decode(REMAINDER_ID, remainderReader, context)
            assertEquals(listOf(1), remainderReader.remainingContexts)
            val remainderWriter = RecordingWriter()
            registryClass.getMethod("encode", TlWriter::class.java, TlObject::class.java)
                .invoke(registry, remainderWriter, remainderValue)
            assertEquals(
                listOf("int:${REMAINDER_ID.toInt()}", "deferred:2"),
                remainderWriter.events,
            )

            val intCodec = RecordingIntCodec()
            val genericResultPlan = codecs.plan.methodResultCodecs.single { it.methodTlName == "fixture.invoke" }
            val genericResultCodecObject = codecInstance(loader, genericResultPlan.kotlinName, genericResultPlan.packageName)
            @Suppress("UNCHECKED_CAST")
            val boundResultCodec = genericResultCodecObject.javaClass
                .getMethod("bind", TlCodec::class.java)
                .invoke(genericResultCodecObject, intCodec) as TlCodec<Any?>
            val genericResultReader = RecordingReader(ints = listOf(55))
            assertEquals(55, boundResultCodec.read(genericResultReader, context))
            assertEquals(listOf(1), intCodec.readContexts)
            val genericResultWriter = RecordingWriter()
            boundResultCodec.write(genericResultWriter, 66)
            assertEquals(listOf("int:66"), genericResultWriter.events)

            val decodeMethod = registryClass.methods.single {
                (it.name == "decodeMethod" || it.name.startsWith("decodeMethod-")) && it.parameterCount == 3
            }
            val decodeGenericMethod = registryClass.methods.single {
                (it.name == "decodeMethod" || it.name.startsWith("decodeMethod-")) && it.parameterCount == 4
            }

            val leafClass = generatedClass(loader, symbol(declarations, "fixture.leaf"))
            val leafReader = RecordingReader(strings = listOf("decoded-leaf"))
            val decodedLeafMethod = decodeMethod.invoke(
                registry,
                LEAF_ID.toInt(),
                leafReader,
                context,
            ) as TlMethod<*>
            assertEquals(LEAF_ID, decodedLeafMethod.constructorId)
            assertEquals(leafClass.name, decodedLeafMethod.javaClass.name)
            assertEquals("decoded-leaf", getter(decodedLeafMethod, "getToken"))
            assertEquals(listOf(1), leafReader.stringContexts)

            val explicitResultCodec = RecordingIntCodec()
            val genericReader = RecordingReader(
                ints = listOf(LEAF_ID.toInt(), 77),
                strings = listOf("generic-leaf"),
            )
            val decodedGenericMethod = decodeGenericMethod.invoke(
                registry,
                INVOKE_ID.toInt(),
                genericReader,
                context,
                explicitResultCodec,
            ) as TlMethod<*>
            assertEquals(INVOKE_ID, decodedGenericMethod.constructorId)
            assertEquals(leafClass.name, getter(decodedGenericMethod, "getQuery")!!.javaClass.name)
            assertEquals("generic-leaf", getter(getter(decodedGenericMethod, "getQuery")!!, "getToken"))
            assertEquals(77, getter(decodedGenericMethod, "getValue_"))
            assertEquals(
                "Explicit result codec was not propagated into the generic value decoder",
                listOf(2),
                explicitResultCodec.readContexts,
            )
            @Suppress("UNCHECKED_CAST")
            val decodedGenericResultCodec = decodedGenericMethod.resultCodec as TlCodec<Any?>
            val decodedGenericResultReader = RecordingReader(ints = listOf(88))
            assertEquals(88, decodedGenericResultCodec.read(decodedGenericResultReader, context))
            assertEquals(listOf(88), decodedGenericResultReader.readInts)

            val leafMethod = leafClass.declaredConstructors.single().newInstance("leaf")
            val invokeClass = generatedClass(loader, symbol(declarations, "fixture.invoke"))
            val genericMethod = invokeClass.declaredConstructors.single().newInstance(leafMethod, 44) as TlMethod<*>
            assertEquals(INVOKE_ID, genericMethod.constructorId)
            assertEquals(leafClass.name, getter(genericMethod, "getQuery")!!.javaClass.name)
            assertEquals("leaf", getter(getter(genericMethod, "getQuery")!!, "getToken"))
            assertEquals(44, getter(genericMethod, "getValue_"))
            val genericWriter = RecordingWriter()
            registryClass.getMethod("encodeMethod", TlWriter::class.java, TlMethod::class.java)
                .invoke(registry, genericWriter, genericMethod)
            assertEquals(
                listOf(
                    "int:${INVOKE_ID.toInt()}",
                    "int:${LEAF_ID.toInt()}",
                    "string:leaf",
                    "int:44",
                ),
                genericWriter.events,
            )

            val unknownReader = RecordingReader(
                ints = listOf(123),
                strings = listOf("must-not-be-read"),
            )
            val unknownFailure = assertThrows(InvocationTargetException::class.java) {
                decodeMethod.invoke(registry, UNKNOWN_METHOD_ID.toInt(), unknownReader, context)
            }
            val unknownCause = unknownFailure.cause as TlUnknownConstructorException
            assertEquals(UNKNOWN_METHOD_ID, unknownCause.constructorId)
            assertEquals(0, unknownReader.readAttempts)
            assertTrue(unknownReader.readInts.isEmpty())
            assertTrue(unknownReader.readStrings.isEmpty())

            val mismatchCodec = RecordingIntCodec()
            val mismatchReader = RecordingReader(
                ints = listOf(LEAF_ID.toInt(), 99),
                strings = listOf("must-not-be-read"),
            )
            val mismatchedSchema = TlSchemaIdentity(TlSchemaKind.CLOUD, 222)
            val mismatchContext = TlDecodeContext(mismatchedSchema, 0, TlLimits.DEFAULT)
            val mismatchFailure = assertThrows(InvocationTargetException::class.java) {
                decodeGenericMethod.invoke(
                    registry,
                    INVOKE_ID.toInt(),
                    mismatchReader,
                    mismatchContext,
                    mismatchCodec,
                )
            }
            val mismatchCause = mismatchFailure.cause as TlSchemaMismatchException
            assertEquals(mismatchedSchema, mismatchCause.expectedSchema)
            assertEquals(schema, mismatchCause.actualSchema)
            assertEquals(0, mismatchReader.readAttempts)
            assertTrue(mismatchReader.readInts.isEmpty())
            assertTrue(mismatchReader.readStrings.isEmpty())
            assertTrue(mismatchCodec.readContexts.isEmpty())

            val objectMethodReader = RecordingReader()
            val objectMethod = decodeMethod.invoke(
                registry,
                OBJECT_METHOD_ID.toInt(),
                objectMethodReader,
                context,
            ) as TlMethod<*>
            assertEquals(OBJECT_METHOD_ID, objectMethod.constructorId)
            assertEquals(0, objectMethodReader.readAttempts)
            @Suppress("UNCHECKED_CAST")
            val objectResultCodec = objectMethod.resultCodec as TlCodec<TlObject>
            val maxDepthContext = TlDecodeContext(
                schema,
                0,
                TlLimits.DEFAULT.lowered(maxDepth = 1),
            )
            val objectResultReader = RecordingReader(
                ints = listOf(BOX_A_ID.toInt()),
                strings = listOf("at-depth-limit"),
            )
            val objectResult = objectResultCodec.read(objectResultReader, maxDepthContext)
            assertEquals(boxAClass.name, objectResult.javaClass.name)
            assertEquals("at-depth-limit", getter(objectResult, "getPayload"))
            assertEquals(listOf(BOX_A_ID.toInt()), objectResultReader.readInts)
            assertEquals(listOf(1), objectResultReader.stringContexts)
        }
    }

    private fun fixtureSchema(): ValidatedTlSchema {
        val box = identifier("fixture.Box", TlReferenceKind.NAMED_BOXED)
        val bare = identifier("fixture.BareValue", TlReferenceKind.NAMED_BOXED)
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
            key = TlSchemaKey(ModelSchemaKind.CLOUD, 223),
            source = TlSourceMetadata("runtime-fixture", "", Path.of("fixture.json"), null),
            constructors = listOf(
                declaration("fixture.box_a", BOX_A_ID, TlDeclarationKind.CONSTRUCTOR, box, listOf(parameter("payload", primitive("string"), 0))),
                declaration("fixture.box_b", BOX_B_ID, TlDeclarationKind.CONSTRUCTOR, box),
                declaration("fixture.bare_value", BARE_ID, TlDeclarationKind.CONSTRUCTOR, bare, listOf(parameter("payload", primitive("string"), 0))),
                declaration(
                    "fixture.other",
                    OTHER_ID,
                    TlDeclarationKind.CONSTRUCTOR,
                    identifier("fixture.Other", TlReferenceKind.NAMED_BOXED),
                    listOf(parameter("payload", primitive("string"), 0)),
                ),
                declaration(
                    "fixture.wrapper",
                    WRAPPER_ID,
                    TlDeclarationKind.CONSTRUCTOR,
                    identifier("fixture.Wrapper", TlReferenceKind.NAMED_BOXED),
                    parameters = listOf(
                        flags,
                        parameter("boxed", box, 1),
                        parameter("bare", TlExpression.Bare(bare), 2, TlCondition("flags", 0)),
                        parameter("enabled", primitive("true"), 3, TlCondition("flags", 0)),
                        parameter(
                            "numbers",
                            TlExpression.Application(
                                constructor = identifier("vector", TlReferenceKind.NAMED_BOXED),
                                arguments = listOf(primitive("int")),
                                applicationKind = TlApplicationKind.VECTOR,
                            ),
                            4,
                            TlCondition("flags", 1),
                        ),
                        parameter("label", primitive("string"), 5),
                    ),
                    flagWords = listOf(TlFlagWord("flags", 0, 3u)),
                ),
                declaration(
                    "fixture.packet",
                    PACKET_ID,
                    TlDeclarationKind.CONSTRUCTOR,
                    identifier("fixture.Packet", TlReferenceKind.NAMED_BOXED),
                    parameters = listOf(
                        parameter("bytes", primitive("int"), 0),
                        parameter(
                            "body",
                            identifier("Object", TlReferenceKind.OBJECT),
                            1,
                            transportPolicy = TlTransportPolicy.ExactLengthDeferred("bytes"),
                        ),
                    ),
                ),
                declaration(
                    "fixture.remainder",
                    REMAINDER_ID,
                    TlDeclarationKind.CONSTRUCTOR,
                    identifier("fixture.Remainder", TlReferenceKind.NAMED_BOXED),
                    parameters = listOf(
                        parameter(
                            "tail",
                            identifier("Object", TlReferenceKind.OBJECT),
                            0,
                            transportPolicy = TlTransportPolicy.RemainingDeferred,
                        ),
                    ),
                ),
            ),
            functions = listOf(
                declaration("fixture.leaf", LEAF_ID, TlDeclarationKind.FUNCTION, primitive("int"), listOf(parameter("token", primitive("string"), 0))),
                declaration(
                    "fixture.invoke",
                    INVOKE_ID,
                    TlDeclarationKind.FUNCTION,
                    identifier("X", TlReferenceKind.TYPE_PARAMETER),
                    parameters = listOf(
                        parameter(
                            "query",
                            identifier("X", TlReferenceKind.TYPE_PARAMETER),
                            0,
                            functional = true,
                        ),
                        parameter("value", identifier("X", TlReferenceKind.TYPE_PARAMETER), 1),
                    ),
                    genericParameters = listOf(TlGenericParameter("X", 0)),
                ),
                declaration(
                    "fixture.object_method",
                    OBJECT_METHOD_ID,
                    TlDeclarationKind.FUNCTION,
                    identifier("Object", TlReferenceKind.OBJECT),
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
        sourceOrder: Int,
        condition: TlCondition? = null,
        functional: Boolean = false,
        transportPolicy: TlTransportPolicy = TlTransportPolicy.None,
    ): TlParameter = TlParameter(
        name = name,
        value = TlArgumentValue.Type(expression),
        implicit = false,
        functional = functional,
        condition = condition,
        description = null,
        sourceOrder = sourceOrder,
        transportPolicy = transportPolicy,
    )

    private fun primitive(name: String): TlExpression.Identifier = identifier(name, TlReferenceKind.PRIMITIVE)

    private fun identifier(name: String, kind: TlReferenceKind): TlExpression.Identifier = TlExpression.Identifier(name, kind)

    private fun symbol(
        declarations: org.monogram.tools.tl.codegen.emit.declaration.TlDeclarationGenerationResult,
        name: String,
    ) = declarations.symbolTable.declarations.single { it.source.name == name }

    private fun generatedClass(loader: ClassLoader, symbol: Any): Class<*> {
        val packageName = symbol.javaClass.getMethod("getPackageName").invoke(symbol) as String
        val kotlinName = symbol.javaClass.getMethod("getKotlinName").invoke(symbol) as String
        return loader.loadClass("$packageName.$kotlinName")
    }

    private fun codecInstance(loader: ClassLoader, name: String, packageName: String): Any =
        loader.loadClass("$packageName.$name").getField("INSTANCE").get(null)

    private fun getter(value: Any, name: String): Any? = value.javaClass.getMethod(name).invoke(value)

    private fun exactRuntimeSources(): List<Path> {
        val root = repositoryRoot.resolve("mtproto/src/main/java/org/monogram/mtproto/tl/runtime")
        val sources = kotlinSources(root)
        assertEquals(EXACT_RUNTIME_ABI_FILES, sources.map { it.fileName.toString() })
        return sources
    }

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.sorted().toList()
    }

    private class RecordingReader(
        ints: List<Int> = emptyList(),
        strings: List<String> = emptyList(),
        private val vectorValues: List<Int> = emptyList(),
        deferred: List<TlDeferredObject> = emptyList(),
        remaining: List<TlDeferredObject> = emptyList(),
    ) : TlReader {
        private val intQueue = ArrayDeque(ints)
        private val stringQueue = ArrayDeque(strings)
        private val deferredQueue = ArrayDeque(deferred)
        private val remainingQueue = ArrayDeque(remaining)
        var readAttempts = 0
            private set
        val readInts = mutableListOf<Int>()
        val readStrings = mutableListOf<String>()
        val vectorContexts = mutableListOf<Int>()
        val stringContexts = mutableListOf<Int>()
        val exactDeferred = mutableListOf<Pair<Int, Int>>()
        val remainingContexts = mutableListOf<Int>()
        override val absoluteOffset: Long get() = readAttempts.toLong() * Int.SIZE_BYTES
        override val size: Long = 4096

        override fun readInt(): Int {
            readAttempts += 1
            return intQueue.removeFirst().also(readInts::add)
        }

        override fun readLong(): Long = error("Unexpected long read")
        override fun readDouble(): Double = error("Unexpected double read")
        override fun readBool(context: TlDecodeContext): Boolean = error("Unexpected bool read")

        override fun readBytes(context: TlDecodeContext): TlBytes = error("Unexpected bytes read")

        override fun readString(context: TlDecodeContext): String {
            stringContexts += context.depth
            return stringQueue.removeFirst().also(readStrings::add)
        }

        override fun readInt128(): TlInt128 = error("Unexpected int128 read")
        override fun readInt256(): TlInt256 = error("Unexpected int256 read")

        override fun readDeferredObject(byteCount: Int, context: TlDecodeContext): TlDeferredObject {
            exactDeferred += byteCount to context.depth
            return deferredQueue.removeFirst()
        }

        override fun readRemainingDeferredObject(context: TlDecodeContext): TlDeferredObject {
            remainingContexts += context.depth
            return remainingQueue.removeFirst()
        }

        override fun <T> readVector(codec: TlCodec<T>, context: TlDecodeContext): List<T> {
            vectorContexts += context.depth
            return List(vectorValues.size) { codec.read(this, context) }
        }

    }

    private class RecordingWriter : TlWriter {
        val events = mutableListOf<String>()
        override val absoluteOffset: Long get() = events.size.toLong()
        override val size: Long get() = events.size.toLong()
        override fun writeInt(value: Int) { events += "int:$value" }
        override fun writeLong(value: Long) = error("Unexpected long write")
        override fun writeDouble(value: Double) = error("Unexpected double write")
        override fun writeBool(value: Boolean) = error("Unexpected bool write")
        override fun writeBytes(value: TlBytes) = error("Unexpected bytes write")
        override fun writeString(value: String) { events += "string:$value" }
        override fun writeInt128(value: TlInt128) = error("Unexpected int128 write")
        override fun writeInt256(value: TlInt256) = error("Unexpected int256 write")
        override fun writeDeferredObject(value: TlDeferredObject) { events += "deferred:${value.size}" }
        override fun <T> writeVector(values: List<T>, codec: TlCodec<T>) {
            events += "vector:${values.size}"
            values.forEach { codec.write(this, it) }
        }

    }

    private class RecordingIntCodec : TlCodec<Int> {
        val readContexts = mutableListOf<Int>()
        override fun read(reader: TlReader, context: TlDecodeContext): Int {
            readContexts += context.depth
            return reader.readInt()
        }

        override fun write(writer: TlWriter, value: Int) = writer.writeInt(value)
    }

    private class BoundedMessages : MessageCollector {
        private val output = StringBuilder()
        private var errors = false
        override fun clear() { output.clear(); errors = false }
        override fun hasErrors(): Boolean = errors
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            if (severity.isError) errors = true
            if (output.length < 64_000) {
                output.append(severity).append(": ").append(message)
                location?.let { output.append(" at ").append(it.path).append(':').append(it.line) }
                output.appendLine()
            }
        }
        fun render(): String = output.toString()
    }

    companion object {
        private const val WRAPPER_ID: UInt = 0x30000001u
        private const val BOX_A_ID: UInt = 0x11000001u
        private const val BOX_B_ID: UInt = 0x11000002u
        private const val BARE_ID: UInt = 0x12000001u
        private const val OTHER_ID: UInt = 0x13000001u
        private const val PACKET_ID: UInt = 0x40000001u
        private const val REMAINDER_ID: UInt = 0x41000001u
        private const val LEAF_ID: UInt = 0x50000001u
        private const val INVOKE_ID: UInt = 0x50000002u
        private const val OBJECT_METHOD_ID: UInt = 0x50000003u
        private const val UNKNOWN_METHOD_ID: UInt = 0xf0000001u
        private val repositoryRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("protocol/schema/manifest.json")) }
        private val EXACT_RUNTIME_ABI_FILES = listOf(
            "TlContracts.kt",
            "TlExceptions.kt",
            "TlIo.kt",
            "TlLimits.kt",
            "TlSchema.kt",
            "TlValues.kt",
        )
    }
}
