package org.monogram.presentation.settings.chatSettings

import android.graphics.Color.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class PaletteMode { LIGHT, DARK }

private data class ThemePalette(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val background: Int,
    val surface: Int,
    val primaryContainer: Int,
    val secondaryContainer: Int,
    val tertiaryContainer: Int,
    val surfaceVariant: Int,
    val outline: Int
)

private data class ThemePreset(
    val name: String,
    val description: String,
    val tag: String,
    val light: ThemePalette,
    val dark: ThemePalette
)
private data class AccentPreset(val name: String, val color: Int)
private data class ColorRole(val label: String, val color: Int, val onApply: (Int) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThemeEditorScreen(
    state: ChatSettingsComponent.State,
    component: ChatSettingsComponent,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(PaletteMode.LIGHT) }
    val isDark = mode == PaletteMode.DARK
    var accentText by remember { mutableStateOf("#FF3390EC") }
    var pickerTarget by remember { mutableStateOf<ColorRole?>(null) }

    val palette = if (isDark) {
        ThemePalette(
            state.themeDarkPrimaryColor, state.themeDarkSecondaryColor, state.themeDarkTertiaryColor,
            state.themeDarkBackgroundColor, state.themeDarkSurfaceColor,
            state.themeDarkPrimaryContainerColor, state.themeDarkSecondaryContainerColor,
            state.themeDarkTertiaryContainerColor, state.themeDarkSurfaceVariantColor, state.themeDarkOutlineColor
        )
    } else {
        ThemePalette(
            state.themePrimaryColor, state.themeSecondaryColor, state.themeTertiaryColor,
            state.themeBackgroundColor, state.themeSurfaceColor,
            state.themePrimaryContainerColor, state.themeSecondaryContainerColor,
            state.themeTertiaryContainerColor, state.themeSurfaceVariantColor, state.themeOutlineColor
        )
    }
    LaunchedEffect(palette.primary) { accentText = argbToHex(palette.primary) }

    fun enableCustomThemeSource() {
        component.onCustomThemeEnabledChanged(true)
        if (state.isDynamicColorsEnabled) component.onDynamicColorsChanged(false)
    }

    fun applyPalette(p: ThemePalette, dark: Boolean) {
        if (dark) {
            component.onThemeDarkPrimaryColorChanged(p.primary); component.onThemeDarkSecondaryColorChanged(p.secondary); component.onThemeDarkTertiaryColorChanged(p.tertiary)
            component.onThemeDarkBackgroundColorChanged(p.background); component.onThemeDarkSurfaceColorChanged(p.surface)
            component.onThemeDarkPrimaryContainerColorChanged(p.primaryContainer); component.onThemeDarkSecondaryContainerColorChanged(p.secondaryContainer)
            component.onThemeDarkTertiaryContainerColorChanged(p.tertiaryContainer); component.onThemeDarkSurfaceVariantColorChanged(p.surfaceVariant)
            component.onThemeDarkOutlineColorChanged(p.outline)
        } else {
            component.onThemePrimaryColorChanged(p.primary); component.onThemeSecondaryColorChanged(p.secondary); component.onThemeTertiaryColorChanged(p.tertiary)
            component.onThemeBackgroundColorChanged(p.background); component.onThemeSurfaceColorChanged(p.surface)
            component.onThemePrimaryContainerColorChanged(p.primaryContainer); component.onThemeSecondaryContainerColorChanged(p.secondaryContainer)
            component.onThemeTertiaryContainerColorChanged(p.tertiaryContainer); component.onThemeSurfaceVariantColorChanged(p.surfaceVariant)
            component.onThemeOutlineColorChanged(p.outline)
        }
        enableCustomThemeSource()
    }

    val accents = remember {
        listOf(
            AccentPreset("Blue", 0xFF3390EC.toInt()), AccentPreset("Green", 0xFF2E7D32.toInt()),
            AccentPreset("Orange", 0xFFF57C00.toInt()), AccentPreset("Rose", 0xFFD81B60.toInt()),
            AccentPreset("Indigo", 0xFF3F51B5.toInt()), AccentPreset("Cyan", 0xFF0097A7.toInt())
        )
    }
    val presets = remember {
        listOf(
            ThemePreset("Classic",
                "Balanced blue with clear readability and neutral surfaces.",
                "Daily",
                ThemePalette(0xFF3390EC.toInt(),0xFF4C7599.toInt(),0xFF00ACC1.toInt(),0xFFFFFBFE.toInt(),0xFFFFFBFE.toInt(),0xFFD4E3FF.toInt(),0xFFD0E4F7.toInt(),0xFFC4EEF4.toInt(),0xFFE1E2EC.toInt(),0xFF757680.toInt()),
                ThemePalette(0xFF64B5F6.toInt(),0xFF81A9CA.toInt(),0xFF4DD0E1.toInt(),0xFF121212.toInt(),0xFF121212.toInt(),0xFF224A77.toInt(),0xFF334F65.toInt(),0xFF1E636F.toInt(),0xFF44474F.toInt(),0xFF8E9099.toInt())
            ),
            ThemePreset("Forest",
                "Natural greens with calm contrast and soft container tones.",
                "Calm",
                ThemePalette(0xFF2E7D32.toInt(),0xFF558B2F.toInt(),0xFF00796B.toInt(),0xFFF6FFF7.toInt(),0xFFFFFFFF.toInt(),0xFFCFEBD2.toInt(),0xFFDFEBD0.toInt(),0xFFCBE7E2.toInt(),0xFFDEE7DD.toInt(),0xFF6F7A73.toInt()),
                ThemePalette(0xFF81C784.toInt(),0xFFA5D6A7.toInt(),0xFF80CBC4.toInt(),0xFF101A12.toInt(),0xFF142018.toInt(),0xFF284A30.toInt(),0xFF35523A.toInt(),0xFF25564F.toInt(),0xFF424D46.toInt(),0xFF909B94.toInt())
            ),
            ThemePreset("Ocean",
                "Cool cyan-blue gradient feeling with fresh light and deep dark mode.",
                "Fresh",
                ThemePalette(0xFF0277BD.toInt(),0xFF0097A7.toInt(),0xFF26A69A.toInt(),0xFFF3FBFF.toInt(),0xFFFFFFFF.toInt(),0xFFC7E7F7.toInt(),0xFFC8EFF2.toInt(),0xFFD2F2EE.toInt(),0xFFDCE8EC.toInt(),0xFF6F7E86.toInt()),
                ThemePalette(0xFF4FC3F7.toInt(),0xFF4DD0E1.toInt(),0xFF80CBC4.toInt(),0xFF0D1820.toInt(),0xFF122029.toInt(),0xFF1E4A63.toInt(),0xFF1E5460.toInt(),0xFF275A55.toInt(),0xFF3D4950.toInt(),0xFF8A979E.toInt())
            ),
            ThemePreset("Sunset",
                "Warm orange and coral accent set with high foreground clarity.",
                "Warm",
                ThemePalette(0xFFE65100.toInt(),0xFFEF6C00.toInt(),0xFFD84315.toInt(),0xFFFFF8F4.toInt(),0xFFFFFFFF.toInt(),0xFFFFDCCB.toInt(),0xFFFFE1CE.toInt(),0xFFFFD9D0.toInt(),0xFFF1E1D9.toInt(),0xFF7C726E.toInt()),
                ThemePalette(0xFFFF8A65.toInt(),0xFFFFA726.toInt(),0xFFFF7043.toInt(),0xFF1A1310.toInt(),0xFF201814.toInt(),0xFF6A3B27.toInt(),0xFF704824.toInt(),0xFF6A332A.toInt(),0xFF4E433D.toInt(),0xFFA1958F.toInt())
            ),
            ThemePreset("Graphite",
                "Neutral grayscale base with blue-gray accents and strong structure.",
                "Pro",
                ThemePalette(0xFF455A64.toInt(),0xFF607D8B.toInt(),0xFF546E7A.toInt(),0xFFF7F8F9.toInt(),0xFFFFFFFF.toInt(),0xFFD6E0E4.toInt(),0xFFD9E1E5.toInt(),0xFFD7E0E3.toInt(),0xFFE2E6E8.toInt(),0xFF737A7D.toInt()),
                ThemePalette(0xFF90A4AE.toInt(),0xFFB0BEC5.toInt(),0xFF8FA1A8.toInt(),0xFF121416.toInt(),0xFF1A1E21.toInt(),0xFF34424A.toInt(),0xFF3C4A52.toInt(),0xFF35444B.toInt(),0xFF43494D.toInt(),0xFF949DA2.toInt())
            ),
            ThemePreset("Mint",
                "Fresh mint and teal combination with gentle containers.",
                "Soft",
                ThemePalette(0xFF00897B.toInt(),0xFF26A69A.toInt(),0xFF43A047.toInt(),0xFFF3FFFC.toInt(),0xFFFFFFFF.toInt(),0xFFC6EDE7.toInt(),0xFFD2F1EB.toInt(),0xFFD5ECD0.toInt(),0xFFD9EAE4.toInt(),0xFF70807A.toInt()),
                ThemePalette(0xFF4DB6AC.toInt(),0xFF80CBC4.toInt(),0xFF81C784.toInt(),0xFF0F1C1A.toInt(),0xFF152422.toInt(),0xFF23534C.toInt(),0xFF2A5B54.toInt(),0xFF2F5634.toInt(),0xFF3D4E4A.toInt(),0xFF8D9F9A.toInt())
            ),
            ThemePreset("Ruby",
                "Deep red accent with neutral containers for strong action focus.",
                "Bold",
                ThemePalette(0xFFB71C1C.toInt(),0xFFD84343.toInt(),0xFFC62828.toInt(),0xFFFFF8F8.toInt(),0xFFFFFFFF.toInt(),0xFFF7D6D6.toInt(),0xFFF4D9D9.toInt(),0xFFF1D2D2.toInt(),0xFFEEE1E1.toInt(),0xFF7F7070.toInt()),
                ThemePalette(0xFFEF9A9A.toInt(),0xFFFF8A80.toInt(),0xFFE57373.toInt(),0xFF1C1111.toInt(),0xFF241717.toInt(),0xFF6A2E2E.toInt(),0xFF703535.toInt(),0xFF5F2E2E.toInt(),0xFF4A3C3C.toInt(),0xFFA59494.toInt())
            ),
            ThemePreset("Lavender Gray",
                "Muted violet-gray scheme with controlled saturation and clean contrast.",
                "Muted",
                ThemePalette(0xFF6A5ACD.toInt(),0xFF7E71B2.toInt(),0xFF8D7AAE.toInt(),0xFFFAF9FF.toInt(),0xFFFFFFFF.toInt(),0xFFE2DDF9.toInt(),0xFFE4E1F1.toInt(),0xFFE8E2F2.toInt(),0xFFE6E3EC.toInt(),0xFF787483.toInt()),
                ThemePalette(0xFFAFA4E8.toInt(),0xFFB8AFDD.toInt(),0xFFC1B5DE.toInt(),0xFF141221.toInt(),0xFF1B192A.toInt(),0xFF433A6C.toInt(),0xFF4A4267.toInt(),0xFF514865.toInt(),0xFF474556.toInt(),0xFF9A97A8.toInt())
            ),
            ThemePreset("Sand",
                "Beige and amber palette tuned for very soft visual noise.",
                "Comfort",
                ThemePalette(0xFF8D6E63.toInt(),0xFFA1887F.toInt(),0xFFBCAAA4.toInt(),0xFFFFFCF7.toInt(),0xFFFFFFFF.toInt(),0xFFEEDFD6.toInt(),0xFFF0E3DB.toInt(),0xFFF1E8E2.toInt(),0xFFEAE4DE.toInt(),0xFF7E766F.toInt()),
                ThemePalette(0xFFD7CCC8.toInt(),0xFFBCAAA4.toInt(),0xFFA1887F.toInt(),0xFF181411.toInt(),0xFF201A16.toInt(),0xFF5A4A3F.toInt(),0xFF594B42.toInt(),0xFF4F433B.toInt(),0xFF47413D.toInt(),0xFF9E948D.toInt())
            ),
            ThemePreset("Arctic",
                "Icy blue-white feel with high legibility and crisp boundaries.",
                "Clean",
                ThemePalette(0xFF1976D2.toInt(),0xFF64B5F6.toInt(),0xFF00BCD4.toInt(),0xFFF6FCFF.toInt(),0xFFFFFFFF.toInt(),0xFFD7E9F9.toInt(),0xFFDDF0FF.toInt(),0xFFD3F2F6.toInt(),0xFFE1EBF0.toInt(),0xFF75828A.toInt()),
                ThemePalette(0xFF90CAF9.toInt(),0xFF81D4FA.toInt(),0xFF80DEEA.toInt(),0xFF101820.toInt(),0xFF16212A.toInt(),0xFF29465F.toInt(),0xFF2C5068.toInt(),0xFF285760.toInt(),0xFF404C55.toInt(),0xFF93A0A8.toInt())
            )
        )
    }

    val roles = listOf(
        ColorRole("Primary", palette.primary) { if (isDark) component.onThemeDarkPrimaryColorChanged(it) else component.onThemePrimaryColorChanged(it) },
        ColorRole("Secondary", palette.secondary) { if (isDark) component.onThemeDarkSecondaryColorChanged(it) else component.onThemeSecondaryColorChanged(it) },
        ColorRole("Tertiary", palette.tertiary) { if (isDark) component.onThemeDarkTertiaryColorChanged(it) else component.onThemeTertiaryColorChanged(it) },
        ColorRole("Background", palette.background) { if (isDark) component.onThemeDarkBackgroundColorChanged(it) else component.onThemeBackgroundColorChanged(it) },
        ColorRole("Surface", palette.surface) { if (isDark) component.onThemeDarkSurfaceColorChanged(it) else component.onThemeSurfaceColorChanged(it) },
        ColorRole("Primary Container", palette.primaryContainer) { if (isDark) component.onThemeDarkPrimaryContainerColorChanged(it) else component.onThemePrimaryContainerColorChanged(it) },
        ColorRole("Surface Variant", palette.surfaceVariant) { if (isDark) component.onThemeDarkSurfaceVariantColorChanged(it) else component.onThemeSurfaceVariantColorChanged(it) },
        ColorRole("Outline", palette.outline) { if (isDark) component.onThemeDarkOutlineColorChanged(it) else component.onThemeOutlineColorChanged(it) }
    )

    if (pickerTarget != null) {
        Material3ColorPickerDialog(
            title = pickerTarget!!.label,
            initialColor = pickerTarget!!.color,
            onDismiss = { pickerTarget = null },
            onApply = {
                pickerTarget!!.onApply(it)
                enableCustomThemeSource()
                pickerTarget = null
            }
        )
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(component.exportCustomThemeJson()) } }
            .onSuccess { Toast.makeText(context, "Theme file saved", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show() }
    }
    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }
            .onSuccess { Toast.makeText(context, if (component.importCustomThemeJson(it)) "Theme loaded" else "Invalid file", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Load failed", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme Editor", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { EditorHeader(state, component) }
            item {

                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Palette Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { mode = PaletteMode.LIGHT }, enabled = isDark, modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) { Text("Light") }
                                Button(
                                    onClick = { mode = PaletteMode.DARK }, enabled = !isDark, modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) { Text("Dark") }
                            }
                        }
                    }

            }
            item {

                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Accent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(accents) { a ->
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.clickable {
                                        component.onApplyThemeAccent(a.color, isDark)
                                        enableCustomThemeSource()
                                    }) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(
                                                Modifier
                                                    .size(14.dp)
                                                    .background(Color(a.color), CircleShape)
                                            )
                                            Text(a.name, style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = accentText, onValueChange = { accentText = it }, label = { Text("Hex accent") }, singleLine = true, modifier = Modifier.weight(1f))
                                Button(onClick = {
                                    parseThemeColor(accentText)?.let {
                                        component.onApplyThemeAccent(it, isDark)
                                        enableCustomThemeSource()
                                    }
                                }) { Text("Apply") }
                            }
                        }

                    }
            }
            item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Preset Themes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Each preset has ready Light and Dark variants. Tap Light, Dark, or Both to apply instantly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(presets) { p -> PresetCard(p, { applyPalette(p.light, false) }, { applyPalette(p.dark, true) }, { applyPalette(p.light, false); applyPalette(p.dark, true) }) }
                            }
                        }

                    }
            }
            item {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Manual Colors (${if (isDark) "Dark" else "Light"})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            roles.forEach { role ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        Modifier
                                            .size(24.dp)
                                            .background(Color(role.color), CircleShape)
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                                CircleShape
                                            )
                                    )
                                    Column(Modifier.weight(1f)) { Text(role.label); Text(argbToHex(role.color), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    OutlinedButton(onClick = { pickerTarget = role }) { Text("Pick") }
                                }
                            }
                        }
                    }

            }
            item { ThemePreview(palette, isDark, state.isAmoledThemeEnabled) }
            item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { saveLauncher.launch("monogram-theme.json") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.size(6.dp)); Text("Save") }
                        Button(onClick = { loadLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Upload, null); Spacer(Modifier.size(6.dp)); Text("Load") }
                    }
                }

        }
    }
}

@Composable
private fun EditorHeader(state: ChatSettingsComponent.State, component: ChatSettingsComponent) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Theme Source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Choose exactly one source for your app colors. Custom uses your editable palettes, Monet uses Android dynamic color. AMOLED only affects dark backgrounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToggleRow(
                text = "Custom theme",
                description = "Use your own Light/Dark palettes, accents, presets, and manual role colors.",
                value = state.isCustomThemeEnabled
            ) { enabled -> component.onCustomThemeEnabledChanged(enabled); if (enabled && state.isDynamicColorsEnabled) component.onDynamicColorsChanged(false) }
            ToggleRow(
                text = "Monet",
                description = "Use system-generated Material You colors based on wallpaper (Android 12+).",
                value = state.isDynamicColorsEnabled
            ) { enabled -> component.onDynamicColorsChanged(enabled); if (enabled && state.isCustomThemeEnabled) component.onCustomThemeEnabledChanged(false) }
            ToggleRow(
                text = "AMOLED dark",
                description = "Force pure black for dark surfaces to reduce glow and save power on OLED screens.",
                value = state.isAmoledThemeEnabled,
                onChange = component::onAmoledThemeChanged
            )
        }
    }
}

@Composable
private fun ToggleRow(text: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PresetCard(p: ThemePreset, onLight: () -> Unit, onDark: () -> Unit, onBoth: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.width(320.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                MiniPalette("Light", p.light, Modifier.weight(1f))
                MiniPalette("Dark", p.dark, Modifier.weight(1f))
            }
            Text(p.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold)
            Text(
                p.description,
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            AssistChip(
                onClick = {},
                label = { Text(p.tag) },
                enabled = false,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .height(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ColorInfoChip("L P", p.light.primary)
                ColorInfoChip("L C", p.light.primaryContainer)
                ColorInfoChip("L BG", p.light.background)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ColorInfoChip("D P", p.dark.primary)
                ColorInfoChip("D C", p.dark.primaryContainer)
                ColorInfoChip("D BG", p.dark.background)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = onLight, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Light") }
                Button(onClick = onDark, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Dark") }
            }
            OutlinedButton(
                onClick = onBoth, modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp), contentPadding = PaddingValues(0.dp)
            ) { Text("Both") }
        }
    }
}

@Composable
private fun ColorInfoChip(label: String, color: Int) {
    val c = Color(color)
    val textColor = if (c.luminance() > 0.5f) Color.Black else Color.White
    Surface(shape = RoundedCornerShape(8.dp), color = c, tonalElevation = 0.dp) {
        Text(
            "$label ${argbToHex(color).takeLast(7)}",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            color = textColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MiniPalette(label: String, p: ThemePalette, modifier: Modifier = Modifier) {
    val bg = Color(p.background); val textColor = if (bg.luminance() > 0.5f) Color.Black else Color.White
    Column(
        modifier
            .background(bg)
            .padding(8.dp), verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(Color(p.primary), CircleShape)
                    .border(1.dp, textColor.copy(alpha = 0.3f), CircleShape)
            )
            Box(
                Modifier
                    .size(9.dp)
                    .background(Color(p.secondary), CircleShape)
                    .border(1.dp, textColor.copy(alpha = 0.3f), CircleShape)
            )
            Box(
                Modifier
                    .size(9.dp)
                    .background(Color(p.tertiary), CircleShape)
                    .border(1.dp, textColor.copy(alpha = 0.3f), CircleShape)
            )
        }
    }
}

@Composable
private fun ThemePreview(p: ThemePalette, dark: Boolean, amoled: Boolean) {
    val bg = if (dark && amoled) Color.Black else Color(p.background)
    val surface = if (dark && amoled) Color.Black else Color(p.surface)
    val primary = Color(p.primary)
    val onBg = if (bg.luminance() > 0.5f) Color.Black else Color.White
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${if (dark) "Dark" else "Light"} preview", color = onBg, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = surface) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(p.surfaceVariant)) { Text("Summary text", Modifier.padding(8.dp)) }
                    Surface(shape = RoundedCornerShape(10.dp), color = primary) { Text("Action", Modifier.padding(8.dp), color = if (primary.luminance() > 0.5f) Color.Black else Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Material3ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var hsv by remember(initialColor) { mutableStateOf(toHsv(initialColor)) }
    var a by remember(initialColor) { mutableStateOf(((initialColor ushr 24) and 0xFF) / 255f) }
    var hex by remember(initialColor) { mutableStateOf(argbToHex(initialColor)) }
    val current = fromHsv(hsv[0], hsv[1], hsv[2], a)
    LaunchedEffect(current) { hex = argbToHex(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = { onApply(current) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(Color(current), RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = {
                        hex = it
                        parseThemeColor(it)?.let { c -> hsv = toHsv(c); a = ((c ushr 24) and 0xFF) / 255f }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Hue ${hsv[0].toInt()}°", style = MaterialTheme.typography.labelSmall); Slider(hsv[0], { hsv = floatArrayOf(it, hsv[1], hsv[2]) }, valueRange = 0f..360f)
                Text("Saturation ${(hsv[1] * 100).toInt()}%", style = MaterialTheme.typography.labelSmall); Slider(hsv[1], { hsv = floatArrayOf(hsv[0], it, hsv[2]) }, valueRange = 0f..1f)
                Text("Brightness ${(hsv[2] * 100).toInt()}%", style = MaterialTheme.typography.labelSmall); Slider(hsv[2], { hsv = floatArrayOf(hsv[0], hsv[1], it) }, valueRange = 0f..1f)
                Text("Alpha ${(a * 100).toInt()}%", style = MaterialTheme.typography.labelSmall); Slider(a, { a = it }, valueRange = 0f..1f)
            }
        }
    )
}

private fun argbToHex(color: Int): String = String.format("#%08X", color)
private fun parseThemeColor(value: String): Int? {
    val s = value.trim()
    if (!s.startsWith("#") || (s.length != 7 && s.length != 9)) return null
    return runCatching { val p = parseColor(s); if (s.length == 7) p or (0xFF shl 24) else p }.getOrNull()
}
private fun toHsv(color: Int): FloatArray = FloatArray(3).also { colorToHSV(color, it) }
private fun fromHsv(h: Float, s: Float, v: Float, a: Float): Int =
    HSVToColor((a * 255f).toInt().coerceIn(0, 255), floatArrayOf(h, s, v))
