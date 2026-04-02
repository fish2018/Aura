package com.example.bizhi.util

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object RemoteVideoSupport {
    private const val MAX_STRUCTURED_RESPONSE_BYTES = 256 * 1024

    private val videoExtensions = setOf(
        "mp4",
        "webm",
        "m4v",
        "mov",
        "3gp"
    )

    fun resolveVideoSourceUrl(
        url: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): String? {
        if (!isRemoteUrl(url)) return null

        val parsedUri = runCatching { Uri.parse(url) }.getOrNull()
        if (parsedUri?.scheme.equals("https", ignoreCase = true) && isLikelyVideoUrl(url)) {
            return url
        }

        val headProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "HEAD",
            followRedirects = false
        )
        if (headProbe?.looksLikeVideo == true) {
            return headProbe.playbackUrl ?: url
        }
        if (headProbe != null &&
            LooseUrlResponseSupport.shouldInspectBodyAsUrlText(url, headProbe.contentType)
        ) {
            return fetchVideoSourceFromStructuredResponse(url, requestHeaders)
        }
        if (headProbe?.isDefinitiveOther == true) {
            return if (isLikelyVideoUrl(url)) url else null
        }

        val rangeProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET",
            rangeRequest = true
        )
        if (rangeProbe?.looksLikeVideo == true) {
            return rangeProbe.playbackUrl ?: url
        }
        if (rangeProbe != null &&
            LooseUrlResponseSupport.shouldInspectBodyAsUrlText(url, rangeProbe.contentType)
        ) {
            return fetchVideoSourceFromStructuredResponse(url, requestHeaders)
        }

        return if (isLikelyVideoUrl(url)) url else null
    }

    private fun fetchVideoSourceFromStructuredResponse(
        url: String,
        requestHeaders: Map<String, String>
    ): String? {
        val connection = openConnection(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET",
            followRedirects = true
        ) ?: return null
        return try {
            connection.connect()
            if (connection.contentType?.startsWith("video/", ignoreCase = true) == true) {
                return runCatching { connection.url?.toString() }.getOrNull() ?: url
            }
            val responseText = connection.inputStream.readTextWithinLimit(MAX_STRUCTURED_RESPONSE_BYTES)
                ?: return null
            extractVideoUrlFromStructuredResponse(responseText, requestHeaders)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractVideoUrlFromStructuredResponse(
        responseText: String,
        requestHeaders: Map<String, String>
    ): String? {
        val directTextCandidate = LooseUrlResponseSupport.extractDirectUrlCandidate(responseText)
            ?: return null
        return directTextCandidate.takeIf {
            isLikelyVideoUrl(it) || isDirectVideoCandidate(it, requestHeaders)
        }
    }

    private fun probeContentType(
        url: String,
        requestHeaders: Map<String, String>,
        method: String,
        rangeRequest: Boolean = false,
        followRedirects: Boolean = true
    ): ContentProbeResult? {
        val connection = openConnection(
            url = url,
            requestHeaders = requestHeaders,
            method = method,
            followRedirects = followRedirects
        ) ?: return null
        return try {
            if (rangeRequest) {
                connection.setRequestProperty("Range", "bytes=0-0")
            }
            connection.connect()
            ContentProbeResult(
                responseCode = runCatching { connection.responseCode }.getOrNull(),
                contentType = connection.contentType,
                redirectUrl = resolveRedirectUrl(url, connection.getHeaderField("Location")),
                finalUrl = runCatching { connection.url?.toString() }.getOrNull()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        requestHeaders: Map<String, String>,
        method: String,
        followRedirects: Boolean
    ): HttpURLConnection? {
        return runCatching {
            (URL(url).openConnection() as? HttpURLConnection)?.apply {
                instanceFollowRedirects = followRedirects
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = method
                requestHeaders.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) {
                        setRequestProperty(name, value)
                    }
                }
                setRequestProperty("Accept", "video/*,application/json,text/plain,text/html,*/*;q=0.8")
            }
        }.getOrNull()
    }

    private fun isDirectVideoCandidate(url: String, requestHeaders: Map<String, String>): Boolean {
        if (!isRemoteUrl(url)) return false
        val headProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "HEAD",
            followRedirects = false
        )
        if (headProbe?.looksLikeVideo == true) return true
        if (headProbe?.isDefinitiveOther == true) return false
        return probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET",
            rangeRequest = true
        )?.looksLikeVideo == true
    }

    private fun resolveRedirectUrl(baseUrl: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { URL(URL(baseUrl), location).toString() }
            .getOrElse { location }
    }

    private fun isRemoteUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.US)
        return scheme == "http" || scheme == "https"
    }

    private fun isLikelyVideoUrl(url: String): Boolean {
        val lastSegment = Uri.parse(url).lastPathSegment?.lowercase(Locale.US).orEmpty()
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
        return extension in videoExtensions
    }

    private fun java.io.InputStream.readTextWithinLimit(limitBytes: Int): String? {
        val output = ByteArrayOutputStream(minOf(limitBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            val remaining = limitBytes - total
            if (remaining <= 0) break
            val bytesToWrite = minOf(read, remaining)
            output.write(buffer, 0, bytesToWrite)
            total += bytesToWrite
            if (bytesToWrite < read) {
                break
            }
        }
        if (total == 0) return null
        return output.toString(Charsets.UTF_8.name())
    }

    private data class ContentProbeResult(
        val responseCode: Int?,
        val contentType: String?,
        val redirectUrl: String?,
        val finalUrl: String?
    ) {
        val looksLikeVideo: Boolean
            get() = isVideoContentType || isVideoRedirect || isVideoFinalUrl

        val playbackUrl: String?
            get() = when {
                isVideoFinalUrl -> finalUrl
                isVideoRedirect -> redirectUrl
                isVideoContentType -> finalUrl
                else -> null
            }

        val isDefinitiveOther: Boolean
            get() = responseCode in 200..299 &&
                !contentType.isNullOrBlank() &&
                !looksLikeVideo

        private val isVideoContentType: Boolean
            get() = contentType?.startsWith("video/", ignoreCase = true) == true

        private val isVideoRedirect: Boolean
            get() = redirectUrl?.let(::isLikelyVideoUrl) == true

        private val isVideoFinalUrl: Boolean
            get() = finalUrl?.let(::isLikelyVideoUrl) == true
    }
}
