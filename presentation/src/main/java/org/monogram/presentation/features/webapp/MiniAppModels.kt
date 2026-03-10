package org.monogram.presentation.features.webapp

import androidx.compose.ui.graphics.Color

data class MainButtonState(
    val isVisible: Boolean = false,
    val isActive: Boolean = true,
    val text: String = "CONTINUE",
    val color: Color? = null,
    val textColor: Color? = null,
    val isProgressVisible: Boolean = false,
    val hasShineEffect: Boolean = false
)

data class SecondaryButtonState(
    val isVisible: Boolean = false,
    val isActive: Boolean = true,
    val text: String = "CANCEL",
    val color: Color? = null,
    val textColor: Color? = null,
    val isProgressVisible: Boolean = false,
    val hasShineEffect: Boolean = false,
    val position: String = "left"
)

data class PopupButton(
    val id: String,
    val type: String,
    val text: String,
    val isDestructive: Boolean
)

data class PopupState(
    val title: String?,
    val message: String,
    val buttons: List<PopupButton>,
    val callbackId: String
)

data class PermissionRequest(
    val message: String,
    val onGranted: () -> Unit,
    val onDenied: () -> Unit,
    val onDismiss: () -> Unit = {}
)

data class CustomMethodRequest(
    val reqId: String,
    val method: String,
    val params: String,
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit
)
