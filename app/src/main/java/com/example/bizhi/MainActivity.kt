package com.example.bizhi

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.util.Patterns
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.bizhi.BuildConfig
import com.example.bizhi.data.ImageDisplayMode
import com.example.bizhi.data.LocalContentType
import com.example.bizhi.data.PlaylistMode
import com.example.bizhi.data.PlaylistOrder
import com.example.bizhi.data.WallpaperPreferences
import com.example.bizhi.databinding.ActivityMainBinding
import com.example.bizhi.databinding.DialogBatchLocalBinding
import com.example.bizhi.databinding.DialogBatchWebBinding
import com.example.bizhi.databinding.DialogOptionsBinding
import com.example.bizhi.media.DoubleBufferedVideoPlayer
import com.example.bizhi.media.LocalVideoPlayer
import com.example.bizhi.util.ImageWallpaperSupport
import com.example.bizhi.util.RemoteVideoSupport
import com.example.bizhi.util.WebInteractionSupport
import com.example.bizhi.vr.VrVideoSurfaceView
import com.example.bizhi.wallpaper.WebWallpaperService
import com.google.android.exoplayer2.PlaybackException
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var localVideoPlayer: LocalVideoPlayer
    private lateinit var remotePreviewPlayer: DoubleBufferedVideoPlayer
    private val webPlaylist = mutableListOf<String>()
    private var currentWebIndex: Int = 0
    private var currentUrl: String = WallpaperPreferences.DEFAULT_URL
    private var imageDisplayMode: ImageDisplayMode = WallpaperPreferences.DEFAULT_IMAGE_DISPLAY_MODE
    private var allowInteraction: Boolean = WallpaperPreferences.DEFAULT_ALLOW_INTERACTION
    private var allowMedia: Boolean = WallpaperPreferences.DEFAULT_ALLOW_MEDIA
    private var enableAutoRefresh: Boolean = WallpaperPreferences.DEFAULT_AUTO_REFRESH
    private var refreshIntervalSeconds: Long =
        WallpaperPreferences.DEFAULT_REFRESH_INTERVAL_SECONDS
    private var urlDirty: Boolean = false
    private var isLocalMode: Boolean = false
    private var currentLocalType: LocalContentType = LocalContentType.NONE
    private var currentLocalFile: File? = null
    private var currentLocalWrapper: File? = null
    private var currentLocalIsVr: Boolean = false
    private var currentRemoteVideoUrl: String? = null
    private val localBatchEntries = mutableListOf<String>()
    private val localBatchVrFlags = mutableListOf<Boolean>()
    private var webBatchEnabled: Boolean = false
    private var localBatchEnabled: Boolean = false
    private var localBatchOrder: PlaylistOrder = PlaylistOrder.SEQUENTIAL
    private var localBatchMode: PlaylistMode = PlaylistMode.INTERVAL
    private var localBatchIntervalSeconds: Long = WallpaperPreferences.DEFAULT_PLAYLIST_INTERVAL_SECONDS
    private var vrGlobalEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_GLOBAL_ENABLED
    private var vrSensorEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_SENSOR_ENABLED
    private var vrGestureEnabled: Boolean = WallpaperPreferences.DEFAULT_VR_GESTURE_ENABLED
    private var localBatchDialogRefresh: (() -> Unit)? = null
    private var previewVrSurface: Surface? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewLoadToken: Long = 0L
    private var previewLoadTimeout: Runnable? = null
    private var previewLoadTask: Runnable? = null
    private val previewStabilizationTasks = mutableListOf<Runnable>()
    private var resumePlaybackCheck: Runnable? = null
    private val wallpaperPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleWallpaperPickerResult(result.resultCode)
        }
    private val hasRotationSensor: Boolean by lazy {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotation != null
    }
    private val pickLocalFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handlePickedFile(uri)
            } else if (currentLocalFile == null) {
                isLocalMode = false
                updateModeUI()
                WallpaperPreferences.writeLastModeIsLocal(this, false)
            }
        }

    private val pickLocalBatchLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                return@registerForActivityResult
            }
            handleBatchPickedFiles(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        localVideoPlayer = LocalVideoPlayer(
            this,
            binding.localVideoSurface,
            object : LocalVideoPlayer.Callback {
                override fun onBuffering() {
                    binding.previewContainer.post { showPreviewLoading() }
                }

                override fun onReady() {
                    binding.previewContainer.post { showPreviewContent() }
                }

                override fun onFirstFrameRendered() {
                    binding.previewContainer.post { showPreviewContent() }
                }

                override fun onError(error: PlaybackException) {
                    binding.previewContainer.post {
                        showPreviewError(getString(R.string.preview_error))
                    }
                }
            },
            "LocalVideoPlayerMain"
        )
        remotePreviewPlayer = DoubleBufferedVideoPlayer(
            this,
            binding.remoteVideoSurfacePrimary,
            binding.remoteVideoSurfaceSecondary,
            object : DoubleBufferedVideoPlayer.Callback {
                override fun onInitialBuffering() {
                    binding.previewContainer.post { showPreviewLoading() }
                }

                override fun onDisplayed(sourceLabel: String) {
                    binding.previewContainer.post {
                        currentRemoteVideoUrl = sourceLabel
                        binding.previewWebView.isVisible = false
                        updateVrPreviewUi(false)
                        binding.localVideoSurface.isVisible = false
                        showPreviewContent()
                    }
                }

                override fun onError(error: PlaybackException) {
                    binding.previewContainer.post {
                        currentRemoteVideoUrl = null
                        showPreviewError(getString(R.string.preview_error))
                    }
                }
            },
            "RemotePreviewPlayer"
        )

        binding.previewStatus.setOnClickListener {
            binding.previewStatus.isVisible = false
            handleUrlAction()
        }

        binding.vrPreviewSurface.addVideoSurfaceListener(
            object : VrVideoSurfaceView.VideoSurfaceListener {
                override fun onVideoSurfaceCreated(surface: Surface) {
                    previewVrSurface = surface
                    if (shouldUseVrPreview()) {
                        localVideoPlayer.setExternalVideoSurface(surface, binding.vrPreviewSurface)
                        binding.localVideoSurface.isVisible = false
                        binding.vrPreviewSurface.isVisible = true
                    }
                }

                override fun onVideoSurfaceDestroyed(surface: Surface) {
                    if (previewVrSurface == surface) {
                        localVideoPlayer.clearExternalVideoSurface(surface)
                        previewVrSurface = null
                        binding.vrPreviewSurface.isVisible = false
                    }
                }
            }
        )
        binding.vrPreviewSurface.alpha = 0f
        binding.vrPreviewSurface.isVisible = false
        binding.vrPreviewTouchBlocker.setOnTouchListener { _, _ -> true }

        configurePreviewWebView(binding.previewWebView)
        currentUrl = WallpaperPreferences.readUrl(this)
        imageDisplayMode = WallpaperPreferences.readImageDisplayMode(this)
        val urlLooksLocal = isLocalFileUrl(currentUrl)
        val lastModeLocal = WallpaperPreferences.readLastModeIsLocal(this).let { persisted ->
            if (persisted && !urlLooksLocal) {
                Log.d(
                    "MainActivity",
                    "clear stale lastModeLocal because current url is not local: $currentUrl"
                )
                WallpaperPreferences.writeLastModeIsLocal(this, false)
                false
            } else {
                persisted
            }
        }
        isLocalMode = lastModeLocal || urlLooksLocal
        allowInteraction = WallpaperPreferences.readAllowInteraction(this)
        allowMedia = WallpaperPreferences.readAllowMediaPlayback(this)
        enableAutoRefresh = WallpaperPreferences.readAutoRefresh(this)
        refreshIntervalSeconds = WallpaperPreferences.readRefreshIntervalSeconds(this)
        vrGlobalEnabled = WallpaperPreferences.readVrGlobalEnabled(this)
        vrSensorEnabled = WallpaperPreferences.readVrSensorEnabled(this)
        vrGestureEnabled = WallpaperPreferences.readVrGestureEnabled(this)
        if (!hasRotationSensor && vrGlobalEnabled) {
            vrGlobalEnabled = false
            WallpaperPreferences.writeVrGlobalEnabled(this, false)
        }
        vrGestureEnabled = vrGlobalEnabled
        vrSensorEnabled = vrGlobalEnabled
        val storedLocalPath = WallpaperPreferences.readLocalFilePath(this)
        val storedLocalTypeOrdinal = WallpaperPreferences.readLocalFileType(this)
        currentLocalIsVr = WallpaperPreferences.readLocalIsVr(this)
        var hasValidLocalSource = false
        if (!storedLocalPath.isNullOrEmpty()) {
            val file = File(storedLocalPath)
            if (file.exists()) {
                hasValidLocalSource = true
                currentLocalFile = file
                currentLocalType = LocalContentType.fromOrdinal(storedLocalTypeOrdinal)
                if (urlLooksLocal) {
                    val path = Uri.parse(currentUrl).path
                    if (!path.isNullOrEmpty()) {
                        currentLocalWrapper = File(path)
                    }
                }
            } else {
                WallpaperPreferences.writeLocalFilePath(this, null)
                currentLocalType = LocalContentType.NONE
                currentLocalIsVr = false
                WallpaperPreferences.writeLocalIsVr(this, false)
                if (lastModeLocal) {
                    WallpaperPreferences.writeLastModeIsLocal(this, false)
                }
            }
        } else {
            currentLocalType = LocalContentType.NONE
            currentLocalIsVr = false
            WallpaperPreferences.writeLocalIsVr(this, false)
        }
        if (isLocalMode) {
            if (hasValidLocalSource && currentLocalFile != null) {
                val needsNewWrapper = currentLocalType == LocalContentType.IMAGE
                if (!urlLooksLocal || needsNewWrapper) {
                    ensureLocalUrlForCurrentFile(
                        forceNewWrapper = needsNewWrapper,
                        persistUrl = true
                    )
                }
            } else {
                isLocalMode = false
                currentLocalType = LocalContentType.NONE
                WallpaperPreferences.writeLastModeIsLocal(this, false)
                if (!currentUrl.startsWith("http")) {
                    currentUrl = WallpaperPreferences.DEFAULT_URL
                    WallpaperPreferences.writeUrl(this, currentUrl)
                }
            }
        } else {
            currentLocalType = LocalContentType.NONE
        }
        localBatchEntries.clear()
        localBatchVrFlags.clear()
        val storedLocalBatch = WallpaperPreferences.readLocalBatch(this)
        val storedLocalBatchVrFlags = WallpaperPreferences.readLocalBatchVrFlags(this)
        val validLocalBatch = mutableListOf<String>()
        val validLocalFlags = mutableListOf<Boolean>()
        val seenPaths = LinkedHashSet<String>()
        storedLocalBatch.forEachIndexed { index, path ->
            val exists = File(path).exists()
            if (exists && seenPaths.add(path)) {
                validLocalBatch.add(path)
                val flag = storedLocalBatchVrFlags.getOrNull(index) ?: false
                validLocalFlags.add(flag)
            }
        }
        if (validLocalBatch.size != storedLocalBatch.size ||
            validLocalFlags.size != storedLocalBatchVrFlags.size
        ) {
            WallpaperPreferences.writeLocalBatch(this, validLocalBatch)
            WallpaperPreferences.writeLocalBatchVrFlags(this, validLocalFlags)
        }
        localBatchEntries.addAll(validLocalBatch)
        localBatchVrFlags.addAll(validLocalFlags)
        webBatchEnabled = WallpaperPreferences.readWebPlaylistEnabled(this)
        localBatchEnabled = WallpaperPreferences.readLocalBatchEnabled(this)
        localBatchOrder = WallpaperPreferences.readLocalBatchOrder(this)
        localBatchMode = WallpaperPreferences.readLocalBatchMode(this)
        localBatchIntervalSeconds =
            WallpaperPreferences.readLocalBatchIntervalSeconds(this)
                .coerceAtLeast(WallpaperPreferences.DEFAULT_PLAYLIST_INTERVAL_SECONDS)
        webPlaylist.clear()
        val storedWebList = WallpaperPreferences.readWebPlaylist(this)
        if (storedWebList.isEmpty()) {
            webPlaylist.add(WallpaperPreferences.DEFAULT_URL)
            WallpaperPreferences.writeWebPlaylist(this, webPlaylist)
        } else {
            webPlaylist.addAll(storedWebList)
        }
        if (!isLocalMode) {
            val storedIndex = WallpaperPreferences.readWebPlaylistIndex(this)
            val matchedIndex = webPlaylist.indexOfFirst { it.equals(currentUrl, ignoreCase = true) }
            currentWebIndex = when {
                matchedIndex != -1 -> matchedIndex
                storedIndex in webPlaylist.indices -> storedIndex
                else -> 0
            }
            WallpaperPreferences.writeWebPlaylist(this, webPlaylist)
            currentUrl = webPlaylist[currentWebIndex]
        } else {
            currentWebIndex = WallpaperPreferences.readWebPlaylistIndex(this)
                .coerceIn(0, webPlaylist.lastIndex.coerceAtLeast(0))
        }

        if (isLocalMode) {
            binding.urlInput.setText(currentLocalFile?.name.orEmpty())
        } else {
            binding.urlInput.setText(currentUrl)
        }
        binding.urlInput.doAfterTextChanged {
            if (!binding.urlInput.isEnabled) return@doAfterTextChanged
            val newValue = it?.toString().orEmpty().trim()
            val dirty = newValue != currentUrl
            setUrlDirty(dirty)
        }
        binding.optionsButton.setOnClickListener { showOptionsDialog() }
        binding.modeButton.setOnClickListener { toggleMode() }
        binding.applyButton.setOnClickListener {
            stagePendingConfiguration()
            openWallpaperPicker()
        }
        binding.urlRefreshButton.setOnClickListener { handleUrlAction() }
        binding.urlBatchButton.setOnClickListener {
            if (!isLocalMode) {
                showWebBatchDialog()
            }
        }
        binding.localBatchButton.setOnClickListener { showLocalBatchDialog() }

        applyLocalConstraintsForCurrentType()
        applyPreviewBehavior()
        updateModeUI()
        setUrlDirty(false)
        loadPreview(currentUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurePreviewWebView(webView: WebView) = with(webView) {
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.previewSurface))
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                showPreviewLoading()
            }

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
                showPreviewContent()
                resumePreviewWebView("onPageCommitVisible")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.let {
                    applyImageDisplayModeIfNeeded(it)
                    WebInteractionSupport.applyCompatibilityFixes(it)
                }
                showPreviewContent()
                resumePreviewWebView("onPageFinished")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                showPreviewError(getString(R.string.preview_error))
                binding.previewWebView.post {
                    loadPreview(currentUrl, forceReload = true)
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != false) {
                    showPreviewError(getString(R.string.preview_error))
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 80) {
                    showPreviewContent()
                }
            }
        }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            offscreenPreRaster = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }
    }

    private fun applyImageDisplayModeIfNeeded(webView: WebView) {
        ImageWallpaperSupport.applyStandaloneImageMode(webView, imageDisplayMode)
    }

    private fun shouldUseVrPreview(): Boolean {
        return vrGlobalEnabled &&
            currentLocalIsVr &&
            isLocalMode &&
            currentLocalType == LocalContentType.VIDEO &&
            hasRotationSensor
    }

    private fun updateVrPreviewUi(requestVr: Boolean) {
        val surfaceReady = previewVrSurface != null
        val useVr = requestVr && surfaceReady
        binding.vrPreviewSurface.isVisible = requestVr
        binding.vrPreviewSurface.alpha = if (useVr) 1f else 0f
        binding.vrPreviewHint.isVisible = useVr
        binding.vrPreviewTouchBlocker.isVisible = useVr && !vrGestureEnabled
        if (useVr) {
            binding.vrPreviewSurface.ensureRendererReady("updateVrPreviewUi")
            binding.vrPreviewSurface.setUseSensorRotation(vrGlobalEnabled)
            previewVrSurface?.let {
                localVideoPlayer.setExternalVideoSurface(it, binding.vrPreviewSurface)
            }
        } else {
            localVideoPlayer.setExternalVideoSurface(null, binding.vrPreviewSurface)
        }
        binding.localVideoSurface.isVisible = !useVr
    }

    private fun isRemoteVideoPreviewActive(): Boolean {
        return !isLocalMode && !currentRemoteVideoUrl.isNullOrBlank()
    }

    private fun applyPreviewBehavior() {
        val isLocalVideo = isLocalMode && currentLocalType == LocalContentType.VIDEO
        val isRemoteVideo = isRemoteVideoPreviewActive()
        val useVrPreview = isLocalVideo && shouldUseVrPreview()
        binding.previewWebView.apply {
            if (allowInteraction) {
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener(null)
                WebInteractionSupport.requestTouchFocus(this)
            } else {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                setOnTouchListener { _, _ -> true }
            }
            settings.mediaPlaybackRequiresUserGesture =
                if (isLocalVideo || isRemoteVideo) false else !allowMedia
            isVisible = !(isLocalVideo || isRemoteVideo)
        }
        updateVrPreviewUi(useVrPreview)
        if (isLocalVideo) {
            remotePreviewPlayer.release()
            localVideoPlayer.updateAudio(allowMedia)
            binding.previewWebView.isVisible = false
        } else if (isRemoteVideo) {
            localVideoPlayer.release()
            updateVrPreviewUi(false)
            binding.localVideoSurface.isVisible = false
            remotePreviewPlayer.updateAudio(allowMedia)
            binding.previewWebView.isVisible = false
        } else {
            remotePreviewPlayer.release()
            binding.localVideoSurface.isVisible = false
            localVideoPlayer.release()
        }
    }

    private fun resetPreviewForModeSwitch() {
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        previewLoadTimeout = null
        cancelPendingPreviewLoadTask()
        cancelPreviewStabilizationTasks()
        previewLoadToken += 1
        binding.previewWebView.stopLoading()
        binding.previewWebView.loadUrl("about:blank")
        binding.previewWebView.isVisible = true
        resumePreviewWebView("resetPreviewForModeSwitch")
        currentRemoteVideoUrl = null
        remotePreviewPlayer.release()
        localVideoPlayer.release()
        updateVrPreviewUi(false)
        binding.localVideoSurface.isVisible = false
        showPreviewLoading()
    }

    private fun renderCurrentModeAfterSwitch() {
        resetPreviewForModeSwitch()
        if (isLocalMode) {
            val file = currentLocalFile?.takeIf { it.exists() }
            if (file == null) {
                currentLocalType = LocalContentType.NONE
                updateModeUI()
                showPreviewError(getString(R.string.toast_no_local_file))
                return
            }
            currentLocalType = determineLocalType(file)
            regenerateLocalWrapper()
            applyLocalConstraintsForCurrentType()
            updateModeUI()
            return
        }
        currentLocalType = LocalContentType.NONE
        val targetUrl = webPlaylist.getOrNull(currentWebIndex.coerceIn(0, webPlaylist.lastIndex))
            ?: WallpaperPreferences.DEFAULT_URL
        currentUrl = normalizeUrlInput(targetUrl)
        binding.urlInput.setText(currentUrl)
        setUrlDirty(false)
        applyLocalConstraintsForCurrentType()
        updateModeUI()
        loadPreview(currentUrl, forceReload = true)
    }

    private fun loadPreview(
        url: String,
        forceReload: Boolean = false,
        requireWindowFocus: Boolean = true
    ) {
        if (isLocalMode && currentLocalFile == null) {
            showPreviewError(getString(R.string.toast_no_local_file))
            return
        }
        if (url.isBlank()) {
            showPreviewError(getString(R.string.toast_no_local_file))
            return
        }
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        previewLoadTimeout = null
        cancelPendingPreviewLoadTask()
        cancelPreviewStabilizationTasks()
        val isLocalVideo = isLocalMode && currentLocalType == LocalContentType.VIDEO
        val targetUrl = if (forceReload && !isLocalFileUrl(url)) {
            val separator = if (url.contains("?")) "&" else "?"
            "$url${separator}t=${System.currentTimeMillis()}"
        } else {
            url
        }
        if (isLocalVideo) {
            val videoFile = currentLocalFile
            if (videoFile == null || !videoFile.exists()) {
                showPreviewError(getString(R.string.toast_no_local_file))
                return
            }
            val useVrPreview = shouldUseVrPreview()
            showPreviewLoading()
            remotePreviewPlayer.release()
            binding.previewWebView.stopLoading()
            binding.previewWebView.isVisible = false
            currentRemoteVideoUrl = null
            updateVrPreviewUi(useVrPreview)
            localVideoPlayer.prepare(videoFile, allowMedia)
            localVideoPlayer.onResume()
            return
        }
        showPreviewLoading()
        localVideoPlayer.release()
        binding.localVideoSurface.isVisible = false
        updateVrPreviewUi(false)
        previewLoadToken += 1
        val loadToken = previewLoadToken
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        binding.previewWebView.stopLoading()
        resolveRemoteVideoPreview(targetUrl, loadToken, requireWindowFocus)
        val timeoutTask = Runnable {
            if (loadToken == previewLoadToken && currentRemoteVideoUrl.isNullOrBlank()) {
                showPreviewContent()
            }
        }
        previewLoadTimeout = timeoutTask
        previewHandler.postDelayed(timeoutTask, 6000)
    }

    private fun resolveRemoteVideoPreview(
        targetUrl: String,
        loadToken: Long,
        requireWindowFocus: Boolean
    ) {
        Thread {
            val resolvedVideoUrl = RemoteVideoSupport.resolveVideoSourceUrl(targetUrl)
            val remoteImageHtml = if (resolvedVideoUrl.isNullOrBlank()) {
                ImageWallpaperSupport.buildRemoteImageDocumentIfNeeded(
                    targetUrl,
                    imageDisplayMode
                )
            } else {
                null
            }
            binding.previewContainer.post {
                if (isFinishing || isDestroyed || loadToken != previewLoadToken) {
                    return@post
                }
                if (!isLocalMode && !resolvedVideoUrl.isNullOrBlank()) {
                    if (currentRemoteVideoUrl == resolvedVideoUrl && remotePreviewPlayer.hasActivePlayback()) {
                        binding.previewWebView.isVisible = false
                        updateVrPreviewUi(false)
                        binding.localVideoSurface.isVisible = false
                        remotePreviewPlayer.updateAudio(allowMedia)
                        showPreviewContent()
                    } else {
                        binding.previewWebView.stopLoading()
                        updateVrPreviewUi(false)
                        binding.localVideoSurface.isVisible = false
                        remotePreviewPlayer.play(
                            Uri.parse(resolvedVideoUrl),
                            allowMedia,
                            resolvedVideoUrl
                        )
                        remotePreviewPlayer.onResume()
                    }
                } else {
                    currentRemoteVideoUrl = null
                    remotePreviewPlayer.release()
                    updateVrPreviewUi(false)
                    binding.localVideoSurface.isVisible = false
                    binding.previewWebView.isVisible = true
                    schedulePreviewUrlLoad(
                        targetUrl,
                        loadToken,
                        remoteImageHtml,
                        requireWindowFocus
                    )
                }
            }
        }.start()
    }

    private fun cancelPendingPreviewLoadTask() {
        previewLoadTask?.let { previewHandler.removeCallbacks(it) }
        previewLoadTask = null
    }

    private fun cancelPreviewStabilizationTasks() {
        previewStabilizationTasks.forEach { previewHandler.removeCallbacks(it) }
        previewStabilizationTasks.clear()
    }

    private fun schedulePreviewUrlLoad(
        targetUrl: String,
        loadToken: Long,
        remoteImageHtml: String? = null,
        requireWindowFocus: Boolean = true
    ) {
        cancelPendingPreviewLoadTask()
        val webView = binding.previewWebView
        val task = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || loadToken != previewLoadToken) {
                    previewLoadTask = null
                    return
                }
                if (!webView.isAttachedToWindow ||
                    webView.width <= 0 ||
                    webView.height <= 0 ||
                    !webView.isShown ||
                    (requireWindowFocus && (!hasWindowFocus() || !webView.hasWindowFocus()))
                ) {
                    previewHandler.postDelayed(this, 32)
                    return
                }
                resumePreviewWebView("schedulePreviewUrlLoad")
                Log.d(
                    "MainActivity",
                    "schedulePreviewUrlLoad token=$loadToken size=${webView.width}x${webView.height} attached=${webView.isAttachedToWindow} focus=${webView.hasWindowFocus()} url=$targetUrl"
                )
                if (remoteImageHtml.isNullOrBlank()) {
                    webView.loadUrl(targetUrl)
                } else {
                    webView.loadDataWithBaseURL(
                        targetUrl,
                        remoteImageHtml,
                        "text/html",
                        "utf-8",
                        targetUrl
                    )
                }
                webView.postVisualStateCallback(
                    loadToken,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (isFinishing || isDestroyed || requestId != previewLoadToken) {
                                return
                            }
                            stabilizePreviewWebView("postVisualStateCallback")
                            schedulePreviewStabilizationPulses(requestId)
                        }
                    }
                )
                previewLoadTask = null
            }
        }
        previewLoadTask = task
        webView.post(task)
    }

    private fun resumePreviewWebView(reason: String) {
        if (isFinishing || isDestroyed) return
        if ((isLocalMode && currentLocalType == LocalContentType.VIDEO) || isRemoteVideoPreviewActive()) {
            return
        }
        val webView = binding.previewWebView
        if (!webView.isVisible) return
        webView.onResume()
        webView.resumeTimers()
        stabilizePreviewWebView(reason)
    }

    private fun stabilizePreviewWebView(reason: String) {
        val webView = binding.previewWebView
        webView.post {
            if (isFinishing || isDestroyed) return@post
            if (!webView.isAttachedToWindow || !webView.isVisible) return@post
            Log.d(
                "MainActivity",
                "stabilizePreviewWebView reason=$reason size=${webView.width}x${webView.height} shown=${webView.isShown} focus=${webView.hasWindowFocus()}"
            )
            webView.requestLayout()
            webView.invalidate()
            webView.postInvalidateOnAnimation()
        }
    }

    private fun schedulePreviewStabilizationPulses(loadToken: Long) {
        cancelPreviewStabilizationTasks()
        val delays = longArrayOf(120L, 320L, 700L, 1400L, 2200L)
        delays.forEach { delayMillis ->
            val task = Runnable {
                if (isFinishing || isDestroyed || loadToken != previewLoadToken) {
                    return@Runnable
                }
                val webView = binding.previewWebView
                if (!webView.isAttachedToWindow || !webView.isVisible) {
                    return@Runnable
                }
                resumePreviewWebView("stabilizationPulse:$delayMillis")
                webView.evaluateJavascript(
                    """
                    (function() {
                      try {
                        window.dispatchEvent(new Event('resize'));
                        return document.readyState + ':' + (document.visibilityState || 'unknown');
                      } catch (error) {
                        return 'error:' + error;
                      }
                    })();
                    """.trimIndent(),
                    null
                )
            }
            previewStabilizationTasks += task
            previewHandler.postDelayed(task, delayMillis)
        }
    }

    private fun addOrUpdateWebEntry(url: String): Int {
        val normalized = normalizeUrlInput(url)
        val existingIndex = webPlaylist.indexOfFirst { it.equals(normalized, ignoreCase = true) }
        val index = if (existingIndex == -1) {
            webPlaylist.add(normalized)
            webPlaylist.lastIndex
        } else {
            webPlaylist[existingIndex] = normalized
            existingIndex
        }
        WallpaperPreferences.writeWebPlaylist(this, webPlaylist)
        return index
    }

    private fun applyWebEntry(
        url: String,
        reload: Boolean,
        deferPreview: Boolean = false,
        requireWindowFocus: Boolean = true
    ) {
        val normalized = normalizeUrlInput(url)
        if (!isValidUrl(normalized)) return
        val index = addOrUpdateWebEntry(normalized)
        currentWebIndex = index
        currentUrl = normalized
        binding.urlInput.setText(normalized)
        setUrlDirty(false)
        if (!deferPreview) {
            if (reload) {
                loadPreview(
                    normalized,
                    forceReload = true,
                    requireWindowFocus = requireWindowFocus
                )
            } else {
                loadPreview(normalized, requireWindowFocus = requireWindowFocus)
            }
        }
    }

    private fun applyFirstWebBatchEntry(deferPreview: Boolean = false): Boolean {
        val firstEntry = webPlaylist.withIndex().firstOrNull { it.value.isNotBlank() } ?: return false
        val normalized = normalizeUrlInput(firstEntry.value)
        if (!isValidUrl(normalized)) return false
        currentWebIndex = firstEntry.index
        currentUrl = normalized
        isLocalMode = false
        currentLocalType = LocalContentType.NONE
        currentLocalIsVr = false
        WallpaperPreferences.applyWebPlaylistIndex(this, currentWebIndex, webPlaylist)
        WallpaperPreferences.writeLastModeIsLocal(this, false)
        binding.urlInput.setText(normalized)
        setUrlDirty(false)
        applyLocalConstraintsForCurrentType()
        updateModeUI()
        Log.d(
            "MainActivity",
            "applyFirstWebBatchEntry index=${firstEntry.index} url=$normalized"
        )
        if (!deferPreview) {
            loadPreview(normalized, forceReload = true)
        }
        return true
    }

    private fun removeWebEntry(index: Int) {
        if (webPlaylist.size <= 1 || index !in webPlaylist.indices) {
            return
        }
        val removed = webPlaylist.removeAt(index)
        WallpaperPreferences.writeWebPlaylist(this, webPlaylist)
        currentWebIndex = currentWebIndex.coerceAtMost(webPlaylist.lastIndex)
        if (removed.equals(currentUrl, ignoreCase = true)) {
            val fallback = webPlaylist.getOrNull(currentWebIndex) ?: WallpaperPreferences.DEFAULT_URL
            applyWebEntry(fallback, reload = true)
        }
    }

    private fun showWebBatchDialog() {
        val dialogBinding = DialogBatchWebBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_close, null)
            .create()

        val playlistOrder = WallpaperPreferences.readWebPlaylistOrder(this)
        val playlistMode = WallpaperPreferences.readWebPlaylistMode(this)
        val playlistInterval =
            WallpaperPreferences.readWebPlaylistIntervalSeconds(this).coerceAtLeast(1)

        dialogBinding.webBatchToggle.isChecked = webBatchEnabled
        dialogBinding.webBatchSettings.isVisible = webBatchEnabled
        dialogBinding.webBatchOrderGroup.check(
            if (playlistOrder == PlaylistOrder.RANDOM) R.id.webBatchOrderRandom
            else R.id.webBatchOrderSequential
        )
        dialogBinding.webBatchModeGroup.check(
            if (playlistMode == PlaylistMode.UNLOCK) R.id.webBatchModeUnlock
            else R.id.webBatchModeInterval
        )
        dialogBinding.webBatchIntervalInput.setText(playlistInterval.toString())
        dialogBinding.webBatchIntervalLayout.isVisible =
            webBatchEnabled && playlistMode == PlaylistMode.INTERVAL

        fun refreshChips() {
            dialogBinding.webBatchChipGroup.removeAllViews()
            dialogBinding.webBatchEmpty.isVisible = webPlaylist.isEmpty()
            val activeIndex = currentWebIndex.coerceIn(0, webPlaylist.lastIndex)
            val activeChipColor = ContextCompat.getColorStateList(this, R.color.surfaceVariant)
            val inactiveChipColor = ContextCompat.getColorStateList(this, R.color.colorSurfaceAlt)
            val activeTextColor = ContextCompat.getColor(this, R.color.colorPrimary)
            val inactiveTextColor = ContextCompat.getColor(this, R.color.colorOnSurface)
            webPlaylist.forEachIndexed { index, url ->
                val chip = Chip(this).apply {
                    text = url
                    isCheckable = false
                    isCloseIconVisible = webPlaylist.size > 1
                    setOnClickListener {
                        applyWebEntry(url, reload = true, requireWindowFocus = false)
                        refreshChips()
                    }
                    setOnCloseIconClickListener {
                        removeWebEntry(index)
                        refreshChips()
                    }
                }
                chip.chipBackgroundColor =
                    if (index == activeIndex) activeChipColor else inactiveChipColor
                chip.setTextColor(if (index == activeIndex) activeTextColor else inactiveTextColor)
                dialogBinding.webBatchChipGroup.addView(chip)
            }
        }

        fun updateSettingsVisibility() {
            val mode = if (dialogBinding.webBatchModeGroup.checkedButtonId == R.id.webBatchModeUnlock) {
                PlaylistMode.UNLOCK
            } else {
                PlaylistMode.INTERVAL
            }
            dialogBinding.webBatchSettings.isVisible = dialogBinding.webBatchToggle.isChecked
            dialogBinding.webBatchIntervalLayout.isVisible =
                dialogBinding.webBatchToggle.isChecked && mode == PlaylistMode.INTERVAL
        }

        dialogBinding.webBatchToggle.setOnCheckedChangeListener { _, isChecked ->
            webBatchEnabled = isChecked
            updateSettingsVisibility()
        }
        dialogBinding.webBatchModeGroup.addOnButtonCheckedListener { _, _, _ ->
            updateSettingsVisibility()
        }

        dialogBinding.webBatchInputLayout.setEndIconOnClickListener {
            val raw = dialogBinding.webBatchInput.text?.toString().orEmpty()
            val normalized = normalizeUrlInput(raw)
            if (!isValidUrl(normalized)) {
                dialogBinding.webBatchInputLayout.error = getString(R.string.toast_invalid_url)
                return@setEndIconOnClickListener
            }
            dialogBinding.webBatchInputLayout.error = null
            dialogBinding.webBatchInput.setText("")
            applyWebEntry(normalized, reload = true, requireWindowFocus = false)
            refreshChips()
        }

        updateSettingsVisibility()

        refreshChips()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                webBatchEnabled = dialogBinding.webBatchToggle.isChecked
                val selectedOrder =
                    if (dialogBinding.webBatchOrderGroup.checkedButtonId == R.id.webBatchOrderRandom) {
                        PlaylistOrder.RANDOM
                    } else {
                        PlaylistOrder.SEQUENTIAL
                    }
                WallpaperPreferences.writeWebPlaylistOrder(this, selectedOrder)
                val selectedMode =
                    if (dialogBinding.webBatchModeGroup.checkedButtonId == R.id.webBatchModeUnlock) {
                        PlaylistMode.UNLOCK
                    } else {
                        PlaylistMode.INTERVAL
                    }
                WallpaperPreferences.writeWebPlaylistMode(this, selectedMode)
                if (webBatchEnabled && selectedMode == PlaylistMode.INTERVAL) {
                    val intervalText =
                        dialogBinding.webBatchIntervalInput.text?.toString()?.trim()
                    val intervalValue = intervalText?.toLongOrNull()
                    if (intervalValue == null || intervalValue <= 0) {
                        dialogBinding.webBatchIntervalLayout.error =
                            getString(R.string.toast_playlist_invalid_interval)
                        return@setOnClickListener
                    }
                    WallpaperPreferences.writeWebPlaylistIntervalSeconds(this, intervalValue)
                    dialogBinding.webBatchIntervalLayout.error = null
                } else {
                    dialogBinding.webBatchIntervalLayout.error = null
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showLocalBatchDialog() {
        val dialogBinding = DialogBatchLocalBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_close, null)
            .create()
        var shouldApplyFirstEntryOnDismiss = false

        dialogBinding.localBatchToggle.isChecked = localBatchEnabled
        dialogBinding.localBatchOptions.isVisible = localBatchEnabled
        dialogBinding.localBatchOrderGroup.check(
            if (localBatchOrder == PlaylistOrder.RANDOM) R.id.localBatchOrderRandom
            else R.id.localBatchOrderSequential
        )
        dialogBinding.localBatchModeGroup.check(
            if (localBatchMode == PlaylistMode.UNLOCK) R.id.localBatchModeUnlock
            else R.id.localBatchModeInterval
        )
        dialogBinding.localBatchIntervalInput.setText(localBatchIntervalSeconds.toString())
        dialogBinding.localBatchIntervalLayout.isVisible =
            localBatchEnabled && localBatchMode == PlaylistMode.INTERVAL

        fun refreshChips() {
            dialogBinding.localBatchChipGroup.removeAllViews()
            dialogBinding.localBatchEmpty.isVisible = localBatchEntries.isEmpty()
            localBatchEntries.forEachIndexed { index, path ->
                val isVr = localBatchVrFlags.getOrNull(index) == true
                val displayName = buildString {
                    append(File(path).name)
                    if (isVr) {
                        append(" (")
                        append(getString(R.string.label_vr_flag))
                        append(")")
                    }
                }
                val chip = Chip(this).apply {
                    text = displayName
                    isCloseIconVisible = true
                    setOnClickListener {
                        applyLocalBatchEntry(path)
                    }
                    setOnCloseIconClickListener {
                        removeLocalBatchEntry(path)
                        refreshChips()
                    }
                    setOnLongClickListener {
                        val type = determineLocalType(File(path))
                        if (type != LocalContentType.VIDEO || !hasRotationSensor) {
                            return@setOnLongClickListener false
                        }
                        promptVrFlagForVideo(File(path).name) { newValue ->
                            if (index < localBatchVrFlags.size) {
                                localBatchVrFlags[index] = newValue
                                persistLocalBatchState()
                                refreshChips()
                            }
                        }
                        true
                    }
                }
                dialogBinding.localBatchChipGroup.addView(chip)
            }
        }

        dialogBinding.localBatchToggle.setOnCheckedChangeListener { _, isChecked ->
            localBatchEnabled = isChecked
            dialogBinding.localBatchOptions.isVisible = isChecked
            dialogBinding.localBatchIntervalLayout.isVisible =
                isChecked && localBatchMode == PlaylistMode.INTERVAL
        }
        dialogBinding.localBatchOrderGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            localBatchOrder = if (checkedId == R.id.localBatchOrderRandom) {
                PlaylistOrder.RANDOM
            } else {
                PlaylistOrder.SEQUENTIAL
            }
        }
        dialogBinding.localBatchModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            localBatchMode = if (checkedId == R.id.localBatchModeUnlock) {
                PlaylistMode.UNLOCK
            } else {
                PlaylistMode.INTERVAL
            }
            dialogBinding.localBatchIntervalLayout.isVisible =
                localBatchEnabled && localBatchMode == PlaylistMode.INTERVAL
        }

        dialogBinding.localBatchAddButton.setOnClickListener {
            openLocalBatchPicker()
        }

        refreshChips()
        localBatchDialogRefresh = { refreshChips() }

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                if (localBatchEnabled && localBatchMode == PlaylistMode.INTERVAL) {
                    val value = dialogBinding.localBatchIntervalInput.text?.toString()
                        ?.trim()?.toLongOrNull()
                    if (value == null || value <= 0) {
                        dialogBinding.localBatchIntervalLayout.error =
                            getString(R.string.toast_playlist_invalid_interval)
                        return@setOnClickListener
                    }
                    localBatchIntervalSeconds = value
                    dialogBinding.localBatchIntervalLayout.error = null
                } else {
                    dialogBinding.localBatchIntervalLayout.error = null
                }
                persistLocalBatchState()
                WallpaperPreferences.writeLocalBatchOrder(this, localBatchOrder)
                WallpaperPreferences.writeLocalBatchMode(this, localBatchMode)
                WallpaperPreferences.writeLocalBatchIntervalSeconds(this, localBatchIntervalSeconds)
                shouldApplyFirstEntryOnDismiss = localBatchEnabled
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            localBatchDialogRefresh = null
            if (shouldApplyFirstEntryOnDismiss) {
                shouldApplyFirstEntryOnDismiss = false
                applyFirstLocalBatchEntry()
            }
        }
        dialog.show()
    }

    private fun visitUrlFromInput() {
        var url = binding.urlInput.text?.toString().orEmpty().trim()
        url = normalizeUrlInput(url)
        if (isValidUrl(url)) {
            currentUrl = url
            binding.urlInput.setText(url)
            val newIndex = addOrUpdateWebEntry(url)
            currentWebIndex = newIndex
            setUrlDirty(false)
            loadPreview(url)
            if (!isLocalFileUrl(url)) {
                isLocalMode = false
                currentLocalType = LocalContentType.NONE
            }
            applyLocalConstraintsForCurrentType()
            updateModeUI()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_invalid_url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUrlAction() {
        if (isLocalMode) {
            if (currentLocalFile == null) {
                Toast.makeText(this, R.string.toast_no_local_file, Toast.LENGTH_SHORT).show()
            }
            openLocalFilePicker()
            return
        }
        if (urlDirty) {
            visitUrlFromInput()
        } else {
            loadPreview(currentUrl, forceReload = true)
        }
    }

    private fun normalizeUrlInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return trimmed
        val normalized = trimmed.lowercase(Locale.US)
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun isValidUrl(url: String): Boolean =
        url.isNotBlank() && (URLUtil.isNetworkUrl(url) || Patterns.WEB_URL.matcher(url).matches())

    private fun setUrlDirty(isDirty: Boolean) {
        urlDirty = isDirty
        val iconRes = if (isDirty) R.drawable.ic_visit else R.drawable.ic_refresh
        binding.urlRefreshButton.setImageResource(iconRes)
        binding.urlRefreshButton.contentDescription = getString(
            if (isDirty) R.string.url_visit else R.string.url_refresh
        )
    }

    private fun showPreviewLoading() {
        binding.previewProgress.isVisible = true
        binding.previewStatus.isVisible = false
        binding.previewStatus.text = getString(R.string.preview_loading)
    }

    private fun showPreviewContent() {
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        previewLoadTimeout = null
        binding.previewProgress.isVisible = false
        binding.previewStatus.isVisible = false
    }

    private fun showPreviewError(message: String) {
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        previewLoadTimeout = null
        binding.previewProgress.isVisible = false
        binding.previewStatus.text = message
        binding.previewStatus.isVisible = true
    }

    private fun showOptionsDialog() {
        val dialogBinding = DialogOptionsBinding.inflate(layoutInflater)
        val localImage = isLocalMode && currentLocalType == LocalContentType.IMAGE
        val localVideo = isLocalMode && currentLocalType == LocalContentType.VIDEO
        val remoteVideo = isRemoteVideoPreviewActive()

        dialogBinding.dialogInteractionSwitch.isChecked = allowInteraction
        dialogBinding.dialogMediaSwitch.isChecked = allowMedia
        dialogBinding.dialogAutoRefreshSwitch.isChecked = enableAutoRefresh
        when (imageDisplayMode) {
            ImageDisplayMode.COVER -> dialogBinding.dialogImageDisplayGroup.check(R.id.dialogImageDisplayCover)
            ImageDisplayMode.CONTAIN -> dialogBinding.dialogImageDisplayGroup.check(R.id.dialogImageDisplayContain)
            ImageDisplayMode.STRETCH -> dialogBinding.dialogImageDisplayGroup.check(R.id.dialogImageDisplayStretch)
        }
        dialogBinding.dialogInteractionSwitch.isEnabled = !localImage && !localVideo && !remoteVideo
        dialogBinding.dialogMediaSwitch.isEnabled = !localImage
        dialogBinding.dialogMediaSwitch.text = if (localVideo || remoteVideo) {
            getString(R.string.switch_play_audio)
        } else {
            getString(R.string.switch_allow_media)
        }
        dialogBinding.dialogAutoRefreshSwitch.isEnabled = !isLocalMode || currentLocalType == LocalContentType.HTML
        if (localImage) {
            dialogBinding.dialogInteractionSwitch.isChecked = false
            dialogBinding.dialogMediaSwitch.isChecked = false
            dialogBinding.dialogAutoRefreshSwitch.isChecked = false
        } else if (localVideo) {
            dialogBinding.dialogInteractionSwitch.isChecked = false
            dialogBinding.dialogAutoRefreshSwitch.isChecked = false
        } else if (remoteVideo) {
            dialogBinding.dialogInteractionSwitch.isChecked = false
        }
        dialogBinding.dialogRefreshIntervalLayout.isVisible =
            dialogBinding.dialogAutoRefreshSwitch.isChecked && dialogBinding.dialogAutoRefreshSwitch.isEnabled
        if (enableAutoRefresh) {
            dialogBinding.dialogRefreshIntervalInput.setText(refreshIntervalSeconds.toString())
        }
        dialogBinding.dialogAutoRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.dialogRefreshIntervalLayout.isVisible =
                isChecked && dialogBinding.dialogAutoRefreshSwitch.isEnabled
        }
        val supportsVr = hasRotationSensor &&
            isLocalMode &&
            currentLocalType == LocalContentType.VIDEO
        dialogBinding.dialogVrSwitch.isEnabled = supportsVr
        dialogBinding.dialogVrSwitch.isChecked = supportsVr && vrGlobalEnabled
        dialogBinding.dialogVrGestureSwitch.isChecked = supportsVr && vrGlobalEnabled
        dialogBinding.dialogVrSwitch.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.dialogVrGestureSwitch.isChecked = supportsVr && isChecked
        }
        dialogBinding.dialogGithubLink.setOnClickListener {
            openExternalLink(GITHUB_URL)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val autoSwitchEnabled = dialogBinding.dialogAutoRefreshSwitch.isEnabled
                val autoEnabled = autoSwitchEnabled && dialogBinding.dialogAutoRefreshSwitch.isChecked
                val intervalText = dialogBinding.dialogRefreshIntervalInput.text?.toString()?.trim()
                val intervalValue = intervalText?.toLongOrNull()
                if (autoEnabled && (intervalValue == null || intervalValue <= 0)) {
                    dialogBinding.dialogRefreshIntervalLayout.error =
                        getString(R.string.toast_invalid_interval)
                    return@setOnClickListener
                }
                dialogBinding.dialogRefreshIntervalLayout.error = null
                val interactionSwitchEnabled = dialogBinding.dialogInteractionSwitch.isEnabled
                val mediaSwitchEnabled = dialogBinding.dialogMediaSwitch.isEnabled
                allowInteraction = if (interactionSwitchEnabled) {
                    dialogBinding.dialogInteractionSwitch.isChecked
                } else {
                    false
                }
                allowMedia = if (mediaSwitchEnabled) {
                    dialogBinding.dialogMediaSwitch.isChecked
                } else {
                    allowMedia && !isLocalMode
                }
                enableAutoRefresh = autoEnabled
                if (autoEnabled && intervalValue != null) {
                    refreshIntervalSeconds = intervalValue
                }
                val vrEnabled = supportsVr && dialogBinding.dialogVrSwitch.isChecked
                val vrSensor = vrEnabled
                val vrGesture = vrEnabled
                val selectedImageDisplayMode = when (dialogBinding.dialogImageDisplayGroup.checkedButtonId) {
                    R.id.dialogImageDisplayContain -> ImageDisplayMode.CONTAIN
                    R.id.dialogImageDisplayStretch -> ImageDisplayMode.STRETCH
                    else -> ImageDisplayMode.COVER
                }
                val imageDisplayModeChanged = selectedImageDisplayMode != imageDisplayMode
                imageDisplayMode = selectedImageDisplayMode
                vrGlobalEnabled = vrEnabled
                vrSensorEnabled = vrSensor
                vrGestureEnabled = vrGesture
                applyPreviewBehavior()
                if (isLocalMode && currentLocalType == LocalContentType.VIDEO) {
                    regenerateLocalWrapper()
                } else if (imageDisplayModeChanged && (!isLocalMode || currentLocalType == LocalContentType.IMAGE)) {
                    if (isLocalMode) {
                        regenerateLocalWrapper()
                    } else {
                        loadPreview(currentUrl, forceReload = true)
                    }
                }
                dialog.dismiss()
                if (
                    vrEnabled &&
                    isLocalMode &&
                    currentLocalType == LocalContentType.VIDEO &&
                    currentLocalFile != null &&
                    !currentLocalIsVr
                ) {
                    val fileName = currentLocalFile?.name ?: return@setOnClickListener
                    promptVrFlagForVideo(fileName) { isVr ->
                        currentLocalIsVr = isVr
                        val index = localBatchEntries.indexOf(currentLocalFile?.absolutePath)
                        if (index != -1 && index < localBatchVrFlags.size) {
                            localBatchVrFlags[index] = isVr
                            persistLocalBatchState()
                        }
                        applyPreviewBehavior()
                        loadPreview(currentUrl, forceReload = true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toggleMode() {
        isLocalMode = !isLocalMode
        syncBatchToggleStateForCurrentMode()
        clearAddressField()
        renderCurrentModeAfterSwitch()
    }

    private fun syncBatchToggleStateForCurrentMode() {
        val lastAppliedModeLocal = WallpaperPreferences.readLastModeIsLocal(this)
        if (isLocalMode) {
            if (lastAppliedModeLocal) {
                localBatchEnabled = WallpaperPreferences.readLocalBatchEnabled(this)
            } else {
                localBatchEnabled = false
            }
            return
        }
        if (!lastAppliedModeLocal) {
            webBatchEnabled = WallpaperPreferences.readWebPlaylistEnabled(this)
        } else {
            webBatchEnabled = false
        }
    }

    private fun clearAddressField() {
        binding.urlInput.text?.clear()
        setUrlDirty(true)
    }

    private fun updateModeUI() {
        binding.urlTrailingActions.isVisible = true
        binding.urlRefreshButton.isVisible = !isLocalMode
        binding.urlBatchButton.isVisible = !isLocalMode
        binding.localBatchButton.isVisible = isLocalMode
        if (isLocalMode) {
            binding.modeButton.text = getString(R.string.mode_local)
            binding.modeButton.setIconResource(R.drawable.ic_folder)
            binding.urlInputLayout.hint = getString(R.string.local_file_hint)
            binding.urlInput.isEnabled = false
            binding.urlInput.isFocusable = false
            binding.urlInput.isFocusableInTouchMode = false
            binding.urlInput.isClickable = true
            binding.urlInput.setOnClickListener { openLocalFilePicker() }
            binding.urlInputLayout.startIconDrawable =
                ContextCompat.getDrawable(this, R.drawable.ic_folder)
            binding.urlInputLayout.startIconContentDescription =
                getString(R.string.action_pick_local)
            binding.urlInputLayout.setStartIconOnClickListener { openLocalFilePicker() }
        } else {
            binding.modeButton.text = getString(R.string.mode_web)
            binding.modeButton.setIconResource(R.drawable.ic_globe)
            binding.urlInputLayout.hint = getString(R.string.url_hint)
            binding.urlInput.isEnabled = true
            binding.urlInput.isFocusable = true
            binding.urlInput.isFocusableInTouchMode = true
            binding.urlInput.isClickable = true
            binding.urlInput.setOnClickListener(null)
            binding.urlInputLayout.startIconDrawable =
                ContextCompat.getDrawable(this, R.drawable.ic_clear_text)
            binding.urlInputLayout.startIconContentDescription =
                getString(R.string.cd_clear_url)
            binding.urlInputLayout.setStartIconOnClickListener { binding.urlInput.setText("") }
        }
    }

    private fun applyLocalConstraintsForCurrentType() {
        when (currentLocalType) {
            LocalContentType.IMAGE -> {
                if (allowInteraction) {
                    allowInteraction = false
                }
                if (allowMedia) {
                    allowMedia = false
                }
                if (enableAutoRefresh) {
                    enableAutoRefresh = false
                }
            }
            LocalContentType.VIDEO -> {
                if (allowInteraction) {
                    allowInteraction = false
                }
                if (enableAutoRefresh) {
                    enableAutoRefresh = false
                }
            }
            LocalContentType.HTML -> {
            }
            else -> {}
        }
        applyPreviewBehavior()
    }

    private fun resetOptionsForNewLocalVideo() {
        allowInteraction = WallpaperPreferences.DEFAULT_ALLOW_INTERACTION
        allowMedia = WallpaperPreferences.DEFAULT_ALLOW_MEDIA
        enableAutoRefresh = WallpaperPreferences.DEFAULT_AUTO_REFRESH
        refreshIntervalSeconds = WallpaperPreferences.DEFAULT_REFRESH_INTERVAL_SECONDS
        vrGlobalEnabled = if (hasRotationSensor) {
            WallpaperPreferences.DEFAULT_VR_GLOBAL_ENABLED
        } else {
            false
        }
        vrSensorEnabled = vrGlobalEnabled
        vrGestureEnabled = vrGlobalEnabled
    }

    private fun detectLocalTypeFromUrl(url: String): LocalContentType {
        if (!isLocalFileUrl(url)) return LocalContentType.NONE
        val path = Uri.parse(url).path ?: return LocalContentType.NONE
        return determineLocalType(File(path))
    }

    private fun isLocalFileUrl(url: String): Boolean =
        runCatching { Uri.parse(url).scheme.equals("file", ignoreCase = true) }
            .getOrDefault(false)

    private fun openLocalFilePicker() {
        pickLocalFileLauncher.launch(
            arrayOf(
                "image/*",
                "video/*",
                "text/html",
                "application/xhtml+xml"
            )
        )
    }

    private fun openLocalBatchPicker() {
        pickLocalBatchLauncher.launch(
            arrayOf(
                "image/*",
                "video/*",
                "text/html",
                "application/xhtml+xml"
            )
        )
    }

    private fun handlePickedFile(uri: Uri) {
        try {
            val file = copyUriToLocalFile(uri)
            if (file == null) {
                Toast.makeText(this, R.string.toast_file_pick_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val type = determineLocalType(file)
            if (type == LocalContentType.VIDEO) {
                resetOptionsForNewLocalVideo()
            }
            resolveVrFlagForType(type, file) { isVr ->
                Log.d("MainActivity", "handlePickedFile type=$type path=${file.absolutePath} markVr=$isVr")
                currentLocalIsVr = isVr
                isLocalMode = true
                updateLocalSource(file, type)
                Toast.makeText(this, R.string.toast_local_file_loaded, Toast.LENGTH_SHORT).show()
            }
        } catch (_: IOException) {
            Toast.makeText(this, R.string.toast_file_pick_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleBatchPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        processBatchUris(uris, 0, 0, null)
    }

    private fun processBatchUris(
        uris: List<Uri>,
        index: Int,
        success: Int,
        firstNewPath: String?
    ) {
        if (index >= uris.size) {
            if (success > 0) {
                persistLocalBatchState()
                Toast.makeText(this, R.string.toast_local_file_loaded, Toast.LENGTH_SHORT).show()
                localBatchDialogRefresh?.invoke()
                firstNewPath?.let { applyLocalBatchEntry(it) }
            } else {
                Toast.makeText(this, R.string.toast_file_pick_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val uri = uris[index]
        try {
            val file = copyUriToLocalFile(uri)
            if (file == null) {
                processBatchUris(uris, index + 1, success, firstNewPath)
                return
            }
            val path = file.absolutePath
            if (localBatchEntries.contains(path)) {
                file.delete()
                processBatchUris(uris, index + 1, success, firstNewPath)
                return
            }
            val type = determineLocalType(file)
            resolveVrFlagForType(type, file) { isVr ->
                localBatchEntries.add(path)
                localBatchVrFlags.add(isVr)
                Log.d("MainActivity", "batchAdd path=$path isVr=$isVr")
                val updatedFirst = firstNewPath ?: path
                processBatchUris(uris, index + 1, success + 1, updatedFirst)
            }
        } catch (_: IOException) {
            processBatchUris(uris, index + 1, success, firstNewPath)
        }
    }

    private fun copyUriToLocalFile(uri: Uri): File? {
        val mimeType = contentResolver.getType(uri)
        val originalName = queryDisplayName(uri)
        val extension = guessExtension(mimeType, originalName)
        val safeBase = (originalName?.substringBeforeLast('.', "") ?: "local_${System.currentTimeMillis()}")
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .ifBlank { "local_${System.currentTimeMillis()}" }
        val finalName = if (originalName?.contains('.') == true) originalName else "$safeBase$extension"
        val destDir = File(filesDir, "local_content").apply { if (!exists()) mkdirs() }
        val destFile = File(destDir, "src_${System.currentTimeMillis()}_$finalName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        return destFile
    }

    private fun persistLocalBatchState() {
        if (localBatchVrFlags.size != localBatchEntries.size) {
            if (localBatchVrFlags.size < localBatchEntries.size) {
                repeat(localBatchEntries.size - localBatchVrFlags.size) {
                    localBatchVrFlags.add(false)
                }
            } else {
                while (localBatchVrFlags.size > localBatchEntries.size) {
                    localBatchVrFlags.removeAt(localBatchVrFlags.lastIndex)
                }
            }
        }
        WallpaperPreferences.writeLocalBatch(this, localBatchEntries)
        WallpaperPreferences.writeLocalBatchVrFlags(this, localBatchVrFlags)
    }

    private fun resolveVrFlagForType(
        type: LocalContentType,
        file: File,
        onResult: (Boolean) -> Unit
    ) {
        if (type != LocalContentType.VIDEO || !hasRotationSensor) {
            onResult(false)
            return
        }
        if (!vrGlobalEnabled) {
            onResult(false)
            return
        }
        promptVrFlagForVideo(file.name, onResult)
    }

    private fun promptVrFlagForVideo(fileName: String, onResult: (Boolean) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_mark_vr_title)
            .setMessage(getString(R.string.dialog_mark_vr_message, fileName))
            .setPositiveButton(R.string.dialog_mark_vr_positive) { dialog, _ ->
                dialog.dismiss()
                onResult(true)
            }
            .setNegativeButton(R.string.dialog_mark_vr_negative) { dialog, _ ->
                dialog.dismiss()
                onResult(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun removeLocalBatchEntry(path: String) {
        val index = localBatchEntries.indexOf(path)
        if (index != -1) {
            localBatchEntries.removeAt(index)
            if (index < localBatchVrFlags.size) {
                localBatchVrFlags.removeAt(index)
            }
            persistLocalBatchState()
        }
    }

    private fun applyLocalBatchEntry(path: String) {
        val file = File(path)
        if (!file.exists()) {
            removeLocalBatchEntry(path)
            Toast.makeText(this, R.string.toast_file_pick_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val type = determineLocalType(file)
        val index = localBatchEntries.indexOf(path)
        val isVr = localBatchVrFlags.getOrNull(index) == true
        currentLocalIsVr = isVr
        isLocalMode = true
        Log.d(
            "MainActivity",
            "applyLocalBatchEntry preview path=$path type=$type isVr=$isVr"
        )
        updateLocalSource(file, type)
        updateModeUI()
    }

    private fun applyFirstLocalBatchEntry(): Boolean {
        val firstExistingPath = localBatchEntries.firstOrNull { File(it).exists() }
        if (firstExistingPath == null) {
            localBatchEntries.filterNot { File(it).exists() }.forEach { removeLocalBatchEntry(it) }
            return false
        }
        applyLocalBatchEntry(firstExistingPath)
        Log.d("MainActivity", "applyFirstLocalBatchEntry path=$firstExistingPath")
        return true
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }

    private fun guessExtension(mimeType: String?, originalName: String?): String {
        val existing = originalName?.substringAfterLast('.', "")
        if (!existing.isNullOrBlank()) {
            return ".${existing}"
        }
        return when {
            mimeType?.contains("gif", ignoreCase = true) == true -> ".gif"
            mimeType?.contains("webp", ignoreCase = true) == true -> ".webp"
            mimeType?.startsWith("video/", ignoreCase = true) == true -> ".mp4"
            mimeType?.contains("html", ignoreCase = true) == true -> ".html"
            else -> ".dat"
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

    private fun ensureLocalUrlForCurrentFile(
        forceNewWrapper: Boolean = false,
        persistUrl: Boolean = false
    ) {
        val file = currentLocalFile ?: return
        currentUrl = when (currentLocalType) {
            LocalContentType.VIDEO -> {
                currentLocalWrapper = null
                file.toURI().toString()
            }
            LocalContentType.HTML -> {
                currentLocalWrapper = file
                file.toURI().toString()
            }
            LocalContentType.IMAGE -> {
                val wrapper = if (!forceNewWrapper) {
                    currentLocalWrapper?.takeIf { it.exists() }
                } else {
                    null
                } ?: createWrapperForLocalFile(file, currentLocalType)
                currentLocalWrapper = wrapper
                wrapper?.toURI()?.toString() ?: file.toURI().toString()
            }
            else -> file.toURI().toString()
        }
        if (persistUrl) {
            WallpaperPreferences.writeUrl(this, currentUrl)
        }
    }

    private fun updateLocalSource(file: File, type: LocalContentType) {
        currentLocalFile = file
        currentLocalType = type
        ensureLocalUrlForCurrentFile(forceNewWrapper = true, persistUrl = false)
        applyLocalConstraintsForCurrentType()
        if (isLocalMode) {
            binding.urlInput.setText(file.name)
            setUrlDirty(false)
        }
        updateModeUI()
        loadPreview(currentUrl, forceReload = true)
    }

    private fun stagePendingConfiguration() {
        val filePath = currentLocalFile?.absolutePath
        val pending = WallpaperPreferences.PendingState(
            token = System.currentTimeMillis(),
            url = currentUrl,
            imageDisplayMode = imageDisplayMode,
            allowInteraction = allowInteraction,
            allowMedia = allowMedia,
            autoRefresh = enableAutoRefresh,
            refreshIntervalSeconds = refreshIntervalSeconds,
            lastModeLocal = isLocalMode,
            localFilePath = filePath,
            localFileType = currentLocalType.ordinal,
            localIsVr = currentLocalIsVr,
            vrGlobalEnabled = vrGlobalEnabled,
            vrSensorEnabled = vrSensorEnabled,
            vrGestureEnabled = vrGestureEnabled,
            webPlaylistEnabled = if (isLocalMode) {
                WallpaperPreferences.readWebPlaylistEnabled(this)
            } else {
                webBatchEnabled
            },
            localBatchEnabled = if (isLocalMode) {
                localBatchEnabled
            } else {
                WallpaperPreferences.readLocalBatchEnabled(this)
            },
            wallpaperAlreadyActive = isWebWallpaperActive()
        )
        WallpaperPreferences.writePendingState(this, pending)
        Log.d(
            "MainActivity",
            "stagePendingConfiguration token=${pending.token} url=${pending.url} localPath=$filePath type=$currentLocalType isVr=$currentLocalIsVr active=${pending.wallpaperAlreadyActive}"
        )
    }

    private fun isWebWallpaperActive(): Boolean {
        val activeComponent = WallpaperManager.getInstance(this).wallpaperInfo?.component
        val expected = ComponentName(this, WebWallpaperService::class.java)
        return activeComponent == expected
    }

    private fun createWrapperForLocalFile(file: File, type: LocalContentType): File? {
        if (type != LocalContentType.IMAGE) return null
        val dir = File(filesDir, "local_content").apply { if (!exists()) mkdirs() }
        currentLocalWrapper?.let {
            if (it.exists() && it.absolutePath != file.absolutePath) {
                it.delete()
            }
        }
        val wrapperName = "wrapper_${type.name.lowercase()}_${System.currentTimeMillis()}.html"
        val wrapperFile = File(dir, wrapperName)
        val sourceUri = file.toURI().toString()
        val html = ImageWallpaperSupport.buildImageHtml(sourceUri, imageDisplayMode)
        wrapperFile.writeText(html, Charsets.UTF_8)
        return wrapperFile
    }

    private fun regenerateLocalWrapper() {
        val file = currentLocalFile ?: return
        when (currentLocalType) {
            LocalContentType.HTML -> {
                loadPreview(currentUrl, forceReload = true)
            }
            LocalContentType.VIDEO -> {
                currentLocalWrapper = null
                currentUrl = file.toURI().toString()
                loadPreview(currentUrl, forceReload = true)
            }
            else -> {
                val wrapper = createWrapperForLocalFile(file, currentLocalType) ?: return
                currentLocalWrapper = wrapper
                currentUrl = wrapper.toURI().toString()
                loadPreview(currentUrl, forceReload = true)
            }
        }
        if (isLocalMode) {
            binding.urlInput.setText(file.name)
            setUrlDirty(false)
        }
    }

    private fun openExternalLink(url: String) {
        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(linkIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_open_link_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, WebWallpaperService::class.java)
        val changeIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            Log.d("MainActivity", "openWallpaperPicker action=${changeIntent.action} component=$component")
            wallpaperPickerLauncher.launch(changeIntent)
        } catch (_: ActivityNotFoundException) {
            val chooserIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            try {
                Log.d("MainActivity", "openWallpaperPicker fallback action=${chooserIntent.action}")
                wallpaperPickerLauncher.launch(chooserIntent)
            } catch (_: ActivityNotFoundException) {
                WallpaperPreferences.clearPendingState(this)
                Toast.makeText(this, R.string.toast_wallpaper_intent_missing, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun handleWallpaperPickerResult(resultCode: Int) {
        val pending = WallpaperPreferences.readPendingState(this)
        val activeComponent = WallpaperManager.getInstance(this).wallpaperInfo?.component
        val expected = ComponentName(this, WebWallpaperService::class.java)
        Log.d(
            "MainActivity",
            "handleWallpaperPickerResult resultCode=$resultCode hasPending=${pending != null} activeMatches=${activeComponent == expected} activeComponent=$activeComponent"
        )
        val pendingState = pending ?: return
        if (resultCode == RESULT_OK) {
            WallpaperPreferences.applyPendingState(this)
            Log.d(
                "MainActivity",
                "handleWallpaperPickerResult applied token=${pendingState.token} url=${pendingState.url}"
            )
        } else if (!pendingState.wallpaperAlreadyActive && activeComponent == expected) {
            WallpaperPreferences.applyPendingState(this)
            Log.d(
                "MainActivity",
                "handleWallpaperPickerResult fallback applied token=${pendingState.token} url=${pendingState.url}"
            )
        } else {
            mainHandler.postDelayed({
                val remaining = WallpaperPreferences.readPendingState(this)
                if (remaining?.token == pendingState.token) {
                    WallpaperPreferences.clearPendingState(this)
                    Log.d(
                        "MainActivity",
                        "handleWallpaperPickerResult cleared token=${pendingState.token} activeBefore=${pendingState.wallpaperAlreadyActive}"
                    )
                }
            }, 800)
            Log.d(
                "MainActivity",
                "handleWallpaperPickerResult deferred cleanup token=${pendingState.token} activeBefore=${pendingState.wallpaperAlreadyActive}"
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumePreviewWebView("onResume")
        localVideoPlayer.onResume()
        remotePreviewPlayer.onResume()
        binding.vrPreviewSurface.onResume()
        ensureVideoPreviewPlaying()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            resumePreviewWebView("onWindowFocusChanged")
        }
    }

    override fun onPause() {
        resumePlaybackCheck?.let { previewHandler.removeCallbacks(it) }
        resumePlaybackCheck = null
        cancelPreviewStabilizationTasks()
        binding.vrPreviewSurface.onPause()
        localVideoPlayer.onPause()
        remotePreviewPlayer.onPause()
        binding.previewWebView.onPause()
        binding.previewWebView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        resumePlaybackCheck?.let { previewHandler.removeCallbacks(it) }
        resumePlaybackCheck = null
        previewLoadTimeout?.let { previewHandler.removeCallbacks(it) }
        previewLoadTimeout = null
        cancelPendingPreviewLoadTask()
        cancelPreviewStabilizationTasks()
        localVideoPlayer.release()
        remotePreviewPlayer.release()
        val previewParent = binding.previewWebView.parent as? ViewGroup
        previewParent?.removeView(binding.previewWebView)
        binding.previewWebView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/fish2018"
    }

    private fun ensureVideoPreviewPlaying() {
        val localVideoFile = currentLocalFile
        val localVideoActive =
            isLocalMode &&
                currentLocalType == LocalContentType.VIDEO &&
                localVideoFile?.exists() == true
        val remoteVideoUrl = currentRemoteVideoUrl
        if (!localVideoActive && remoteVideoUrl.isNullOrBlank()) {
            return
        }
        resumePlaybackCheck?.let { previewHandler.removeCallbacks(it) }
        val task = Runnable {
            resumePlaybackCheck = null
            if (isFinishing || isDestroyed) return@Runnable
            val stillLocalVideo =
                isLocalMode &&
                    currentLocalType == LocalContentType.VIDEO &&
                    currentLocalFile?.absolutePath == localVideoFile?.absolutePath
            val stillRemoteVideo = !isLocalMode && currentRemoteVideoUrl == remoteVideoUrl
            if (!stillLocalVideo && !stillRemoteVideo) return@Runnable
            val needsRestart = if (stillLocalVideo) {
                localVideoPlayer.needsPlaybackRestart()
            } else {
                remotePreviewPlayer.needsPlaybackRestart()
            }
            if (needsRestart) {
                Log.d(
                    "MainActivity",
                    "ensureVideoPreviewPlaying restart currentUrl=$currentUrl local=$stillLocalVideo remote=$stillRemoteVideo"
                )
                loadPreview(currentUrl, forceReload = true)
            }
        }
        resumePlaybackCheck = task
        previewHandler.postDelayed(task, 250)
    }
}
