package org.monogram.presentation.settings.privacy

import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.monogram.domain.models.PrivacyRule
import org.monogram.domain.repository.PrivacyKey
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext
import org.monogram.presentation.settings.privacy.userSelection.DefaultUserSelectionComponent
import org.monogram.presentation.settings.privacy.userSelection.UserSelectionComponent

interface PrivacyComponent {
    val childStack: Value<ChildStack<*, Child>>

    sealed class Child {
        class ListChild(val component: PrivacyListComponent) : Child()
        class SettingChild(val component: PrivacySettingComponent) : Child()
        class BlockedUsersChild(val component: BlockedUsersComponent) : Child()
        class UserSelectionChild(val component: UserSelectionComponent) : Child()
    }
}

class DefaultPrivacyComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onSessionsClick: () -> Unit,
    private val onProfileClick: (Long) -> Unit,
    private val onPasscodeClick: () -> Unit
) : PrivacyComponent, AppComponentContext by context {

    private val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    private val navigation = StackNavigation<Config>()
    private val scope = componentScope

    override val childStack: Value<ChildStack<*, PrivacyComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.List,
        handleBackButton = true,
        childFactory = ::createChild
    )

    private fun createChild(config: Config, context: AppComponentContext): PrivacyComponent.Child =
        when (config) {
            is Config.List -> PrivacyComponent.Child.ListChild(
                DefaultPrivacyListComponent(
                    context = context,
                    onBack = onBack,
                    onNavigateToPrivacySetting = { key -> navigation.push(Config.Setting(key)) },
                    onNavigateToBlockedUsers = { navigation.push(Config.BlockedUsers) },
                    onSessionsClick = onSessionsClick,
                    onPasscodeClick = onPasscodeClick
                )
            )

            is Config.Setting -> PrivacyComponent.Child.SettingChild(
                DefaultPrivacySettingComponent(
                    context = context,
                    privacyKey = config.key,
                    onBack = { navigation.pop() },
                    onProfileClick = onProfileClick,
                    onUserSelect = { isAllow -> navigation.push(Config.UserSelection(config.key, isAllow)) }
                )
            )

            is Config.BlockedUsers -> PrivacyComponent.Child.BlockedUsersChild(
                DefaultBlockedUsersComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onProfileClick = onProfileClick,
                    onAddBlockedUser = { navigation.push(Config.UserSelection(null, false)) }
                )
            )

            is Config.UserSelection -> PrivacyComponent.Child.UserSelectionChild(
                DefaultUserSelectionComponent(
                    context = context,
                    onBack = { navigation.pop() },
                    onUserSelected = { userId ->
                        navigation.pop()
                        scope.launch {
                            if (config.privacyKey != null) {
                                updatePrivacyRule(config.privacyKey, userId, config.isAllow)
                            } else {
                                privacyRepository.blockUser(userId)
                            }
                        }
                    }
                )
            )
        }

    private suspend fun updatePrivacyRule(key: PrivacyKey, userId: Long, isAllow: Boolean) {
        val rules = privacyRepository.getPrivacyRules(key).first()
        val newRules = rules.toMutableList()

        val allowUsers = newRules.filterIsInstance<PrivacyRule.AllowUsers>().flatMap { it.userIds }.toMutableList()
        val disallowUsers =
            newRules.filterIsInstance<PrivacyRule.DisallowUsers>().flatMap { it.userIds }.toMutableList()

        if (isAllow) {
            if (!allowUsers.contains(userId)) {
                allowUsers.add(userId)
                disallowUsers.remove(userId)
            }
        } else {
            if (!disallowUsers.contains(userId)) {
                disallowUsers.add(userId)
                allowUsers.remove(userId)
            }
        }

        val finalRules = mutableListOf<PrivacyRule>()
        if (allowUsers.isNotEmpty()) finalRules.add(PrivacyRule.AllowUsers(allowUsers))
        if (disallowUsers.isNotEmpty()) finalRules.add(PrivacyRule.DisallowUsers(disallowUsers))

        val baseRule = rules.firstOrNull { it !is PrivacyRule.AllowUsers && it !is PrivacyRule.DisallowUsers }
        if (baseRule != null) {
            finalRules.add(baseRule)
            if (baseRule is PrivacyRule.AllowContacts) {
                finalRules.add(PrivacyRule.AllowNone)
            }
        } else {
            finalRules.add(PrivacyRule.AllowAll)
        }

        privacyRepository.setPrivacyRule(key, finalRules)
    }

    @Serializable
    sealed class Config {
        @Serializable
        object List : Config()

        @Serializable
        data class Setting(val key: PrivacyKey) : Config()

        @Serializable
        object BlockedUsers : Config()

        @Serializable
        data class UserSelection(val privacyKey: PrivacyKey?, val isAllow: Boolean) : Config()
    }
}