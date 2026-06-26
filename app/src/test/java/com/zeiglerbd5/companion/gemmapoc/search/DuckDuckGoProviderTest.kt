package com.zeiglerbd5.companion.gemmapoc.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the iOS `DuckDuckGoProviderTests.swift`. Exercises the regex
 * scrape ([DuckDuckGoProvider.parseResults]) against captured fixture HTML —
 * no network. Pure-JVM: the `uddg` redirect decode no longer uses
 * `android.net.Uri`, so these run as local unit tests.
 */
class DuckDuckGoProviderTest {

    private val twoResultHtml = """
        <html><body>
          <div class="result">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage1&rut=abc">First Page Title</a>
            </h2>
            <a class="result__snippet" href="//x.example/y">First page snippet here.</a>
          </div>
          <div class="result">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="https://example.org/page2">Second Page Title</a>
            </h2>
            <a class="result__snippet" href="//x.example/z">Second page snippet here.</a>
          </div>
        </body></html>
    """.trimIndent()

    @Test fun happyPath() {
        val results = DuckDuckGoProvider().parseResults(twoResultHtml)
        assertEquals(2, results.size)
        assertEquals("First Page Title", results[0].title)
        assertEquals("First page snippet here.", results[0].snippet)
        assertEquals("https://example.com/page1", results[0].url)
        assertEquals("DuckDuckGo", results[0].source)
        assertEquals("Second Page Title", results[1].title)
        assertEquals("Second page snippet here.", results[1].snippet)
        assertEquals("https://example.org/page2", results[1].url)
    }

    @Test fun redirectDecoded() {
        val html = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FEiffel_Tower&rut=abc">Title</a>
            <a class="result__snippet" href="x">Snippet</a>
        """.trimIndent()
        val results = DuckDuckGoProvider().parseResults(html)
        assertEquals(1, results.size)
        assertEquals("https://en.wikipedia.org/wiki/Eiffel_Tower", results[0].url)
    }

    @Test fun directUrlPassthrough() {
        val html = """
            <a rel="nofollow" class="result__a" href="https://example.com/direct">Direct</a>
            <a class="result__snippet" href="x">Snippet</a>
        """.trimIndent()
        val results = DuckDuckGoProvider().parseResults(html)
        assertEquals("https://example.com/direct", results[0].url)
    }

    @Test fun htmlEntitiesDecoded() {
        val html = """
            <a rel="nofollow" class="result__a" href="https://x/">R&amp;D and &lt;tag&gt; with &quot;quotes&quot; and Joe&#39;s</a>
            <a class="result__snippet" href="x">&nbsp;leading space then 1 &amp; 2</a>
        """.trimIndent()
        val results = DuckDuckGoProvider().parseResults(html)
        assertEquals("R&D and <tag> with \"quotes\" and Joe's", results[0].title)
        assertEquals("leading space then 1 & 2", results[0].snippet)
    }

    @Test fun nestedTagsStripped() {
        val html = """
            <a rel="nofollow" class="result__a" href="https://x/"><b>Helena</b>, Montana</a>
            <a class="result__snippet" href="x">A <b>capital</b> city.</a>
        """.trimIndent()
        val results = DuckDuckGoProvider().parseResults(html)
        assertEquals("Helena, Montana", results[0].title)
        assertEquals("A capital city.", results[0].snippet)
    }

    @Test fun noResultMarkers() {
        val results = DuckDuckGoProvider().parseResults("<html><body><h1>About Page</h1></body></html>")
        assertTrue(results.isEmpty())
    }

    @Test fun mismatchedCounts() {
        val html = """
            <a rel="nofollow" class="result__a" href="https://x/">A</a>
            <a rel="nofollow" class="result__a" href="https://y/">B</a>
        """.trimIndent()
        val results = DuckDuckGoProvider().parseResults(html)
        assertTrue(results.isEmpty())
    }

    @Test fun capsAtFive() {
        val html = buildString {
            for (i in 1..8) {
                append("""<a rel="nofollow" class="result__a" href="https://example.com/page$i">Page $i</a>""")
                append("\n")
                append("""<a class="result__snippet" href="x">Snippet $i</a>""")
                append("\n\n")
            }
        }
        val results = DuckDuckGoProvider().parseResults(html)
        assertEquals(5, results.size)
        assertEquals(listOf("Page 1", "Page 2", "Page 3", "Page 4", "Page 5"), results.map { it.title })
    }

    @Test fun sourceTagged() {
        val results = DuckDuckGoProvider().parseResults(twoResultHtml)
        assertTrue(results.all { it.source == "DuckDuckGo" })
    }
}
