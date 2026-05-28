package com.zeiglerbd5.companion.gemmapoc.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        parse(json)
    }

    private fun parse(json: String): List<SearchResult> {
        // { "query": { "pages": [ { "index": 1, "title": …, "extract": … } ] } }
        // `index` preserves search-relevance ordering; the API doesn't sort
        // pages by relevance otherwise.
        val pages = runCatching {
            JSONObject(json).optJSONObject("query")?.optJSONArray("pages")
        }.getOrNull() ?: return emptyList()

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
