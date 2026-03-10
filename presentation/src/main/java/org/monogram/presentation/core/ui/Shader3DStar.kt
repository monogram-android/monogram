package org.monogram.presentation.core.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Base64
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.collections.iterator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun TelegramStarInteractive(
    resId: Int,
    starColor: Color = Color(0xFFFFD700),
    alpha: Float = 1f
) {
    val context = LocalContext.current
    val geometry = remember(resId) { parseStarFromRaw(context, resId) }

    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    var isInteracting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val starColorFloats = remember(starColor) {
        floatArrayOf(starColor.red, starColor.green, starColor.blue)
    }

    val renderThreadRef = remember { mutableStateOf<StarRenderThread?>(null) }

    LaunchedEffect(isInteracting) {
        if (!isInteracting) {
            launch {
                rotX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                )
            }
            launch {
                rotY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        stiffness = Spring.StiffnessLow,
                        dampingRatio = Spring.DampingRatioMediumBouncy
                    )
                )
            }
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isInteracting = true },
                    onDragEnd = { isInteracting = false },
                    onDragCancel = { isInteracting = false }
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        rotX.snapTo(rotX.value + dragAmount.x * 0.4f)
                        rotY.snapTo(rotY.value + dragAmount.y * 0.4f)
                    }
                }
            },
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val thread = StarRenderThread(st, geometry).apply {
                            updateSize(w, h)
                            updateParams(rotX.value, rotY.value, starColorFloats, alpha)
                            start()
                        }
                        renderThreadRef.value = thread
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        renderThreadRef.value?.updateSize(w, h)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        renderThreadRef.value?.stopRendering()
                        renderThreadRef.value = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        update = {
            renderThreadRef.value?.updateParams(rotX.value, rotY.value, starColorFloats, alpha)
        }
    )
}

data class StarParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var rotSpeed: Float = 0f,
    var life: Float = 1f, // 1.0 = full, 0.0 = dead
    var active: Boolean = false
)

class StarRenderThread(
    private val surface: SurfaceTexture,
    private val geometry: GltfGeometry
) : Thread() {
    @Volatile private var running = true
    @Volatile private var width = 1
    @Volatile private var height = 1
    @Volatile private var rotX = 0f
    @Volatile private var rotY = 0f
    @Volatile private var starColor = floatArrayOf(1f, 1f, 1f)
    @Volatile private var alpha = 1f

    private val particles = Array(15) { StarParticle() }
    private val random = Random()
    private var lastFrameTime = 0L

    fun updateParams(rx: Float, ry: Float, sc: FloatArray, a: Float) {
        rotX = rx; rotY = ry; starColor = sc; alpha = a
    }

    fun updateSize(w: Int, h: Int) {
        width = if (w > 0) w else 1
        height = if (h > 0) h else 1
    }

    fun stopRendering() { running = false }

    override fun run() {
        val egl = EGLContext.getEGL() as EGL10
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        egl.eglInitialize(display, null)

        val configSpec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = intArrayOf(0)
        egl.eglChooseConfig(display, configSpec, configs, 1, numConfig)
        val config = configs[0] ?: return

        val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL10.EGL_NONE))
        val eglSurface = egl.eglCreateWindowSurface(display, config, surface, null)
        egl.eglMakeCurrent(display, eglSurface, eglSurface, context)

        val vShaderCode = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            attribute vec4 vPosition;
            attribute vec3 vNormal;
            varying vec3 vFragNormal;
            varying vec3 vFragPos;
            void main() {
                gl_Position = uMVPMatrix * vPosition;
                vFragPos = vec3(uModelMatrix * vPosition);
                vFragNormal = normalize(vec3(uModelMatrix * vec4(vNormal, 0.0)));
            }
        """.trimIndent()

        val fShaderCode = """
            precision mediump float;
            uniform vec3 uBaseColor;
            uniform float uAlpha;
            varying vec3 vFragNormal;
            varying vec3 vFragPos;
            
            void main() {
                vec3 lightPos = vec3(20.0, 50.0, 100.0);
                vec3 viewPos = vec3(0.0, 0.0, 110.0);
                
                vec3 norm = normalize(vFragNormal);
                vec3 lightDir = normalize(lightPos - vFragPos);
                vec3 viewDir = normalize(viewPos - vFragPos);
                vec3 halfDir = normalize(lightDir + viewDir);
                
                float NdotL = dot(norm, lightDir);
                float diff = NdotL * 0.6 + 0.4; 
                
                float spec = pow(max(dot(norm, halfDir), 0.0), 16.0);
                
                vec3 ambient = uBaseColor * 0.5; 
                vec3 diffuse = uBaseColor * diff * 0.8;
                vec3 specular = vec3(1.0, 0.98, 0.9) * spec * 0.8; 
                
                gl_FragColor = vec4(ambient + diffuse + specular, uAlpha);
            }
        """.trimIndent()

        val program = GLES20.glCreateProgram().apply {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { GLES20.glShaderSource(it, vShaderCode); GLES20.glCompileShader(it) }
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { GLES20.glShaderSource(it, fShaderCode); GLES20.glCompileShader(it) }
            GLES20.glAttachShader(this, vs); GLES20.glAttachShader(this, fs); GLES20.glLinkProgram(this)
        }

        val vertexBuffer = ByteBuffer.allocateDirect(geometry.vertices.size * 4).run { order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(geometry.vertices).position(0) } }
        val normalBuffer = ByteBuffer.allocateDirect(geometry.normals.size * 4).run { order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(geometry.normals).position(0) } }
        val indexBuffer = ByteBuffer.allocateDirect(geometry.indices.size * 2).run { order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(geometry.indices).position(0) } }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)

        GLES20.glUseProgram(program)
        val uMVPLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val uModelLoc = GLES20.glGetUniformLocation(program, "uModelMatrix")
        val uColorLoc = GLES20.glGetUniformLocation(program, "uBaseColor")
        val uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        val posHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val normalHandle = GLES20.glGetAttribLocation(program, "vNormal")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)

        lastFrameTime = SystemClock.uptimeMillis()

        while (running) {
            val currentTime = SystemClock.uptimeMillis()
            val dt = (currentTime - lastFrameTime) / 1000f
            lastFrameTime = currentTime

            updateParticles(dt)

            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            val curW = width
            val curH = height
            GLES20.glViewport(0, 0, curW, curH)

            val ratio = curW.toFloat() / curH
            Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 1000f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 110f, 0f, 0f, 0f, 0f, 1.0f, 0f)

            for (p in particles) {
                if (p.active) {
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, p.x, p.y, p.z)
                    Matrix.scaleM(modelMatrix, 0, p.scale, p.scale, p.scale)
                    Matrix.rotateM(modelMatrix, 0, p.rotation, 0f, 0f, 1f)

                    Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

                    GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
                    GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)

                    GLES20.glUniform3fv(uColorLoc, 1, starColor, 0)
                    GLES20.glUniform1f(uAlphaLoc, p.life)

                    GLES20.glDrawElements(
                        GLES20.GL_TRIANGLES,
                        geometry.indices.size,
                        GLES20.GL_UNSIGNED_SHORT,
                        indexBuffer
                    )
                }
            }

            Matrix.setRotateM(modelMatrix, 0, rotX, 0f, 1f, 0f)
            Matrix.rotateM(modelMatrix, 0, rotY, 1f, 0f, 0f)

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uModelLoc, 1, false, modelMatrix, 0)
            GLES20.glUniform3fv(uColorLoc, 1, starColor, 0)
            GLES20.glUniform1f(uAlphaLoc, alpha)

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, geometry.indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

            egl.eglSwapBuffers(display, eglSurface)
            try { sleep(16) } catch (e: Exception) {}
        }

        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        egl.eglDestroySurface(display, eglSurface)
        egl.eglDestroyContext(display, context)
        egl.eglTerminate(display)
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            if (p.active) {
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.z += p.vz * dt
                p.rotation += p.rotSpeed * dt
                p.life -= dt * 0.8f // Fade speed

                if (p.life <= 0f) {
                    p.active = false
                }
            }
        }

        // 15% chance per frame (at 60fps = ~15 stars per second)
        if (random.nextFloat() < 0.15f) {
            spawnParticle()
        }
    }

    private fun spawnParticle() {
        val p = particles.firstOrNull { !it.active } ?: return

        p.active = true
        p.life = 1f
        p.scale = 0.2f + random.nextFloat() * 0.15f

        p.x = (random.nextFloat() - 0.5f) * 5f
        p.y = (random.nextFloat() - 0.5f) * 5f
        p.z = -5f

        val angle = random.nextFloat() * Math.PI * 2.0
        val speed = 25f + random.nextFloat() * 15f

        p.vx = (cos(angle) * speed).toFloat()
        p.vy = (sin(angle) * speed).toFloat()
        p.vz = 0f

        p.rotation = random.nextFloat() * 360f
        p.rotSpeed = (random.nextFloat() - 0.5f) * 200f
    }
}

fun parseStarFromRaw(context: Context, resId: Int): GltfGeometry {
    val gltfString = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
    val json = JSONObject(gltfString)
    val bufferUri = json.getJSONArray("buffers").getJSONObject(0).getString("uri")
    val bytes = Base64.decode(bufferUri.substringAfter("base64,"), Base64.DEFAULT)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val indexCount = json.getJSONArray("accessors").getJSONObject(0).getInt("count")
    val indexArray = ShortArray(indexCount)
    buffer.position(0)
    for (i in 0 until indexCount) indexArray[i] = buffer.short
    val posCount = json.getJSONArray("accessors").getJSONObject(1).getInt("count")
    val posOffset = 8680
    val stride = 32
    val vertexArray = FloatArray(posCount * 3)
    val normalArray = FloatArray(posCount * 3)

    for (i in 0 until posCount) {
        buffer.position(posOffset + (i * stride))
        vertexArray[i * 3] = buffer.float
        vertexArray[i * 3 + 1] = buffer.float
        vertexArray[i * 3 + 2] = buffer.float
        normalArray[i * 3] = buffer.float
        normalArray[i * 3 + 1] = buffer.float
        normalArray[i * 3 + 2] = buffer.float
    }
    smoothNormals(vertexArray, normalArray, posCount)
    return GltfGeometry(vertexArray, normalArray, indexArray)
}

fun smoothNormals(vertices: FloatArray, normals: FloatArray, count: Int) {
    val posMap = HashMap<String, MutableList<Int>>()
    for (i in 0 until count) {
        val key = "${vertices[i * 3]},${vertices[i * 3 + 1]},${vertices[i * 3 + 2]}"
        if (!posMap.containsKey(key)) posMap[key] = ArrayList()
        posMap[key]?.add(i)
    }
    for ((_, indices) in posMap) {
        var avgX = 0f
        var avgY = 0f
        var avgZ = 0f
        for (idx in indices) {
            avgX += normals[idx * 3]; avgY += normals[idx * 3 + 1]; avgZ += normals[idx * 3 + 2]
        }
        val length = sqrt((avgX * avgX + avgY * avgY + avgZ * avgZ).toDouble()).toFloat()
        if (length > 0.00001f) {
            avgX /= length; avgY /= length; avgZ /= length
        }
        for (idx in indices) {
            normals[idx * 3] = avgX; normals[idx * 3 + 1] = avgY; normals[idx * 3 + 2] = avgZ
        }
    }
}

data class GltfGeometry(val vertices: FloatArray, val normals: FloatArray, val indices: ShortArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GltfGeometry

        if (!vertices.contentEquals(other.vertices)) return false
        if (!normals.contentEquals(other.normals)) return false
        if (!indices.contentEquals(other.indices)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + normals.contentHashCode()
        result = 31 * result + indices.contentHashCode()
        return result
    }
}