package com.example.bizhi.util

import android.net.Uri
import android.text.TextUtils
import android.webkit.ValueCallback
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.example.bizhi.data.ImageDisplayMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object ImageWallpaperSupport {
    private const val MAX_STRUCTURED_RESPONSE_BYTES = 512 * 1024
    private const val WRAPPER_BYPASS_MARKER = "aura-image-wrapper-bypass"

    private val imageExtensions = setOf(
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "svg",
        "avif",
        "heic",
        "heif"
    )

    private fun objectFit(mode: ImageDisplayMode): String = when (mode) {
        ImageDisplayMode.CONTAIN -> "contain"
        ImageDisplayMode.COVER -> "cover"
        ImageDisplayMode.STRETCH -> "fill"
    }

    fun buildImageHtml(sourceUri: String, mode: ImageDisplayMode): String {
        val escapedSource = TextUtils.htmlEncode(sourceUri)
        val fit = objectFit(mode)
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <style>
            html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;}
            body{display:block;}
            img{
                display:block;
                width:100%;
                height:100%;
                margin:0;
                padding:0;
                object-fit:$fit;
                object-position:center center;
                max-width:none;
                max-height:none;
                user-select:none;
                -webkit-user-select:none;
                -webkit-touch-callout:none;
            }
            </style>
            </head>
            <body>
            <img src="$escapedSource" alt="" />
            </body>
            </html>
        """.trimIndent()
    }

    fun buildRemoteImageDocumentIfNeeded(
        url: String,
        mode: ImageDisplayMode,
        requestHeaders: Map<String, String> = emptyMap(),
        allowNetworkProbe: Boolean = true
    ): String? {
        if (!isRemoteUrl(url) || hasWrapperBypassMarker(url)) {
            return null
        }
        if (allowNetworkProbe) {
            resolveRemoteImageSourceUrl(url, requestHeaders)?.let { imageSource ->
                return buildImageHtml(imageSource, mode)
            }
        }
        return buildBrowserResolvedImageHtmlIfNeeded(url, mode)
    }

    fun buildImageWrapperResponseIfNeeded(
        url: String,
        requestHeaders: Map<String, String>,
        mode: ImageDisplayMode
    ): WebResourceResponse? {
        val html = buildRemoteImageDocumentIfNeeded(url, mode, requestHeaders) ?: return null
        return WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }
    }

    fun resolveRemoteImageSourceUrl(
        url: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): String? {
        if (!isRemoteUrl(url)) return null
        return resolveImageSourceUrl(url, requestHeaders)
    }

    private fun isRemoteUrl(url: String): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.US)
        return scheme == "http" || scheme == "https"
    }

    private fun hasWrapperBypassMarker(url: String): Boolean {
        val fragment = Uri.parse(url).fragment.orEmpty()
        return fragment.contains(WRAPPER_BYPASS_MARKER)
    }

    private fun buildBrowserResolvedImageHtmlIfNeeded(
        url: String,
        mode: ImageDisplayMode
    ): String? {
        if (!shouldTryBrowserImageWrapper(url)) {
            return null
        }
        val fallbackUrl = appendWrapperBypassMarker(url)
        return buildBrowserResolvedImageHtml(url, fallbackUrl, mode)
    }

    private fun shouldTryBrowserImageWrapper(url: String): Boolean {
        if (!isRemoteUrl(url) || hasWrapperBypassMarker(url)) {
            return false
        }
        if (isLikelyImageUrl(url)) {
            return true
        }
        val parsed = Uri.parse(url)
        val host = parsed.host?.lowercase(Locale.US).orEmpty()
        val path = parsed.path?.lowercase(Locale.US).orEmpty()
        if (host.startsWith("api.") || path.contains("/api/")) {
            return true
        }
        if (
            path.contains("image") ||
            path.contains("wallpaper") ||
            path.contains("photo") ||
            path.contains("picture")
        ) {
            return true
        }
        val queryKeys = runCatching { parsed.queryParameterNames }.getOrDefault(emptySet())
        return queryKeys.any { key ->
            val normalized = key.lowercase(Locale.US)
            normalized in browserWrapperQueryKeys
        }
    }

    private fun appendWrapperBypassMarker(url: String): String {
        val parsed = Uri.parse(url)
        val fragment = parsed.fragment.orEmpty()
        if (fragment.contains(WRAPPER_BYPASS_MARKER)) {
            return url
        }
        val updatedFragment = if (fragment.isBlank()) {
            WRAPPER_BYPASS_MARKER
        } else {
            "$fragment&$WRAPPER_BYPASS_MARKER"
        }
        return parsed.buildUpon()
            .encodedFragment(updatedFragment)
            .build()
            .toString()
    }

    private fun buildBrowserResolvedImageHtml(
        sourceUrl: String,
        fallbackUrl: String,
        mode: ImageDisplayMode
    ): String {
        val escapedSource = TextUtils.htmlEncode(sourceUrl)
        val sourceLiteral = JSONObject.quote(sourceUrl)
        val fallbackLiteral = JSONObject.quote(fallbackUrl)
        val fit = objectFit(mode)
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <style>
            html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;}
            body{display:flex;align-items:center;justify-content:center;}
            img{
                display:block;
                width:100%;
                height:100%;
                margin:0;
                padding:0;
                object-fit:$fit;
                object-position:center center;
                max-width:none;
                max-height:none;
                user-select:none;
                -webkit-user-select:none;
                -webkit-touch-callout:none;
            }
            </style>
            </head>
            <body>
            <img id="wallpaperImage" src="$escapedSource" alt="" />
            <script>
            (function() {
              var image = document.getElementById('wallpaperImage');
              if (!image) return;
              var sourceUrl = $sourceLiteral;
              var fallbackUrl = $fallbackLiteral;
              var redirected = false;
              var resolvedFromStructuredResponse = false;
              var objectUrl = null;

              function releaseObjectUrl() {
                if (!objectUrl) return;
                URL.revokeObjectURL(objectUrl);
                objectUrl = null;
              }

              function fallback() {
                if (redirected) return;
                redirected = true;
                releaseObjectUrl();
                window.location.replace(fallbackUrl);
              }

              function sanitizeUrlCandidate(value) {
                if (!value) return null;
                var normalized = String(value).trim().replace(/^["']+|["']+$/g, '');
                return /^https?:\/\//i.test(normalized) ? normalized : null;
              }

              function looksLikeHtmlDocument(text) {
                var normalized = String(text || '').trimStart().toLowerCase();
                return normalized.startsWith('<!doctype html') ||
                  normalized.startsWith('<html') ||
                  normalized.indexOf('<head') !== -1 ||
                  normalized.indexOf('<body') !== -1 ||
                  normalized.indexOf('<script') !== -1 ||
                  normalized.indexOf('</') !== -1;
              }

              function extractUrlFromJson(value) {
                if (!value) return null;
                if (typeof value === 'string') {
                  return sanitizeUrlCandidate(value);
                }
                if (Array.isArray(value)) {
                  for (var index = 0; index < value.length; index += 1) {
                    var arrayCandidate = extractUrlFromJson(value[index]);
                    if (arrayCandidate) return arrayCandidate;
                  }
                  return null;
                }
                if (typeof value === 'object') {
                  for (var key in value) {
                    if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
                    var objectCandidate = extractUrlFromJson(value[key]);
                    if (objectCandidate) return objectCandidate;
                  }
                }
                return null;
              }

              function extractImageCandidate(text) {
                var normalized = String(text || '')
                  .replace(/\uFEFF/g, '')
                  .replace(/\\\//g, '/')
                  .trim();
                if (!normalized) return null;
                var directCandidate = sanitizeUrlCandidate(normalized);
                if (directCandidate) return directCandidate;
                if (looksLikeHtmlDocument(normalized)) return null;
                if (normalized[0] === '{' || normalized[0] === '[') {
                  try {
                    var parsed = JSON.parse(normalized);
                    var jsonCandidate = extractUrlFromJson(parsed);
                    if (jsonCandidate) return jsonCandidate;
                  } catch (error) {
                  }
                }
                var matches = normalized.match(/https?:\/\/[^\s"'<>\\]+/gi) || [];
                if (matches.length !== 1) return null;
                return sanitizeUrlCandidate(matches[0]);
              }

              async function resolveStructuredResponse() {
                try {
                  var response = await fetch(sourceUrl, {
                    credentials: 'include',
                    cache: 'no-store'
                  });
                  if (!response.ok) {
                    fallback();
                    return;
                  }
                  var contentType = String(response.headers.get('content-type') || '').toLowerCase();
                  if (contentType.indexOf('image/') === 0) {
                    var blob = await response.blob();
                    releaseObjectUrl();
                    objectUrl = URL.createObjectURL(blob);
                    image.src = objectUrl;
                    return;
                  }
                  var responseText = await response.text();
                  var candidateUrl = extractImageCandidate(responseText);
                  if (!candidateUrl) {
                    fallback();
                    return;
                  }
                  image.src = candidateUrl;
                } catch (error) {
                  fallback();
                }
              }

              image.addEventListener('error', function() {
                if (resolvedFromStructuredResponse) {
                  fallback();
                  return;
                }
                resolvedFromStructuredResponse = true;
                resolveStructuredResponse();
              });
              image.addEventListener('load', function() {
                if (!String(image.src || '').startsWith('blob:')) {
                  releaseObjectUrl();
                }
              });
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun isLikelyImageUrl(url: String): Boolean {
        val lastSegment = Uri.parse(url).lastPathSegment?.lowercase(Locale.US).orEmpty()
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
        return extension in imageExtensions
    }

    private fun resolveImageSourceUrl(url: String, requestHeaders: Map<String, String>): String? {
        if (isLikelyImageUrl(url)) return url

        val headProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "HEAD",
            followRedirects = false
        )
        headProbe?.resolvedImageSourceUrl?.let { return it }
        if (headProbe != null &&
            LooseUrlResponseSupport.shouldInspectBodyAsUrlText(url, headProbe.contentType)
        ) {
            return fetchImageSourceFromStructuredResponse(url, requestHeaders)
        }
        if (headProbe?.isDefinitiveOther == true) return null

        val rangeProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET",
            rangeRequest = true
        )
        rangeProbe?.resolvedImageSourceUrl?.let { return it }
        if (rangeProbe != null &&
            LooseUrlResponseSupport.shouldInspectBodyAsUrlText(url, rangeProbe.contentType)
        ) {
            return fetchImageSourceFromStructuredResponse(url, requestHeaders)
        }
        return null
    }

    private fun openConnection(
        url: String,
        requestHeaders: Map<String, String>,
        method: String,
        followRedirects: Boolean = true
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
                setRequestProperty("Accept", "image/*,application/json,text/plain,*/*;q=0.8")
            }
        }.getOrNull()
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

    private fun fetchImageSourceFromStructuredResponse(
        url: String,
        requestHeaders: Map<String, String>
    ): String? {
        val connection = openConnection(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET"
        ) ?: return null
        return try {
            connection.connect()
            if (connection.contentType?.startsWith("image/", ignoreCase = true) == true) {
                return runCatching { connection.url?.toString() }.getOrNull() ?: url
            }
            val responseText = connection.inputStream.readTextWithinLimit(MAX_STRUCTURED_RESPONSE_BYTES)
                ?: return null
            extractImageUrlFromStructuredResponse(responseText, requestHeaders)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractImageUrlFromStructuredResponse(
        responseText: String,
        requestHeaders: Map<String, String>
    ): String? {
        val trimmed = responseText.trimStart()
        val parsedRoot = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        } else {
            null
        }
        val structuredCandidate = parsedRoot?.let {
            extractImageUrlFromJsonValue(it, requestHeaders)
        }
        if (structuredCandidate != null) {
            return structuredCandidate
        }
        val directTextCandidate = LooseUrlResponseSupport.extractDirectUrlCandidate(responseText)
        if (directTextCandidate != null &&
            (isLikelyImageUrl(directTextCandidate) ||
                isDirectImageCandidate(directTextCandidate, requestHeaders))
        ) {
            return directTextCandidate
        }
        if (LooseUrlResponseSupport.looksLikeHtmlDocument(responseText)) {
            return null
        }
        return extractImageUrlWithRegex(responseText)
    }

    private fun extractImageUrlFromJsonValue(root: Any, requestHeaders: Map<String, String>): String? {
        val candidates = mutableListOf<ImageUrlCandidate>()
        collectJsonImageCandidates(
            value = root,
            keyHint = null,
            pathHint = null,
            candidates = candidates,
            order = intArrayOf(0)
        )
        return candidates
            .sortedWith(
                compareByDescending<ImageUrlCandidate> { it.score }
                    .thenBy { it.order }
            )
            .firstOrNull { candidate ->
                candidate.looksLikeImage || isDirectImageCandidate(candidate.url, requestHeaders)
            }
            ?.url
    }

    private fun isDirectImageCandidate(url: String, requestHeaders: Map<String, String>): Boolean {
        if (!isRemoteUrl(url)) return false
        val headProbe = probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "HEAD",
            followRedirects = false
        )
        if (headProbe?.looksLikeImage == true) return true
        if (headProbe?.isDefinitiveOther == true ||
            (headProbe != null &&
                LooseUrlResponseSupport.shouldInspectBodyAsUrlText(url, headProbe.contentType))
        ) {
            return false
        }
        return probeContentType(
            url = url,
            requestHeaders = requestHeaders,
            method = "GET",
            rangeRequest = true
        )?.looksLikeImage == true
    }

    private fun collectJsonImageCandidates(
        value: Any?,
        keyHint: String?,
        pathHint: String?,
        candidates: MutableList<ImageUrlCandidate>,
        order: IntArray
    ) {
        when (value) {
            is JSONObject -> {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectJsonImageCandidates(
                        value = value.opt(key),
                        keyHint = key,
                        pathHint = if (pathHint.isNullOrBlank()) key else "$pathHint.$key",
                        candidates = candidates,
                        order = order
                    )
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectJsonImageCandidates(
                        value = value.opt(index),
                        keyHint = keyHint,
                        pathHint = pathHint,
                        candidates = candidates,
                        order = order
                    )
                }
            }

            is String -> {
                val normalized = value.replace("\\/", "/").trim()
                if (!isRemoteUrl(normalized)) {
                    return
                }
                candidates += ImageUrlCandidate(
                    url = normalized,
                    score = scoreImageUrlCandidate(keyHint, pathHint, normalized),
                    looksLikeImage = isLikelyImageUrl(normalized),
                    order = order[0]++
                )
            }
        }
    }

    private fun scoreImageUrlCandidate(keyHint: String?, pathHint: String?, url: String): Int {
        val normalizedKey = keyHint?.lowercase(Locale.US).orEmpty()
        val normalizedPath = pathHint?.lowercase(Locale.US).orEmpty()
        var score = 0
        if (normalizedKey in highPriorityImageKeys) score += 120
        if (normalizedKey in mediumPriorityImageKeys) score += 60
        if (normalizedPath.contains("original")) score += 40
        if (normalizedPath.contains("urls")) score += 10
        if (isLikelyImageUrl(url)) score += 30
        return score
    }

    private fun extractImageUrlWithRegex(responseText: String): String? {
        val normalized = responseText.replace("\\/", "/")
        return imageUrlRegex.find(normalized)?.value
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

    private fun resolveRedirectUrl(baseUrl: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        return runCatching { URL(URL(baseUrl), location).toString() }
            .getOrElse { location }
    }

    private data class ContentProbeResult(
        val responseCode: Int?,
        val contentType: String?,
        val redirectUrl: String?,
        val finalUrl: String?
    ) {
        val looksLikeImage: Boolean
            get() = isImageContentType || isImageRedirect

        val resolvedImageSourceUrl: String?
            get() = when {
                isImageRedirect -> redirectUrl
                isImageContentType -> finalUrl ?: redirectUrl
                else -> null
            }

        val isDefinitiveOther: Boolean
            get() = responseCode in 200..299 &&
                !contentType.isNullOrBlank() &&
                !looksLikeImage

        private val isImageContentType: Boolean
            get() = contentType?.startsWith("image/", ignoreCase = true) == true
        private val isImageRedirect: Boolean
            get() = redirectUrl?.let(::isLikelyImageUrl) == true
    }

    private data class ImageUrlCandidate(
        val url: String,
        val score: Int,
        val looksLikeImage: Boolean,
        val order: Int
    )

    private val highPriorityImageKeys = setOf(
        "original",
        "url",
        "imageurl",
        "image_url",
        "imgurl",
        "img_url",
        "src"
    )

    private val mediumPriorityImageKeys = setOf(
        "image",
        "img",
        "photo",
        "picture",
        "file",
        "download",
        "full",
        "large",
        "raw"
    )

    private val browserWrapperQueryKeys = setOf(
        "image",
        "img",
        "imageurl",
        "image_url",
        "imgurl",
        "img_url",
        "imagetype",
        "image_type",
        "wallpaper",
        "photo",
        "picture",
        "pic",
        "random"
    )

    private val imageUrlRegex = Regex(
        """https?://[^\s"'<>\\]+?\.(?:png|jpg|jpeg|gif|webp|bmp|svg|avif|heic|heif)(?:\?[^\s"'<>\\]*)?""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun applyStandaloneImageMode(
        webView: WebView,
        mode: ImageDisplayMode,
        callback: ValueCallback<String>? = null
    ) {
        val fit = objectFit(mode)
        val script = """
            (function() {
              try {
                var docEl = document.documentElement;
                var body = document.body;
                if (!docEl || !body) return 'no-body';
                var contentType = document.contentType || '';
                var images = document.images || [];
                var hasSingleImage = images.length === 1 &&
                  body.children.length === 1 &&
                  body.firstElementChild &&
                  body.firstElementChild.tagName === 'IMG' &&
                  ((body.textContent || '').trim() === '');
                var isStandaloneImage = contentType.indexOf('image/') === 0;
                if (!isStandaloneImage && !hasSingleImage) return 'skip';
                var image = images[0];
                docEl.style.setProperty('margin', '0', 'important');
                docEl.style.setProperty('padding', '0', 'important');
                docEl.style.setProperty('width', '100%', 'important');
                docEl.style.setProperty('height', '100%', 'important');
                docEl.style.setProperty('overflow', 'hidden', 'important');
                docEl.style.setProperty('background', '#000', 'important');
                body.style.setProperty('margin', '0', 'important');
                body.style.setProperty('padding', '0', 'important');
                body.style.setProperty('width', '100%', 'important');
                body.style.setProperty('height', '100%', 'important');
                body.style.setProperty('overflow', 'hidden', 'important');
                body.style.setProperty('background', '#000', 'important');
                if (!image) return 'no-image';
                image.style.setProperty('display', 'block', 'important');
                image.style.setProperty('width', '100%', 'important');
                image.style.setProperty('height', '100%', 'important');
                image.style.setProperty('max-width', 'none', 'important');
                image.style.setProperty('max-height', 'none', 'important');
                image.style.setProperty('margin', '0', 'important');
                image.style.setProperty('padding', '0', 'important');
                image.style.setProperty('object-fit', '$fit', 'important');
                image.style.setProperty('object-position', 'center center', 'important');
                return isStandaloneImage ? 'image-doc' : 'single-image';
              } catch (error) {
                return 'error:' + error;
              }
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(script, callback)
        }
    }
}
