package com.zeiglerbd5.companion.gemmapoc

import com.zeiglerbd5.companion.gemmapoc.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the iOS `PromptParsingFormatterTests.swift`. Covers the three
 * result formatters: the RAG block fed to the model ([PromptParsing.formatSearchContext]),
 * the user-facing `/search` markdown ([PromptParsing.formatResults]), and the
 * search-tool bubble ([PromptParsing.renderToolBreadcrumb]).
 */
class PromptParsingFormatterTest {

    private fun makeResult(
        title: String,
        snippet: String = "snippet text",
        url: String = "https://example.com/article",
        source: String = "Wikipedia",
    ) = SearchResult(title = title, snippet = snippet, url = url, source = source)

    // region formatSearchContext

    @Test fun emptyResultsCanned() {
        val block = PromptParsing.formatSearchContext(emptyList())
        assertTrue(block.contains("Web search returned no results"))
        assertFalse(block.contains("Web search results"))
    }

    @Test fun headerPresent() {
        val block = PromptParsing.formatSearchContext(listOf(makeResult(title = "T")))
        assertTrue(block.contains("Web search results"))
    }

    @Test fun resultLineShape() {
        val block = PromptParsing.formatSearchContext(
            listOf(makeResult(title = "Eiffel Tower", snippet = "built 1889", url = "https://en.wikipedia.org/wiki/Eiffel_Tower")),
        )
        assertTrue(block.contains("- Eiffel Tower: built 1889 [en.wikipedia.org]"))
    }

    @Test fun emptySnippetFallsBackToTitle() {
        val block = PromptParsing.formatSearchContext(
            listOf(makeResult(title = "Helena, Montana", snippet = "", url = "https://en.wikipedia.org/wiki/Helena,_Montana")),
        )
        assertTrue(block.contains("- Helena, Montana: Helena, Montana"))
    }

    @Test fun noHostFallsBackToSource() {
        val block = PromptParsing.formatSearchContext(
            listOf(makeResult(title = "T", url = "data:text/plain,hello", source = "DuckDuckGo")),
        )
        assertTrue(block.contains("[DuckDuckGo]"))
    }

    @Test fun verbatimDirective() {
        val block = PromptParsing.formatSearchContext(listOf(makeResult(title = "T")))
        assertTrue(block.contains("copy it EXACTLY"))
    }

    @Test fun couldntFindLicense() {
        val block = PromptParsing.formatSearchContext(listOf(makeResult(title = "T")))
        assertTrue(block.contains("couldn't find that"))
    }

    @Test fun noSecondSearchDirective() {
        val block = PromptParsing.formatSearchContext(listOf(makeResult(title = "T")))
        assertTrue(block.contains("Do not emit another SEARCH:"))
    }

    @Test fun contextPreservesOrder() {
        val block = PromptParsing.formatSearchContext(
            listOf(makeResult(title = "First"), makeResult(title = "Second"), makeResult(title = "Third")),
        )
        assertTrue(block.indexOf("First") < block.indexOf("Second"))
        assertTrue(block.indexOf("Second") < block.indexOf("Third"))
    }

    // endregion

    // region formatResults

    @Test fun formatResultsEmpty() {
        assertEquals("No results for \"Helena\".", PromptParsing.formatResults("Helena", emptyList()))
    }

    @Test fun headerHasQuery() {
        val out = PromptParsing.formatResults("Helena Montana", listOf(makeResult(title = "T")))
        assertTrue(out.startsWith("Results for \"Helena Montana\":"))
    }

    @Test fun numberedList() {
        val out = PromptParsing.formatResults(
            "q",
            listOf(makeResult(title = "A"), makeResult(title = "B"), makeResult(title = "C")),
        )
        assertTrue(out.contains("1. [A]"))
        assertTrue(out.contains("2. [B]"))
        assertTrue(out.contains("3. [C]"))
    }

    @Test fun markdownLink() {
        val out = PromptParsing.formatResults(
            "q",
            listOf(makeResult(title = "Eiffel Tower", url = "https://en.wikipedia.org/wiki/Eiffel_Tower")),
        )
        assertTrue(out.contains("[Eiffel Tower](https://en.wikipedia.org/wiki/Eiffel_Tower)"))
    }

    @Test fun snippetLinePresent() {
        val out = PromptParsing.formatResults("q", listOf(makeResult(title = "T", snippet = "the snippet body")))
        assertTrue(out.contains("   the snippet body"))
    }

    @Test fun snippetLineOmittedWhenEmpty() {
        val out = PromptParsing.formatResults("q", listOf(makeResult(title = "T", snippet = "", url = "https://example.com/a")))
        assertTrue(out.contains("[T](https://example.com/a)"))
        assertFalse(out.contains("   \n   _"))
    }

    @Test fun hostLineItalic() {
        val out = PromptParsing.formatResults("q", listOf(makeResult(title = "T", url = "https://en.wikipedia.org/wiki/X")))
        assertTrue(out.contains("   _en.wikipedia.org_"))
    }

    @Test fun hostLineOmittedNoHost() {
        val out = PromptParsing.formatResults("q", listOf(makeResult(title = "T", url = "data:text/plain,hello")))
        assertFalse(out.contains("_data_"))
        assertFalse(out.contains("__"))
    }

    @Test fun truncatesAtFive() {
        val results = (1..8).map { makeResult(title = "R$it") }
        val out = PromptParsing.formatResults("q", results)
        for (i in 1..5) assertTrue("expected R$i", out.contains("[R$i]"))
        for (i in 6..8) assertFalse("did not expect R$i", out.contains("[R$i]"))
    }

    // endregion

    // region renderToolBreadcrumb

    @Test fun breadcrumbEmptyResults() {
        assertEquals("🔍 Looked up: _Helena_ — no results.", PromptParsing.renderToolBreadcrumb("Helena", emptyList()))
    }

    @Test fun headerItalicQuery() {
        val out = PromptParsing.renderToolBreadcrumb("Eiffel Tower", listOf(makeResult(title = "T")))
        assertTrue(out.startsWith("🔍 Looked up: _Eiffel Tower_"))
    }

    @Test fun sourcesSection() {
        val out = PromptParsing.renderToolBreadcrumb(
            "q",
            listOf(
                makeResult(title = "A", url = "https://example.com/a"),
                makeResult(title = "B", url = "https://example.com/b"),
            ),
        )
        assertTrue(out.contains("Sources:"))
        assertTrue(out.contains("- [A](https://example.com/a)"))
        assertTrue(out.contains("- [B](https://example.com/b)"))
    }

    @Test fun capsAtThree() {
        val results = (1..5).map { makeResult(title = "R$it", url = "https://example.com/r$it") }
        val out = PromptParsing.renderToolBreadcrumb("q", results)
        for (i in 1..3) assertTrue("expected R$i", out.contains("[R$i]"))
        for (i in 4..5) assertFalse("did not expect R$i", out.contains("[R$i]"))
    }

    @Test fun breadcrumbPreservesOrder() {
        val out = PromptParsing.renderToolBreadcrumb(
            "q",
            listOf(
                makeResult(title = "Alpha", url = "https://example.com/alpha"),
                makeResult(title = "Beta", url = "https://example.com/beta"),
                makeResult(title = "Gamma", url = "https://example.com/gamma"),
            ),
        )
        assertTrue(out.indexOf("Alpha") < out.indexOf("Beta"))
        assertTrue(out.indexOf("Beta") < out.indexOf("Gamma"))
    }

    // endregion
}
