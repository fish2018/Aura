package com.example.bizhi.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object WallpaperPreferences {
    const val KEY_URL: String = "wallpaper_url"
    const val DEFAULT_URL: String = "https://www.252035.xyz/time.html"
    const val KEY_IMAGE_DISPLAY_MODE: String = "image_display_mode"
    const val KEY_ALLOW_INTERACTION: String = "allow_interaction"
    const val KEY_ALLOW_MEDIA: String = "allow_media"
    const val KEY_AUTO_REFRESH: String = "auto_refresh"
    const val KEY_REFRESH_INTERVAL: String = "refresh_interval_seconds"
    const val KEY_LOCAL_FILE_PATH: String = "local_file_path"
    const val KEY_LOCAL_FILE_TYPE: String = "local_file_type"
    const val KEY_URL_VERSION: String = "wallpaper_url_version"
    const val KEY_WEB_PLAYLIST: String = "web_playlist"
    const val KEY_WEB_PLAYLIST_INDEX: String = "web_playlist_index"
    const val KEY_WEB_PLAYLIST_ENABLED: String = "web_playlist_enabled"
    const val KEY_WEB_PLAYLIST_ORDER: String = "web_playlist_order"
    const val KEY_WEB_PLAYLIST_MODE: String = "web_playlist_mode"
    const val KEY_WEB_PLAYLIST_INTERVAL: String = "web_playlist_interval"
    const val KEY_LAST_MODE_LOCAL: String = "last_mode_local"
    const val KEY_LOCAL_BATCH_LIST: String = "local_batch_list"
    const val KEY_LOCAL_BATCH_ENABLED: String = "local_batch_enabled"
    const val KEY_LOCAL_BATCH_ORDER: String = "local_batch_order"
    const val KEY_LOCAL_BATCH_MODE: String = "local_batch_mode"
    const val KEY_LOCAL_BATCH_INTERVAL: String = "local_batch_interval"
    const val KEY_VR_GLOBAL_ENABLED: String = "vr_global_enabled"
    const val KEY_VR_SENSOR_ENABLED: String = "vr_sensor_enabled"
    const val KEY_VR_GESTURE_ENABLED: String = "vr_gesture_enabled"
    const val KEY_LOCAL_IS_VR: String = "local_is_vr"
    const val KEY_LOCAL_BATCH_VR_FLAGS: String = "local_batch_vr_flags"
    const val KEY_PENDING_APPLY: String = "pending_apply"
    const val KEY_PENDING_TOKEN: String = "pending_token"
    const val KEY_PENDING_URL: String = "pending_url"
    const val KEY_PENDING_ALLOW_INTERACTION: String = "pending_allow_interaction"
    const val KEY_PENDING_ALLOW_MEDIA: String = "pending_allow_media"
    const val KEY_PENDING_AUTO_REFRESH: String = "pending_auto_refresh"
    const val KEY_PENDING_REFRESH_INTERVAL: String = "pending_refresh_interval_seconds"
    const val KEY_PENDING_LOCAL_FILE_PATH: String = "pending_local_file_path"
    const val KEY_PENDING_LOCAL_FILE_TYPE: String = "pending_local_file_type"
    const val KEY_PENDING_LOCAL_IS_VR: String = "pending_local_is_vr"
    const val KEY_PENDING_LAST_MODE_LOCAL: String = "pending_last_mode_local"
    const val KEY_PENDING_VR_GLOBAL_ENABLED: String = "pending_vr_global_enabled"
    const val KEY_PENDING_VR_SENSOR_ENABLED: String = "pending_vr_sensor_enabled"
    const val KEY_PENDING_VR_GESTURE_ENABLED: String = "pending_vr_gesture_enabled"
    const val KEY_PENDING_IMAGE_DISPLAY_MODE: String = "pending_image_display_mode"
    const val KEY_PENDING_WEB_PLAYLIST_ENABLED: String = "pending_web_playlist_enabled"
    const val KEY_PENDING_LOCAL_BATCH_ENABLED: String = "pending_local_batch_enabled"
    const val KEY_PENDING_WALLPAPER_ALREADY_ACTIVE: String = "pending_wallpaper_already_active"
    val DEFAULT_IMAGE_DISPLAY_MODE: ImageDisplayMode = ImageDisplayMode.COVER
    const val DEFAULT_ALLOW_INTERACTION: Boolean = false
    const val DEFAULT_ALLOW_MEDIA: Boolean = false
    const val DEFAULT_AUTO_REFRESH: Boolean = false
    const val DEFAULT_REFRESH_INTERVAL_SECONDS: Long = 300
    const val DEFAULT_PLAYLIST_INTERVAL_SECONDS: Long = 5
    const val DEFAULT_VR_GLOBAL_ENABLED: Boolean = false
    const val DEFAULT_VR_SENSOR_ENABLED: Boolean = true
    const val DEFAULT_VR_GESTURE_ENABLED: Boolean = true

    private const val PREFS_NAME = "web_wallpaper_prefs"

    data class PendingState(
        val token: Long,
        val url: String,
        val imageDisplayMode: ImageDisplayMode,
        val allowInteraction: Boolean,
        val allowMedia: Boolean,
        val autoRefresh: Boolean,
        val refreshIntervalSeconds: Long,
        val lastModeLocal: Boolean,
        val localFilePath: String?,
        val localFileType: Int,
        val localIsVr: Boolean,
        val vrGlobalEnabled: Boolean,
        val vrSensorEnabled: Boolean,
        val vrGestureEnabled: Boolean,
        val webPlaylistEnabled: Boolean,
        val localBatchEnabled: Boolean,
        val wallpaperAlreadyActive: Boolean
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readUrl(context: Context): String =
        prefs(context).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun writeUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, url).apply()
        bumpUrlVersion(context)
    }

    fun readUrlVersion(context: Context): Long =
        prefs(context).getLong(KEY_URL_VERSION, 0L)

    fun bumpUrlVersion(context: Context) {
        val prefs = prefs(context)
        val nextValue = prefs.getLong(KEY_URL_VERSION, 0L) + 1L
        prefs.edit().putLong(KEY_URL_VERSION, nextValue).apply()
    }

    fun readImageDisplayMode(context: Context): ImageDisplayMode =
        ImageDisplayMode.fromName(
            prefs(context).getString(KEY_IMAGE_DISPLAY_MODE, DEFAULT_IMAGE_DISPLAY_MODE.name)
        )

    fun writeImageDisplayMode(context: Context, mode: ImageDisplayMode) {
        prefs(context).edit().putString(KEY_IMAGE_DISPLAY_MODE, mode.name).apply()
    }

    fun readAllowInteraction(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_INTERACTION, DEFAULT_ALLOW_INTERACTION)

    fun writeAllowInteraction(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_INTERACTION, enabled).apply()
    }

    fun readAllowMediaPlayback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_MEDIA, DEFAULT_ALLOW_MEDIA)

    fun writeAllowMediaPlayback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_MEDIA, enabled).apply()
    }

    fun readAutoRefresh(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_REFRESH, DEFAULT_AUTO_REFRESH)

    fun writeAutoRefresh(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_REFRESH, enabled).apply()
    }

    fun readRefreshIntervalSeconds(context: Context): Long =
        prefs(context).getLong(KEY_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL_SECONDS)

    fun writeRefreshIntervalSeconds(context: Context, seconds: Long) {
        prefs(context).edit().putLong(KEY_REFRESH_INTERVAL, seconds).apply()
    }

    fun writeLocalFilePath(context: Context, path: String?) {
        prefs(context).edit().putString(KEY_LOCAL_FILE_PATH, path).apply()
    }

    fun readLocalFilePath(context: Context): String? =
        prefs(context).getString(KEY_LOCAL_FILE_PATH, null)

    fun writeLocalFileType(context: Context, typeOrdinal: Int) {
        prefs(context).edit().putInt(KEY_LOCAL_FILE_TYPE, typeOrdinal).apply()
    }

    fun readLocalFileType(context: Context): Int =
        prefs(context).getInt(KEY_LOCAL_FILE_TYPE, 0)

    fun readWebPlaylist(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_WEB_PLAYLIST, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val items = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i).orEmpty()
            if (value.isNotBlank()) {
                items.add(value)
            }
        }
        return items
    }

    fun writeWebPlaylist(context: Context, urls: List<String>) {
        val array = JSONArray()
        urls.filter { it.isNotBlank() }.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_WEB_PLAYLIST, array.toString()).apply()
    }

    fun readWebPlaylistIndex(context: Context): Int =
        prefs(context).getInt(KEY_WEB_PLAYLIST_INDEX, 0)

    fun writeWebPlaylistIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_WEB_PLAYLIST_INDEX, index).apply()
    }

    fun readWebPlaylistEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_PLAYLIST_ENABLED, false)

    fun writeWebPlaylistEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_PLAYLIST_ENABLED, enabled).apply()
    }

    fun readWebPlaylistOrder(context: Context): PlaylistOrder {
        val value = prefs(context).getString(KEY_WEB_PLAYLIST_ORDER, PlaylistOrder.SEQUENTIAL.name)
        return runCatching { PlaylistOrder.valueOf(value ?: PlaylistOrder.SEQUENTIAL.name) }
            .getOrDefault(PlaylistOrder.SEQUENTIAL)
    }

    fun writeWebPlaylistOrder(context: Context, order: PlaylistOrder) {
        prefs(context).edit().putString(KEY_WEB_PLAYLIST_ORDER, order.name).apply()
    }

    fun readWebPlaylistMode(context: Context): PlaylistMode {
        val value = prefs(context).getString(KEY_WEB_PLAYLIST_MODE, PlaylistMode.INTERVAL.name)
        return runCatching { PlaylistMode.valueOf(value ?: PlaylistMode.INTERVAL.name) }
            .getOrDefault(PlaylistMode.INTERVAL)
    }

    fun writeWebPlaylistMode(context: Context, mode: PlaylistMode) {
        prefs(context).edit().putString(KEY_WEB_PLAYLIST_MODE, mode.name).apply()
    }

    fun readWebPlaylistIntervalSeconds(context: Context): Long =
        prefs(context).getLong(KEY_WEB_PLAYLIST_INTERVAL, DEFAULT_PLAYLIST_INTERVAL_SECONDS)

    fun writeWebPlaylistIntervalSeconds(context: Context, seconds: Long) {
        prefs(context).edit().putLong(KEY_WEB_PLAYLIST_INTERVAL, seconds).apply()
    }

    fun readLastModeIsLocal(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LAST_MODE_LOCAL, false)

    fun writeLastModeIsLocal(context: Context, local: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAST_MODE_LOCAL, local).apply()
    }

    fun readLocalBatch(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_LOCAL_BATCH_LIST, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val path = array.optString(i).orEmpty()
            if (path.isNotBlank()) {
                result.add(path)
            }
        }
        return result
    }

    fun writeLocalBatch(context: Context, paths: List<String>) {
        val array = JSONArray()
        paths.filter { it.isNotBlank() }.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_LOCAL_BATCH_LIST, array.toString()).apply()
    }

    fun readLocalBatchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCAL_BATCH_ENABLED, false)

    fun writeLocalBatchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_BATCH_ENABLED, enabled).apply()
    }

    fun readLocalBatchOrder(context: Context): PlaylistOrder {
        val value =
            prefs(context).getString(KEY_LOCAL_BATCH_ORDER, PlaylistOrder.SEQUENTIAL.name)
        return runCatching { PlaylistOrder.valueOf(value ?: PlaylistOrder.SEQUENTIAL.name) }
            .getOrDefault(PlaylistOrder.SEQUENTIAL)
    }

    fun writeLocalBatchOrder(context: Context, order: PlaylistOrder) {
        prefs(context).edit().putString(KEY_LOCAL_BATCH_ORDER, order.name).apply()
    }

    fun readLocalBatchMode(context: Context): PlaylistMode {
        val value =
            prefs(context).getString(KEY_LOCAL_BATCH_MODE, PlaylistMode.INTERVAL.name)
        return runCatching { PlaylistMode.valueOf(value ?: PlaylistMode.INTERVAL.name) }
            .getOrDefault(PlaylistMode.INTERVAL)
    }

    fun writeLocalBatchMode(context: Context, mode: PlaylistMode) {
        prefs(context).edit().putString(KEY_LOCAL_BATCH_MODE, mode.name).apply()
    }

    fun readLocalBatchIntervalSeconds(context: Context): Long =
        prefs(context).getLong(KEY_LOCAL_BATCH_INTERVAL, DEFAULT_PLAYLIST_INTERVAL_SECONDS)

    fun writeLocalBatchIntervalSeconds(context: Context, seconds: Long) {
        prefs(context).edit().putLong(KEY_LOCAL_BATCH_INTERVAL, seconds).apply()
    }

    fun readVrGlobalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VR_GLOBAL_ENABLED, DEFAULT_VR_GLOBAL_ENABLED)

    fun writeVrGlobalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VR_GLOBAL_ENABLED, enabled).apply()
    }

    fun readVrSensorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VR_SENSOR_ENABLED, DEFAULT_VR_SENSOR_ENABLED)

    fun writeVrSensorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VR_SENSOR_ENABLED, enabled).apply()
    }

    fun readVrGestureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VR_GESTURE_ENABLED, DEFAULT_VR_GESTURE_ENABLED)

    fun writeVrGestureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VR_GESTURE_ENABLED, enabled).apply()
    }

    fun writePendingState(context: Context, state: PendingState) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PENDING_APPLY, true)
            .putLong(KEY_PENDING_TOKEN, state.token)
            .putString(KEY_PENDING_URL, state.url)
            .putString(KEY_PENDING_IMAGE_DISPLAY_MODE, state.imageDisplayMode.name)
            .putBoolean(KEY_PENDING_ALLOW_INTERACTION, state.allowInteraction)
            .putBoolean(KEY_PENDING_ALLOW_MEDIA, state.allowMedia)
            .putBoolean(KEY_PENDING_AUTO_REFRESH, state.autoRefresh)
            .putLong(KEY_PENDING_REFRESH_INTERVAL, state.refreshIntervalSeconds)
            .putBoolean(KEY_PENDING_LAST_MODE_LOCAL, state.lastModeLocal)
            .putString(KEY_PENDING_LOCAL_FILE_PATH, state.localFilePath)
            .putInt(KEY_PENDING_LOCAL_FILE_TYPE, state.localFileType)
            .putBoolean(KEY_PENDING_LOCAL_IS_VR, state.localIsVr)
            .putBoolean(KEY_PENDING_VR_GLOBAL_ENABLED, state.vrGlobalEnabled)
            .putBoolean(KEY_PENDING_VR_SENSOR_ENABLED, state.vrSensorEnabled)
            .putBoolean(KEY_PENDING_VR_GESTURE_ENABLED, state.vrGestureEnabled)
            .putBoolean(KEY_PENDING_WEB_PLAYLIST_ENABLED, state.webPlaylistEnabled)
            .putBoolean(KEY_PENDING_LOCAL_BATCH_ENABLED, state.localBatchEnabled)
            .putBoolean(KEY_PENDING_WALLPAPER_ALREADY_ACTIVE, state.wallpaperAlreadyActive)
            .apply()
    }

    fun readPendingState(context: Context): PendingState? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING_APPLY, false)) {
            return null
        }
        return PendingState(
            token = prefs.getLong(KEY_PENDING_TOKEN, 0L),
            url = prefs.getString(KEY_PENDING_URL, DEFAULT_URL) ?: DEFAULT_URL,
            imageDisplayMode =
                ImageDisplayMode.fromName(
                    prefs.getString(KEY_PENDING_IMAGE_DISPLAY_MODE, DEFAULT_IMAGE_DISPLAY_MODE.name)
                ),
            allowInteraction = prefs.getBoolean(KEY_PENDING_ALLOW_INTERACTION, DEFAULT_ALLOW_INTERACTION),
            allowMedia = prefs.getBoolean(KEY_PENDING_ALLOW_MEDIA, DEFAULT_ALLOW_MEDIA),
            autoRefresh = prefs.getBoolean(KEY_PENDING_AUTO_REFRESH, DEFAULT_AUTO_REFRESH),
            refreshIntervalSeconds =
                prefs.getLong(KEY_PENDING_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL_SECONDS),
            lastModeLocal = prefs.getBoolean(KEY_PENDING_LAST_MODE_LOCAL, false),
            localFilePath = prefs.getString(KEY_PENDING_LOCAL_FILE_PATH, null),
            localFileType = prefs.getInt(KEY_PENDING_LOCAL_FILE_TYPE, 0),
            localIsVr = prefs.getBoolean(KEY_PENDING_LOCAL_IS_VR, false),
            vrGlobalEnabled =
                prefs.getBoolean(KEY_PENDING_VR_GLOBAL_ENABLED, DEFAULT_VR_GLOBAL_ENABLED),
            vrSensorEnabled =
                prefs.getBoolean(KEY_PENDING_VR_SENSOR_ENABLED, DEFAULT_VR_SENSOR_ENABLED),
            vrGestureEnabled =
                prefs.getBoolean(KEY_PENDING_VR_GESTURE_ENABLED, DEFAULT_VR_GESTURE_ENABLED),
            webPlaylistEnabled = prefs.getBoolean(KEY_PENDING_WEB_PLAYLIST_ENABLED, false),
            localBatchEnabled = prefs.getBoolean(KEY_PENDING_LOCAL_BATCH_ENABLED, false),
            wallpaperAlreadyActive =
                prefs.getBoolean(KEY_PENDING_WALLPAPER_ALREADY_ACTIVE, false)
        )
    }

    fun readPendingToken(context: Context): Long? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING_APPLY, false)) return null
        return prefs.getLong(KEY_PENDING_TOKEN, 0L)
    }

    fun clearPendingState(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PENDING_APPLY, false)
            .remove(KEY_PENDING_TOKEN)
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_IMAGE_DISPLAY_MODE)
            .remove(KEY_PENDING_ALLOW_INTERACTION)
            .remove(KEY_PENDING_ALLOW_MEDIA)
            .remove(KEY_PENDING_AUTO_REFRESH)
            .remove(KEY_PENDING_REFRESH_INTERVAL)
            .remove(KEY_PENDING_LAST_MODE_LOCAL)
            .remove(KEY_PENDING_LOCAL_FILE_PATH)
            .remove(KEY_PENDING_LOCAL_FILE_TYPE)
            .remove(KEY_PENDING_LOCAL_IS_VR)
            .remove(KEY_PENDING_VR_GLOBAL_ENABLED)
            .remove(KEY_PENDING_VR_SENSOR_ENABLED)
            .remove(KEY_PENDING_VR_GESTURE_ENABLED)
            .remove(KEY_PENDING_WEB_PLAYLIST_ENABLED)
            .remove(KEY_PENDING_LOCAL_BATCH_ENABLED)
            .remove(KEY_PENDING_WALLPAPER_ALREADY_ACTIVE)
            .apply()
    }

    fun applyPendingState(context: Context): PendingState? {
        val state = readPendingState(context) ?: return null
        writeImageDisplayMode(context, state.imageDisplayMode)
        writeAllowInteraction(context, state.allowInteraction)
        writeAllowMediaPlayback(context, state.allowMedia)
        writeAutoRefresh(context, state.autoRefresh)
        writeRefreshIntervalSeconds(context, state.refreshIntervalSeconds)
        writeVrGlobalEnabled(context, state.vrGlobalEnabled)
        writeVrSensorEnabled(context, state.vrSensorEnabled)
        writeVrGestureEnabled(context, state.vrGestureEnabled)
        writeWebPlaylistEnabled(context, state.webPlaylistEnabled)
        writeLocalBatchEnabled(context, state.localBatchEnabled)
        writeLastModeIsLocal(context, state.lastModeLocal)
        writeLocalFilePath(context, state.localFilePath)
        writeLocalFileType(context, state.localFileType)
        writeLocalIsVr(context, state.localIsVr)
        writeUrl(context, state.url)
        clearPendingState(context)
        return state
    }

    fun readLocalIsVr(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCAL_IS_VR, false)

    fun writeLocalIsVr(context: Context, isVr: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_IS_VR, isVr).apply()
    }

    fun readLocalBatchVrFlags(context: Context): List<Boolean> {
        val raw = prefs(context).getString(KEY_LOCAL_BATCH_VR_FLAGS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Boolean>()
        for (i in 0 until array.length()) {
            result.add(array.optBoolean(i, false))
        }
        return result
    }

    fun writeLocalBatchVrFlags(context: Context, flags: List<Boolean>) {
        val array = JSONArray()
        flags.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_LOCAL_BATCH_VR_FLAGS, array.toString()).apply()
    }

    fun applyWebPlaylistIndex(
        context: Context,
        index: Int,
        playlist: List<String>? = null
    ): String? {
        val items = (playlist ?: readWebPlaylist(context)).filter { it.isNotBlank() }
        if (items.isEmpty()) return null
        val bounded = ((index % items.size) + items.size) % items.size
        writeWebPlaylistIndex(context, bounded)
        val url = items[bounded]
        writeUrl(context, url)
        return url
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
