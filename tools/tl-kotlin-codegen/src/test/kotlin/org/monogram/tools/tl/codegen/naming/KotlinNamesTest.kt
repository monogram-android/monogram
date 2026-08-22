package org.monogram.tools.tl.codegen.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinNamesTest {
    @Test
    fun `maps TL names keywords acronyms and digits deterministically`() {
        assertEquals("RpcError", KotlinNames.type("RPCError"))
        assertEquals("XmlHttpRequest", KotlinNames.type("XMLHttpRequest"))
        assertEquals("Http2ApiResponse", KotlinNames.type("HTTP2APIResponse"))
        assertEquals("RpcError2", KotlinNames.type("RPC_error2"))
        assertEquals("rpcError2", KotlinNames.value("RPC_error2"))
        assertEquals("when_", KotlinNames.value("when"))
        assertEquals("class_", KotlinNames.packageSegment("class"))
        assertEquals("_2Fa", KotlinNames.value("2FA"))
    }

    @Test
    fun `acronym boundaries normalize into the same collision group`() {
        val result = DeterministicKotlinNameAllocator().allocate(
            listOf(
                KotlinNameRequest("a", "RPCError", "scope", KotlinNameStyle.TYPE),
                KotlinNameRequest("b", "rpc_error", "scope", KotlinNameStyle.TYPE),
            ),
        )

        assertEquals(1, result.collisions.size)
        assertEquals("RpcError", result.collisions.single().preferredName)
        assertNotEquals(result["a"].allocatedName, result["b"].allocatedName)
    }

    @Test
    fun `batch allocation resolves collisions independently of input order`() {
        val requests = listOf(
            KotlinNameRequest("a", "foo_bar", "scope", KotlinNameStyle.TYPE, "constructor"),
            KotlinNameRequest("b", "fooBar", "scope", KotlinNameStyle.TYPE, "constructor"),
        )
        val allocator = DeterministicKotlinNameAllocator()
        val forward = allocator.allocate(requests)
        val reverse = allocator.allocate(requests.reversed())

        assertEquals(forward, reverse)
        assertEquals(1, forward.collisions.size)
        assertNotEquals(forward["a"].allocatedName, forward["b"].allocatedName)
        assertTrue(forward["a"].allocatedName.startsWith("FooBar_"))
    }

    @Test
    fun `generated suffix does not collide with another preferred name regardless of order`() {
        val foo = KotlinNameRequest("a", "foo", "scope", KotlinNameStyle.PACKAGE, "namespace")
        val initialCandidate = "foo_${KotlinNames.stableSuffix(foo)}"
        val requests = listOf(
            foo,
            KotlinNameRequest("b", "Foo", "scope", KotlinNameStyle.PACKAGE, "namespace"),
            KotlinNameRequest("literal", initialCandidate, "scope", KotlinNameStyle.PACKAGE, "namespace"),
        )
        val allocator = DeterministicKotlinNameAllocator()
        val forward = allocator.allocate(requests)
        val reverse = allocator.allocate(requests.reversed())

        assertEquals(forward, reverse)
        assertEquals(initialCandidate, forward["literal"].allocatedName)
        assertNotEquals(initialCandidate, forward["a"].allocatedName)
        assertTrue(forward["a"].allocatedName.startsWith("${initialCandidate}"))
        assertEquals(3, forward.allocations.values.map { it.allocatedName }.toSet().size)
    }
}
