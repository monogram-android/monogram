package org.monogram.presentation.core.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.Canvas as ComposeCanvas
import org.monogram.presentation.R

@Composable
fun StyledQRCode(content: String, modifier: Modifier, primaryColor: Color, backgroundColor: Color) {
    val qrMatrix = remember(content) { generateQrMatrix(content) }
    ComposeCanvas(modifier = modifier) {
        val matrix = qrMatrix ?: return@ComposeCanvas
        val matrixSize = matrix.width
        val cellSize = size.width / matrixSize

        for (x in 0 until matrixSize) {
            for (y in 0 until matrixSize) {
                if (matrix.get(x, y) && !isPositionMarker(x, y, matrixSize)) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(x * cellSize + (cellSize * 0.05f), y * cellSize + (cellSize * 0.05f)),
                        size = Size(cellSize * 0.9f, cellSize * 0.9f),
                        cornerRadius = CornerRadius(cellSize * 0.35f)
                    )
                }
            }
        }
        val eyePositions = listOf(Offset(0f, 0f), Offset((matrixSize - 7).toFloat(), 0f), Offset(0f, (matrixSize - 7).toFloat()))
        eyePositions.forEach { pos ->
            drawCustomEye(Offset(pos.x * cellSize, pos.y * cellSize), cellSize, primaryColor, backgroundColor)
        }
    }
}
private val QrBackgroundColor = Color(0xFFEFF1E6)
private val QrDarkGreen = Color(0xFF3E4D36)
private val QrSurfaceShapeColor = Color(0xFFE3E6D8)
fun DrawScope.drawCustomEye(offset: Offset, cellSize: Float, primaryColor: Color, backgroundColor: Color) {
    drawRoundRect(primaryColor, offset, Size(7 * cellSize, 7 * cellSize), CornerRadius(2 * cellSize))
    drawRoundRect(backgroundColor, Offset(offset.x + cellSize, offset.y + cellSize), Size(5 * cellSize, 5 * cellSize), CornerRadius(1.5f * cellSize))
    drawRoundRect(primaryColor, Offset(offset.x + 2 * cellSize, offset.y + 2 * cellSize), Size(3 * cellSize, 3 * cellSize), CornerRadius(0.8f * cellSize))
}

fun isPositionMarker(x: Int, y: Int, matrixSize: Int) =
    (x < 7 && y < 7) || (x >= matrixSize - 7 && y < 7) || (x < 7 && y >= matrixSize - 7)

fun generateQrMatrix(content: String): BitMatrix? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H)
    QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 25, 25, hints)
} catch (e: Exception) { null }

fun generatePureBitmap(content: String, size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = QrSurfaceShapeColor.toArgb()
    canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.15f, size * 0.15f, paint)

    val matrix = generateQrMatrix(content) ?: return bitmap
    val matrixSize = matrix.width
    val cellSize = size.toFloat() / (matrixSize + 4) * 0.8f
    val xOffset = (size - (matrixSize * cellSize)) / 2f
    val yOffset = (size - (matrixSize * cellSize)) / 2.5f

    paint.color = QrDarkGreen.toArgb()
    for (x in 0 until matrixSize) {
        for (y in 0 until matrixSize) {
            if (matrix.get(x, y) && !isPositionMarker(x, y, matrixSize)) {
                val left = xOffset + x * cellSize + (cellSize * 0.05f)
                val top = yOffset + y * cellSize + (cellSize * 0.05f)
                canvas.drawRoundRect(RectF(left, top, left + cellSize * 0.9f, top + cellSize * 0.9f), cellSize * 0.35f, cellSize * 0.35f, paint)
            }
        }
    }

    val eyePositions = listOf(Pair(0, 0), Pair(matrixSize - 7, 0), Pair(0, matrixSize - 7))
    eyePositions.forEach { (ex, ey) ->
        val exPos = xOffset + ex * cellSize
        val eyPos = yOffset + ey * cellSize
        paint.color = QrDarkGreen.toArgb()
        canvas.drawRoundRect(RectF(exPos, eyPos, exPos + 7 * cellSize, eyPos + 7 * cellSize), 2 * cellSize, 2 * cellSize, paint)
        paint.color = QrSurfaceShapeColor.toArgb()
        canvas.drawRoundRect(RectF(exPos + cellSize, eyPos + cellSize, exPos + 6 * cellSize, eyPos + 6 * cellSize), 1.5f * cellSize, 1.5f * cellSize, paint)
        paint.color = QrDarkGreen.toArgb()
        canvas.drawRoundRect(RectF(exPos + 2 * cellSize, eyPos + 2 * cellSize, exPos + 5 * cellSize, eyPos + 5 * cellSize), 0.8f * cellSize, 0.8f * cellSize, paint)
    }

    paint.textSize = size * 0.05f
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(content, size / 2f, size * 0.9f, paint)
    return bitmap
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val filename = "QR_${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/QR")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        Toast.makeText(context, context.getString(R.string.qr_saved_to_gallery), Toast.LENGTH_SHORT).show()
    }
}

fun shareBitmap(context: Context, bitmap: Bitmap) {
    try {
        val file = File(context.cacheDir, "qr_share.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.qr_share_title)))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, context.getString(R.string.qr_share_error, e.message), Toast.LENGTH_SHORT).show()
    }
}