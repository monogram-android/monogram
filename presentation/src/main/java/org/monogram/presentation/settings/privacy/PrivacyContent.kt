package org.monogram.presentation.settings.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.settings.privacy.userSelection.UserSelectionContent

@Composable
fun PrivacyContent(component: PrivacyComponent) {
    val childStack by component.childStack.subscribeAsState()

    Children(
        stack = childStack
    ) { child ->
        key(child.key) {
            when (val instance = child.instance) {
                is PrivacyComponent.Child.ListChild -> PrivacyListContent(instance.component)
                is PrivacyComponent.Child.SettingChild -> PrivacySettingContent(instance.component)
                is PrivacyComponent.Child.BlockedUsersChild -> BlockedUsersContent(instance.component)
                is PrivacyComponent.Child.UserSelectionChild -> UserSelectionContent(instance.component)
            }
        }
    }
}