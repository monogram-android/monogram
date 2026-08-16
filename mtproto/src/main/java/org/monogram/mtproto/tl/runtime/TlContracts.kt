package org.monogram.mtproto.tl.runtime

interface TlObject {
    val constructorId: UInt
}

interface TlMethod<R> : TlObject {
    val resultCodec: TlCodec<R>
}

interface TlCodec<T> {
    fun read(reader: TlReader, context: TlDecodeContext): T

    fun write(writer: TlWriter, value: T)
}

interface TlConstructorRegistry {
    val schema: TlSchemaIdentity

    fun decode(id: UInt, reader: TlReader, context: TlDecodeContext): TlObject
}

interface TlMethodRegistry : TlConstructorRegistry {
    fun encodeMethod(writer: TlWriter, value: TlMethod<*>)
}
