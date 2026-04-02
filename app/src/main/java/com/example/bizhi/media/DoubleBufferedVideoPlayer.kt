package com.example.bizhi.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.core.view.isVisible
import com.google.android.exoplayer2.PlaybackException

class DoubleBufferedVideoPlayer(
    private val context: Context,
    private val primaryView: TextureView,
    private val secondaryView: TextureView,
    private val callback: Callback? = null,
    private val logTag: String = "DoubleBufferedVideoPlayer"
) {

    interface Callback {
        fun onInitialBuffering()
        fun onDisplayed(sourceLabel: String)
        fun onError(error: PlaybackException)
    }

    private inner class Slot(
        val index: Int,
        val name: String,
        val view: TextureView
    ) {
        var requestToken: Long = 0L
        val player = LocalVideoPlayer(
            context,
            view,
            object : LocalVideoPlayer.Callback {
                override fun onBuffering() {
                    if (activeIndex == null && pendingIndex == index && requestToken == switchToken) {
                        callback?.onInitialBuffering()
                    }
                }

                override fun onReady() {}

                override fun onFirstFrameRendered() {
                    if (pendingIndex == index && requestToken == switchToken) {
                        promote(index)
                    }
                }

                override fun onError(error: PlaybackException) {
                    handleSlotError(index, error)
                }
            },
            "$logTag-$name"
        )
    }

    private val slots = arrayOf(
        Slot(index = 0, name = "A", view = primaryView),
        Slot(index = 1, name = "B", view = secondaryView)
    )

    private var activeIndex: Int? = null
    private var pendingIndex: Int? = null
    private var switchToken: Long = 0L
    private var activeSourceLabel: String? = null
    private var pendingSourceLabel: String? = null
    private var audioEnabled: Boolean = false

    init {
        hideAllViews()
    }

    fun play(source: Uri, playAudio: Boolean, sourceLabel: String = source.toString()) {
        audioEnabled = playAudio
        if (activeSourceLabel == sourceLabel && pendingIndex == null) {
            updateAudio(playAudio)
            return
        }

        switchToken += 1
        val targetIndex = when {
            activeIndex == null && pendingIndex != null -> pendingIndex!!
            activeIndex == null -> 0
            else -> 1 - activeIndex!!
        }
        val target = slots[targetIndex]
        target.requestToken = switchToken
        pendingIndex = targetIndex
        pendingSourceLabel = sourceLabel

        Log.d(
            logTag,
            "play token=$switchToken source=$sourceLabel active=$activeIndex pending=$targetIndex"
        )

        activeIndex?.let { active ->
            slots[active].player.updateAudio(audioEnabled)
        }

        target.view.isVisible = true
        target.view.alpha = 0f
        target.view.bringToFront()
        target.player.updateAudio(false)
        target.player.prepare(source, false)
    }

    fun updateAudio(enabled: Boolean) {
        audioEnabled = enabled
        activeIndex?.let { slots[it].player.updateAudio(enabled) }
        pendingIndex?.let { slots[it].player.updateAudio(false) }
    }

    fun onResume() {
        slots.forEach { it.player.onResume() }
    }

    fun onPause() {
        slots.forEach { it.player.onPause() }
    }

    fun release() {
        switchToken += 1
        activeIndex = null
        pendingIndex = null
        activeSourceLabel = null
        pendingSourceLabel = null
        slots.forEach { slot ->
            slot.requestToken = 0L
            slot.player.release()
        }
        hideAllViews()
    }

    fun needsPlaybackRestart(): Boolean {
        val targetIndex = activeIndex ?: pendingIndex ?: return true
        return slots[targetIndex].player.needsPlaybackRestart()
    }

    fun hasActivePlayback(): Boolean = activeIndex != null

    fun getActiveSourceLabel(): String? = activeSourceLabel

    private fun promote(index: Int) {
        val sourceLabel = pendingSourceLabel ?: return
        val previousIndex = activeIndex
        val activeSlot = slots[index]

        Log.d(
            logTag,
            "promote token=$switchToken source=$sourceLabel previous=$previousIndex next=$index"
        )

        activeIndex = index
        pendingIndex = null
        activeSourceLabel = sourceLabel
        pendingSourceLabel = null

        activeSlot.view.isVisible = true
        activeSlot.view.alpha = 1f
        activeSlot.view.bringToFront()
        activeSlot.player.updateAudio(audioEnabled)

        slots.forEachIndexed { slotIndex, slot ->
            if (slotIndex == index) return@forEachIndexed
            slot.player.updateAudio(false)
            slot.view.alpha = 0f
            if (slotIndex == previousIndex) {
                slot.player.release()
            } else {
                slot.view.isVisible = false
            }
        }

        callback?.onDisplayed(sourceLabel)
    }

    private fun handleSlotError(index: Int, error: PlaybackException) {
        val slot = slots[index]
        if (pendingIndex != index || slot.requestToken != switchToken) {
            return
        }

        Log.e(logTag, "handleSlotError token=$switchToken index=$index active=$activeIndex", error)
        pendingIndex = null
        pendingSourceLabel = null
        slot.player.release()
        slot.view.alpha = 0f
        slot.view.isVisible = false

        if (activeIndex == null) {
            callback?.onError(error)
        }
    }

    private fun hideAllViews() {
        primaryView.alpha = 0f
        primaryView.isVisible = false
        secondaryView.alpha = 0f
        secondaryView.isVisible = false
    }
}
