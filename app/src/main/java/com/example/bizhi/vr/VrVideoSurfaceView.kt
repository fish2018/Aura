package com.example.bizhi.vr

import android.content.Context
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.example.bizhi.vr.internal.OrientationListener
import com.example.bizhi.vr.internal.SceneRenderer
import com.example.bizhi.vr.internal.TouchTracker
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.util.GlProgram
import com.google.android.exoplayer2.util.GlUtil
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import com.google.android.exoplayer2.video.spherical.CameraMotionListener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin

/**
 * TextureView-based VR renderer that mirrors ExoPlayer's [SphericalGLSurfaceView] but works
 * inside [Presentation] / [VirtualDisplay] contexts.
 */
@RequiresApi(30)
class VrVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    enum class RenderMode {
        SPHERICAL,
        FLAT
    }

    interface VideoSurfaceListener {
        fun onVideoSurfaceCreated(surface: Surface)
        fun onVideoSurfaceDestroyed(surface: Surface)
    }

    val videoFrameMetadataListener: VideoFrameMetadataListener
        get() = sceneRenderer

    val cameraMotionListener: CameraMotionListener
        get() = sceneRenderer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sceneRenderer = SceneRenderer()
    private val renderer = Renderer()
    private val videoSurfaceListeners = CopyOnWriteArrayList<VideoSurfaceListener>()

    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)
    private val orientationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val display: Display? =
        context.getSystemService(WindowManager::class.java)?.defaultDisplay
    private val touchTracker = TouchTracker(context, renderer, PX_PER_DEGREES, UPRIGHT_ROLL)
    private val orientationListener: OrientationListener? =
        display?.let { OrientationListener(it, touchTracker, renderer) }

    private var useSensorRotation = true
    private var isStarted = false
    private var isOrientationRegistered = false

    private var eglThread: HandlerThread? = null
    private var eglHandler: Handler? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    @Volatile private var isRendering = false

    private var sceneSurface: Surface? = null
    private var sceneSurfaceTexture: SurfaceTexture? = null
    private var defaultStereoMode: Int = C.STEREO_MODE_MONO
    private var renderMode: RenderMode = RenderMode.SPHERICAL

    init {
        isClickable = true
        surfaceTextureListener = this
        setOnTouchListener(touchTracker)
        Log.d(TAG, "init isAvailable=$isAvailable")
        if (isAvailable) {
            surfaceTexture?.let { onSurfaceTextureAvailable(it, width, height) }
        }
    }

    fun addVideoSurfaceListener(listener: VideoSurfaceListener) {
        videoSurfaceListeners.add(listener)
        sceneSurface?.let { listener.onVideoSurfaceCreated(it) }
    }

    fun removeVideoSurfaceListener(listener: VideoSurfaceListener) {
        videoSurfaceListeners.remove(listener)
    }

    fun setUseSensorRotation(enabled: Boolean) {
        Log.d(TAG, "setUseSensorRotation enabled=$enabled")
        useSensorRotation = enabled
        renderer.setSensorEnabled(enabled)
        updateOrientationListenerRegistration()
    }

    fun setDefaultStereoMode(mode: @C.StereoMode Int) {
        defaultStereoMode = mode
        sceneRenderer.setDefaultStereoMode(mode)
    }

    fun setRenderMode(mode: RenderMode) {
        if (renderMode == mode) return
        renderMode = mode
        Log.d(TAG, "setRenderMode mode=$mode")
    }

    fun onResume() {
        isStarted = true
        renderer.onResume()
        Log.d(TAG, "onResume -> isStarted=$isStarted")
        ensureRendererReady("onResume")
        updateOrientationListenerRegistration()
    }

    fun onPause() {
        isStarted = false
        renderer.onPause()
        Log.d(TAG, "onPause -> isStarted=$isStarted")
        updateOrientationListenerRegistration()
    }

    override fun onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow")
        super.onDetachedFromWindow()
        stopRenderThread()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable width=$width height=$height")
        surfaceWidth = width
        surfaceHeight = height
        startRenderThread(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged width=$width height=$height")
        surfaceWidth = width
        surfaceHeight = height
        eglHandler?.post { renderer.onSurfaceChanged(width, height) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed")
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // No-op.
    }

    fun ensureRendererReady(reason: String) {
        val texture = surfaceTexture
        val canStart = texture != null && isAvailable && !isRendering && sceneSurface == null
        Log.d(
            TAG,
            "ensureRendererReady reason=$reason canStart=$canStart isAvailable=$isAvailable " +
                "isRendering=$isRendering sceneSurface=${sceneSurface != null} size=${width}x${height}"
        )
        if (canStart) {
            val readyTexture = texture ?: return
            onSurfaceTextureAvailable(
                readyTexture,
                width.coerceAtLeast(1),
                height.coerceAtLeast(1)
            )
        }
    }

    @MainThread
    private fun updateOrientationListenerRegistration() {
        val shouldEnable =
            useSensorRotation && isStarted && orientationSensor != null && orientationListener != null
        Log.d(
            TAG,
            "updateOrientationListenerRegistration shouldEnable=$shouldEnable started=$isStarted " +
                "sensor=${orientationSensor != null} listener=${orientationListener != null}"
        )
        if (shouldEnable == isOrientationRegistered) {
            return
        }
        if (shouldEnable) {
            sensorManager?.registerListener(
                orientationListener,
                orientationSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            Log.d(TAG, "Orientation listener registered")
        } else if (isOrientationRegistered) {
            sensorManager?.unregisterListener(orientationListener)
            Log.d(TAG, "Orientation listener unregistered")
        }
        isOrientationRegistered = shouldEnable
    }

    private fun startRenderThread(target: SurfaceTexture) {
        Log.d(TAG, "startRenderThread target=$target")
        stopRenderThread()
        val thread = HandlerThread("VrRenderer")
        thread.start()
        eglThread = thread
        val handler = Handler(thread.looper)
        eglHandler = handler
        isRendering = true
        handler.post {
            if (!initializeEgl(target)) {
                isRendering = false
                return@post
            }
            renderer.onContextCreated()
            renderer.onSurfaceChanged(surfaceWidth, surfaceHeight)
            Log.d(TAG, "EGL initialized, starting render loop")
            renderLoop()
            renderer.onContextReleased()
            releaseSceneSurface()
            releaseEgl()
            Log.d(TAG, "Render thread finished")
        }
    }

    private fun stopRenderThread() {
        isRendering = false
        val thread = eglThread ?: return
        Log.d(TAG, "stopRenderThread thread=${thread.name}")
        thread.quitSafely()
        try {
            thread.join()
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        eglThread = null
        eglHandler = null
    }

    private fun initializeEgl(target: SurfaceTexture): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "Unable to get EGL14 display")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "Unable to initialize EGL14")
            return false
        }
        val configAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e(TAG, "Unable to choose EGL config")
            EGL14.eglTerminate(display)
            return false
        }
        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttrs,
            0
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "Failed to create EGL context")
            EGL14.eglTerminate(display)
            return false
        }
        val surface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            target,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "Failed to create EGL window surface")
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.e(TAG, "Failed to make EGL context current")
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return false
        }
        Log.d(TAG, "EGL context ready version=${version[0]}.${version[1]}")
        eglDisplay = display
        eglContext = context
        eglSurface = surface
        return true
    }

    private fun releaseEgl() {
        val display = eglDisplay ?: return
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        eglSurface?.let { EGL14.eglDestroySurface(display, it) }
        eglContext?.let { EGL14.eglDestroyContext(display, it) }
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    private fun renderLoop() {
        while (isRendering && eglDisplay != null && eglSurface != null && eglContext != null) {
            renderer.drawFrame()
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                break
            }
            try {
                Thread.sleep(RENDER_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Log.d(TAG, "renderLoop finished isRendering=$isRendering")
    }

    private fun handleSceneSurfaceCreated(texture: SurfaceTexture) {
        Log.d(TAG, "handleSceneSurfaceCreated texture=$texture")
        sceneSurfaceTexture = texture
        mainHandler.post {
            val oldSurface = sceneSurface
            if (oldSurface != null) {
                videoSurfaceListeners.forEach { it.onVideoSurfaceDestroyed(oldSurface) }
                oldSurface.release()
            }
            val newSurface = Surface(texture)
            sceneSurface = newSurface
            Log.d(TAG, "dispatch onVideoSurfaceCreated listeners=${videoSurfaceListeners.size}")
            videoSurfaceListeners.forEach { it.onVideoSurfaceCreated(newSurface) }
        }
    }

    private fun releaseSceneSurface() {
        val texture = sceneSurfaceTexture
        sceneSurfaceTexture = null
        mainHandler.post {
            val surface = sceneSurface
            if (surface != null) {
                Log.d(TAG, "dispatch onVideoSurfaceDestroyed")
                videoSurfaceListeners.forEach { it.onVideoSurfaceDestroyed(surface) }
                surface.release()
            }
            sceneSurface = null
        }
        texture?.release()
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private inner class Renderer : TouchTracker.Listener, OrientationListener.Listener {
        private val projectionMatrix = FloatArray(16)
        private val viewProjectionMatrix = FloatArray(16)
        private val deviceOrientationMatrix = FloatArray(16)
        private val touchPitchMatrix = FloatArray(16)
        private val touchYawMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val tempMatrix = FloatArray(16)
        private var touchPitch = 0f
        private var deviceRoll = UPRIGHT_ROLL
        private var sensorEnabled = true
        private var orientationLogged = false
        private val flatRenderer = FlatRenderer()

        init {
            Matrix.setIdentityM(projectionMatrix, 0)
            GlUtil.setToIdentity(deviceOrientationMatrix)
            GlUtil.setToIdentity(touchPitchMatrix)
            GlUtil.setToIdentity(touchYawMatrix)
        }

        fun onContextCreated() {
            sceneRenderer.setDefaultStereoMode(defaultStereoMode)
            handleSceneSurfaceCreated(sceneRenderer.init())
            flatRenderer.init()
            Log.d(TAG, "Renderer onContextCreated defaultStereoMode=$defaultStereoMode")
        }

        fun onContextReleased() {
            sceneRenderer.shutdown()
            flatRenderer.release()
            Log.d(TAG, "Renderer onContextReleased")
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            GLES20.glViewport(0, 0, width, height)
            val aspect = width.toFloat() / height.toFloat()
            val fovY = calculateFieldOfViewInYDirection(aspect)
            Matrix.perspectiveM(projectionMatrix, 0, fovY, aspect, Z_NEAR, Z_FAR)
        }

        fun drawFrame() {
            synchronized(this) {
                Matrix.multiplyMM(tempMatrix, 0, deviceOrientationMatrix, 0, touchYawMatrix, 0)
                Matrix.multiplyMM(viewMatrix, 0, touchPitchMatrix, 0, tempMatrix, 0)
            }
            if (renderMode == RenderMode.SPHERICAL) {
                Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
                sceneRenderer.drawFrame(viewProjectionMatrix, false)
            } else {
                sceneRenderer.updateFrameIfAvailable()
                flatRenderer.draw(
                    sceneRenderer.getTextureId(),
                    width.toFloat().coerceAtLeast(1f),
                    height.toFloat().coerceAtLeast(1f),
                    sceneRenderer.getVideoAspectRatio()
                )
            }
        }

        fun setSensorEnabled(enabled: Boolean) {
            sensorEnabled = enabled
        }

        fun onResume() {
            orientationLogged = false
        }

        fun onPause() {
            // No-op currently; hook preserved for parity.
        }

        @AnyThread
        override fun onOrientationChange(deviceOrientationMatrix: FloatArray, deviceRoll: Float) {
            if (!sensorEnabled) {
                return
            }
            synchronized(this) {
                System.arraycopy(
                    deviceOrientationMatrix,
                    0,
                    this.deviceOrientationMatrix,
                    0,
                    this.deviceOrientationMatrix.size
                )
                this.deviceRoll = -deviceRoll
                updatePitchMatrix()
            }
            if (!orientationLogged) {
                Log.d(TAG, "Orientation stream active roll=$deviceRoll")
                orientationLogged = true
            }
        }

        override fun onScrollChange(scrollOffsetDegrees: PointF) {
            synchronized(this) {
                touchPitch = scrollOffsetDegrees.y
                updatePitchMatrix()
                Matrix.setRotateM(touchYawMatrix, 0, -scrollOffsetDegrees.x, 0f, 1f, 0f)
            }
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            return performClick()
        }

        private fun updatePitchMatrix() {
            Matrix.setRotateM(
                touchPitchMatrix,
                0,
                -touchPitch,
                cos(deviceRoll.toDouble()).toFloat(),
                sin(deviceRoll.toDouble()).toFloat(),
                0f
            )
        }

        private fun calculateFieldOfViewInYDirection(aspect: Float): Float {
            val landscapeMode = aspect > 1f
            return if (landscapeMode) {
                val halfFovX = FIELD_OF_VIEW_DEGREES / 2f
                val tanY = kotlin.math.tan(Math.toRadians(halfFovX.toDouble())) / aspect
                val halfFovY = Math.toDegrees(kotlin.math.atan(tanY))
                (halfFovY * 2f).toFloat()
            } else {
                FIELD_OF_VIEW_DEGREES
            }
        }
    }

    companion object {
        private const val TAG = "VrVideoSurface"
        private const val FIELD_OF_VIEW_DEGREES = 90f
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
        private const val PX_PER_DEGREES = 25f
        private const val RENDER_INTERVAL_MS = 16L
        const val UPRIGHT_ROLL = Math.PI.toFloat()
    }

    private class FlatRenderer {
        private var program: GlProgram? = null
        private var mvpMatrixHandle: Int = 0
        private var positionHandle: Int = 0
        private var texCoordsHandle: Int = 0
        private var textureHandle: Int = 0
        private val mvpMatrix = FloatArray(16)
        private val vertexBuffer = GlUtil.createBuffer(VERTICES)
        private val texBuffer = GlUtil.createBuffer(TEX_COORDS)

        fun init() {
            if (program != null) return
            try {
                program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).also { program ->
                    mvpMatrixHandle = program.getUniformLocation("uMvpMatrix")
                    positionHandle = program.getAttributeArrayLocationAndEnable("aPosition")
                    texCoordsHandle = program.getAttributeArrayLocationAndEnable("aTexCoords")
                    textureHandle = program.getUniformLocation("uTexture")
                }
            } catch (_: GlUtil.GlException) {
                program = null
            }
        }

        fun release() {
            try {
                program?.delete()
            } catch (_: GlUtil.GlException) {
            }
            program = null
        }

        fun draw(textureId: Int, viewWidth: Float, viewHeight: Float, videoAspect: Float) {
            val program = program ?: return
            try {
                program.use()
            } catch (_: GlUtil.GlException) {
                return
            }
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            Matrix.setIdentityM(mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
            )
            GLES20.glVertexAttribPointer(
                texCoordsHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                texBuffer
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        companion object {
            private const val VERTEX_SHADER =
                "uniform mat4 uMvpMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoords;\n" +
                    "varying vec2 vTexCoords;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMvpMatrix * aPosition;\n" +
                    "  vTexCoords = aTexCoords;\n" +
                    "}\n"
            private const val FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoords;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoords);\n" +
                    "}\n"
            private val VERTICES = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
            private val TEX_COORDS = floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f
            )
        }
    }
}
