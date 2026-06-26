package com.zeiglerbd5.companion.gemmapoc

import com.zeiglerbd5.companion.gemmapoc.search.SearchResult
import com.zeiglerbd5.companion.gemmapoc.search.SearchRouter
import com.zeiglerbd5.companion.gemmapoc.search.WebSearchProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the iOS `SearchRouterTests.swift`. Exercises both the merge path
 * ([SearchRouter.searchBoth], used by the agent) and the single-provider
 * fallback path ([SearchRouter.search], used by `/search`) with in-memory
 * fake providers. `runTest` runs the `async` provider calls on a single
 * cooperative dispatcher, so the fakes don't need locking.
 */
class SearchRouterTest {

    private class FakeError : Exception()

    /** In-memory fake. Records every query, returns stubbed results or throws. */
    private class FakeProvider(override val name: String) : WebSearchProvider {
        private var stubbedResults: List<SearchResult> = emptyList()
        private var stubbedError: Throwable? = null
        val queries = mutableListOf<String>()

        fun stub(results: List<SearchResult>) {
            stubbedResults = results
            stubbedError = null
        }

        fun stub(error: Throwable) {
            stubbedError = error
        }

        override suspend fun search(query: String): List<SearchResult> {
            queries.add(query)
            stubbedError?.let { throw it }
            return stubbedResults
        }
    }

    private fun makeResult(title: String, source: String) = SearchResult(
        title = title,
        snippet = "snippet-$title",
        url = "https://example.com/$title",
        source = source,
    )

    private class Rig(val router: SearchRouter, val wiki: FakeProvider, val ddg: FakeProvider)

    /** Both fakes preloaded with 3 distinct, source-tagged results. */
    private fun makeRig(): Rig {
        val wiki = FakeProvider("Wikipedia")
        val ddg = FakeProvider("DuckDuckGo")
        wiki.stub(
            listOf(
                makeResult("W1", "Wikipedia"),
                makeResult("W2", "Wikipedia"),
                makeResult("W3", "Wikipedia"),
            ),
        )
        ddg.stub(
            listOf(
                makeResult("D1", "DuckDuckGo"),
                makeResult("D2", "DuckDuckGo"),
                makeResult("D3", "DuckDuckGo"),
            ),
        )
        return Rig(SearchRouter(wikipedia = wiki, duckduckgo = ddg), wiki, ddg)
    }

    // region searchBoth — Wikipedia-first routing

    @Test fun encyclopedicRoutesWiki() = runTest {
        val queries = listOf(
            "what is the Eiffel Tower", "what are black holes", "what was the Renaissance",
            "what were the Crusades", "who is Albert Einstein", "who are the Beatles",
            "who was Marie Curie", "who were the Vikings", "when did World War II end",
            "when was the moon landing", "when is the next eclipse", "where is Helena",
            "where was the treaty signed", "why is the sky blue", "why did the Roman Empire fall",
            "how does photosynthesis work", "how do bees make honey",
            "tell me about quantum mechanics", "define entropy",
        )
        for (q in queries) {
            val results = makeRig().router.searchBoth(q)
            assertEquals("query: $q", 3, results.size)
            assertEquals("query: $q", "Wikipedia", results[0].source)
            assertEquals("query: $q", "Wikipedia", results[1].source)
            assertEquals("query: $q", "DuckDuckGo", results[2].source)
        }
    }

    @Test fun encyclopedicCaseInsensitive() = runTest {
        for (q in listOf("WHAT IS the Eiffel Tower", "Tell Me About Helena")) {
            val results = makeRig().router.searchBoth(q)
            assertEquals("query: $q", "Wikipedia", results[0].source)
        }
    }

    @Test fun capitalisedNamedEntityRoutesWiki() = runTest {
        for (q in listOf("weather in New York", "best things to do in Helena Montana", "directions to the Eiffel Tower")) {
            val results = makeRig().router.searchBoth(q)
            assertEquals("query: $q", "Wikipedia", results[0].source)
        }
    }

    @Test fun lowercaseEntityMarkerRoutesWiki() = runTest {
        val queries = listOf(
            "stillwater river orono maine", "tallest mountain in the world",
            "largest lake by surface area", "yellowstone national park entrance fee",
            "old stone bridge in prague", "natural history museum hours",
        )
        for (q in queries) {
            val results = makeRig().router.searchBoth(q)
            assertEquals("query: $q", "Wikipedia", results[0].source)
        }
    }

    @Test fun firstWordCapNotTriggered() = runTest {
        val results = makeRig().router.searchBoth("Apple stock price today")
        assertEquals("DuckDuckGo", results[0].source)
    }

    @Test fun midSentenceIExcluded() = runTest {
        val results = makeRig().router.searchBoth("what should I do today")
        assertEquals("DuckDuckGo", results[0].source)
    }

    // endregion

    // region searchBoth — DuckDuckGo-first routing

    @Test fun plainQueryRoutesDDG() = runTest {
        for (q in listOf("best pizza near me", "weather forecast tomorrow", "cheap flights to anywhere", "good books to read")) {
            val results = makeRig().router.searchBoth(q)
            assertEquals("query: $q", 3, results.size)
            assertEquals("query: $q", "DuckDuckGo", results[0].source)
            assertEquals("query: $q", "DuckDuckGo", results[1].source)
            assertEquals("query: $q", "Wikipedia", results[2].source)
        }
    }

    // endregion

    // region searchBoth — merge and error handling

    @Test fun bothEmpty() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(emptyList()) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub(emptyList()) }
        val results = SearchRouter(wiki, ddg).searchBoth("what is anything")
        assertTrue(results.isEmpty())
    }

    @Test fun primaryEmptyUsesSecondary() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(emptyList()) }
        val ddg = FakeProvider("DuckDuckGo").apply {
            stub(listOf(makeResult("D1", "DuckDuckGo"), makeResult("D2", "DuckDuckGo")))
        }
        val results = SearchRouter(wiki, ddg).searchBoth("what is anything")
        assertEquals(1, results.size)
        assertEquals("DuckDuckGo", results[0].source)
    }

    @Test fun errorSwallowed() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(FakeError()) }
        val ddg = FakeProvider("DuckDuckGo").apply {
            stub(listOf(makeResult("D1", "DuckDuckGo"), makeResult("D2", "DuckDuckGo"), makeResult("D3", "DuckDuckGo")))
        }
        val results = SearchRouter(wiki, ddg).searchBoth("what is anything")
        assertEquals(1, results.size)
        assertEquals("DuckDuckGo", results[0].source)
    }

    @Test fun bothThrow() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(FakeError()) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub(FakeError()) }
        val results = SearchRouter(wiki, ddg).searchBoth("what is anything")
        assertTrue(results.isEmpty())
    }

    @Test fun mergeShape() = runTest {
        val results = makeRig().router.searchBoth("what is the Eiffel Tower")
        assertEquals(3, results.size)
        assertEquals(listOf("W1", "W2", "D1"), results.map { it.title })
    }

    @Test fun primaryTruncated() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub((1..5).map { makeResult("W$it", "Wikipedia") }) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub((1..5).map { makeResult("D$it", "DuckDuckGo") }) }
        val results = SearchRouter(wiki, ddg).searchBoth("what is anything")
        assertEquals(listOf("W1", "W2", "D1"), results.map { it.title })
    }

    @Test fun queryPassedThrough() = runTest {
        val rig = makeRig()
        rig.router.searchBoth("Helena Montana population")
        assertEquals(listOf("Helena Montana population"), rig.wiki.queries)
        assertEquals(listOf("Helena Montana population"), rig.ddg.queries)
    }

    // endregion

    // region search — single-provider with fallback

    @Test fun primarySatisfies() = runTest {
        val rig = makeRig()
        val results = rig.router.search("what is the Eiffel Tower")
        assertTrue(results.all { it.source == "Wikipedia" })
        assertEquals(1, rig.wiki.queries.size)
        assertTrue("secondary should not be called when primary returned results", rig.ddg.queries.isEmpty())
    }

    @Test fun primaryEmptyFallsThrough() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(emptyList()) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub(listOf(makeResult("D1", "DuckDuckGo"))) }
        val results = SearchRouter(wiki, ddg).search("what is anything")
        assertEquals(1, results.size)
        assertEquals("DuckDuckGo", results[0].source)
        assertEquals(listOf("what is anything"), ddg.queries)
    }

    @Test fun primaryThrowsFallsThrough() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(FakeError()) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub(listOf(makeResult("D1", "DuckDuckGo"))) }
        val results = SearchRouter(wiki, ddg).search("what is anything")
        assertEquals(1, results.size)
        assertEquals("DuckDuckGo", results[0].source)
    }

    @Test fun plainQueryHitsDDGFirst() = runTest {
        val rig = makeRig()
        val results = rig.router.search("best pizza near me")
        assertTrue(results.all { it.source == "DuckDuckGo" })
        assertEquals(1, rig.ddg.queries.size)
        assertTrue("wikipedia secondary should not be called when DDG returned results", rig.wiki.queries.isEmpty())
    }

    @Test fun bothFailPropagates() = runTest {
        val wiki = FakeProvider("Wikipedia").apply { stub(FakeError()) }
        val ddg = FakeProvider("DuckDuckGo").apply { stub(FakeError()) }
        var thrown: Throwable? = null
        try {
            SearchRouter(wiki, ddg).search("what is anything")
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected FakeError to propagate", thrown is FakeError)
    }

    // endregion
}
