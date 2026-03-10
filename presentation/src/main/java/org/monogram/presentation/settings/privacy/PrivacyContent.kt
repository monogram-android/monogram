package org.monogram.presentation.settings.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.settings.privacy.userSelection.UserSelectionContent

@Composable
fun PrivacyContent(component: PrivacyComponent) {
    val childStack by component.childStack.subscribeAsState()

    Children(
        stack = childStack
    ) {
        when (val child = it.instance) {
            is PrivacyComponent.Child.ListChild -> PrivacyListContent(child.component)
            is PrivacyComponent.Child.SettingChild -> PrivacySettingContent(child.component)
            is PrivacyComponent.Child.BlockedUsersChild -> BlockedUsersContent(child.component)
            is PrivacyComponent.Child.UserSelectionChild -> UserSelectionContent(child.component)
        }
    }
}