package org.monogram.presentation.di.coil

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import org.monogram.presentation.features.stickers.core.RLottieWrapper

class LottieDecoder(
    private val source: ImageSource
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val file = java.io.File(source.file().toString())
        val decoder = RLottieWrapper()
        try {
            if (!decoder.open(file)) {
                throw RuntimeException("Failed to load rlottie animation")
            }

            val width = decoder.getWidth().coerceAtLeast(1)
            val height = decoder.getHeight().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0)

            val rendered = decoder.renderFrame(
                bitmap = bitmap,
                frameNo = 0,
                drawLeft = 0,
                drawTop = 0,
                drawWidth = width,
                drawHeight = height
            )
            if (!rendered) {
                throw RuntimeException("Failed to render rlottie frame")
            }

            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = false
            )
        } finally {
            decoder.release()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val isLottie = result.mimeType == "application/tgs" ||
                    result.mimeType == "application/json" ||
                    result.source.file().name.endsWith(".tgs", ignoreCase = true) ||
                    result.source.file().name.endsWith(".json", ignoreCase = true)

            if (isLottie) {
                return LottieDecoder(result.source)
            }

            return null
        }
    }
}
