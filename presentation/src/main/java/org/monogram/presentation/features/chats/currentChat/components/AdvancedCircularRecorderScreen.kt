package org.monogram.presentation.features.chats.currentChat.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.presentation.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

private const val EGL_RECORDABLE_ANDROID = 0x3142

@Composable
fun AdvancedCircularRecorderScreen(
    onClose: () -> Unit,
    onVideoRecorded: (File) -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { perms -> hasPermissions = perms.values.all { it } }
    )

    LaunchedEffect(key1 = true) {
        if (!hasPermissions) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasPermissions) {
                NativeCircularCameraContent(onClose, onVideoRecorded)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.recorder_permissions_required))
                }
            }
        }
    }
}

@SuppressLint("MissingPermission", "RestrictedApi")
@Composable
fun NativeCircularCameraContent(
    onClose: () -> Unit,
    onVideoRecorded: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val recordedSegments = remember { mutableStateListOf<File>() }
    var recording by remember { mutableStateOf<Recording?>(null) }

    var isRecording by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    var isSwitchingCamera by remember { mutableStateOf(false) }
    var pendingResume by remember { mutableStateOf(false) }
    var shouldDiscardAll by remember { mutableStateOf(false) }
    var recordingStartMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    val zoomRounded = ((currentZoomRatio * 10f).toInt() / 10f)
    val minZoomRounded = ((minZoom * 10f).toInt() / 10f)
    val maxZoomRounded = ((maxZoom * 10f).toInt() / 10f)

    val pulseTransition = rememberInfiniteTransition(label = "rec_pulse")
    val recDotScale by pulseTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_scale"
    )
    val recDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_dot_alpha"
    )

    val recordInnerSize by animateDpAsState(
        targetValue = if (isRecording) 34.dp else 72.dp,
        animationSpec = tween(durationMillis = 220),
        label = "record_inner_size"
    )
    val recordInnerColor by animateColorAsState(
        targetValue = if (isRecording) Color(0xFFFF3B30) else Color.White,
        animationSpec = tween(durationMillis = 220),
        label = "record_inner_color"
    )
    val preview = remember { Preview.Builder().build() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
            .build()

        VideoCapture.Builder(recorder)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun finishRecording() {
        if (recordedSegments.isEmpty()) {
            isRecording = false
            recording = null
            recordingStartMs = 0L
            elapsedSeconds = 0L
            return
        }
        isProcessing = true
        isRecording = false
        recording = null
        recordingStartMs = 0L
        elapsedSeconds = 0L
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) { e.printStackTrace() }

        coroutineScope.launch(Dispatchers.IO) {
            val finalFile = File(context.filesDir, "CIRCLE_FULL_${System.currentTimeMillis()}.mp4")
            try {
                val segmentsToProcess = ArrayList(recordedSegments)
                val transcoder = CircularTranscoder(segmentsToProcess, finalFile)
                transcoder.start()

                withContext(Dispatchers.Main) {
                    onVideoRecorded(finalFile)
                }

                segmentsToProcess.forEach { it.delete() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val message = context.getString(
                        R.string.recorder_processing_error,
                        e.message ?: ""
                    )
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                recordedSegments.clear()
                isProcessing = false
            }
        }
    }

    LaunchedEffect(isRecording, recordingStartMs) {
        if (isRecording && recordingStartMs > 0L) {
            while (isRecording) {
                elapsedSeconds = ((System.currentTimeMillis() - recordingStartMs) / 1000L).coerceAtLeast(0L)
                delay(250)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    fun cancelAndClose() {
        shouldDiscardAll = true
        isSwitchingCamera = false
        pendingResume = false

        if (isRecording) {
            recording?.stop()
            return
        }

        recordedSegments.forEach { it.delete() }
        recordedSegments.clear()
        recording = null
        recordingStartMs = 0L
        elapsedSeconds = 0L
        shouldDiscardAll = false
        onClose()
    }

    fun handleSegmentSaved(file: File) {
        if (shouldDiscardAll) {
            file.delete()
            recordedSegments.forEach { it.delete() }
            recordedSegments.clear()
            isRecording = false
            recording = null
            recordingStartMs = 0L
            elapsedSeconds = 0L
            shouldDiscardAll = false
            onClose()
            return
        }

        recordedSegments.add(file)
        if (isSwitchingCamera) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
        } else {
            finishRecording()
        }
    }

    LaunchedEffect(lensFacing) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            cameraProvider.unbindAll()
            val cam = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            camera = cam
            cam.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                currentZoomRatio = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
            }
            preview.surfaceProvider = previewView.surfaceProvider

            if (pendingResume) {
                pendingResume = false
                isSwitchingCamera = false

                startNativeSegment(
                    context,
                    videoCapture,
                    onStart = { rec -> recording = rec },
                    onSegmentSaved = ::handleSegmentSaved
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        camera?.let { cam ->
                            val newZoom = (currentZoomRatio * zoom).coerceIn(minZoom, maxZoom)
                            cam.cameraControl.setZoomRatio(newZoom)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        camera?.let { cam ->
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        }
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(390.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                IconButton(onClick = onClose, enabled = !isRecording && !isProcessing) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.recorder_close_cd),
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .graphicsLayer {
                                    scaleX = if (isRecording) recDotScale else 1f
                                    scaleY = if (isRecording) recDotScale else 1f
                                    alpha = if (isRecording) recDotAlpha else 0.5f
                                }
                                .clip(CircleShape)
                                .background(if (isRecording) Color(0xFFFF453A) else Color(0xFF8E8E93))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) {
                                stringResource(R.string.recorder_rec_label)
                            } else {
                                stringResource(R.string.recorder_ready_label)
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = stringResource(
                            R.string.recorder_time_format,
                            formatElapsedTime(elapsedSeconds)
                        ),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.recorder_zoom_format,
                        zoomRounded,
                        minZoomRounded,
                        maxZoomRounded
                    ),
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                isSwitchingCamera = true
                                pendingResume = true
                                recording?.stop()
                            } else {
                                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                    CameraSelector.LENS_FACING_BACK
                                } else {
                                    CameraSelector.LENS_FACING_FRONT
                                }
                            }
                        },
                        enabled = !isProcessing && !isSwitchingCamera,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.recorder_switch_cd),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(94.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    if (isProcessing || isSwitchingCamera) return@detectTapGestures
                                    if (!isRecording) {
                                        isRecording = true
                                        recordingStartMs = System.currentTimeMillis()
                                        startNativeSegment(
                                            context,
                                            videoCapture,
                                            onStart = { r -> recording = r },
                                            onSegmentSaved = ::handleSegmentSaved
                                        )
                                    } else {
                                        recording?.stop()
                                    }
                                })
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(recordInnerSize)
                                .clip(if (isRecording) RoundedCornerShape(8.dp) else CircleShape)
                                .background(recordInnerColor)
                        )
                        if (isRecording) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isRecording) {
                                recording?.stop()
                            } else {
                                finishRecording()
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.recorder_done_cd),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = ::cancelAndClose,
                    enabled = !isProcessing,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = stringResource(R.string.recorder_cancel),
                        color = Color.White.copy(alpha = 0.95f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (isProcessing) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        stringResource(R.string.recorder_processing),
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

private fun formatElapsedTime(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val minutes = safeSeconds / 60
    val seconds = safeSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@SuppressLint("MissingPermission")
fun startNativeSegment(
    context: Context,
    videoCapture: VideoCapture<Recorder>,
    onStart: (Recording) -> Unit,
    onSegmentSaved: (File) -> Unit
) {
    val tempFile = File(context.cacheDir, "segment_${System.currentTimeMillis()}.mp4")
    val outputOptions = FileOutputOptions.Builder(tempFile).build()

    val recording = videoCapture.output
        .prepareRecording(context, outputOptions)
        .withAudioEnabled()
        .start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (!event.hasError()) {
                    onSegmentSaved(tempFile)
                } else {
                    if (event.cause != null) event.cause?.printStackTrace()
                }
            }
        }
    onStart(recording)
}

class CircularTranscoder(private val inputFiles: List<File>, private val outputFile: File) {
    private val OUTPUT_WIDTH = 384
    private val OUTPUT_HEIGHT = 384
    private val OUTPUT_BIT_RATE = 1_800_000
    private val FRAME_RATE = 60

    private var muxerAudioTrackIndex = -1
    private var muxerVideoTrackIndex = -1
    private var muxerStarted = false

    fun start() {
        if (inputFiles.isEmpty()) return

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            processVideoSequence(muxer)
            if (muxerStarted) {
                processAudioSequence(muxer)
            }
        } finally {
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun processVideoSequence(muxer: MediaMuxer) {
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, OUTPUT_WIDTH, OUTPUT_HEIGHT)
        outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BIT_RATE)
        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = InputSurface(encoder.createInputSurface())
        inputSurface.makeCurrent()
        encoder.start()

        var scaleX = 1.0f
        var scaleY = 1.0f

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(inputFiles[0].absolutePath)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

            val rawWidth = widthStr?.toIntOrNull() ?: 640
            val rawHeight = heightStr?.toIntOrNull() ?: 480
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val (inputWidth, inputHeight) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            val inputAspect = inputWidth.toFloat() / inputHeight
            val outputAspect = OUTPUT_WIDTH.toFloat() / OUTPUT_HEIGHT

            if (inputAspect > outputAspect) {
                scaleX = inputAspect / outputAspect
                scaleY = 1.0f
            } else {
                scaleX = 1.0f
                scaleY = outputAspect / inputAspect
            }
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 1000L
        var totalDurationUs = 0L

        val surfaceTextureRenderer = TextureRenderer()
        surfaceTextureRenderer.setScale(scaleX, scaleY)

        for (file in inputFiles) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val videoTrackIndex = selectTrack(extractor, "video/")
                if (videoTrackIndex < 0) continue

                extractor.selectTrack(videoTrackIndex)
                val inputFormat = extractor.getTrackFormat(videoTrackIndex)

                val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
                val decoder = MediaCodec.createDecoderByType(mime)

                val surfaceTexture = SurfaceTexture(surfaceTextureRenderer.getTextureId())
                val surface = Surface(surfaceTexture)

                decoder.configure(inputFormat, surface, null, 0)
                decoder.start()

                var outputDone = false
                var inputDone = false
                var fileLastPts = 0L

                while (!outputDone) {
                    if (!inputDone) {
                        val inputBufIndex = decoder.dequeueInputBuffer(timeoutUs)
                        if (inputBufIndex >= 0) {
                            val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                            val chunkSize = extractor.readSampleData(inputBuf, 0)
                            if (chunkSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputBufIndex, 0, chunkSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    var decoderOutputAvailable = true
                    while (decoderOutputAvailable) {
                        val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            decoderOutputAvailable = false
                        } else if (outputBufIndex >= 0) {
                            val doRender = (bufferInfo.size != 0)
                            decoder.releaseOutputBuffer(outputBufIndex, doRender)

                            if (doRender) {
                                surfaceTexture.updateTexImage()
                                surfaceTextureRenderer.draw(OUTPUT_WIDTH, OUTPUT_HEIGHT, surfaceTexture)

                                val currentPts = bufferInfo.presentationTimeUs
                                fileLastPts = currentPts
                                val finalPts = currentPts + totalDurationUs

                                inputSurface.setPresentationTime(finalPts * 1000)
                                inputSurface.swapBuffers()
                            }

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                decoderOutputAvailable = false
                            }
                        }
                    }

                    var encoderOutputAvailable = true
                    while (encoderOutputAvailable) {
                        val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            encoderOutputAvailable = false
                        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerStarted) throw RuntimeException("Format changed twice")
                            val newFormat = encoder.outputFormat
                            muxerVideoTrackIndex = muxer.addTrack(newFormat)

                            val audioEx = MediaExtractor()
                            audioEx.setDataSource(inputFiles[0].absolutePath)
                            val at = selectTrack(audioEx, "audio/")
                            if (at >= 0) {
                                val af = audioEx.getTrackFormat(at)
                                muxerAudioTrackIndex = muxer.addTrack(af)
                            }
                            audioEx.release()

                            muxer.start()
                            muxerStarted = true
                        } else if (encoderStatus >= 0) {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0

                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encoderStatus, false)
                        }
                    }
                }

                decoder.stop(); decoder.release()
                surfaceTexture.release(); surface.release()

                totalDurationUs += (fileLastPts + 20000L)

            } catch (e: Exception) { e.printStackTrace() }
            finally { extractor.release() }
        }

        encoder.signalEndOfInputStream()
        var eos = false
        while (!eos) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (encoderStatus >= 0) {
                val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(muxerVideoTrackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
            } else if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }

        encoder.stop(); encoder.release()
        inputSurface.release()
    }

    private fun processAudioSequence(muxer: MediaMuxer) {
        if (muxerAudioTrackIndex < 0) return

        var totalDurationUs = 0L
        val buffer = ByteBuffer.allocate(256 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        for (file in inputFiles) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = selectTrack(extractor, "audio/")
                if (trackIndex < 0) continue

                extractor.selectTrack(trackIndex)
                var fileLastPts = 0L

                while (true) {
                    val chunkSize = extractor.readSampleData(buffer, 0)
                    if (chunkSize < 0) break

                    bufferInfo.offset = 0
                    bufferInfo.size = chunkSize
                    bufferInfo.flags = extractor.sampleFlags

                    val originalPts = extractor.sampleTime
                    fileLastPts = originalPts
                    bufferInfo.presentationTimeUs = originalPts + totalDurationUs

                    muxer.writeSampleData(muxerAudioTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                totalDurationUs += (fileLastPts + 20000L)
            } catch (e: Exception) { e.printStackTrace() }
            finally { extractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(prefix) == true) return i
        }
        return -1
    }

    private class TextureRenderer {
        private var program = 0; private var textureId = 0
        private var uSTMatrixHandle = 0
        private var uScaleHandle = 0
        private val stMatrix = FloatArray(16)
        private val vertexStride = 8
        private val vertexData = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData).apply { position(0) }

        private var scaleX = 1.0f
        private var scaleY = 1.0f

        private val vertexShaderCode = """
            uniform mat4 uSTMatrix;
            uniform vec2 uScale; 
            attribute vec4 aPosition;
            varying vec2 vTextureCoord;
            varying vec2 vPosition;
            void main() {
                vec4 scaledPos = vec4(aPosition.x * uScale.x, aPosition.y * uScale.y, aPosition.z, 1.0);
                gl_Position = scaledPos;
                vPosition = aPosition.xy; 
                vec2 texCoord = (aPosition.xy + 1.0) / 2.0;
                vTextureCoord = (uSTMatrix * vec4(texCoord, 0.0, 1.0)).xy;
            }
        """

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            varying vec2 vPosition;
            uniform samplerExternalOES sTexture;
            void main() {
                float dist = length(vPosition);
                if (dist < 1.0) { 
                   gl_FragColor = texture2D(sTexture, vTextureCoord);
                } else {
                   gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
                }
            }
        """

        init {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0); textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = createProgram(vertexShaderCode, fragmentShaderCode)
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            uScaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        }

        fun getTextureId(): Int = textureId

        fun setScale(x: Float, y: Float) {
            scaleX = x
            scaleY = y
        }

        fun draw(width: Int, height: Int, surfaceTexture: SurfaceTexture) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)

            GLES20.glUniform2f(uScaleHandle, scaleX, scaleY)

            surfaceTexture.getTransformMatrix(stMatrix)
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader); GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program); return program
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader); return shader
        }
    }

    private class InputSurface(surface: Surface) {
        private var eglDisplay = EGL14.EGL_NO_DISPLAY; private var eglContext = EGL14.EGL_NO_CONTEXT; private var eglSurface = EGL14.EGL_NO_SURFACE

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2); EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
            val attribList = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
            val attrib_list = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0)
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        }

        fun makeCurrent() { EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext) }
        fun swapBuffers() { EGL14.eglSwapBuffers(eglDisplay, eglSurface) }
        fun setPresentationTime(nsecs: Long) { EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs) }
        fun release() {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface); EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}