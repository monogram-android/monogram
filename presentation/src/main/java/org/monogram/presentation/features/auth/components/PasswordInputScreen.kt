package org.monogram.presentation.features.auth.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import org.monogram.presentation.R
import kotlin.math.max
import kotlin.math.roundToInt

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
    val keyboardController = LocalSoftwareKeyboardController.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()

    var isFocused by remember { mutableStateOf(false) }
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isInputMode = isKeyboardVisible || isFocused

    val inputScale = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        inputScale.snapTo(0.9f)

        inputScale.animateTo(
            1.15f,
            animationSpec = tween(120, easing = FastOutSlowInEasing)
        )

        inputScale.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = 500f
            )
        )
    }

    val shapes: List<RoundedPolygon> = remember {
        listOf(
            MaterialShapes.Circle,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Cookie6Sided,
            MaterialShapes.Cookie7Sided,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Cookie12Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Pentagon,
            MaterialShapes.Gem,
            MaterialShapes.Diamond,
        )
    }
    val charShapes = remember { mutableMapOf<Int, RoundedPolygon>() }

    fun pickShape(index: Int): RoundedPolygon {
        val prev = if (index > 0) charShapes[index - 1] else null
        return shapes.filter { it != prev }.random()
    }

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
            onValueChange = { newValue ->
                val old = password
                password = newValue
                if (newValue.length < old.length) {
                    for (i in newValue.length until old.length) {
                        charShapes.remove(i)
                    }
                }
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (password.isNotBlank()) onConfirm(password) }
            ),
            decorationBox = {}
        )

        val density = LocalDensity.current
        val contentOffset = remember { Animatable(0f) }

        LaunchedEffect(password.length) {
            val step = with(density) { (20.dp + 8.dp).toPx() }
            val cursor = password.length * step

            val target = -max(0f, cursor - with(density) { 180.dp.toPx() })

            contentOffset.animateTo(
                target,
                animationSpec = spring(stiffness = 400f)
            )
        }

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
                            val density = LocalDensity.current
                            val textMeasurer = rememberTextMeasurer()
                            val textStyle = MaterialTheme.typography.bodyLarge
                            val textLayoutResult = remember(password, textStyle) {
                                textMeasurer.measure(
                                    AnnotatedString(password),
                                    style = textStyle
                                )
                            }
                            val textWidthPx = textLayoutResult.size.width.toFloat()

                            val cursorX by animateFloatAsState(
                                targetValue = textWidthPx + with(density) { 1.dp.toPx() },
                                animationSpec = spring(stiffness = 500f),
                                label = "cursor_pos_text"
                            )

                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(cursorX.roundToInt(), 0) }
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                } else {
                    Box {
                        Row(
                            modifier = Modifier.graphicsLayer {
                                translationX = contentOffset.value
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            password.indices.forEach { index ->
                                val polygon = charShapes.getOrPut(index) { pickShape(index) }
                                val shape = polygon.toShape()

                                val scale = remember { Animatable(1f) }

                                LaunchedEffect(Unit) {
                                    scale.snapTo(0.9f)

                                    scale.animateTo(
                                        1.15f,
                                        animationSpec = tween(120, easing = FastOutSlowInEasing)
                                    )

                                    scale.animateTo(
                                        1f,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 500f
                                        )
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .graphicsLayer {
                                            scaleX = scale.value
                                            scaleY = scale.value
                                        }
                                        .size(20.dp)
                                        .background(MaterialTheme.colorScheme.secondary, shape)
                                )
                            }
                        }

                        if (isFocused) {
                            val density = LocalDensity.current
                            val shapePx = with(density) { 20.dp.toPx() }
                            val spacingPx = with(density) { 8.dp.toPx() }

                            val cursorX by animateFloatAsState(
                                targetValue =
                                    if (password.isEmpty()) 0f
                                    else password.length * (shapePx + spacingPx) + contentOffset.value,
                                animationSpec = spring(stiffness = 500f),
                                label = "cursor_pos"
                            )

                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(cursorX.roundToInt(), 0) }
                                    .width(3.dp)
                                    .height(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }
            }

            IconButton(onClick = { passwordVisible = !passwordVisible }) {
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
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = password.isNotBlank() && !isSubmitting,
            shape = RoundedCornerShape(24.dp)
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
                    .verticalScroll(scrollState)
                    .padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    content()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                content()
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
