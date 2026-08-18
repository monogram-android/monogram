package org.monogram.mtproto.codec

import org.monogram.mtproto.tl.generated.cloud.layer223.registry.CloudLayer223ConstructorRegistry
import org.monogram.mtproto.tl.runtime.TlDecodeContext
import org.monogram.mtproto.tl.runtime.TlLimits
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.tl.runtime.TlSchemaIdentity
import org.monogram.mtproto.tl.runtime.TlSchemaKind

object CloudTlObjectCodec {
    private val context = TlDecodeContext(
        schema = TlSchemaIdentity(TlSchemaKind.CLOUD, CLOUD_LAYER),
        depth = 0,
        limits = TlLimits.DEFAULT,
    )

    fun encode(value: TlObject): ByteArray =
        TlBinaryWriter().also { CloudLayer223ConstructorRegistry.encode(it, value) }.toByteArray()

    fun decode(bytes: ByteArray): TlObject =
        TlBinaryCodec.decodeObject(CloudLayer223ConstructorRegistry, bytes, context)

    private const val CLOUD_LAYER = 223
}
