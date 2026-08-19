package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_ba027bdead
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_d319adbade

internal class MtProtoDialogResultStager(
    private val dialogStore: MtProtoDialogStore,
) {
    suspend fun stage(scope: MtProtoAuthKeyScope, result: Dialogs_ba027bdead): Boolean = when (result) {
        is Dialogs_d319adbade -> {
            dialogStore.upsert(scope, result.dialogs.filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd>())
            true
        }
        is DialogsSlice -> {
            dialogStore.upsert(scope, result.dialogs.filterIsInstance<org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd>())
            true
        }
        else -> false
    }
}
