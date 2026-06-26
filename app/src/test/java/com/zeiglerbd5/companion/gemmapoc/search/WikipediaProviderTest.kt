package com.zeiglerbd5.companion.gemmapoc.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the iOS `WikipediaProviderTests.swift`. Exercises the JSON decode +
 * URL construction ([WikipediaProvider.parseResults]) against captured
 * fixture bytes — no network. `org.json` is on the unit-test classpath so
 * this runs without Robolectric.
 *
 * One intentional divergence from iOS: malformed JSON throws
 * [WebSearchError.ParseFailure] (same as iOS), but valid JSON with no
 * `query`/`pages` returns an empty list rather than throwing — that's a real
 * no-results, not a broken response.
 */
class WikipediaProviderTest {

    private val twoResultJson = """
        {
          "query": {
            "pages": [
              {
                "index": 1,
                "title": "Helena, Montana",
                "extract": "Helena is the capital city of the U.S. state of Montana and the county seat of Lewis and Clark County."
              },
              {
                "index": 2,
                "title": "Eiffel Tower",
                "extract": "The Eiffel Tower is a wrought-iron lattice tower on the Champ de Mars in Paris, France."
              }
            ]
          }
        }
    """.trimIndent()

    @Test fun happyPath() {
        val results = WikipediaProvider().parseResults(twoResultJson)
        assertEquals(2, results.size)
        assertEquals("Helena, Montana", results[0].title)
        assertTrue(results[0].snippet.startsWith("Helena is the capital"))
        assertEquals("https://en.wikipedia.org/wiki/Helena,_Montana", results[0].url)
        assertEquals("Wikipedia", results[0].source)
        assertEquals("Eiffel Tower", results[1].title)
        assertEquals("https://en.wikipedia.org/wiki/Eiffel_Tower", results[1].url)
    }

    @Test fun ordersByIndex() {
        val shuffled = """
            {
              "query": {
                "pages": [
                  {"index": 3, "title": "C", "extract": "third"},
                  {"index": 1, "title": "A", "extract": "first"},
                  {"index": 2, "title": "B", "extract": "second"}
                ]
              }
            }
        """.trimIndent()
        assertEquals(listOf("A", "B", "C"), WikipediaProvider().parseResults(shuffled).map { it.title })
    }

    @Test fun nilIndexAtEnd() {
        val json = """
            {
              "query": {
                "pages": [
                  {"title": "NoIndex", "extract": "x"},
                  {"index": 1, "title": "First", "extract": "y"}
                ]
              }
            }
        """.trimIndent()
        assertEquals(listOf("First", "NoIndex"), WikipediaProvider().parseResults(json).map { it.title })
    }

    @Test fun missingExtract() {
        val json = """
            { "query": { "pages": [ {"index": 1, "title": "BareTitle"} ] } }
        """.trimIndent()
        val results = WikipediaProvider().parseResults(json)
        assertEquals(1, results.size)
        assertEquals("BareTitle", results[0].title)
        assertTrue(results[0].snippet.isEmpty())
    }

    @Test fun trimsExtract() {
        val json = """
            { "query": { "pages": [ {"index": 1, "title": "T", "extract": "\n   some text   \n\n"} ] } }
        """.trimIndent()
        assertEquals("some text", WikipediaProvider().parseResults(json)[0].snippet)
    }

    @Test fun emptyPages() {
        assertTrue(WikipediaProvider().parseResults("""{ "query": { "pages": [] } }""").isEmpty())
    }

    @Test fun missingQuery() {
        assertTrue(WikipediaProvider().parseResults("""{ "batchcomplete": true }""").isEmpty())
    }

    @Test fun spacesToUnderscores() {
        val json = """
            { "query": { "pages": [ {"index": 1, "title": "World War II", "extract": "x"} ] } }
        """.trimIndent()
        assertEquals(
            "https://en.wikipedia.org/wiki/World_War_II",
            WikipediaProvider().parseResults(json)[0].url,
        )
    }

    @Test fun malformedJsonThrows() {
        var thrown: Throwable? = null
        try {
            WikipediaProvider().parseResults("this is not JSON")
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected WebSearchError on malformed JSON", thrown is WebSearchError)
    }

    @Test fun sourceTagged() {
        assertTrue(WikipediaProvider().parseResults(twoResultJson).all { it.source == "Wikipedia" })
    }
}
