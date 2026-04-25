package org.monogram.presentation.features.chats.conversation.ui.message

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import org.intellij.lang.annotations.Language
import kotlin.math.sin

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
                AnimatedSpoilerEffectApi33()
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

fun DrawScope.drawSpoilerEffectFallback(
    layoutResult: TextLayoutResult,
    start: Int,
    end: Int,
    time: Float,
    color: Color
) {
    val path = layoutResult.getPathForRange(start, end)
    drawPath(path = path, color = color.copy(alpha = 0.5f))

    val bounds = path.getBounds()
    val spacing = 11f
    val phase = (time * 24f) % spacing

    clipPath(path, clipOp = ClipOp.Intersect) {
        var y = bounds.top - spacing + phase
        while (y <= bounds.bottom + spacing) {
            val rowOffset = if (((y / spacing).toInt() and 1) == 0) 0f else spacing * 0.5f
            var x = bounds.left - spacing + rowOffset

            while (x <= bounds.right + spacing) {
                val pulse = ((sin((x + y) * 0.07f + time * 3.6f) + 1f) * 0.5f)
                drawCircle(
                    color = color.copy(alpha = 0.10f + 0.22f * pulse),
                    radius = 1.0f + 1.2f * pulse,
                    center = Offset(x, y)
                )
                x += spacing
            }

            y += spacing
        }
    }
}
