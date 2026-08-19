package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_ba027bdead
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_d319adbade

internal class MtProtoDialogResultStager(
    private val dialogStore: MtProtoDialogStore,
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
) {
    suspend fun stage(scope: MtProtoAuthKeyScope, result: Dialogs_ba027bdead): Boolean {
        val payload = when (result) {
            is Dialogs_d319adbade -> DialogPayload(result.dialogs, result.users, result.chats)
            is DialogsSlice -> DialogPayload(result.dialogs, result.users, result.chats)
            else -> return false
        }
        dialogStore.upsert(
            scope,
            payload.dialogs.filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd>(),
        )
        userStore?.upsert(scope, payload.users)
        chatStore?.upsert(scope, payload.chats)
        return true
    }

    private data class DialogPayload(
        val dialogs: List<org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_79d3c6da89>,
        val users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        val chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>,
    )
}
