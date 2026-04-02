package com.example.bizhi.util

import android.net.Uri
import java.util.Locale

object LooseUrlResponseSupport {
    private val apiEndpointSuffixes = setOf(
        ".php",
        ".asp",
        ".aspx",
        ".jsp",
        ".do",
        ".action"
    )

    private val directUrlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun shouldInspectBodyAsUrlText(requestUrl: String, contentType: String?): Boolean {
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        return when {
            mediaType == "application/json" || mediaType.endsWith("+json") -> true
            mediaType == "text/plain" ||
                mediaType == "text/javascript" ||
                mediaType == "application/javascript" -> true
            mediaType == "text/html" -> looksLikeApiStyleEndpoint(requestUrl)
            else -> false
        }
    }

    fun extractDirectUrlCandidate(responseText: String): String? {
        val normalized = responseText
            .replace("\\/", "/")
            .replace("\uFEFF", "")
            .trim()
        if (normalized.isBlank()) return null
        if (looksLikeHtmlDocument(normalized)) return null

        val wholeTextCandidate = sanitizeUrlCandidate(stripWrappingQuotes(normalized))
        if (wholeTextCandidate != null && isRemoteUrl(wholeTextCandidate)) {
            return wholeTextCandidate
        }

        val matches = directUrlRegex.findAll(normalized).take(2).toList()
        if (matches.size != 1) return null
        val matchedUrl = sanitizeUrlCandidate(matches.first().value) ?: return null
        if (!isRemoteUrl(matchedUrl)) return null

        val remainingText = normalized
            .replace(matches.first().value, "")
            .trim()
            .trim('"', '\'', '(', ')', '[', ']', '{', '}', ',', ';')
        return matchedUrl.takeIf { remainingText.isEmpty() }
    }

    fun looksLikeHtmlDocument(text: String): Boolean {
        val normalized = text.trimStart().lowercase(Locale.US)
        return normalized.startsWith("<!doctype html") ||
            normalized.startsWith("<html") ||
            normalized.contains("<head") ||
            normalized.contains("<body") ||
            normalized.contains("<script") ||
            normalized.contains("<meta") ||
            normalized.contains("<div") ||
            normalized.contains("<img") ||
            normalized.contains("</")
    }

    private fun looksLikeApiStyleEndpoint(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val host = parsed?.host?.lowercase(Locale.US).orEmpty()
        val path = parsed?.path?.lowercase(Locale.US).orEmpty()
        return host.startsWith("api.") ||
            path.contains("/api/") ||
            apiEndpointSuffixes.any { suffix -> path.endsWith(suffix) }
    }

    private fun sanitizeUrlCandidate(candidate: String): String? {
        val trimmed = candidate.trim().trim('"', '\'')
        return trimmed.takeIf { it.isNotBlank() }
    }

    private fun stripWrappingQuotes(text: String): String {
        var result = text.trim()
        while (result.length >= 2) {
            val first = result.first()
            val last = result.last()
            val wrappedByDoubleQuotes = first == '"' && last == '"'
            val wrappedBySingleQuotes = first == '\'' && last == '\''
            if (!wrappedByDoubleQuotes && !wrappedBySingleQuotes) {
                break
            }
            result = result.substring(1, result.length - 1).trim()
        }
        return result
    }

    private fun isRemoteUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase(Locale.US)
        return scheme == "http" || scheme == "https"
    }
}
