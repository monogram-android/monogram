package org.monogram.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable

/**
 * The folder screens rendered inside the Settings stack.
 *
 * The app module builds this from `feature/folders` and hands it to
 * [SettingsComponent], so the folders page shares the Settings app bar and the
 * Settings page animation while features stay independent of each other.
 */
class SettingsFolderHost(
    /** App-bar title: the edited folder's name, or the list title. */
    val title: String,
    val isEditing: Boolean,
    val canSave: Boolean,
    val onAdd: () -> Unit,
    val onRefresh: () -> Unit,
    val onSave: () -> Unit,
    /** Handles back inside the folders page. Returns true when it consumed the press. */
    val onBack: () -> Boolean,
    val content: @Composable (PaddingValues) -> Unit,
)
