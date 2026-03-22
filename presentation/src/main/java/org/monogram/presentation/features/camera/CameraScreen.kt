package org.monogram.presentation.features.camera

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    onImageCaptured: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOpen by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = tween(240),
        label = "cameraSheetProgress"
    )

    val dismiss: () -> Unit = {
        if (isOpen) {
            isOpen = false
            scope.launch {
                delay(220)
                onDismiss()
            }
        }
    }

    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCaptureUri?.let(onImageCaptured)
        }
        pendingCaptureUri = null
    }

    LaunchedEffect(Unit) {
        isOpen = true
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss
                )
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .graphicsLayer {
                    translationY = (1f - progress) * 140f
                    alpha = 0.7f + 0.3f * progress
                },
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Camera",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    IconButton(
                        onClick = dismiss,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Take a high-quality photo",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Photo will be added to input attachments.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        val uri = context.newCameraPhotoUri() ?: return@Button
                        pendingCaptureUri = uri
                        cameraLauncher.launch(uri)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Camera")
                }
            }
        }
    }
}

private fun android.content.Context.newCameraPhotoUri(): Uri? {
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DISPLAY_NAME, "camera_${System.nanoTime()}.jpg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Monogram")
            }
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } catch (_: Exception) {
        null
    }
}
