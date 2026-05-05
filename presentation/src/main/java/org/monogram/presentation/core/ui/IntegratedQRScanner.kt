package org.monogram.presentation.core.ui

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.monogram.presentation.R
import java.nio.ByteBuffer

@Composable
fun IntegratedQRScanner(
    onCodeDetected: (String) -> Unit,
    onBackClicked: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    var lastScannedCode by remember { mutableStateOf("") }
    var torchEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)

        cameraController.setImageAnalysisAnalyzer(
            executor,
            ZxingQrAnalyzer { code ->
                if (code != lastScannedCode) {
                    lastScannedCode = code
                    onCodeDetected(code)
                }
            }
        )
        cameraController.bindToLifecycle(lifecycleOwner)
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = onBackClicked,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_close_scanner),
                tint = Color.White
            )
        }

        IconButton(
            onClick = {
                torchEnabled = !torchEnabled
                cameraController.enableTorch(torchEnabled)
            },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                contentDescription = "Toggle flashlight",
                tint = Color.White
            )
        }
    }
}

private class ZxingQrAnalyzer(
    private val onCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val fallbackReader = MultiFormatReader()

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val plane = image.planes.firstOrNull()
        if (plane == null) {
            image.close()
            return
        }

        val bytes = plane.buffer.toByteArray()
        val source = PlanarYUVLuminanceSource(
            bytes,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )

        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val text = decode(bitmap)
        if (!text.isNullOrBlank()) {
            onCodeDetected(text)
        }
        image.close()
    }

    private fun decode(bitmap: BinaryBitmap): String? {
        return try {
            reader.decode(bitmap).text
        } catch (_: NotFoundException) {
            runCatching { fallbackReader.decode(bitmap).text }.getOrNull()
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
            fallbackReader.reset()
        }
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    return ByteArray(remaining()).also { get(it) }
}
