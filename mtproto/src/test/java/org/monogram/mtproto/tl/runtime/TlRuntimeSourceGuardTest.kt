package org.monogram.mtproto.tl.runtime

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlRuntimeSourceGuardTest {
    @Test
    fun `frozen contracts retain exact capability signatures and generic surface`() {
        assertTrue(TlObject::class.java.isInterface)
        assertTrue(TlMethod::class.java.isInterface)
        assertTrue(TlCodec::class.java.isInterface)
        assertTrue(TlConstructorRegistry::class.java.isInterface)
        assertTrue(TlReader::class.java.isInterface)
        assertTrue(TlWriter::class.java.isInterface)
        assertEquals(2, TlCodec::class.java.declaredMethods.size)
        assertEquals(2, TlConstructorRegistry::class.java.declaredMethods.size)

        assertEquals(
            setOf(
                MethodShape("getAbsoluteOffset", Long::class.javaPrimitiveType!!),
                MethodShape("getSize", Long::class.javaPrimitiveType!!),
                MethodShape("readInt", Int::class.javaPrimitiveType!!),
                MethodShape("readLong", Long::class.javaPrimitiveType!!),
                MethodShape("readDouble", Double::class.javaPrimitiveType!!),
                MethodShape("readBool", Boolean::class.javaPrimitiveType!!, TlDecodeContext::class.java),
                MethodShape("readBytes", TlBytes::class.java, TlDecodeContext::class.java),
                MethodShape("readString", String::class.java, TlDecodeContext::class.java),
                MethodShape("readInt128", TlInt128::class.java),
                MethodShape("readInt256", TlInt256::class.java),
                MethodShape(
                    "readDeferredObject",
                    TlDeferredObject::class.java,
                    Int::class.javaPrimitiveType!!,
                    TlDecodeContext::class.java,
                ),
                MethodShape("readRemainingDeferredObject", TlDeferredObject::class.java, TlDecodeContext::class.java),
                MethodShape("readVector", List::class.java, TlCodec::class.java, TlDecodeContext::class.java),
            ),
            TlReader::class.java.declaredMethods.map { MethodShape(it) }.toSet(),
        )
        assertEquals(
            setOf(
                MethodShape("getAbsoluteOffset", Long::class.javaPrimitiveType!!),
                MethodShape("getSize", Long::class.javaPrimitiveType!!),
                MethodShape("writeInt", Void.TYPE, Int::class.javaPrimitiveType!!),
                MethodShape("writeLong", Void.TYPE, Long::class.javaPrimitiveType!!),
                MethodShape("writeDouble", Void.TYPE, Double::class.javaPrimitiveType!!),
                MethodShape("writeBool", Void.TYPE, Boolean::class.javaPrimitiveType!!),
                MethodShape("writeBytes", Void.TYPE, TlBytes::class.java),
                MethodShape("writeString", Void.TYPE, String::class.java),
                MethodShape("writeInt128", Void.TYPE, TlInt128::class.java),
                MethodShape("writeInt256", Void.TYPE, TlInt256::class.java),
                MethodShape("writeDeferredObject", Void.TYPE, TlDeferredObject::class.java),
                MethodShape("writeVector", Void.TYPE, List::class.java, TlCodec::class.java),
            ),
            TlWriter::class.java.declaredMethods.map { MethodShape(it) }.toSet(),
        )

        val readVector = TlReader::class.java.getDeclaredMethod(
            "readVector",
            TlCodec::class.java,
            TlDecodeContext::class.java,
        )
        assertEquals(listOf("T"), readVector.typeParameters.map { it.name })
        assertEquals("java.util.List<T>", readVector.genericReturnType.typeName)
        assertEquals(
            listOf("org.monogram.mtproto.tl.runtime.TlCodec<T>", TlDecodeContext::class.java.name),
            readVector.genericParameterTypes.map { it.typeName },
        )

        val writeVector = TlWriter::class.java.getDeclaredMethod("writeVector", List::class.java, TlCodec::class.java)
        assertEquals(listOf("T"), writeVector.typeParameters.map { it.name })
        assertEquals("void", writeVector.genericReturnType.typeName)
        assertEquals(
            listOf("java.util.List<? extends T>", "org.monogram.mtproto.tl.runtime.TlCodec<T>"),
            writeVector.genericParameterTypes.map { it.typeName },
        )
    }

    @Test
    fun `value wrappers are final and deferred object stores no decoded value`() {
        listOf(TlBytes::class.java, TlInt128::class.java, TlInt256::class.java, TlDeferredObject::class.java).forEach {
            assertTrue(Modifier.isFinal(it.modifiers))
        }
        val deferredFields = TlDeferredObject::class.java.declaredFields
            .filterNot { it.name == "Companion" }
        assertEquals(1, deferredFields.size)
        assertEquals(ByteArray::class.java, deferredFields.single().type)
    }

    @Test
    fun `handwritten runtime source contains no codec algorithms dependencies or depth bypass`() {
        val sources = Files.list(runtimeSourceRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .toList()
                .associate { it.fileName.toString() to Files.readString(it) }
        }
        val allSource = sources.values.joinToString("\n")

        assertEquals(
            setOf("TlContracts.kt", "TlExceptions.kt", "TlIo.kt", "TlLimits.kt", "TlSchema.kt", "TlValues.kt"),
            sources.keys,
        )
        assertFalse(allSource.contains("org.monogram.tools"))
        assertFalse(allSource.contains("tl.generated"))
        assertFalse(allSource.contains("ByteBuffer"))
        assertFalse(allSource.contains("ByteOrder"))
        assertFalse(allSource.contains("GZIPInputStream"))
        assertFalse(allSource.contains("GZIPOutputStream"))
        assertFalse(allSource.contains("java.util.zip"))
        assertFalse(Regex("class\\s+\\w+\\s*:\\s*TlCodec").containsMatchIn(allSource))
        assertTrue(Regex("interface\\s+TlCodec\\s*<\\s*T\\s*>").containsMatchIn(sources.getValue("TlContracts.kt")))
        val exceptionSource = sources.getValue("TlExceptions.kt")
        assertTrue(Regex("sealed\\s+class\\s+TlCodecException").containsMatchIn(exceptionSource))
        assertFalse(
            Regex("(?s)TlCodecException\\s+protected\\s+constructor\\s*\\([^)]*\\b(message|cause)\\b")
                .containsMatchIn(exceptionSource),
        )

        val directContextConstruction = Regex("\\bTlDecodeContext\\s*\\(")
        val depthCopyMutation = Regex("(?s)\\bcopy\\s*\\(.*?\\bdepth\\s*=")
        assertTrue(directContextConstruction.containsMatchIn("TlDecodeContext  \n (schema, 0, limits)"))
        assertTrue(depthCopyMutation.containsMatchIn("context.copy  (\n limits = limits,\n depth = 1)"))
        sources.filterKeys { it != "TlSchema.kt" }.forEach { (name, source) ->
            assertFalse("$name constructs TlDecodeContext directly", directContextConstruction.containsMatchIn(source))
            assertFalse("$name mutates depth through copy", depthCopyMutation.containsMatchIn(source))
        }
    }

    private data class MethodShape(
        val name: String,
        val returnType: Class<*>,
        val parameterTypes: List<Class<*>>,
    ) {
        constructor(name: String, returnType: Class<*>, vararg parameterTypes: Class<*>) :
            this(name, returnType, parameterTypes.toList())

        constructor(method: java.lang.reflect.Method) :
            this(method.name, method.returnType, method.parameterTypes.toList())
    }

    private companion object {
        val runtimeSourceRoot: Path by lazy {
            listOf(
                Path.of("mtproto/src/main/java/org/monogram/mtproto/tl/runtime"),
                Path.of("src/main/java/org/monogram/mtproto/tl/runtime"),
            ).first(Files::isDirectory)
        }
    }
}
