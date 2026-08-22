package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.account.ToggleSponsoredMessages

internal fun interface MtProtoPremiumRepository {
    suspend fun setSponsoredMessagesEnabled(enabled: Boolean)
}

/** Applies the Premium ads preference only after the server accepts the exact requested state. */
internal class MtProtoPremiumRepositoryImpl(
    private val transportFactory: MtProtoSessionTransportFactory,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoPremiumRepository {
    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) {
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(ToggleSponsoredMessages(enabled))) {
                "MTProto sponsored message preference was rejected"
            }
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
