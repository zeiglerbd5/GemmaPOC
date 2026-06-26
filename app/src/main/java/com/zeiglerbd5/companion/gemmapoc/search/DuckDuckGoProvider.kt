package com.zeiglerbd5.companion.gemmapoc.search

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Searches DuckDuckGo's HTML-only endpoint and scrapes the result list.
 * No key, no signup. Port of the iOS `DuckDuckGoProvider`.
 *
 *   https://html.duckduckgo.com/html/?q=Q
 *
 * Informal-tolerated, not contractual — when DDG reshapes their HTML the
 * regexes below need re-tuning against a fresh raw response.
 */
class DuckDuckGoProvider : WebSearchProvider {
    override val name = "DuckDuckGo"

    private val titleRegex = Regex(
        """<a\s+rel="nofollow"\s+class="result__a"\s+href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val snippetRegex = Regex(
        """<a\s+class="result__snippet"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
        val html = httpGet(
            url,
            mapOf(
                // Identify as a normal mobile browser so DDG doesn't serve a
                // different layout to an unknown User-Agent.
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html",
            ),
        )
        if (html == null) {
            Log.w(TAG, "request failed (network error or non-2xx) for query: \"$q\"")
            return@withContext emptyList()
        }

        val results = parseResults(html)
        if (results.isEmpty()) {
            // 200 OK but zero parsed results almost always means DDG reshaped
            // their HTML and titleRegex/snippetRegex no longer match. The
            // SearchRouter still falls back to Wikipedia, so this would fail
            // silently without the log — which is exactly the "search went
            // quiet and we don't know why" trap this warning exists to avoid.
            Log.w(
                TAG,
                "0 results parsed from ${html.length} chars of HTML for query \"$q\" — " +
                    "DDG markup may have changed; re-check titleRegex/snippetRegex against a fresh response",
            )
        }
        results
    }

    /**
     * Non-private so the regex-based scrape can be exercised against
     * captured fixture HTML in tests without doing a real network call.
     */
    @VisibleForTesting
    internal fun parseResults(html: String): List<SearchResult> {
        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()
        val n = minOf(titles.size, snippets.size, 5)
        return (0 until n).mapNotNull { i ->
            val rawUrl = titles[i].groupValues[1]
            val real = extractRealUrl(rawUrl) ?: rawUrl
            SearchResult(
                title = stripHtml(titles[i].groupValues[2]),
                snippet = stripHtml(snippets[i].groupValues[1]),
                url = real,
                source = name,
            )
        }
    }

    /**
     * `//duckduckgo.com/l/?uddg=ENCODED&rut=…` → decoded ENCODED. Returns
     * the input unchanged if it already looks like a real URL. Pure-JVM
     * (no `android.net.Uri`) so [parseResults] is unit-testable off-device:
     * pull the `uddg` query param out by hand and percent-decode it.
     */
    private fun extractRealUrl(ddgUrl: String): String? {
        if (ddgUrl.startsWith("http")) return ddgUrl
        val queryStart = ddgUrl.indexOf('?')
        if (queryStart < 0) return null
        val query = ddgUrl.substring(queryStart + 1)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) == "uddg") {
                val encoded = pair.substring(eq + 1)
                return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            }
        }
        return null
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()

    private companion object {
        const val TAG = "DuckDuckGoProvider"
    }
}
