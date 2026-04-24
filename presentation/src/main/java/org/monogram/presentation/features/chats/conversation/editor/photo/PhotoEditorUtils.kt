package org.monogram.presentation.features.chats.conversation.editor.photo

import android.content.Context
import android.graphics.*
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.presentation.R
import java.io.File
import java.io.FileOutputStream
import java.util.*
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation

data class DrawnPath(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val alpha: Float = 1f,
    val isEraser: Boolean = false
)

data class TextElement(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val color: Color,
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val rotation: Float = 0f
)

data class ImageFilter(
    @StringRes val nameRes: Int,
    val colorMatrix: ColorMatrix
)

fun getPresetFilters(): List<ImageFilter> {
    return listOf(
        ImageFilter(R.string.photo_editor_filter_original, ColorMatrix()),
        ImageFilter(R.string.photo_editor_filter_bw, ColorMatrix().apply { setToSaturation(0f) }),
        ImageFilter(
            R.string.photo_editor_filter_sepia, ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        ImageFilter(
            R.string.photo_editor_filter_vintage, ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.7f, 0f, 0f, 0f,
                    0f, 0f, 0.5f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        ImageFilter(
            R.string.photo_editor_filter_cool, ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0.5f, 0f, 0f,
                    0f, 0f, 1.5f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        ImageFilter(
            R.string.photo_editor_filter_warm, ColorMatrix(
                floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        ImageFilter(
            R.string.photo_editor_filter_polaroid, ColorMatrix(
                floatArrayOf(
                    1.438f, -0.062f, -0.062f, 0f, 0f,
                    -0.122f, 1.378f, -0.122f, 0f, 0f,
                    -0.016f, -0.016f, 1.483f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        ImageFilter(
            R.string.photo_editor_filter_invert, ColorMatrix(
                floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
                )
            )
        )
    )
}

suspend fun saveImage(
    context: Context,
    originalPath: String,
    paths: List<DrawnPath>,
    textElements: List<TextElement>,
    filter: ImageFilter?,
    canvasSize: IntSize,
    cropRect: Rect,
    transformPivot: Offset = cropRect.center,
    imageRotation: Float = 0f,
    imageScale: Float = 1f,
    imageOffset: Offset = Offset.Zero
): String? = withContext(Dispatchers.IO) {
    try {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 || cropRect.width <= 0f || cropRect.height <= 0f) {
            return@withContext null
        }

        val options = BitmapFactory.Options().apply { inMutable = true }
        var bitmap = BitmapFactory.decodeFile(originalPath, options) ?: return@withContext null

        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()
            bitmap = copy
        }

        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()
        val screenW = canvasSize.width.toFloat()
        val screenH = canvasSize.height.toFloat()

        val bitmapRatio = bitmapW / bitmapH
        val screenRatio = screenW / screenH

        val baseScale: Float
        val dx: Float
        val dy: Float

        if (bitmapRatio > screenRatio) {
            baseScale = screenW / bitmapW
            dx = 0f
            dy = (screenH - (bitmapH * baseScale)) / 2f
        } else {
            baseScale = screenH / bitmapH
            dy = 0f
            dx = (screenW - (bitmapW * baseScale)) / 2f
        }

        val exportScale = if (baseScale > 0f) 1f / baseScale else 1f
        val resultBitmap = createBitmap(
            (cropRect.width * exportScale).toInt().coerceAtLeast(1),
            (cropRect.height * exportScale).toInt().coerceAtLeast(1)
        )
        val canvas = AndroidCanvas(resultBitmap)
        val transformPivotX = transformPivot.x * exportScale
        val transformPivotY = transformPivot.y * exportScale
        val scaledCropLeft = cropRect.left * exportScale
        val scaledCropTop = cropRect.top * exportScale
        val scaledImageOffset = Offset(imageOffset.x * exportScale, imageOffset.y * exportScale)

        canvas.translate(-scaledCropLeft, -scaledCropTop)

        canvas.withTranslation(scaledImageOffset.x + transformPivotX, scaledImageOffset.y + transformPivotY) {
            rotate(imageRotation)
            scale(imageScale, imageScale)
            translate(-transformPivotX, -transformPivotY)

            val imagePaint = AndroidPaint().apply {
                isAntiAlias = true
                if (filter != null) {
                    colorFilter = android.graphics.ColorMatrixColorFilter(filter.colorMatrix.values)
                }
            }
            val destRect = RectF(
                dx * exportScale,
                dy * exportScale,
                dx * exportScale + bitmapW,
                dy * exportScale + bitmapH
            )
            drawBitmap(bitmap, null, destRect, imagePaint)

            val pathPaint = AndroidPaint().apply {
                isAntiAlias = true
                style = AndroidPaint.Style.STROKE
                strokeCap = AndroidPaint.Cap.ROUND
                strokeJoin = AndroidPaint.Join.ROUND
            }

            paths.forEach { pathData ->
                if (pathData.isEraser) {
                    pathPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    pathPaint.xfermode = null
                    pathPaint.color = pathData.color.toArgb()
                    pathPaint.alpha = (pathData.alpha * 255).toInt()
                }
                pathPaint.strokeWidth = pathData.strokeWidth * exportScale
                val scaledPath = android.graphics.Path(pathData.path.asAndroidPath())
                scaledPath.transform(
                    android.graphics.Matrix().apply { setScale(exportScale, exportScale) }
                )
                drawPath(scaledPath, pathPaint)
            }

            val textPaint = AndroidPaint().apply {
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }

            textElements.forEach { element ->
                textPaint.color = element.color.toArgb()
                textPaint.textSize = 64f * element.scale * exportScale

                withTranslation(element.offset.x * exportScale, element.offset.y * exportScale) {
                    rotate(Math.toDegrees(element.rotation.toDouble()).toFloat())

                    val textWidth = textPaint.measureText(element.text)
                    val fontMetrics = textPaint.fontMetrics
                    val textHeight = fontMetrics.descent - fontMetrics.ascent

                    drawText(element.text, -textWidth / 2f, textHeight / 4f, textPaint)
                }
            }

        }

        val file = File(context.cacheDir, "edited_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        bitmap.recycle()
        resultBitmap.recycle()

        return@withContext file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}
