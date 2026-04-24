package org.monogram.presentation.features.chats.conversation.ui.message

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object SpoilerShaderApi33 {
    fun createShader(shaderCode: String): Any = RuntimeShader(shaderCode)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun DrawScope.drawSpoilerEffectApi33(
    layoutResult: TextLayoutResult,
    start: Int,
    end: Int,
    shader: Any,
    time: Float,
    color: Color
) {
    val runtimeShader = shader as RuntimeShader
    val path = layoutResult.getPathForRange(start, end)
    runtimeShader.setFloatUniform("resolution", size.width, size.height)
    runtimeShader.setFloatUniform("time", time)
    runtimeShader.setColorUniform("particleColor", color.toArgb())

    drawPath(
        path = path,
        brush = ShaderBrush(runtimeShader)
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AnimatedSpoilerEffectApi33(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray
) {
    val time by produceState(0f) {
        while (true) {
            withInfiniteAnimationFrameMillis {
                value = it / 1000f
            }
        }
    }

    val shader = remember { RuntimeShader(SpoilerShader.SHADER_CODE) }

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("time", time)
        shader.setColorUniform("particleColor", color.toArgb())

        drawRect(brush = ShaderBrush(shader))
    }
}
