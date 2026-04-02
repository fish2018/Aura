package com.example.bizhi.wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.bizhi.BuildConfig
import com.example.bizhi.R
import com.example.bizhi.data.ImageDisplayMode
import com.example.bizhi.data.LocalContentType
import com.example.bizhi.data.PlaylistMode
import com.example.bizhi.data.PlaylistOrder
import com.example.bizhi.data.WallpaperPreferences
import com.example.bizhi.media.DoubleBufferedVideoPlayer
import com.example.bizhi.media.LocalVideoPlayer
import com.example.bizhi.util.ImageWallpaperSupport
import com.example.bizhi.util.RemoteVideoSupport
import com.example.bizhi.util.WebInteractionSupport
import com.example.bizhi.vr.VrVideoSurfaceView
import com.google.android.exoplayer2.PlaybackException
import java.util.LinkedHashSet
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.text.Charsets

@RequiresApi(Build.VERSION_CODES.R)
class WebWallpaperService : WallpaperService() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onCreateEngine(): Engine = WebWallpaperEngine()

    private inner class WebWallpaperEngine :
        Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val logTag = "WebWallpaperService"
        private val displayManager = applicationContext.getSystemService(DisplayManager::class.java)
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: WallpaperPresentation? = null
        private var webView: WebView? = null
        private var webViewToken: Long = 0L
        private var contentLoadToken: Long = 0L
        private var localVideoSurface: VrVideoSurfaceView? = null
        private var videoPlayer: LocalVideoPlayer? = null
        private var remoteVideoPlayer: DoubleBufferedVideoPlayer? = null
        private var pendingLoadTask: Runnable? = null
        private var pendingPreferenceReload: Runnable? = null
        private var pendingClearTask: Runnable? = null
        private var wallpaperChangedReceiverRegistered: Boolean = false
        private var imageDisplayMode: ImageDisplayMode = WallpaperPreferences.DEFAULT_IMAGE_DISPLAY_MODE
        private var allowInteraction: Boolean = WallpaperPreferences.DEFAULT_ALLOW_INTERACTION
        private var allowMediaPlayback: Boolean = WallpaperPreferences.DEFAULT_ALLOW_MEDIA
        private var engineVisible: Boolean = false
        private var autoRefreshEnabled: Boolean = WallpaperPreferences.DEFAULT_AUTO_REFRESH
        private var refreshIntervalSeconds: Long =
            WallpaperPreferences.DEFAULT_REFRESH_INTERVAL_SECONDS
        private var pendingPreviewState: WallpaperPreferences.PendingState? = null
        private var currentLocalType: LocalContentType = LocalContentType.NONE
        private var currentLocalPath: String? = null
        private var currentLocalWrapper: File? = null
        private var currentLocalIsVr: Boolean = false
        private var currentRemoteVideoUrl: String? = null
        private var pendingLocalVideoPath: String? = null
        private var playlistRotationEnabled: Boolean = false
        private var playlistOrder: PlaylistOrder = PlaylistOrder.SEQUENTIAL
        private var playlistMode: PlaylistMode = PlaylistMode.INTERVAL
        private var playlistIntervalSeconds: Long =
            WallpaperPreferences.DEFAULT_PLAYLIST_INTERVAL_SECONDS
        private var localBatchEnabled: Boolean = false
        private var localBatchOrder: PlaylistOrder = PlaylistOrder.SEQUENTIAL
        private var localBatchMode: PlaylistMode = PlaylistMode.INTERVAL
        private var localBatchIntervalSeconds: Long =
            WallpaperPreferences.DEFAULT_PLAYLIST_INTERVAL_SECONDS
        private var vrGlobalEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_GLOBAL_ENABLED
        private var vrSensorEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_SENSOR_ENABLED
        private var vrGestureEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_GESTURE_ENABLED
        private var vrSurfaceHandle: Surface? = null
        private val hasRotationSensor: Boolean =
            hasRotationSensor(applicationContext.getSystemService(SensorManager::class.java))
        private val localBatchRotationRunnable = object : Runnable {
            override fun run() {
                if (
                    localBatchEnabled &&
                    localBatchMode == PlaylistMode.INTERVAL &&
                    advanceLocalBatch()
                ) {
                    loadFromPreferences()
                }
                scheduleLocalBatchRotation()
            }
        }
        private val vrSurfaceListener = object : VrVideoSurfaceView.VideoSurfaceListener {
            override fun onVideoSurfaceCreated(surface: Surface) {
                vrSurfaceHandle = surface
                Log.d(logTag, "vrSurfaceListener surfaceCreated")
                if (videoPlayer != null || pendingLocalVideoPath != null) {
                    videoPlayer?.setExternalVideoSurface(surface, localVideoSurface)
                    if (pendingLocalVideoPath != null) {
                        Log.d(logTag, "vrSurfaceListener restarting deferred local video")
                        startLocalVideoPlayback()
                    }
                }
            }

            override fun onVideoSurfaceDestroyed(surface: Surface) {
                if (vrSurfaceHandle == surface) {
                    Log.d(logTag, "vrSurfaceListener surfaceDestroyed")
                    videoPlayer?.clearExternalVideoSurface(surface)
                    vrSurfaceHandle = null
                }
            }
        }
        private val autoRefreshRunnable = object : Runnable {
            override fun run() {
                if (autoRefreshEnabled && !isAutoRefreshBlockedByLocalVideo() && shouldRunContent() && webView != null) {
                    loadFromPreferences()
                }
                scheduleAutoRefresh()
            }
        }
        private val playlistRotationRunnable = object : Runnable {
            override fun run() {
                if (
                    playlistRotationEnabled &&
                    playlistMode == PlaylistMode.INTERVAL &&
                    advancePlaylist()
                ) {
                    loadFromPreferences()
                }
                schedulePlaylistRotation()
            }
        }
        private val wallpaperChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_WALLPAPER_CHANGED) {
                    return
                }
                val pending = WallpaperPreferences.readPendingState(applicationContext) ?: return
                val active = isActiveWallpaper()
                Log.d(
                    logTag,
                    "wallpaperChanged active=$active token=${pending.token} activeBefore=${pending.wallpaperAlreadyActive}"
                )
                if (active) {
                    cancelPendingClear()
                    WallpaperPreferences.applyPendingState(applicationContext)
                } else {
                    schedulePendingClear("wallpaper_changed_other")
                }
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(false)
            imageDisplayMode = WallpaperPreferences.readImageDisplayMode(applicationContext)
            registerWallpaperChangedReceiver()
            WallpaperPreferences.registerListener(applicationContext, this)
            applyBehaviorPreferences()
        }

        private fun hasRotationSensor(sensorManager: SensorManager?): Boolean {
            sensorManager ?: return false
            val primary = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            val fallback = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            return primary != null || fallback != null
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            attachContent(holder, width, height)
        }

        private fun attachContent(holder: SurfaceHolder, width: Int, height: Int) {
            val targetSurface: Surface = holder.surface
            if (!targetSurface.isValid) {
                return
            }
            val dm = displayManager ?: return
            val density = this@WebWallpaperService.resources.displayMetrics.densityDpi
            mainHandler.post {
                releaseHost()
                virtualDisplay = dm.createVirtualDisplay(
                    "WebWallpaper",
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                    density,
                    targetSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )
                val vdDisplay = virtualDisplay?.display ?: return@post
                val themedContext = ContextThemeWrapper(applicationContext, R.style.Theme_Aura)
                presentation = WallpaperPresentation(
                    themedContext,
                    vdDisplay
                ) { availableWebView, surfaceView, remotePrimary, remoteSecondary ->
                    webViewToken++
                    webView = availableWebView
                    localVideoSurface?.removeVideoSurfaceListener(vrSurfaceListener)
                    localVideoSurface = surfaceView
                    remoteVideoPlayer = DoubleBufferedVideoPlayer(
                        applicationContext,
                        remotePrimary,
                        remoteSecondary,
                        object : DoubleBufferedVideoPlayer.Callback {
                            override fun onInitialBuffering() {}

                            override fun onDisplayed(sourceLabel: String) {
                                mainHandler.post {
                                    currentRemoteVideoUrl = sourceLabel
                                    showRemoteVideoContent()
                                }
                            }

                            override fun onError(error: PlaybackException) {
                                mainHandler.post {
                                    if (currentRemoteVideoUrl.isNullOrBlank()) {
                                        showWebContent()
                                    }
                                }
                            }
                        },
                        "RemoteVideoPlayerWallpaper"
                    )
                    surfaceView.addVideoSurfaceListener(vrSurfaceListener)
                    Log.d(
                        logTag,
                        "vrView ready isAvailable=${surfaceView.isAvailable} size=${surfaceView.width}x${surfaceView.height}"
                    )
                    surfaceView.setUseSensorRotation(vrGlobalEnabled)
                    surfaceView.onResume()
                    surfaceView.ensureRendererReady("attachContent")
                    surfaceView.post {
                        Log.d(
                            logTag,
                            "vrView post-layout isAvailable=${surfaceView.isAvailable} size=${surfaceView.width}x${surfaceView.height}"
                        )
                        surfaceView.ensureRendererReady("attachContent.post")
                    }
                    configureWebView(availableWebView)
                    applyBehaviorPreferences(pendingPreviewState)
                    applyVisibilityState()
                    loadFromPreferences()
                }.apply {
                    show()
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun configureWebView(webView: WebView) {
            webView.apply {
                val bgColor = ContextCompat.getColor(applicationContext, R.color.webviewBackground)
                setBackgroundColor(bgColor)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val targetRequest = request ?: return super.shouldInterceptRequest(view, request)
                        if (!targetRequest.isForMainFrame) {
                            return super.shouldInterceptRequest(view, request)
                        }
                        return ImageWallpaperSupport.buildImageWrapperResponseIfNeeded(
                            targetRequest.url.toString(),
                            targetRequest.requestHeaders,
                            imageDisplayMode
                        ) ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        view?.let {
                            applyImageDisplayModeIfNeeded(it)
                            WebInteractionSupport.applyCompatibilityFixes(it)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.let {
                            applyImageDisplayModeIfNeeded(it)
                            WebInteractionSupport.applyCompatibilityFixes(it)
                        }
                    }
                }
                webChromeClient = WebChromeClient()
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
        }

        private fun applyImageDisplayModeIfNeeded(webView: WebView) {
            ImageWallpaperSupport.applyStandaloneImageMode(webView, imageDisplayMode)
        }

        private fun schedulePreferenceReload() {
            pendingPreferenceReload?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable {
                pendingPreferenceReload = null
                loadFromPreferences()
            }
            pendingPreferenceReload = task
            mainHandler.postDelayed(task, 50)
        }

        private fun loadFromPreferences() {
            pendingPreviewState = if (isPreview) {
                WallpaperPreferences.readPendingState(applicationContext)
            } else {
                null
            }
            val pending = pendingPreviewState
            imageDisplayMode = pending?.imageDisplayMode
                ?: WallpaperPreferences.readImageDisplayMode(applicationContext)
            if (pending != null) {
                currentLocalPath = pending.localFilePath
                currentLocalType = LocalContentType.fromOrdinal(pending.localFileType)
                currentLocalIsVr = pending.localIsVr
            } else {
                currentLocalPath = WallpaperPreferences.readLocalFilePath(applicationContext)
                currentLocalType =
                    LocalContentType.fromOrdinal(
                        WallpaperPreferences.readLocalFileType(applicationContext)
                    )
                currentLocalIsVr = WallpaperPreferences.readLocalIsVr(applicationContext)
            }
            val url = if (currentLocalType == LocalContentType.IMAGE) {
                currentLocalPath?.let { path ->
                    File(path).takeIf { it.exists() }?.let { buildLocalUrl(it, LocalContentType.IMAGE) }
                } ?: pending?.url ?: WallpaperPreferences.readUrl(applicationContext)
            } else {
                pending?.url ?: WallpaperPreferences.readUrl(applicationContext)
            }
            Log.d(
                logTag,
                "loadFromPreferences url=$url localPath=$currentLocalPath type=$currentLocalType isVr=$currentLocalIsVr"
            )
            applyBehaviorPreferences(pending)
            contentLoadToken += 1
            val loadToken = contentLoadToken
            if (shouldUseLocalVideo(url)) {
                pendingLoadTask?.let { mainHandler.removeCallbacks(it) }
                pendingLoadTask = null
                stopRemoteVideoPlayback()
                startLocalVideoPlayback()
                return
            }
            stopLocalVideoPlayback()
            resolveRemoteVideoAndLoad(url, loadToken)
        }

        private fun resolveRemoteVideoAndLoad(url: String, loadToken: Long) {
            Thread {
                val resolvedVideoUrl = RemoteVideoSupport.resolveVideoSourceUrl(url)
                val remoteImageHtml = if (resolvedVideoUrl.isNullOrBlank()) {
                    ImageWallpaperSupport.buildRemoteImageDocumentIfNeeded(
                        url,
                        imageDisplayMode
                    )
                } else {
                    null
                }
                mainHandler.post {
                    if (loadToken != contentLoadToken) {
                        return@post
                    }
                    if (!resolvedVideoUrl.isNullOrBlank()) {
                        startRemoteVideoPlayback(resolvedVideoUrl)
                    } else {
                        stopRemoteVideoPlayback()
                        scheduleWebViewLoad(url, loadToken, remoteImageHtml)
                    }
                }
            }.start()
        }

        private fun scheduleWebViewLoad(
            url: String,
            loadToken: Long,
            remoteImageHtml: String? = null
        ) {
            val token = webViewToken
            pendingLoadTask?.let { mainHandler.removeCallbacks(it) }
            val task = object : Runnable {
                override fun run() {
                    val target = webView
                    if (target == null || token != webViewToken || loadToken != contentLoadToken) {
                        pendingLoadTask = null
                        return
                    }
                    if (!target.isAttachedToWindow) {
                        mainHandler.postDelayed(this, 100)
                        return
                    }
                    showWebContent()
                    target.stopLoading()
                    if (remoteImageHtml.isNullOrBlank()) {
                        target.loadUrl(url)
                    } else {
                        target.loadDataWithBaseURL(
                            url,
                            remoteImageHtml,
                            "text/html",
                            "utf-8",
                            url
                        )
                    }
                    pendingLoadTask = null
                }
            }
            pendingLoadTask = task
            mainHandler.post(task)
        }

        private fun shouldUseLocalVideo(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            if (uri?.scheme != "file") {
                return false
            }
            val path = currentLocalPath
            if (path.isNullOrEmpty()) return false
            if (currentLocalType != LocalContentType.VIDEO) {
                currentLocalIsVr = false
                return false
            }
            val exists = File(path).exists()
            if (!exists) return false
            return true
        }

        private fun shouldUseVrWallpaper(): Boolean {
            val useVr =
                vrGlobalEnabled &&
                    currentLocalIsVr &&
                    currentLocalType == LocalContentType.VIDEO &&
                    hasRotationSensor
            Log.d(
                logTag,
                "shouldUseVrWallpaper result=$useVr global=$vrGlobalEnabled isVr=$currentLocalIsVr type=$currentLocalType hasSensor=$hasRotationSensor"
            )
            return useVr
        }

        private fun startLocalVideoPlayback() {
            val path = currentLocalPath ?: return
            val file = File(path)
            if (!file.exists()) {
                stopLocalVideoPlayback()
                showWebContent()
                return
            }
            val surfaceView = localVideoSurface ?: return
            currentRemoteVideoUrl = null
            Log.d(
                logTag,
                "startLocalVideoPlayback path=$path isVr=$currentLocalIsVr vrSurfaceReady=${vrSurfaceHandle != null} " +
                    "viewAvailable=${surfaceView.isAvailable} size=${surfaceView.width}x${surfaceView.height}"
            )
            if (videoPlayer == null) {
                videoPlayer = LocalVideoPlayer(
                    applicationContext,
                    surfaceView,
                    object : LocalVideoPlayer.Callback {
                        override fun onBuffering() {}

                        override fun onReady() {}

                        override fun onFirstFrameRendered() {}

                        override fun onError(error: PlaybackException) {
                            mainHandler.post { stopLocalVideoPlayback() }
                        }
                    },
                    "LocalVideoPlayerWallpaper"
                )
            }
            val useVr = shouldUseVrWallpaper()
            if (vrSurfaceHandle == null) {
                pendingLocalVideoPath = path
                Log.d(logTag, "defer local video playback until vr surface is ready")
                surfaceView.visibility = View.VISIBLE
                surfaceView.alpha = 1f
                surfaceView.ensureRendererReady("startLocalVideoPlayback")
                webView?.visibility = View.VISIBLE
                return
            }
            pendingLocalVideoPath = null
            surfaceView.setRenderMode(
                if (useVr) VrVideoSurfaceView.RenderMode.SPHERICAL else VrVideoSurfaceView.RenderMode.FLAT
            )
            surfaceView.setUseSensorRotation(useVr)
            vrSurfaceHandle?.let { surfaceHandle ->
                videoPlayer?.setExternalVideoSurface(surfaceHandle, surfaceView)
            }
            showLocalVideoContent()
            videoPlayer?.prepare(file, allowMediaPlayback)
            applyVisibilityState()
        }

        private fun startRemoteVideoPlayback(sourceUrl: String) {
            val player = remoteVideoPlayer ?: return
            pendingLocalVideoPath = null
            Log.d(
                logTag,
                "startRemoteVideoPlayback url=$sourceUrl active=${player.getActiveSourceLabel()}"
            )
            if (player.getActiveSourceLabel() == sourceUrl && player.hasActivePlayback()) {
                currentRemoteVideoUrl = sourceUrl
                player.updateAudio(allowMediaPlayback)
                showRemoteVideoContent()
                return
            }
            player.play(Uri.parse(sourceUrl), allowMediaPlayback, sourceUrl)
            applyVisibilityState()
        }

        private fun stopLocalVideoPlayback() {
            videoPlayer?.setExternalVideoSurface(null, localVideoSurface)
            videoPlayer?.release()
            videoPlayer = null
            pendingLocalVideoPath = null
        }

        private fun stopRemoteVideoPlayback() {
            remoteVideoPlayer?.release()
            currentRemoteVideoUrl = null
        }

        private fun showWebContent() {
            webView?.visibility = View.VISIBLE
            localVideoSurface?.visibility = View.GONE
        }

        private fun showLocalVideoContent() {
            webView?.visibility = View.GONE
            localVideoSurface?.visibility = View.VISIBLE
        }

        private fun showRemoteVideoContent() {
            webView?.visibility = View.GONE
            localVideoSurface?.visibility = View.GONE
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            releaseHost()
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            val useVrGesture = shouldUseVrWallpaper() && vrGestureEnabled
            if (!allowInteraction && !useVrGesture) {
                return
            }
            val eventCopy = MotionEvent.obtain(event)
            try {
                if (useVrGesture) {
                    val vrTarget = localVideoSurface
                    if (vrTarget != null && vrTarget.isShown) {
                        vrTarget.dispatchTouchEvent(eventCopy)
                        return
                    }
                    if (!allowInteraction) {
                        return
                    }
                }
                val targetView = presentation?.window?.decorView ?: return
                targetView.dispatchTouchEvent(eventCopy)
            } finally {
                eventCopy.recycle()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (
                visible &&
                playlistRotationEnabled &&
                playlistMode == PlaylistMode.UNLOCK &&
                advancePlaylist()
            ) {
                loadFromPreferences()
            }
            if (
                visible &&
                localBatchEnabled &&
                localBatchMode == PlaylistMode.UNLOCK &&
                advanceLocalBatch()
            ) {
                loadFromPreferences()
            }
            applyVisibilityState()
        }

        override fun onDestroy() {
            super.onDestroy()
            if (isPreview) {
                val pending = WallpaperPreferences.readPendingState(applicationContext)
                if (pending != null) {
                    val manager = WallpaperManager.getInstance(applicationContext)
                    val info = manager.wallpaperInfo
                    val expected = ComponentName(applicationContext, WebWallpaperService::class.java)
                    if (info?.component == expected) {
                        if (pending.wallpaperAlreadyActive) {
                            Log.d(
                                logTag,
                                "preview destroyed, keep pending token=${pending.token} for preview recreation"
                            )
                        } else {
                            cancelPendingClear()
                            WallpaperPreferences.applyPendingState(applicationContext)
                        }
                    } else {
                        WallpaperPreferences.clearPendingState(applicationContext)
                    }
                }
            }
            unregisterWallpaperChangedReceiver()
            WallpaperPreferences.unregisterListener(applicationContext, this)
            releaseHost()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                WallpaperPreferences.KEY_URL,
                WallpaperPreferences.KEY_URL_VERSION,
                WallpaperPreferences.KEY_LOCAL_FILE_PATH,
                WallpaperPreferences.KEY_LOCAL_FILE_TYPE -> schedulePreferenceReload()
                WallpaperPreferences.KEY_PENDING_APPLY,
                WallpaperPreferences.KEY_PENDING_URL,
                WallpaperPreferences.KEY_PENDING_LOCAL_FILE_PATH,
                WallpaperPreferences.KEY_PENDING_LOCAL_FILE_TYPE,
                WallpaperPreferences.KEY_PENDING_LOCAL_IS_VR,
                WallpaperPreferences.KEY_PENDING_ALLOW_INTERACTION,
                WallpaperPreferences.KEY_PENDING_ALLOW_MEDIA,
                WallpaperPreferences.KEY_PENDING_AUTO_REFRESH,
                WallpaperPreferences.KEY_PENDING_REFRESH_INTERVAL,
                WallpaperPreferences.KEY_PENDING_LAST_MODE_LOCAL,
                WallpaperPreferences.KEY_PENDING_VR_GLOBAL_ENABLED,
                WallpaperPreferences.KEY_PENDING_VR_SENSOR_ENABLED,
                WallpaperPreferences.KEY_PENDING_VR_GESTURE_ENABLED,
                WallpaperPreferences.KEY_PENDING_IMAGE_DISPLAY_MODE,
                WallpaperPreferences.KEY_PENDING_WEB_PLAYLIST_ENABLED,
                WallpaperPreferences.KEY_PENDING_LOCAL_BATCH_ENABLED,
                WallpaperPreferences.KEY_PENDING_WALLPAPER_ALREADY_ACTIVE -> {
                    if (isPreview) {
                        loadFromPreferences()
                    }
                }
                WallpaperPreferences.KEY_IMAGE_DISPLAY_MODE -> {
                    imageDisplayMode = WallpaperPreferences.readImageDisplayMode(applicationContext)
                    if (currentLocalType == LocalContentType.IMAGE || !isLocalContentActive()) {
                        schedulePreferenceReload()
                    }
                }
                WallpaperPreferences.KEY_ALLOW_INTERACTION,
                WallpaperPreferences.KEY_ALLOW_MEDIA,
                WallpaperPreferences.KEY_AUTO_REFRESH,
                WallpaperPreferences.KEY_REFRESH_INTERVAL -> applyBehaviorPreferences()
                WallpaperPreferences.KEY_WEB_PLAYLIST,
                WallpaperPreferences.KEY_WEB_PLAYLIST_INDEX,
                WallpaperPreferences.KEY_WEB_PLAYLIST_ENABLED,
                WallpaperPreferences.KEY_WEB_PLAYLIST_ORDER,
                WallpaperPreferences.KEY_WEB_PLAYLIST_MODE,
                WallpaperPreferences.KEY_WEB_PLAYLIST_INTERVAL -> refreshRotationConfig()
                WallpaperPreferences.KEY_LOCAL_BATCH_LIST,
                WallpaperPreferences.KEY_LOCAL_BATCH_ENABLED,
                WallpaperPreferences.KEY_LOCAL_BATCH_ORDER,
                WallpaperPreferences.KEY_LOCAL_BATCH_MODE,
                WallpaperPreferences.KEY_LOCAL_BATCH_INTERVAL -> refreshLocalBatchConfig()
                WallpaperPreferences.KEY_VR_GLOBAL_ENABLED,
                WallpaperPreferences.KEY_VR_SENSOR_ENABLED,
                WallpaperPreferences.KEY_VR_GESTURE_ENABLED -> {
                    refreshVrPreferences()
                    if (isLocalContentActive()) {
                        schedulePreferenceReload()
                    }
                }
                WallpaperPreferences.KEY_LOCAL_IS_VR -> {
                    currentLocalIsVr = WallpaperPreferences.readLocalIsVr(applicationContext)
                    if (isLocalContentActive()) {
                        schedulePreferenceReload()
                    }
                }
            }
        }

        private fun releaseHost() {
            val cleanup = {
                pendingLoadTask?.let { mainHandler.removeCallbacks(it) }
                pendingLoadTask = null
                pendingPreferenceReload?.let { mainHandler.removeCallbacks(it) }
                pendingPreferenceReload = null
                mainHandler.removeCallbacks(autoRefreshRunnable)
                mainHandler.removeCallbacks(playlistRotationRunnable)
                mainHandler.removeCallbacks(localBatchRotationRunnable)
                val view = webView
                webView = null
                webViewToken++
                contentLoadToken++
                localVideoSurface?.removeVideoSurfaceListener(vrSurfaceListener)
                videoPlayer?.release()
                videoPlayer = null
                remoteVideoPlayer?.release()
                remoteVideoPlayer = null
                pendingLocalVideoPath = null
                currentRemoteVideoUrl = null
                view?.let {
                    it.onPause()
                    it.pauseTimers()
                    (it.parent as? ViewGroup)?.removeView(it)
                }
                localVideoSurface = null
                presentation?.dismiss()
                presentation = null
                view?.stopLoading()
                view?.destroy()
                vrSurfaceHandle = null
                virtualDisplay?.release()
                virtualDisplay = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup()
            } else {
                mainHandler.post { cleanup() }
            }
        }

        private inner class WallpaperPresentation(
            context: Context,
            display: Display,
            private val onViewsReady: (WebView, VrVideoSurfaceView, TextureView, TextureView) -> Unit
        ) : Presentation(context, display) {

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.wallpaper_webview)
                val videoSurface = findViewById<VrVideoSurfaceView>(R.id.wallpaperVideoSurface)
                val remotePrimary = findViewById<TextureView>(R.id.wallpaperRemoteVideoPrimary)
                val remoteSecondary = findViewById<TextureView>(R.id.wallpaperRemoteVideoSecondary)
                val container = findViewById<ViewGroup>(R.id.wallpaperWebViewContainer)
                val popupContext = applicationContext.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    null
                )
                val themedContext = ContextThemeWrapper(popupContext, R.style.Theme_Aura)
                val webView = WebView(themedContext).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
                container.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                videoSurface.visibility = View.VISIBLE
                videoSurface.alpha = 1f
                remotePrimary.visibility = View.GONE
                remoteSecondary.visibility = View.GONE
                onViewsReady(webView, videoSurface, remotePrimary, remoteSecondary)
            }
        }

        private fun applyBehaviorPreferences(stateOverride: WallpaperPreferences.PendingState? = null) {
            if (stateOverride != null) {
                allowInteraction = stateOverride.allowInteraction
                allowMediaPlayback = stateOverride.allowMedia
                autoRefreshEnabled = stateOverride.autoRefresh
                refreshIntervalSeconds = stateOverride.refreshIntervalSeconds
            } else {
                allowInteraction = WallpaperPreferences.readAllowInteraction(applicationContext)
                allowMediaPlayback = WallpaperPreferences.readAllowMediaPlayback(applicationContext)
                autoRefreshEnabled = WallpaperPreferences.readAutoRefresh(applicationContext)
                refreshIntervalSeconds =
                    WallpaperPreferences.readRefreshIntervalSeconds(applicationContext)
            }
            applyInteractionState()
            applyMediaPlaybackState()
            videoPlayer?.updateAudio(allowMediaPlayback)
            remoteVideoPlayer?.updateAudio(allowMediaPlayback)
            refreshVrPreferences(stateOverride)
            scheduleAutoRefresh()
            refreshRotationConfig()
            refreshLocalBatchConfig()
        }

        private fun applyInteractionState() {
            setTouchEventsEnabled(allowInteraction)
            val target = webView ?: return
            if (allowInteraction) {
                target.isClickable = true
                target.isFocusable = true
                target.isFocusableInTouchMode = true
                target.setOnTouchListener(null)
                WebInteractionSupport.requestTouchFocus(target)
            } else {
                target.isClickable = false
                target.isFocusable = false
                target.isFocusableInTouchMode = false
                target.setOnTouchListener { _, _ -> true }
            }
        }

        private fun applyMediaPlaybackState() {
            webView?.settings?.mediaPlaybackRequiresUserGesture = !allowMediaPlayback
        }

        private fun refreshVrPreferences(stateOverride: WallpaperPreferences.PendingState? = null) {
            if (stateOverride != null) {
                vrGlobalEnabled = stateOverride.vrGlobalEnabled && hasRotationSensor
                vrSensorEnabled = vrGlobalEnabled
                vrGestureEnabled = vrGlobalEnabled
            } else {
                vrGlobalEnabled =
                    WallpaperPreferences.readVrGlobalEnabled(applicationContext) && hasRotationSensor
                vrSensorEnabled = vrGlobalEnabled
                vrGestureEnabled = vrGlobalEnabled
            }
            localVideoSurface?.setUseSensorRotation(vrGlobalEnabled && shouldUseVrWallpaper())
            Log.d(
                logTag,
                "refreshVrPreferences global=$vrGlobalEnabled sensor=$vrSensorEnabled gesture=$vrGestureEnabled"
            )
        }

        private fun isActiveWallpaper(): Boolean {
            val manager = WallpaperManager.getInstance(applicationContext)
            val info = manager.wallpaperInfo ?: return false
            val expected = ComponentName(applicationContext, WebWallpaperService::class.java)
            return info.component == expected
        }

        private fun registerWallpaperChangedReceiver() {
            if (wallpaperChangedReceiverRegistered) {
                return
            }
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(
                    wallpaperChangedReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(wallpaperChangedReceiver, filter)
            }
            wallpaperChangedReceiverRegistered = true
        }

        private fun unregisterWallpaperChangedReceiver() {
            if (!wallpaperChangedReceiverRegistered) {
                return
            }
            applicationContext.unregisterReceiver(wallpaperChangedReceiver)
            wallpaperChangedReceiverRegistered = false
        }

        private fun cancelPendingClear() {
            pendingClearTask?.let { mainHandler.removeCallbacks(it) }
            pendingClearTask = null
        }

        private fun schedulePendingClear(reason: String, delayMillis: Long = 1000L) {
            cancelPendingClear()
            val task = Runnable {
                pendingClearTask = null
                val pending = WallpaperPreferences.readPendingState(applicationContext) ?: return@Runnable
                Log.d(
                    logTag,
                    "schedulePendingClear clear token=${pending.token} reason=$reason"
                )
                WallpaperPreferences.clearPendingState(applicationContext)
            }
            pendingClearTask = task
            mainHandler.postDelayed(task, delayMillis)
        }

        private fun applyPendingIfNeeded() {
            if (isPreview) {
                return
            }
            if (WallpaperPreferences.readPendingState(applicationContext) != null && isActiveWallpaper()) {
                WallpaperPreferences.applyPendingState(applicationContext)
            }
        }

        private fun applyVisibilityState() {
            val shouldRun = shouldRunContent()
            webView?.let { target ->
                if (shouldRun) {
                    target.onResume()
                    target.resumeTimers()
                } else {
                    target.onPause()
                    target.pauseTimers()
                }
            }
            videoPlayer?.let { player ->
                if (shouldRun) {
                    player.onResume()
                } else {
                    player.onPause()
                }
            }
            remoteVideoPlayer?.let { player ->
                if (shouldRun) {
                    player.onResume()
                } else {
                    player.onPause()
                }
            }
            val localVideoActive = videoPlayer != null
            val requestVr = shouldUseVrWallpaper()
            localVideoSurface?.let { surface ->
                if (shouldRun && localVideoActive) {
                    surface.onResume()
                    surface.setUseSensorRotation(requestVr)
                    surface.ensureRendererReady("applyVisibilityState")
                } else {
                    surface.onPause()
                }
            }
            scheduleAutoRefresh()
            schedulePlaylistRotation()
            scheduleLocalBatchRotation()
        }

        private fun scheduleAutoRefresh() {
            mainHandler.removeCallbacks(autoRefreshRunnable)
            if (
                autoRefreshEnabled &&
                !isAutoRefreshBlockedByLocalVideo() &&
                refreshIntervalSeconds > 0 &&
                shouldRunContent() &&
                webView != null
            ) {
                val delayMillis = TimeUnit.SECONDS.toMillis(refreshIntervalSeconds)
                mainHandler.postDelayed(autoRefreshRunnable, delayMillis)
            }
        }

        private fun isAutoRefreshBlockedByLocalVideo(): Boolean {
            return videoPlayer != null
        }

        private fun shouldRunContent(): Boolean = engineVisible || isPreview

        private fun refreshRotationConfig() {
            playlistRotationEnabled =
                if (isPreview) {
                    pendingPreviewState?.webPlaylistEnabled
                        ?: WallpaperPreferences.readWebPlaylistEnabled(applicationContext)
                } else {
                    WallpaperPreferences.readWebPlaylistEnabled(applicationContext)
                }
            playlistOrder = WallpaperPreferences.readWebPlaylistOrder(applicationContext)
            playlistMode = WallpaperPreferences.readWebPlaylistMode(applicationContext)
            playlistIntervalSeconds =
                WallpaperPreferences.readWebPlaylistIntervalSeconds(applicationContext)
                    .coerceAtLeast(1)
            schedulePlaylistRotation()
        }

        private fun schedulePlaylistRotation() {
            mainHandler.removeCallbacks(playlistRotationRunnable)
            if (
                !playlistRotationEnabled ||
                playlistMode != PlaylistMode.INTERVAL ||
                !shouldRunContent() ||
                !hasPlaylistForRotation() ||
                isLocalContentActive()
            ) {
                return
            }
            val delayMillis = TimeUnit.SECONDS.toMillis(playlistIntervalSeconds)
            mainHandler.postDelayed(playlistRotationRunnable, delayMillis)
        }

        private fun hasPlaylistForRotation(): Boolean {
            return WallpaperPreferences.readWebPlaylist(applicationContext).size > 1
        }

        private fun advancePlaylist(): Boolean {
            if (!playlistRotationEnabled || isLocalContentActive()) return false
            val playlist = WallpaperPreferences.readWebPlaylist(applicationContext)
            if (playlist.size <= 1) return false
            var currentIndex =
                WallpaperPreferences.readWebPlaylistIndex(applicationContext).coerceIn(
                    0,
                    playlist.lastIndex
                )
            val nextIndex = selectNextIndex(currentIndex, playlist.size)
            if (nextIndex == currentIndex) return false
            val result = WallpaperPreferences.applyWebPlaylistIndex(
                applicationContext,
                nextIndex,
                playlist
            )
            return result != null
        }

        private fun selectNextIndex(currentIndex: Int, size: Int): Int {
            return selectNextIndex(playlistOrder, currentIndex, size)
        }

        private fun selectNextIndex(order: PlaylistOrder, currentIndex: Int, size: Int): Int {
            return when (order) {
                PlaylistOrder.SEQUENTIAL -> (currentIndex + 1) % size
                PlaylistOrder.RANDOM -> {
                    if (size <= 1) {
                        currentIndex
                    } else {
                        var candidate = currentIndex
                        repeat(5) {
                            candidate = Random.nextInt(size)
                            if (candidate != currentIndex) return candidate
                        }
                        (currentIndex + 1) % size
                    }
                }
            }
        }

        private fun isLocalContentActive(): Boolean {
            val url = WallpaperPreferences.readUrl(applicationContext)
            val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()
            return scheme.equals("file", ignoreCase = true)
        }

        private fun refreshLocalBatchConfig() {
            localBatchEnabled =
                if (isPreview) {
                    pendingPreviewState?.localBatchEnabled
                        ?: WallpaperPreferences.readLocalBatchEnabled(applicationContext)
                } else {
                    WallpaperPreferences.readLocalBatchEnabled(applicationContext)
                }
            localBatchOrder = WallpaperPreferences.readLocalBatchOrder(applicationContext)
            localBatchMode = WallpaperPreferences.readLocalBatchMode(applicationContext)
            localBatchIntervalSeconds =
                WallpaperPreferences.readLocalBatchIntervalSeconds(applicationContext)
                    .coerceAtLeast(1)
            Log.d(
                logTag,
                "refreshLocalBatchConfig enabled=$localBatchEnabled order=$localBatchOrder " +
                    "mode=$localBatchMode interval=$localBatchIntervalSeconds entries=" +
                    WallpaperPreferences.readLocalBatch(applicationContext).size
            )
            scheduleLocalBatchRotation()
        }

        private fun scheduleLocalBatchRotation() {
            mainHandler.removeCallbacks(localBatchRotationRunnable)
            if (
                !localBatchEnabled ||
                localBatchMode != PlaylistMode.INTERVAL ||
                !shouldRunContent() ||
                !hasLocalBatchForRotation() ||
                !isLocalContentActive()
            ) {
                Log.d(
                    logTag,
                    "scheduleLocalBatchRotation skipped enabled=$localBatchEnabled mode=$localBatchMode " +
                        "run=${shouldRunContent()} has=${hasLocalBatchForRotation()} local=${isLocalContentActive()}"
                )
                return
            }
            val delayMillis = TimeUnit.SECONDS.toMillis(localBatchIntervalSeconds)
            Log.d(logTag, "scheduleLocalBatchRotation delay=$delayMillis")
            mainHandler.postDelayed(localBatchRotationRunnable, delayMillis)
        }

        private fun hasLocalBatchForRotation(): Boolean {
            return WallpaperPreferences.readLocalBatch(applicationContext).size > 1
        }

        private fun advanceLocalBatch(): Boolean {
            if (!localBatchEnabled || !isLocalContentActive()) return false
            val storedPaths = WallpaperPreferences.readLocalBatch(applicationContext)
            val storedFlags = WallpaperPreferences.readLocalBatchVrFlags(applicationContext)
            val seen = LinkedHashSet<String>()
            val entries = mutableListOf<Pair<String, Boolean>>()
            var modified = false
            storedPaths.forEachIndexed { index, path ->
                val file = File(path)
                if (file.exists()) {
                    if (seen.add(path)) {
                        val flag = storedFlags.getOrNull(index) ?: false
                        entries.add(path to flag)
                    } else {
                        modified = true
                    }
                } else {
                    modified = true
                }
            }
            if (modified) {
                WallpaperPreferences.writeLocalBatch(
                    applicationContext,
                    entries.map { it.first }
                )
                WallpaperPreferences.writeLocalBatchVrFlags(
                    applicationContext,
                    entries.map { it.second }
                )
            }
            if (entries.isEmpty()) return false
            val currentIndex = currentLocalPath?.let { current ->
                entries.indexOfFirst { it.first == current }
            } ?: -1
            val nextIndex = if (entries.size == 1) {
                if (currentIndex == 0) return false else 0
            } else {
                val safeIndex = currentIndex.coerceIn(0, entries.lastIndex)
                val candidate = selectNextIndex(localBatchOrder, safeIndex, entries.size)
                if (candidate == safeIndex && entries.size <= 1) return false else candidate
            }
            val entry = entries[nextIndex]
            val path = entry.first
            val file = File(path)
            if (!file.exists()) {
                WallpaperPreferences.writeLocalBatch(
                    applicationContext,
                    entries.mapNotNull { (candidatePath, _) ->
                        candidatePath.takeIf { File(it).exists() }
                    }
                )
                WallpaperPreferences.writeLocalBatchVrFlags(
                    applicationContext,
                    entries.mapNotNull { (candidatePath, flag) ->
                        candidatePath.takeIf { File(it).exists() }?.let { flag }
                    }
                )
                return false
            }
            val type = determineLocalType(file)
            val targetUrl = buildLocalUrl(file, type)
            WallpaperPreferences.writeLocalBatch(
                applicationContext,
                entries.map { it.first }
            )
            WallpaperPreferences.writeLocalBatchVrFlags(
                applicationContext,
                entries.map { it.second }
            )
            WallpaperPreferences.writeLocalFilePath(applicationContext, path)
            WallpaperPreferences.writeLocalFileType(applicationContext, type.ordinal)
            WallpaperPreferences.writeUrl(applicationContext, targetUrl)
            WallpaperPreferences.writeLastModeIsLocal(applicationContext, true)
            WallpaperPreferences.writeLocalIsVr(applicationContext, entry.second)
            currentLocalPath = path
            currentLocalType = type
            currentLocalIsVr = entry.second
             Log.d(
                logTag,
                "advanceLocalBatch -> path=$path type=$type mode=$localBatchMode order=$localBatchOrder"
            )
            return true
        }

        private fun buildLocalUrl(file: File, type: LocalContentType): String {
            return when (type) {
                LocalContentType.IMAGE -> {
                    val wrapper = createWrapperForLocalFile(file)
                    wrapper?.toURI()?.toString() ?: file.toURI().toString()
                }
                else -> file.toURI().toString()
            }
        }

        private fun determineLocalType(file: File): LocalContentType {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".mp4") || name.endsWith(".webm") ||
                    name.endsWith(".mov") || name.endsWith(".mkv") -> LocalContentType.VIDEO
                name.endsWith(".html") || name.endsWith(".htm") -> LocalContentType.HTML
                name.endsWith(".gif") || name.endsWith(".webp") ||
                    name.endsWith(".png") || name.endsWith(".jpg") ||
                    name.endsWith(".jpeg") || name.endsWith(".bmp") -> LocalContentType.IMAGE
                else -> LocalContentType.HTML
            }
        }

        private fun createWrapperForLocalFile(file: File): File? {
            val dir = File(applicationContext.filesDir, "local_content").apply {
                if (!exists()) mkdirs()
            }
            currentLocalWrapper?.let {
                if (it.exists() && it.absolutePath != file.absolutePath) {
                    it.delete()
                }
            }
            val wrapper = File(dir, "wrapper_${System.currentTimeMillis()}.html")
            val sourceUri = file.toURI().toString()
            val html = ImageWallpaperSupport.buildImageHtml(sourceUri, imageDisplayMode)
            wrapper.writeText(html, Charsets.UTF_8)
            currentLocalWrapper = wrapper
            return wrapper
        }
    }
}
