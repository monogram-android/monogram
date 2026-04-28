package org.monogram.presentation.features.chats.conversation.ui.inputbar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.log10

@Composable
fun rememberVoiceRecorder(
    onRecordingFinished: (String, Int, ByteArray) -> Unit,
    onPermissionDenied: () -> Unit = {}
): VoiceRecorderState {
    val context = LocalContext.current
    val state = remember { VoiceRecorderState(context) }

    LaunchedEffect(onRecordingFinished) {
        state.onRecordingFinished = onRecordingFinished
    }

    LaunchedEffect(onPermissionDenied) {
        state.onPermissionDenied = onPermissionDenied
    }

    DisposableEffect(Unit) {
        onDispose {
            state.stopRecording(cancel = true)
        }
    }

    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            state.runUpdateLoop()
        }
    }

    return state
}

class VoiceRecorderState(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    var isLocked by mutableStateOf(false)
        private set
    var durationMillis by mutableLongStateOf(0L)
        private set
    var amplitude by mutableFloatStateOf(0f)
        private set

    val waveform = mutableStateListOf<Byte>()

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTime = 0L

    var onRecordingFinished: ((String, Int, ByteArray) -> Unit)? = null
    var onPermissionDenied: (() -> Unit)? = null

    @Suppress("DEPRECATION")
    fun startRecording() {
        if (isRecording) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionDenied?.invoke()
            return
        }

        try {
            val supportsOggOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val extension = if (supportsOggOpus) "ogg" else "m4a"

            val file = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.$extension")
            currentFile = file
            waveform.clear()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)

                if (supportsOggOpus) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioEncodingBitRate(320000)
                    setAudioSamplingRate(48000)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(320000)
                    setAudioSamplingRate(48000)
                }

                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            startTime = System.currentTimeMillis()
            isRecording = true
            isLocked = false
            durationMillis = 0
        } catch (e: Exception) {
            e.printStackTrace()
            releaseResources()
            isRecording = false
        }
    }

    private fun releaseResources() {
        mediaRecorder?.let { recorder ->
            try {
                recorder.stop()
            } catch (e: Exception) {
                // Ignore: stop() can fail if called too soon after start()
            } finally {
                try {
                    recorder.release()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        mediaRecorder = null
    }

    fun lockRecording() {
        if (isRecording) {
            isLocked = true
        }
    }

    fun stopRecording(cancel: Boolean = false) {
        if (!isRecording) return

        val capturedDurationMillis = durationMillis
        val wasRecording = isRecording
        val file = currentFile

        releaseResources()
        isRecording = false
        isLocked = false
        currentFile = null

        if (wasRecording && !cancel && file != null) {
            val durationSec = (capturedDurationMillis / 1000).toInt()
            if (durationSec >= 1) {
                onRecordingFinished?.invoke(file.absolutePath, durationSec, waveform.toByteArray())
            } else {
                file.delete()
            }
        } else {
            file?.delete()
        }
    }

    suspend fun runUpdateLoop() {
        while (isRecording) {
            durationMillis = System.currentTimeMillis() - startTime

            val maxAmp = try {
                mediaRecorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }

            amplitude = if (maxAmp > 0) {
                (20 * log10(maxAmp.toDouble() / 32767.0)).toFloat().coerceIn(-60f, 0f)
            } else -60f

            // Map -60..0 to 0..31 for TDLib waveform
            val normalized = ((amplitude + 60) / 60 * 31).toInt().coerceIn(0, 31)
            waveform.add(normalized.toByte())

            delay(100)
        }
    }
}