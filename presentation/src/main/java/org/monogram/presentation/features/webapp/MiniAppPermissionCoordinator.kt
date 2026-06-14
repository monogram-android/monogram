package org.monogram.presentation.features.webapp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.R
import java.net.URLEncoder

internal class MiniAppPermissionCoordinator(
    private val context: Context,
    private val botName: String,
    private val userRepository: UserRepository,
    private val scope: CoroutineScope,
    private val emitter: MiniAppResponseEmitter,
    private val requestDialog: (CustomMethodRequest) -> Unit,
    private val clearDialog: () -> Unit
) {
    private var pendingRequestedContact: String? = null

    fun requestPhone() {
        requestDialog(
            CustomMethodRequest(
                reqId = "req_phone",
                method = "web_app_request_phone",
                params = "",
                title = context.getString(R.string.mini_app_share_contact_title),
                message = context.getString(
                    R.string.mini_app_share_contact_message,
                    botName
                ),
                onConfirm = {
                    scope.launch {
                        val me = userRepository.getMe()
                        val contactJson = JSONObject().apply {
                            put("phone_number", me.phoneNumber ?: "")
                            put("first_name", me.firstName)
                            put("user_id", me.id)
                        }.toString()
                        pendingRequestedContact =
                            "contact=" + URLEncoder.encode(contactJson, "UTF-8")
                        emitter.emit("phone_requested") { put("status", "sent") }
                        clearDialog()
                    }
                },
                onCancel = {
                    emitter.emit("phone_requested") { put("status", "cancelled") }
                    clearDialog()
                }
            )
        )
    }

    fun requestedContact(): String? = pendingRequestedContact

    fun consumeRequestedContact() {
        pendingRequestedContact = null
    }
}
