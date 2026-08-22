package org.monogram.mtproto.tl.runtime

import org.junit.Assert.assertSame
import org.junit.Test

class TlContractsCompileTest {
    @Test
    fun `methods compile with primitive vector generic and object results`() {
        val intCodec = FixtureCodec<Int>()
        val vectorCodec = FixtureCodec<List<String>>()
        val objectCodec = FixtureCodec<FixtureObject>()
        val genericCodec = FixtureCodec<String>()

        val primitive: TlMethod<Int> = FixtureMethod(intCodec)
        val vector: TlMethod<List<String>> = FixtureMethod(vectorCodec)
        val objectResult: TlMethod<FixtureObject> = FixtureMethod(objectCodec)
        val generic: TlMethod<String> = genericMethod(genericCodec)

        assertSame(intCodec, primitive.resultCodec)
        assertSame(vectorCodec, vector.resultCodec)
        assertSame(objectCodec, objectResult.resultCodec)
        assertSame(genericCodec, generic.resultCodec)
    }

    private fun <R> genericMethod(codec: TlCodec<R>): TlMethod<R> = FixtureMethod(codec)

    private class FixtureMethod<R>(
        override val resultCodec: TlCodec<R>,
    ) : TlMethod<R> {
        override val constructorId: UInt = 0xfedcba98u
    }

    private class FixtureObject : TlObject {
        override val constructorId: UInt = 1u
    }

    private class FixtureCodec<T> : TlCodec<T> {
        override fun read(reader: TlReader, context: TlDecodeContext): T =
            error("Compile fixture has no wire behavior")

        override fun write(writer: TlWriter, value: T) = Unit
    }
}
