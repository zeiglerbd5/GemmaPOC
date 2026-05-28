package com.zeiglerbd5.companion.gemmapoc.search

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Front door for web search. Port of the iOS `SearchRouter`. Runs both
 * providers in parallel and merges the top results, ordered by the
 * router's preference for the query shape (Wikipedia-first for
 * encyclopedic / named-entity queries, DuckDuckGo-first otherwise).
 */
class SearchRouter(
    private val wikipedia: WikipediaProvider = WikipediaProvider(),
    private val duckduckgo: DuckDuckGoProvider = DuckDuckGoProvider(),
) {
    suspend fun searchBoth(query: String): List<SearchResult> = coroutineScope {
        val wikiTask = async { runCatching { wikipedia.search(query) }.getOrDefault(emptyList()) }
        val ddgTask = async { runCatching { duckduckgo.search(query) }.getOrDefault(emptyList()) }
        val wikiResults = wikiTask.await()
        val ddgResults = ddgTask.await()

        if (wikiResults.isEmpty() && ddgResults.isEmpty()) return@coroutineScope emptyList()

        val wikipediaFirst = isEncyclopedic(query) || looksLikeNamedEntity(query)
        val primary = if (wikipediaFirst) wikiResults else ddgResults
        val secondary = if (wikipediaFirst) ddgResults else wikiResults

        // 3 total: 2 from primary, 1 from secondary.
        buildList {
            addAll(primary.take(2))
            addAll(secondary.take(1))
        }
    }

    /** Question-stem detection → Wikipedia is usually the right hit. */
    private fun isEncyclopedic(query: String): Boolean {
        val q = query.lowercase().trim()
        val prefixes = listOf(
            "what is ", "what are ", "what was ", "what were ",
            "who is ", "who are ", "who was ", "who were ",
            "when did ", "when was ", "when is ",
            "where is ", "where was ",
            "why is ", "why did ",
            "how does ", "how do ",
            "tell me about ", "define ",
        )
        return prefixes.any { q.startsWith(it) }
    }

    /**
     * Heuristic: query looks like a named-entity lookup. Mid-sentence
     * capitalised words (proper nouns) OR geographic / physical-feature
     * keywords. The keyword half catches Gemma's flat lowercase queries
     * ("stillwater river orono maine").
     */
    private fun looksLikeNamedEntity(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return false

        val words = trimmed.split(Regex("[\\s,]+"))
        for ((i, raw) in words.withIndex()) {
            if (i == 0) continue
            val w = raw.trim { it in ".,;:!?\"'()" }
            val first = w.firstOrNull() ?: continue
            if (first.isUpperCase() && w != "I") return true
        }

        val q = trimmed.lowercase()
        val entityMarkers = listOf(
            " river", " mountain", " lake", " ocean", " sea", " bay", " gulf",
            " county", " city", " town", " village", " state", " province",
            " country", " island", " park", " forest", " desert", " canyon",
            " bridge", " museum", " university", " college",
        )
        return entityMarkers.any { q.contains(it) }
    }
}
