package org.monogram.presentation.features.auth.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.CountryFlag
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.util.Country
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import java.util.Locale

enum class ActiveField {
    CODE, PHONE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhoneInputScreen(
    onConfirm: (String) -> Unit,
    isSubmitting: Boolean
) {
    val isPreview = LocalInspectionMode.current

    val countries = remember {
        if (isPreview) {
            listOf(
                Country(name = "United States", code = "1", iso = "US", flagEmoji = "🇺🇸"),
            )
        } else {
            CountryManager.getCountries()
        }
    }

    val context = LocalContext.current
    val defaultCountry = remember {
        val iso = if (isPreview) "US" else
            (CountryManager.getSimIso(context) ?: Locale.getDefault().country)
        countries.find { it.iso == iso } ?: countries.find { it.code == "380" } ?: countries.first()
    }

    var phoneBody by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf<Country?>(defaultCountry) }
    var codeInput by remember { mutableStateOf(defaultCountry.code) }

    var codeFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = defaultCountry.code,
                selection = TextRange(defaultCountry.code.length)
            )
        )
    }
    var phoneFieldValue by remember { mutableStateOf(TextFieldValue()) }

    var showCountryPicker by remember { mutableStateOf(false) }
    var activeField by remember { mutableStateOf(ActiveField.PHONE) }
    var searchQuery by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isInputMode = isKeyboardVisible || isFocused

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible && isFocused) {
            focusManager.clearFocus()
        }
    }

    var phoneDisplay by remember { mutableStateOf("") }

    val iconSize by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 72.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconSize"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isInputMode) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "iconAlpha"
    )
    val topSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 24.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "topSpacerHeight"
    )
    val middleSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 16.dp else 32.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "middleSpacerHeight"
    )

    val closeCountryPicker = {
        showCountryPicker = false
        searchQuery = ""
    }

    val onCountrySelected: (Country) -> Unit = remember {
        { country ->
            selectedCountry = country
            codeInput = country.code
            codeFieldValue =
                TextFieldValue(text = country.code, selection = TextRange(country.code.length))
            phoneBody = ""
            phoneFieldValue = TextFieldValue()
            phoneDisplay = ""
            activeField = ActiveField.PHONE
            closeCountryPicker()
        }
    }

    val phonePlaceholder = remember(selectedCountry) {
        selectedCountry?.let { country ->
            try {
                val example = CountryManager.getExampleNumber(country.iso)
                val prefix = "+${country.code}"
                if (example.startsWith(prefix)) example.removePrefix(prefix).trim() else example
            } catch (_: Exception) {
                ""
            }
        } ?: ""
    }

    val fullNumber = "+$codeInput$phoneBody"
    val isFormValid = remember(fullNumber, selectedCountry?.iso) {
        val iso = selectedCountry?.iso
        codeInput.isNotEmpty() && iso != null && CountryManager.isValidPhoneNumber(fullNumber, iso)
    }

    val content: @Composable () -> Unit = {
        Spacer(modifier = Modifier.height(topSpacerHeight))

        Box(
            modifier = Modifier
                .size(iconSize)
                .alpha(iconAlpha),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .requiredSize(72.dp)
                    .graphicsLayer {
                        val scale = if (iconSize.value > 0) iconSize.value / 72f else 0f
                        scaleX = scale
                        scaleY = scale
                    },
                shape = MaterialShapes.Cookie4Sided.toShape(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isInputMode) 8.dp else 16.dp))

        Text(
            text = stringResource(R.string.phone_input_title),
            style = if (isInputMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(if (isInputMode) 4.dp else 8.dp))

        Text(
            text = stringResource(R.string.phone_input_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(middleSpacerHeight))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = !isSubmitting) {
                    focusManager.clearFocus()
                    showCountryPicker = true
                }
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                verticalAlignment = Alignment.CenterVertically
            ) {

                AnimatedContent(
                    targetState = selectedCountry,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                                scaleIn(
                                    initialScale = 0.6f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                ).togetherWith(
                                fadeOut(animationSpec = tween(90)) +
                                        scaleOut(targetScale = 0.8f)
                            )
                    },
                    label = "CountryIconAnimation"
                ) { targetCountry ->
                    if (targetCountry != null) {
                        CountryFlag(
                            iso = targetCountry.iso,
                            size = 40.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "Unknown country",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = selectedCountry?.name,
                        transitionSpec = {
                            if (targetState != null) {
                                (slideInVertically { height -> height / 2 } + fadeIn(tween(250))) togetherWith
                                        (slideOutVertically { height -> -height / 2 } + fadeOut(
                                            tween(150)
                                        ))
                            } else {
                                (slideInVertically { height -> -height / 2 } + fadeIn(tween(250))) togetherWith
                                        (slideOutVertically { height -> height / 2 } + fadeOut(
                                            tween(
                                                150
                                            )
                                        ))
                            }.using(SizeTransform(clip = false))
                        },
                        label = "CountryTextAnimation"
                    ) { countryName ->
                        Text(
                            text = countryName ?: stringResource(R.string.unknown_country),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (countryName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = stringResource(R.string.country_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    Icons.Default.ArrowDropDown,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box {
            val currentValue =
                if (activeField == ActiveField.CODE) codeFieldValue else phoneFieldValue

            BasicTextField(
                value = currentValue,
                onValueChange = { newValue ->
                    val digits = newValue.text.filter { it.isDigit() }

                    if (activeField == ActiveField.CODE) {
                        if (digits.length <= 4) {
                            codeInput = digits
                            codeFieldValue = newValue.copy(text = digits)

                            val newCountry = CountryManager.getCountryForPhone(digits)
                            selectedCountry = newCountry

                            phoneDisplay = newCountry?.let {
                                CountryManager.formatPartialPhoneNumber(it.iso, phoneBody)
                            } ?: phoneBody
                        }
                    } else {
                        if (digits.length <= 15) {
                            phoneBody = digits
                            phoneDisplay = selectedCountry?.let {
                                CountryManager.formatPartialPhoneNumber(it.iso, digits)
                            } ?: digits

                            phoneFieldValue = newValue.copy(text = digits)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = if (activeField == ActiveField.CODE) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = {
                        activeField = ActiveField.PHONE
                    },
                    onDone = {
                        if (isFormValid) {
                            onConfirm(fullNumber)
                        } else {
                            focusManager.clearFocus()
                        }
                    }
                ),
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                decorationBox = { }
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(70.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isSubmitting) {
                                activeField = ActiveField.CODE
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "+$codeInput",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (activeField == ActiveField.CODE && isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.code_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(32.dp)
                            .padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isSubmitting) {
                                activeField = ActiveField.PHONE
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column {
                            Text(
                                text = if (phoneBody.isEmpty())
                                    phonePlaceholder.ifEmpty { stringResource(R.string.phone_number_placeholder) }
                                else phoneDisplay,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (phoneBody.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.5f
                                )
                                else if (activeField == ActiveField.PHONE && isFocused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.phone_number_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onConfirm(fullNumber) },
            shapes = ExpressiveDefaults.extraLargeButtonShapes(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = isFormValid && !isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isSubmitting) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    stringResource(R.string.continue_button),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() },
        color = MaterialTheme.colorScheme.background
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    content()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    content()
                }
            }
        }
    }

    if (showCountryPicker) {
        ModalBottomSheet(
            onDismissRequest = closeCountryPicker,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.select_country_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )

                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingsTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.search_country_hint),
                        icon = Icons.Default.Search,
                        position = ItemPosition.STANDALONE,
                        singleLine = true
                    )
                }

                val filteredCountries = remember(searchQuery, countries) {
                    if (searchQuery.isBlank()) countries
                    else countries.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                                it.code.contains(searchQuery)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(filteredCountries) { index, country ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCountrySelected(country) }
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CountryFlag(
                                    iso = country.iso,
                                    size = 32.dp,
                                    flagEmoji = country.flagEmoji
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = country.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "+${country.code}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (index < filteredCountries.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PhoneInputScreenPreview() {
    MaterialTheme {
        PhoneInputScreen(
            onConfirm = { },
            isSubmitting = false
        )
    }
}