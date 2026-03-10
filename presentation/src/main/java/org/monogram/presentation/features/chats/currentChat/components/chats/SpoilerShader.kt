package org.monogram.presentation.features.chats.currentChat.components.chats

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import org.intellij.lang.annotations.Language

object SpoilerShader {
    @Language("AGSL")
    const val SHADER_CODE = """
uniform float2 resolution;
uniform float time;
layout(color) uniform half4 particleColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

float2 getFlow(float2 p, float t) {
    float freq = 0.01;
    float speed = 0.5;
    float n1 = sin(p.y * freq + t * speed);
    float n2 = cos(p.x * freq + t * speed);
    return float2(n1, n2);
}

half4 main(float2 coord) {
    float spacing = 15.0;
    float2 currentGrid = floor(coord / spacing);
    
    float intensity = 0.0;
    
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float2 gridId = currentGrid + float2(x, y);
            
            float seed = hash(gridId);
            
            float t = fract(time * 0.2 + seed);
            float alpha_pulse = sin(t * 3.14159); 
            
            float2 origin = (gridId + 0.5) * spacing;
            float2 randOffset = (float2(hash(gridId + 4.0), hash(gridId + 9.0)) - 0.5) * spacing;
            
            float2 flow = getFlow(origin, time);
            float2 position = origin + randOffset + flow * 40.0 * t; 
            
            float dist = distance(coord, position);
            float r = 1.2 + seed * 1.8;
            
            intensity += smoothstep(r, r - 1.0, dist) * alpha_pulse * (0.3 + 0.7 * seed);
        }
    }

    intensity = clamp(intensity, 0.0, 1.2);
    return half4(particleColor.rgb * intensity, intensity);
}
"""
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun DrawScope.drawSpoilerEffect(
    layoutResult: TextLayoutResult,
    start: Int,
    end: Int,
    shader: RuntimeShader,
    time: Float,
    color: Color
) {
    val path = layoutResult.getPathForRange(start, end)
    shader.setFloatUniform("resolution", size.width, size.height)
    shader.setFloatUniform("time", time)
    shader.setColorUniform("particleColor", color.toArgb())

    drawPath(
        path = path,
        brush = ShaderBrush(shader)
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun AnimatedSpoilerEffect(
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

@Composable
fun SpoilerWrapper(
    isRevealed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        if (!isRevealed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AnimatedSpoilerEffect()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray.copy(alpha = 0.5f))
                )
            }
        }
    }
}
