package com.zeiglerbd5.companion.gemmapoc.search

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Searches Wikipedia via the public full-text search API, pulling each
 * hit's article intro in the same round-trip. No key, no signup. Port of
 * the iOS `WikipediaProvider`.
 *
 *   https://en.wikipedia.org/w/api.php
 *     ?action=query&generator=search&gsrsearch=Q&gsrlimit=5
 *     &prop=extracts&exintro&explaintext&exchars=500&format=json&formatversion=2
 *
 * `generator=search` + `prop=extracts` returns the top-N hits AND each
 * hit's plain-text intro in one call — the bare search snippets are too
 * short (~25 words) to ground Gemma reliably.
 */
class WikipediaProvider : WebSearchProvider {
    override val name = "Wikipedia"

    override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val url = "https://en.wikipedia.org/w/api.php?" + listOf(
            "action=query",
            "generator=search",
            "gsrsearch=" + URLEncoder.encode(q, "UTF-8"),
            "gsrlimit=5",
            "prop=extracts",
            "exintro=1",
            "explaintext=1",
            "exchars=500",
            "format=json",
            "formatversion=2",
        ).joinToString("&")

        val json = httpGet(
            url,
            mapOf("User-Agent" to "Companion-ai-Android/0.1 (https://github.com/zeiglerbd5/GemmaPOC)"),
        ) ?: return@withContext emptyList()

        parseResults(json)
    }

    /**
     * Non-private so JSON decoding + URL construction can be exercised
     * against captured fixture bytes in tests without a real network
     * round-trip. Throws [WebSearchError.ParseFailure] when the body isn't
     * valid JSON (a 200 with a broken shape), but returns an empty list for
     * valid JSON that simply has no `query`/`pages` — that's a real
     * no-results, not a parse error.
     *
     * Response shape (formatversion=2 with generator=search):
     *   { "query": { "pages": [ { "index": 1, "title": …, "extract": … } ] } }
     * `index` preserves search-relevance ordering; the API doesn't sort
     * pages by relevance otherwise.
     */
    @VisibleForTesting
    internal fun parseResults(json: String): List<SearchResult> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw WebSearchError.ParseFailure("unexpected Wikipedia response shape")
        }
        val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()

        val indexed = ArrayList<Pair<Int, SearchResult>>(pages.length())
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val title = page.optString("title").takeIf { it.isNotEmpty() } ?: continue
            val extract = page.optString("extract").trim()
            val index = page.optInt("index", Int.MAX_VALUE)
            indexed += index to SearchResult(
                title = title,
                snippet = extract,
                url = articleUrl(title),
                source = name,
            )
        }
        return indexed.sortedBy { it.first }.map { it.second }
    }

    private fun articleUrl(title: String): String =
        "https://en.wikipedia.org/wiki/" + title.replace(" ", "_")
}
