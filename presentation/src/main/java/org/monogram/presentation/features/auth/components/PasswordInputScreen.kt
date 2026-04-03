package org.monogram.presentation.features.auth.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val passwordShapes = listOf(
    MaterialShapes.Sunny,
    MaterialShapes.VerySunny,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.Pentagon,
    MaterialShapes.Gem,
    MaterialShapes.Diamond,
    MaterialShapes.PuffyDiamond,
    MaterialShapes.Triangle,
    MaterialShapes.Slanted,
    MaterialShapes.Arrow,
    MaterialShapes.ClamShell,
    MaterialShapes.Ghostish,
    MaterialShapes.Burst,
    MaterialShapes.SoftBurst,
    MaterialShapes.Flower,
    MaterialShapes.PixelCircle,
    MaterialShapes.PixelTriangle,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PasswordInputScreen(
    onConfirm: (String) -> Unit,
    isSubmitting: Boolean
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    var isFocused by remember { mutableStateOf(false) }
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isInputMode = isKeyboardVisible || isFocused

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val charShapeList = remember { List(64) { passwordShapes.random() } }

    LaunchedEffect(isKeyboardVisible) {
        if (!isKeyboardVisible && isFocused) {
            focusManager.clearFocus()
        }
    }

    BackHandler(enabled = isFocused) {
        focusManager.clearFocus()
    }

    val iconSize by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 80.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "iconSize"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isInputMode) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "iconAlpha"
    )
    val topSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 0.dp else 32.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "topSpacerHeight"
    )
    val middleSpacerHeight by animateDpAsState(
        targetValue = if (isInputMode) 24.dp else 40.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "middleSpacerHeight"
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() },
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = if (isLandscape) 64.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PasswordContent(
                password = password,
                onPasswordChange = {
                    val filtered = it.filter { c -> c != ' ' }
                    password = filtered.take(64)
                },
                passwordVisible = passwordVisible,
                onPasswordVisibleChange = { passwordVisible = it },
                isFocused = isFocused,
                onFocusChanged = { isFocused = it },
                focusRequester = focusRequester,
                charShapeList = charShapeList,
                isInputMode = isInputMode,
                iconSize = iconSize,
                iconAlpha = iconAlpha,
                topSpacerHeight = topSpacerHeight,
                middleSpacerHeight = middleSpacerHeight,
                isSubmitting = isSubmitting,
                onConfirm = onConfirm,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PasswordContent(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    charShapeList: List<RoundedPolygon>,
    isInputMode: Boolean,
    iconSize: Dp,
    iconAlpha: Float,
    topSpacerHeight: Dp,
    middleSpacerHeight: Dp,
    isSubmitting: Boolean,
    onConfirm: (String) -> Unit,
) {
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var containerWidth by remember { mutableFloatStateOf(0f) }
    val contentOffset = remember { Animatable(0f) }

    LaunchedEffect(password.length, containerWidth) {
        if (containerWidth == 0f) return@LaunchedEffect
        val shapePx = with(density) { 20.dp.toPx() }
        val spacingPx = with(density) { 8.dp.toPx() }
        val cursorPx = with(density) { 3.dp.toPx() }
        val paddingPx = with(density) { 8.dp.toPx() }
        val step = shapePx + spacingPx
        val rowWidth = if (password.isEmpty()) 0f else password.length * step - spacingPx
        val cursorEnd = rowWidth + spacingPx + cursorPx + paddingPx

        val target = if (cursorEnd <= containerWidth) {
            0f
        } else {
            -max(0f, cursorEnd - containerWidth)
        }

        contentOffset.animateTo(target, animationSpec = spring(stiffness = 400f))
    }

    Spacer(modifier = Modifier.height(topSpacerHeight))

    Box(
        modifier = Modifier
            .size(iconSize)
            .alpha(iconAlpha),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .requiredSize(80.dp)
                .graphicsLayer {
                    val scale = if (iconSize.value > 0) iconSize.value / 80f else 0f
                    scaleX = scale
                    scaleY = scale
                },
            shape = MaterialShapes.Cookie4Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(if (isInputMode) 12.dp else 24.dp))

    Text(
        text = stringResource(R.string.two_step_verification_title),
        style = if (isInputMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(if (isInputMode) 4.dp else 12.dp))

    Text(
        text = stringResource(R.string.two_step_verification_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(middleSpacerHeight))

    BasicTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (password.isNotBlank()) onConfirm(password) }
        ),
        decorationBox = {}
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { containerWidth = it.width.toFloat() }
        ) {
            if (password.isEmpty()) {
                Text(
                    stringResource(R.string.password_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (passwordVisible) {
                Box {
                    Text(
                        text = password,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isFocused) {
                        val textMeasurer = rememberTextMeasurer()
                        val textStyle = MaterialTheme.typography.bodyLarge
                        val textLayoutResult = remember(password, textStyle) {
                            textMeasurer.measure(AnnotatedString(password), style = textStyle)
                        }
                        val cursorX by animateFloatAsState(
                            targetValue = textLayoutResult.size.width.toFloat() + with(density) { 1.dp.toPx() },
                            animationSpec = spring(stiffness = 500f),
                            label = "cursor_pos_text"
                        )
                        PasswordCursor(offsetX = cursorX)
                    }
                }
            } else {
                Box {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth(align = Alignment.Start, unbounded = true)
                            .offset { IntOffset(contentOffset.value.roundToInt(), 0) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        password.indices.forEach { index ->
                            key(index) {
                                PasswordShape(shape = charShapeList[index].toShape())
                            }
                        }
                    }

                    if (isFocused) {
                        val shapePx = with(density) { 20.dp.toPx() }
                        val spacingPx = with(density) { 8.dp.toPx() }
                        val cursorX by animateFloatAsState(
                            targetValue = if (password.isEmpty()) 0f
                            else password.length * (shapePx + spacingPx) + contentOffset.value,
                            animationSpec = spring(stiffness = 500f),
                            label = "cursor_pos"
                        )
                        PasswordCursor(offsetX = cursorX)
                    }
                }
            }
        }

        IconButton(
            onClick = { onPasswordVisibleChange(!passwordVisible) },
            shapes = ExpressiveDefaults.iconButtonShapes()
        ) {
            Icon(
                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = { onConfirm(password) },
        shapes = ExpressiveDefaults.extraLargeButtonShapes(),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = password.isNotBlank() && !isSubmitting
    ) {
        if (isSubmitting) {
            LoadingIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(stringResource(R.string.unlock_button), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun PasswordCursor(offsetX: Float) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .width(3.dp)
            .height(20.dp)
            .background(
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(2.dp)
            )
    )
}

@Composable
private fun PasswordShape(shape: Shape) {
    val scale = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        scale.snapTo(0.9f)
        scale.animateTo(1.15f, animationSpec = tween(120, easing = FastOutSlowInEasing))
        scale.animateTo(1f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f))
    }

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .size(20.dp)
            .background(MaterialTheme.colorScheme.secondary, shape)
    )
}