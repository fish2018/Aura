package com.example.bizhi.media

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.core.view.isVisible
import com.example.bizhi.vr.VrVideoSurfaceView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.video.spherical.CameraMotionListener
import java.io.File

class LocalVideoPlayer(
    private val context: Context,
    private val textureView: TextureView,
    private val callback: Callback? = null,
    private val logTag: String = "LocalVideoPlayer"
) : Player.Listener {

    interface Callback {
        fun onBuffering()
        fun onReady()
        fun onFirstFrameRendered()
        fun onError(error: PlaybackException)
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.CONTENT_TYPE_MOVIE)
        .build()

    private var player: ExoPlayer? = null
    private var audioEnabled = false
    private var externalSurface: Surface? = null
    private var externalSurfaceView: View? = null
    private var videoFrameMetadataListener: VideoFrameMetadataListener? = null
    private var cameraMotionListener: CameraMotionListener? = null
    private var lastVideoSize: VideoSize? = null

    fun prepare(source: File, playAudio: Boolean) {
        prepare(Uri.fromFile(source), playAudio, source.absolutePath)
    }

    fun prepare(source: Uri, playAudio: Boolean) {
        prepare(source, playAudio, source.toString())
    }

    private fun prepare(source: Uri, playAudio: Boolean, sourceLabel: String) {
        Log.d(
            logTag,
            "prepare source=$sourceLabel playAudio=$playAudio externalSurface=${externalSurface != null}"
        )
        ensurePlayer()
        audioEnabled = playAudio
        callback?.onBuffering()
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(source))
            applyRequestedVolume()
            prepare()
            playWhenReady = true
        }
        applyVideoOutput()
    }

    fun updateAudio(enabled: Boolean) {
        audioEnabled = enabled
        applyRequestedVolume()
    }

    fun onResume() {
        player?.playWhenReady = true
    }

    fun onPause() {
        player?.playWhenReady = false
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun needsPlaybackRestart(): Boolean {
        val currentPlayer = player ?: return true
        return when {
            currentPlayer.isPlaying -> false
            currentPlayer.playbackState == Player.STATE_IDLE -> true
            currentPlayer.playbackState == Player.STATE_ENDED -> true
            currentPlayer.playbackState == Player.STATE_READY && !currentPlayer.playWhenReady -> true
            currentPlayer.playbackState == Player.STATE_READY && !currentPlayer.isPlaying -> true
            else -> false
        }
    }

    fun release() {
        player?.removeListener(this)
        updateSphericalListeners(null)
        player?.release()
        player = null
        textureView.visibility = View.GONE
        externalSurfaceView?.isVisible = false
        externalSurface = null
        externalSurfaceView = null
    }

    fun setExternalVideoSurface(surface: Surface?, renderView: View? = null) {
        externalSurface = surface
        externalSurfaceView = renderView
        updateSphericalListeners(renderView)
        applyVideoOutput()
    }

    fun clearExternalVideoSurface(surface: Surface?) {
        if (surface != null && surface == externalSurface) {
            player?.clearVideoSurface(surface)
            externalSurface = null
            externalSurfaceView?.isVisible = false
            updateSphericalListeners(null)
            applyVideoOutput()
        }
    }

    private fun ensurePlayer() {
        if (player != null) return
        Log.d(logTag, "Creating ExoPlayer instance")
        player = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(audioAttributes, true)
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(this@LocalVideoPlayer)
            playWhenReady = true
        }
        updateSphericalListeners(externalSurfaceView)
    }

    private fun applyRequestedVolume(target: ExoPlayer? = player) {
        val volume = if (audioEnabled) 1f else 0f
        target?.volume = volume
        target?.audioComponent?.volume = volume
    }

    private fun applyVideoOutput() {
        val player = player ?: return
        val surface = externalSurface
        if (surface != null) {
            Log.d(logTag, "applyVideoOutput -> external surface")
            textureView.isVisible = false
            externalSurfaceView?.isVisible = true
            player.setVideoSurface(surface)
        } else {
            Log.d(logTag, "applyVideoOutput -> texture view")
            externalSurfaceView?.isVisible = false
            textureView.isVisible = true
            player.setVideoTextureView(textureView)
            lastVideoSize?.let { size ->
                textureView.post { applyTextureViewTransform(size) }
            }
        }
    }

    private fun applyTextureViewTransform(videoSize: VideoSize) {
        if (externalSurface != null) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val matrix = Matrix()
        matrix.reset()
        textureView.setTransform(matrix)
    }

    private fun updateSphericalListeners(renderView: View?) {
        videoFrameMetadataListener?.let { listener ->
            player?.clearVideoFrameMetadataListener(listener)
        }
        cameraMotionListener?.let { listener ->
            player?.clearCameraMotionListener(listener)
        }
        videoFrameMetadataListener = null
        cameraMotionListener = null
        if (renderView is VrVideoSurfaceView) {
            Log.d(logTag, "Attaching VR metadata listeners")
            videoFrameMetadataListener = renderView.videoFrameMetadataListener
            cameraMotionListener = renderView.cameraMotionListener
            videoFrameMetadataListener?.let { listener ->
                player?.setVideoFrameMetadataListener(listener)
            }
            cameraMotionListener?.let { listener ->
                player?.setCameraMotionListener(listener)
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d(logTag, "onPlaybackStateChanged state=$playbackState")
        when (playbackState) {
            Player.STATE_BUFFERING -> callback?.onBuffering()
            Player.STATE_READY -> callback?.onReady()
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        lastVideoSize = videoSize
        textureView.post { applyTextureViewTransform(videoSize) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d(
            logTag,
            "onIsPlayingChanged isPlaying=$isPlaying playWhenReady=${player?.playWhenReady} state=${player?.playbackState}"
        )
    }

    override fun onRenderedFirstFrame() {
        Log.d(logTag, "onRenderedFirstFrame")
        callback?.onFirstFrameRendered()
    }

    override fun onPlayerError(error: PlaybackException) {
        callback?.onError(error)
    }
}
