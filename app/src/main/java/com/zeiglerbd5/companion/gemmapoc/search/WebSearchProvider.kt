package com.zeiglerbd5.companion.gemmapoc.search

import java.net.HttpURLConnection
import java.net.URL

/**
 * Single result from any web-search provider. Mirrors the iOS sibling's
 * `SearchResult` struct.
 */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    /** Provider that produced this result ("Wikipedia", "DuckDuckGo"). */
    val source: String,
)

/**
 * Abstraction so providers can be swapped without touching callers.
 * Phase 1 wires Wikipedia + DuckDuckGo; future providers (Brave, Tavily…)
 * just implement this.
 */
interface WebSearchProvider {
    val name: String
    suspend fun search(query: String): List<SearchResult>
}

/**
 * Blocking GET returning the response body, or null on any failure. Call
 * from a background dispatcher. Kept dependency-free (HttpURLConnection)
 * so the search layer adds no third-party network library — matters under
 * the project's "audit every dependency" rule.
 */
internal fun httpGet(urlString: String, headers: Map<String, String>): String? {
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    return try {
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Throwable) {
        null
    } finally {
        conn.disconnect()
    }
}
