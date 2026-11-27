package org.monogram.feature.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.monogram.core.common.TextToolsProviderConfig
import org.monogram.core.common.TextToolsProviderSettings
import org.monogram.core.ui.TextToolsSettings as TranslationPreferences
import org.monogram.core.ui.components.LanguagePickerDialog
import org.monogram.core.ui.components.SectionHeader
import org.monogram.core.ui.components.languageDisplayName
import org.monogram.core.ui.components.SettingsCard
import org.monogram.core.ui.components.SettingsCardRow
import org.monogram.core.ui.components.SettingsChoice
import org.monogram.core.ui.components.SettingsChoiceGroup
import org.monogram.feature.settings.R
import org.monogram.feature.settings.SettingsComponent
import java.net.URI

@Composable
internal fun TextToolsSettings(
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    val accountId = component.activeAccountUserId
    var saved by remember(accountId) {
        mutableStateOf(
            TextToolsProviderSettings.load(
                accountId ?: 0
            )
        )
    }
    var custom by rememberSaveable(accountId) { mutableStateOf(saved.isCustom) }
    var baseUrl by rememberSaveable(accountId) { mutableStateOf(saved.baseUrl) }
    var model by rememberSaveable(accountId) { mutableStateOf(saved.model) }
    var apiKey by remember(accountId) { mutableStateOf(saved.apiKey) }
    var saveError by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }
    var manualTranslation by remember { mutableStateOf(TranslationPreferences.manualTranslation()) }
    var chatTranslation by remember { mutableStateOf(TranslationPreferences.chatTranslation()) }
    var excludedLanguages by remember { mutableStateOf(TranslationPreferences.excludedLanguages()) }
    var choosingExclusions by rememberSaveable { mutableStateOf(false) }
    fun savePreferences(manual: Boolean, chat: Boolean, excluded: Set<String>) {
        TranslationPreferences.setGlobal(manual, chat, excluded)
        manualTranslation = manual
        chatTranslation = chat
        excludedLanguages = excluded
    }

    val endpoint = baseUrl.trim()
    val uri = remember(endpoint) { runCatching { URI(endpoint) }.getOrNull() }
    val validUrl =
        endpoint.length <= 200 && endpoint.none { it.isWhitespace() || it.isISOControl() } &&
                uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null
    val config =
        TextToolsProviderConfig(
            provider = if (custom) "openai-compatible" else "telegram",
            baseUrl = endpoint,
            model = model.trim(),
            apiKey = apiKey.trim(),
        )
    val changed = config != saved
    val canSave =
        accountId != null && changed && (!custom || (validUrl && model.isNotBlank() && apiKey.isNotBlank()))

    SettingsPageList(innerPadding = PaddingValues(0.dp), modifier = modifier) {
        item {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader(stringResource(R.string.settings_text_tools_translation))
                SettingsCard {
                    SettingsCardRow(
                        icon = Icons.Outlined.Translate,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_text_tools_manual_enabled),
                        toggle = manualTranslation,
                        onToggle = { savePreferences(it, chatTranslation, excludedLanguages) },
                    )
                    SettingsCardRow(
                        icon = Icons.Outlined.Translate,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_text_tools_chat_enabled),
                        toggle = chatTranslation,
                        onToggle = { savePreferences(manualTranslation, it, excludedLanguages) },
                    )
                    SettingsCardRow(
                        icon = Icons.Outlined.Language,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_text_tools_excluded),
                        subtitle = excludedLanguages.map { languageDisplayName(it) }.sorted()
                            .joinToString().takeIf { it.isNotEmpty() },
                        onClick = { choosingExclusions = true },
                    )
                }
                SectionHeader(stringResource(R.string.settings_text_tools))
                Text(
                    stringResource(R.string.settings_text_tools_sub),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                SettingsCard {
                    SettingsChoiceGroup(
                        options =
                            listOf(
                                SettingsChoice(stringResource(R.string.settings_text_tools_telegram)),
                                SettingsChoice(stringResource(R.string.settings_text_tools_openai)),
                            ),
                        selectedIndex = if (custom) 1 else 0,
                        onSelect = {
                            custom = it == 1
                            savedNotice = false
                            saveError = false
                        },
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            stringResource(if (custom) R.string.settings_text_tools_hint else R.string.settings_text_tools_telegram_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AnimatedVisibility(visible = custom) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedTextField(
                                    value = baseUrl,
                                    onValueChange = {
                                        baseUrl = it
                                        savedNotice = false
                                        saveError = false
                                    },
                                    label = { Text(stringResource(R.string.settings_text_tools_base_url)) },
                                    placeholder = { Text("https://api.openai.com/v1") },
                                    isError = baseUrl.isNotEmpty() && !validUrl,
                                    supportingText = {
                                        if (baseUrl.isNotEmpty() &&
                                            !validUrl
                                        ) {
                                            Text(stringResource(R.string.settings_text_tools_invalid_url))
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = model,
                                    onValueChange = {
                                        model = it
                                        savedNotice = false
                                        saveError = false
                                    },
                                    label = { Text(stringResource(R.string.settings_text_tools_model)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = {
                                        apiKey = it
                                        savedNotice = false
                                        saveError = false
                                    },
                                    label = { Text(stringResource(R.string.settings_text_tools_api_key)) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val id = accountId ?: return@Button
                                saveError = runCatching {
                                    TextToolsProviderSettings.save(
                                        id,
                                        config
                                    )
                                }.isFailure
                                if (!saveError) {
                                    saved = config
                                    savedNotice = true
                                }
                            },
                            enabled = canSave
                        ) { Text(stringResource(R.string.settings_text_tools_save)) }
                        if (saveError || savedNotice) {
                            Text(
                                stringResource(
                                    if (saveError) R.string.settings_text_tools_save_error else R.string.settings_text_tools_saved,
                                ),
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                color = if (saveError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (choosingExclusions) {
        LanguagePickerDialog(
            title = stringResource(R.string.settings_text_tools_excluded),
            searchLabel = stringResource(R.string.settings_text_tools_search_language),
            emptyLabel = stringResource(R.string.settings_text_tools_no_languages),
            doneLabel = stringResource(R.string.settings_notifications_chat_done),
            selected = excludedLanguages,
            multiple = true,
            onSelect = { code ->
                val excluded =
                    if (code in excludedLanguages) excludedLanguages - code else excludedLanguages + code
                savePreferences(manualTranslation, chatTranslation, excluded)
            },
            onDismiss = { choosingExclusions = false },
        )
    }
}
