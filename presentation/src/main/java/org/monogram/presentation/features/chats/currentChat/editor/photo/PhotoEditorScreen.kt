@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.chats.currentChat.editor.photo

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.editor.photo.components.*
import org.monogram.presentation.features.chats.currentChat.editor.photo.crop.*
import java.io.File

enum class EditorTool(val labelRes: Int, val icon: ImageVector) {
    NONE(R.string.photo_editor_tool_view, Icons.Rounded.Visibility),
    TRANSFORM(R.string.photo_editor_tool_crop, Icons.Rounded.Crop),
    FILTER(R.string.photo_editor_tool_filters, Icons.Rounded.AutoAwesome),
    DRAW(R.string.photo_editor_tool_draw, Icons.Rounded.Brush),
    TEXT(R.string.photo_editor_tool_text, Icons.Rounded.TextFields),
    ERASER(R.string.photo_editor_tool_eraser, Icons.Rounded.CleaningServices)
}

private const val MinImageScale = 0.5f
private const val MaxImageScale = 10f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditorScreen(
    imagePath: String,
    onClose: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var currentTool by remember { mutableStateOf(EditorTool.TRANSFORM) }

    val paths = remember { mutableStateListOf<DrawnPath>() }
    val pathsRedo = remember { mutableStateListOf<DrawnPath>() }
    val textElements = remember { mutableStateListOf<TextElement>() }

    var selectedColor by remember { mutableStateOf(Color.Red) }
    var brushSize by remember { mutableFloatStateOf(15f) }
    var currentFilter by remember { mutableStateOf<ImageFilter?>(null) }

    
    var imageRotation by remember { mutableFloatStateOf(0f) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffset by remember { mutableStateOf(Offset.Zero) }

    var showTextDialog by remember { mutableStateOf(false) }
    var editingTextElement by remember { mutableStateOf<TextElement?>(null) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var isSaving by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val imageSize by produceState(initialValue = IntSize.Zero, key1 = imagePath) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, options)
            IntSize(options.outWidth.coerceAtLeast(0), options.outHeight.coerceAtLeast(0))
        }
    }

    
    val pivot by remember(canvasSize) {
        derivedStateOf { Offset(canvasSize.width / 2f, canvasSize.height / 2f) }
    }

    val cropState = rememberCropEditorState(
        canvasSize = canvasSize,
        imageSize = imageSize,
        transformPivot = pivot,
        imageScale = imageScale,
        imageRotation = imageRotation,
        imageOffset = imageOffset
    )
    var transformAnimationJob by remember { mutableStateOf<Job?>(null) }

    fun animateTransformTo(
        targetCropRect: Rect,
        targetRotation: Float,
        targetScale: Float,
        targetOffset: Offset,
        durationMillis: Int = 180
    ) {
        val startCrop = cropState.cropRect
        val startRotation = imageRotation
        val startScale = imageScale
        val startOffset = imageOffset

        if (
            startCrop == targetCropRect &&
            startRotation == targetRotation &&
            startScale == targetScale &&
            startOffset == targetOffset
        ) {
            cropState.setCropRect(targetCropRect)
            imageRotation = targetRotation
            imageScale = targetScale
            imageOffset = targetOffset
            return
        }

        transformAnimationJob?.cancel()
        transformAnimationJob = scope.launch {
            val anim = androidx.compose.animation.core.Animatable(0f)
            anim.animateTo(1f, androidx.compose.animation.core.tween(durationMillis)) {
                val t = value
                cropState.setCropRect(
                    Rect(
                        left = startCrop.left + (targetCropRect.left - startCrop.left) * t,
                        top = startCrop.top + (targetCropRect.top - startCrop.top) * t,
                        right = startCrop.right + (targetCropRect.right - startCrop.right) * t,
                        bottom = startCrop.bottom + (targetCropRect.bottom - startCrop.bottom) * t
                    )
                )
                imageRotation = startRotation + (targetRotation - startRotation) * t
                imageScale = startScale + (targetScale - startScale) * t
                imageOffset = Offset(
                    x = startOffset.x + (targetOffset.x - startOffset.x) * t,
                    y = startOffset.y + (targetOffset.y - startOffset.y) * t
                )
            }
        }
    }

    
    fun fillAreaAfterResize() {
        val crop = cropState.cropRect
        if (crop.width <= 0f || crop.height <= 0f || canvasSize.width <= 0 || canvasSize.height <= 0) return

        val currentAspect = crop.width / crop.height
        val targetCropRect = calculateTargetFillRect(canvasSize, currentAspect)
        if (targetCropRect == Rect.Zero) return

        
        val scaleFactor = maxOf(
            targetCropRect.width / crop.width,
            targetCropRect.height / crop.height
        )
        val targetScale = (imageScale * scaleFactor).coerceIn(MinImageScale, MaxImageScale)
        val z = if (imageScale != 0f) targetScale / imageScale else 1f

        
        
        
        
        val targetOffset = Offset(
            x = (targetCropRect.center.x - pivot.x) - z * (crop.center.x - pivot.x - imageOffset.x),
            y = (targetCropRect.center.y - pivot.y) - z * (crop.center.y - pivot.y - imageOffset.y)
        )

        val targetImageBounds = calculateScalarTransformedBounds(
            baseBounds = cropState.imageBounds,
            scale = targetScale,
            rotationDegrees = imageRotation,
            offset = targetOffset,
            pivot = pivot
        )
        val safeTargetCropRect = constrainCropRectToImage(
            currentCropRect = crop,
            candidateRect = targetCropRect,
            visibleBounds = targetImageBounds,
            minCropSizePx = cropState.minCropSizePx,
            baseBounds = cropState.imageBounds,
            scale = targetScale,
            rotationDegrees = imageRotation,
            offset = targetOffset,
            pivot = pivot
        )

        
        animateTransformTo(
            targetCropRect = safeTargetCropRect,
            targetRotation = imageRotation,
            targetScale = targetScale,
            targetOffset = targetOffset,
            durationMillis = 200
        )
    }

    val shouldConstrain by remember(currentTool) {
        derivedStateOf { currentTool == EditorTool.TRANSFORM || currentTool == EditorTool.NONE }
    }

    fun applyTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val effectiveMinScale = if (shouldConstrain && cropState.cropRect != Rect.Zero && cropState.imageBounds != Rect.Zero) {
            minimumScaleToCoverCrop(
                baseBounds = cropState.imageBounds,
                cropRect = cropState.cropRect,
                currentScale = imageScale,
                rotationDegrees = imageRotation,
                offset = imageOffset,
                pivot = pivot
            ).coerceAtLeast(MinImageScale)
        } else {
            MinImageScale
        }

        val newScale = (imageScale * zoom).coerceIn(effectiveMinScale, MaxImageScale)
        val actualZoom = if (imageScale != 0f) newScale / imageScale else 1f

        val offsetAfterZoom = offsetForZoomAroundAnchor(imageOffset, pivot, centroid, actualZoom)
        val newOffset = offsetAfterZoom + pan

        imageScale = newScale
        imageOffset = if (shouldConstrain && cropState.cropRect != Rect.Zero && cropState.imageBounds != Rect.Zero) {
            clampOffsetToCoverCrop(
                baseBounds = cropState.imageBounds,
                cropRect = cropState.cropRect,
                scale = newScale,
                rotationDegrees = imageRotation,
                offset = newOffset,
                pivot = pivot
            )
        } else {
            newOffset
        }
    }

    fun applyRotation(newRotation: Float) {
        val deltaAngle = newRotation - imageRotation
        
        val anchor = if (cropState.cropRect != Rect.Zero) cropState.cropRect.center else pivot
        val newOffset = offsetForRotationAroundAnchor(imageOffset, pivot, anchor, deltaAngle)

        imageRotation = newRotation

        
        if (shouldConstrain && cropState.cropRect != Rect.Zero && cropState.imageBounds != Rect.Zero) {
            val (fittedScale, fittedOffset) = fitContentInBounds(
                baseBounds = cropState.imageBounds,
                cropRect = cropState.cropRect,
                scale = imageScale,
                rotationDegrees = newRotation,
                offset = newOffset,
                pivot = pivot,
                minScale = MinImageScale,
                maxScale = MaxImageScale
            )
            imageScale = fittedScale
            imageOffset = fittedOffset
        } else {
            imageOffset = newOffset
        }
    }

    fun rotateClockwise() {
        val targetRotation = rotateClockwiseAnimationTarget(imageRotation)

        animateTransformTo(
            targetCropRect = if (cropState.imageBounds == Rect.Zero) {
                Rect.Zero
            } else {
                calculateScalarTransformedBounds(
                    baseBounds = cropState.imageBounds,
                    scale = 1f,
                    rotationDegrees = targetRotation,
                    offset = Offset.Zero,
                    pivot = pivot
                )
            },
            targetRotation = targetRotation,
            targetScale = 1f,
            targetOffset = Offset.Zero
        )
    }

    val hasChanges by remember {
        derivedStateOf {
            paths.isNotEmpty() ||
                    textElements.isNotEmpty() ||
                    currentFilter != null ||
                    (cropState.cropRect != Rect.Zero && cropState.cropRect != cropState.defaultCropRect) ||
                    normalizeRotationDegrees(imageRotation) != 0f ||
                    imageScale != 1f ||
                    imageOffset != Offset.Zero
        }
    }

    val handleBack = {
        if (currentTool != EditorTool.NONE) {
            currentTool = EditorTool.NONE
        } else if (hasChanges) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    BackHandler(onBack = handleBack)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            EditorTopBar(
                onClose = handleBack,
                onSave = {
                    if (!isSaving) {
                        scope.launch {
                            isSaving = true
                            val result = saveImage(
                                context,
                                imagePath,
                                paths,
                                textElements,
                                currentFilter,
                                canvasSize,
                                cropState.cropRect,
                                pivot,
                                normalizeRotationDegrees(imageRotation),
                                imageScale,
                                imageOffset
                            )
                            isSaving = false
                            if (result != null) onSave(result)
                        }
                    }
                },
                onUndo = {
                    if (paths.isNotEmpty()) {
                        pathsRedo.add(paths.removeAt(paths.lastIndex))
                    } else if (textElements.isNotEmpty()) {
                        textElements.removeAt(textElements.lastIndex)
                    }
                },
                onRedo = {
                    if (pathsRedo.isNotEmpty()) {
                        paths.add(pathsRedo.removeAt(pathsRedo.lastIndex))
                    }
                },
                canUndo = paths.isNotEmpty() || textElements.isNotEmpty(),
                canRedo = pathsRedo.isNotEmpty()
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AnimatedContent(
                        targetState = currentTool,
                        label = "ToolOptions",
                        modifier = Modifier.fillMaxWidth()
                    ) { tool ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (tool) {
                                EditorTool.TRANSFORM -> {
                                    TransformControls(
                                        rotation = imageRotation,
                                        onRotationChange = { newRotation -> applyRotation(newRotation) },
                                        onRotateClockwise = { rotateClockwise() },
                                        onReset = {
                                            imageRotation = 0f
                                            imageScale = 1f
                                            imageOffset = Offset.Zero
                                            cropState.reset()
                                        }
                                    )
                                }
                                EditorTool.DRAW, EditorTool.ERASER -> {
                                    DrawControls(
                                        isEraser = tool == EditorTool.ERASER,
                                        color = selectedColor,
                                        size = brushSize,
                                        onColorChange = { selectedColor = it },
                                        onSizeChange = { brushSize = it }
                                    )
                                }
                                EditorTool.FILTER -> {
                                    FilterControls(
                                        imagePath = imagePath,
                                        currentFilter = currentFilter,
                                        onFilterSelect = { currentFilter = it }
                                    )
                                }
                                EditorTool.TEXT -> {
                                    Button(
                                        onClick = {
                                            editingTextElement = null
                                            showTextDialog = true
                                        },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.photo_editor_action_add_text))
                                    }
                                }
                                else -> {
                                    Text(
                                        stringResource(R.string.photo_editor_label_select_tool),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                        EditorTool.entries.forEach { tool ->
                            val label = stringResource(tool.labelRes)
                            NavigationBarItem(
                                selected = currentTool == tool,
                                onClick = { currentTool = tool },
                                icon = { Icon(tool.icon, contentDescription = label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clip(RectangleShape)
                .background(Color.Black)
                .onGloballyPositioned { canvasSize = it.size }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = imageScale,
                            scaleY = imageScale,
                            rotationZ = imageRotation,
                            translationX = imageOffset.x,
                            translationY = imageOffset.y,
                            transformOrigin = TransformOrigin.Center
                        )
                        .pointerInput(currentTool) {
                            if (currentTool == EditorTool.NONE) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    applyTransform(centroid, pan, zoom)
                                }
                            }
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(File(imagePath)).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        colorFilter = currentFilter?.let { ColorFilter.colorMatrix(it.colorMatrix) }
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentTool) {
                                if (currentTool == EditorTool.DRAW || currentTool == EditorTool.ERASER) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val path = Path().apply { moveTo(offset.x, offset.y) }
                                            paths.add(
                                                DrawnPath(
                                                    path = path,
                                                    color = if (currentTool == EditorTool.ERASER) Color.Transparent else selectedColor,
                                                    strokeWidth = brushSize,
                                                    isEraser = currentTool == EditorTool.ERASER
                                                )
                                            )
                                            pathsRedo.clear()
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val index = paths.lastIndex
                                            if (index == -1) return@detectDragGestures
                                            val cur = paths[index]
                                            val x1 = change.previousPosition.x
                                            val y1 = change.previousPosition.y
                                            val x2 = change.position.x
                                            val y2 = change.position.y
                                            cur.path.quadraticTo(x1, y1, (x1 + x2) / 2, (y1 + y2) / 2)
                                            paths.add(paths.removeAt(index))
                                        }
                                    )
                                }
                            }
                    ) {
                        paths.forEach { pathData ->
                            drawPath(
                                path = pathData.path,
                                color = pathData.color,
                                alpha = pathData.alpha,
                                style = Stroke(width = pathData.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                blendMode = if (pathData.isEraser) BlendMode.Clear else BlendMode.SrcOver
                            )
                        }
                    }

                    textElements.forEach { element ->
                        var currentOffset by remember(element.id) {
                            mutableStateOf(
                                if (element.offset == Offset.Zero) Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                                else element.offset
                            )
                        }
                        var currentScale by remember(element.id) { mutableStateOf(element.scale) }
                        var currentRotation by remember(element.id) { mutableStateOf(element.rotation) }

                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) { currentOffset.x.toDp() },
                                    y = with(density) { currentOffset.y.toDp() }
                                )
                                .graphicsLayer(
                                    scaleX = currentScale,
                                    scaleY = currentScale,
                                    rotationZ = currentRotation,
                                    translationX = -with(density) { 100.dp.toPx() },
                                    translationY = -with(density) { 25.dp.toPx() }
                                )
                                .pointerInput(currentTool, element.id) {
                                    if (currentTool == EditorTool.TEXT || currentTool == EditorTool.NONE) {
                                        detectTransformGestures { _, pan, zoom, rotation ->
                                            currentOffset += pan
                                            currentScale *= zoom
                                            currentRotation += rotation
                                        }
                                    }
                                }
                                .pointerInput(currentTool, element.id) {
                                    if (currentTool == EditorTool.TEXT || currentTool == EditorTool.NONE) {
                                        detectTapGestures(onTap = {
                                            if (currentTool == EditorTool.TEXT) {
                                                editingTextElement = element
                                                selectedColor = element.color
                                                showTextDialog = true
                                            }
                                        })
                                    }
                                }
                        ) {
                            LaunchedEffect(currentOffset, currentScale, currentRotation) {
                                val idx = textElements.indexOfFirst { it.id == element.id }
                                if (idx != -1 && (textElements[idx].offset != currentOffset || textElements[idx].rotation != currentRotation)) {
                                    textElements[idx] = textElements[idx].copy(
                                        offset = currentOffset, scale = currentScale, rotation = currentRotation
                                    )
                                }
                            }
                            Text(
                                text = element.text,
                                color = element.color,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 4f)
                                )
                            )
                            if (currentTool == EditorTool.TEXT) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }

            if (currentTool == EditorTool.TRANSFORM && cropState.cropRect != Rect.Zero) {
                CropScrim(cropRect = cropState.cropRect)
            }

            AnimatedVisibility(
                visible = currentTool == EditorTool.TRANSFORM && cropState.cropRect != Rect.Zero,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CropOverlay(
                    cropRect = cropState.cropRect,
                    bounds = cropState.currentImageBounds,
                    minCropSizePx = cropState.minCropSizePx,
                    onCropRectChange = cropState.updateCropRect,
                    onContentTransform = { centroid, pan, zoom -> applyTransform(centroid, pan, zoom) },
                    onResizeEnded = { fillAreaAfterResize() }
                )
            }

            if (isSaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    ContainedLoadingIndicator()
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.photo_editor_discard_title)) },
            text = { Text(stringResource(R.string.photo_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.photo_editor_discard_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.photo_editor_action_cancel))
                }
            }
        )
    }

    if (showTextDialog) {
        TextEntryDialog(
            initialText = editingTextElement?.text ?: "",
            initialColor = editingTextElement?.color ?: selectedColor,
            onDismiss = {
                showTextDialog = false
                editingTextElement = null
            },
            onConfirm = { text, color ->
                if (editingTextElement != null) {
                    val index = textElements.indexOfFirst { it.id == editingTextElement!!.id }
                    if (index != -1) {
                        textElements[index] = textElements[index].copy(text = text, color = color)
                    }
                } else {
                    textElements.add(
                        TextElement(
                            text = text,
                            color = color,
                            offset = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                        )
                    )
                }
                showTextDialog = false
                editingTextElement = null
            },
            onDelete = {
                editingTextElement?.let { el -> textElements.removeIf { it.id == el.id } }
                showTextDialog = false
                editingTextElement = null
            }
        )
    }
}
