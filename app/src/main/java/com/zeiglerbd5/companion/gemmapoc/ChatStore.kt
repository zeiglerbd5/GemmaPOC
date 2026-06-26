package com.zeiglerbd5.companion.gemmapoc

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Engine
import com.zeiglerbd5.companion.gemmapoc.search.SearchRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the visible chat transcript and drives prompt-response cycles, plus
 * the agentic-search loop. Full port of the iOS sibling's `ConversationStore`
 * + `PromptRunner` split: the inference itself lives behind a [PromptRunning]
 * seam (so this can be unit-tested without a real LiteRT-LM [Engine]), while
 * this type owns the orchestration:
 *
 *  - `/search …` slash command → single-provider lookup, no inference
 *  - prompt-extraction defense → canned refusal before the model is reached
 *  - ephemeral hints (privacy grounding, search-disabled note)
 *  - the SEARCH: tool loop (model asks → providers run → grounded re-ask)
 *  - post-decode fact check + best-of-N rerank against the search context
 *
 * Streaming output updates the bubble in place. The [searchEnabled] and
 * [detailedMode] toggles mirror the iOS menu switches.
 */
class ChatStore @JvmOverloads constructor(
    private val searchRouter: SearchRouter = SearchRouter(),
) : ViewModel() {

    sealed interface Status {
        data object Idle : Status
        data object Sending : Status
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    val isResponding: Boolean get() = _status.value is Status.Sending

    /** Last error surfaced to the UI. Reset by [clear]. */
    var lastError: String? = null

    /**
     * Last search-context block fed to the model on a search-grounded turn.
     * Captured for benchmark introspection; reset at the start of every send.
     */
    var lastSearchContextSent: String? = null
        private set

    /**
     * Numbers in the most recent search-grounded reply that didn't appear in
     * the search context — likely faithfulness failures. Empty (or null)
     * when nothing suspicious was found. Reset on each send.
     */
    var lastFactCheckWarnings: List<String>? = null
        private set

    /**
     * Number of best-of-N reranking retries used on the last search-grounded
     * turn. 0 means the first answer passed fact check. null for non-search
     * turns. Reset on each send.
     */
    var lastFactCheckRetries: Int? = null
        private set

    /** "In Depth" toggle — longer, more thorough replies when on. */
    var detailedMode: Boolean = false

    /**
     * When true (default), the agent may emit SEARCH: directives that hit
     * Wikipedia + DuckDuckGo. When false, any SEARCH directive is swallowed
     * and the model is nudged via ephemeral hint to answer from its own
     * knowledge.
     */
    var searchEnabled: Boolean = true

    /** Internal topic anchor — first user message, truncated. Not shown. */
    private var topic: String = ""

    /**
     * Lazily-created inference runner. Bound to the first engine seen in
     * [send]. Non-private so tests can pre-inject a fake [PromptRunning] and
     * skip the engine-init path.
     */
    var runner: PromptRunning? = null

    // MARK: - Entry points

    /**
     * UI entry-point: builds the [PromptRunner] lazily from [engine] on the
     * first call, then runs the engine-free [send] core on the view-model
     * scope.
     */
    fun send(engine: Engine, text: String) {
        if (isResponding) return
        viewModelScope.launch {
            if (runner == null) runner = PromptRunner(engine)
            send(text)
        }
    }

    /**
     * Engine-free core. Requires [runner] to already be configured (either by
     * [send] having built it, or by a test injecting one). Holds the bulk of
     * the conversation logic.
     */
    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isResponding) return
        lastSearchContextSent = null
        lastFactCheckWarnings = null
        lastFactCheckRetries = null

        PromptParsing.parseSearchCommand(trimmed)?.let { query ->
            runSearch(rawCommand = trimmed, query = query)
            return
        }

        // Prompt-extraction defense: short-circuit obvious "tell me your
        // system prompt" / "ignore previous instructions" attempts before
        // they reach the model.
        if (PromptParsing.looksLikePromptExtraction(trimmed)) {
            append(ChatMessage(ChatRole.User, trimmed))
            append(ChatMessage(ChatRole.Model, PromptParsing.PROMPT_EXTRACTION_REFUSAL))
            return
        }

        append(ChatMessage(ChatRole.User, trimmed))
        _status.value = Status.Sending
        val runner = this.runner
        if (runner == null) {
            _status.value = Status.Idle
            return
        }

        try {
            val currentTopic = topic.ifEmpty { null }
            val detailed = detailedMode

            // On-demand context hints — cost tokens only on the turns that
            // need them, keeping the persistent system prompt lean.
            val hints = PromptParsing.ephemeralHints(trimmed).toMutableList()
            if (!searchEnabled) hints += PromptParsing.SEARCH_DISABLED_HINT

            val initialStream = runner.respondStream(
                userMessage = trimmed,
                context = null,
                location = null,
                topic = currentTopic,
                detailed = detailed,
                ephemeralHints = hints,
            )
            // If search is off, treat SEARCH: directives as plain reply text —
            // strip them but never hit the network.
            val outcome = streamReplyIntoBubble(initialStream, detectSearch = searchEnabled)

            if (outcome is StreamOutcome.Search) {
                runSearchGroundedTurn(
                    query = outcome.query,
                    originalQuestion = trimmed,
                    topic = currentTopic,
                    detailed = detailed,
                    runner = runner,
                )
            }

            anchorTopicIfNeeded()
        } catch (t: Throwable) {
            lastError = "Inference failed: ${t.message ?: t::class.simpleName ?: "error"}"
        } finally {
            _status.value = Status.Idle
        }
    }

    fun clear() {
        val r = runner
        runner = null
        _messages.value = emptyList()
        topic = ""
        lastError = null
        _status.value = Status.Idle
        // Discard the model-side history alongside our visible transcript.
        viewModelScope.launch { r?.reset() }
    }

    override fun onCleared() {
        super.onCleared()
        val r = runner
        viewModelScope.launch { r?.reset() }
    }

    // MARK: - Search-grounded turn

    /**
     * The model emitted `SEARCH: query`. Drop a tool breadcrumb, run both
     * providers, fold the merged results back as a follow-up turn, render the
     * grounded reply, then fact-check it with a best-of-N rerank.
     */
    private suspend fun runSearchGroundedTurn(
        query: String,
        originalQuestion: String,
        topic: String?,
        detailed: Boolean,
        runner: PromptRunning,
    ) {
        Log.i(TAG, "parsed SEARCH directive: \"$query\"")
        val tool = ChatMessage(ChatRole.Tool, "🔍 Looking up: _${query}_")
        append(tool)

        val results = runCatching { searchRouter.searchBoth(query) }.getOrDefault(emptyList())
        Log.i(TAG, "searchBoth(\"$query\") → ${results.size} result(s): ${results.map { it.source }}")
        replace(
            tool.id,
            tool.copy(
                text = PromptParsing.renderToolBreadcrumb(query, results),
                source = results.firstOrNull()?.source,
            ),
        )

        val context = PromptParsing.formatSearchContext(results.take(3))
        lastSearchContextSent = context

        // Restate the original question alongside the results — without this
        // the model sometimes loses track of what was asked and
        // free-associates off the results' phrasing.
        val followupStream = runner.respondStream(
            userMessage = "Please answer my original question using the search results below.\n\nMy question: $originalQuestion",
            context = context,
            location = null,
            topic = topic,
            detailed = detailed,
            ephemeralHints = emptyList(),
        )
        // detectSearch = false — if the model still tries SEARCH: we strip the
        // directive line and stream the rest, no recursive lookup.
        streamReplyIntoBubble(followupStream, detectSearch = false)

        factCheckAndRerank(originalQuestion, context, topic, detailed, runner)
    }

    /**
     * Post-decode fact check + best-of-N rerank. Scan the just-committed
     * model bubble for numbers absent from the search context; if any fail,
     * retry silently up to [MAX_FACT_CHECK_RETRIES] times and keep the
     * cleanest answer. If every attempt still has warnings, append a badge.
     */
    private suspend fun factCheckAndRerank(
        originalQuestion: String,
        context: String,
        topic: String?,
        detailed: Boolean,
        runner: PromptRunning,
    ) {
        val modelId = lastModelMessageId() ?: return
        var bestText = messageText(modelId)
        var bestWarnings = PromptParsing.unverifiedNumbers(bestText, context)
        var retriesUsed = 0

        while (bestWarnings.isNotEmpty() && retriesUsed < MAX_FACT_CHECK_RETRIES) {
            retriesUsed++
            Log.i(TAG, "fact check found $bestWarnings — retrying ($retriesUsed/$MAX_FACT_CHECK_RETRIES)")
            val retryText = silentRetrySearchAnswer(originalQuestion, context, topic, detailed, runner)
            val retryWarnings = PromptParsing.unverifiedNumbers(retryText, context)
            if (retryWarnings.size < bestWarnings.size) {
                bestText = retryText
                bestWarnings = retryWarnings
                replaceText(modelId, bestText)
            }
            if (bestWarnings.isEmpty()) break
        }

        lastFactCheckWarnings = bestWarnings
        lastFactCheckRetries = retriesUsed
        if (bestWarnings.isNotEmpty()) {
            val badge = "\n\n⚠️ Could not verify: ${bestWarnings.joinToString(", ")} (not found in search results)"
            replaceText(modelId, messageText(modelId) + badge)
        }
    }

    /**
     * Re-runs the search-grounded inference WITHOUT touching the visible
     * transcript. Used for best-of-N rerank: score a second candidate before
     * deciding whether to swap.
     */
    private suspend fun silentRetrySearchAnswer(
        originalQuestion: String,
        context: String,
        topic: String?,
        detailed: Boolean,
        runner: PromptRunning,
    ): String {
        val stream = runner.respondStream(
            userMessage = "Please answer my original question using the search results below.\n\nMy question: $originalQuestion",
            context = context,
            location = null,
            topic = topic,
            detailed = detailed,
            ephemeralHints = emptyList(),
        )
        val sb = StringBuilder()
        stream.collect { sb.append(it) }
        return PromptParsing.stripStraySearchDirective(sb.toString())
    }

    // MARK: - Streaming helper

    private sealed interface StreamOutcome {
        data object Answered : StreamOutcome
        data class Search(val query: String) : StreamOutcome
    }

    /**
     * Consume a token stream (each emission is an incremental delta) and
     * surface it as an incrementally-updating model bubble.
     *
     * - detectSearch = true: buffer the first line. If it parses as a
     *   `SEARCH: <query>` directive, return [StreamOutcome.Search] without
     *   ever creating a model bubble. Otherwise commit and stream.
     * - detectSearch = false: buffer until any stray leading `SEARCH:` line
     *   can be stripped, then commit and stream the rest.
     *
     * In both modes, once a directive is detected we keep draining the
     * upstream silently so the model finishes its turn cleanly.
     */
    private suspend fun streamReplyIntoBubble(
        stream: Flow<String>,
        detectSearch: Boolean,
    ): StreamOutcome {
        var buffer = ""
        var committed = false
        var modelId = -1L
        var pendingSearch: String? = null

        stream.collect { delta ->
            buffer += delta
            if (pendingSearch != null) return@collect

            if (committed) {
                replaceText(modelId, buffer)
                return@collect
            }

            val nlIdx = buffer.indexOf('\n')
            if (nlIdx >= 0) {
                val firstLine = buffer.substring(0, nlIdx)
                val query = PromptParsing.parseSearchDirective(firstLine)
                if (query != null) {
                    if (detectSearch) {
                        pendingSearch = query
                        return@collect
                    }
                    // Strip stray directive and start streaming what's left.
                    val rest = buffer.substring(nlIdx + 1)
                    val msg = ChatMessage(ChatRole.Model, rest)
                    append(msg)
                    modelId = msg.id
                    buffer = rest
                    committed = true
                } else {
                    val msg = ChatMessage(ChatRole.Model, buffer)
                    append(msg)
                    modelId = msg.id
                    committed = true
                }
            } else if (buffer.length > 80) {
                // No newline yet, but well beyond a plausible SEARCH:
                // directive length. Commit and stream.
                val msg = ChatMessage(ChatRole.Model, buffer)
                append(msg)
                modelId = msg.id
                committed = true
            }
        }

        pendingSearch?.let { return StreamOutcome.Search(it) }
        if (!committed && buffer.isNotEmpty()) {
            // Stream ended before a newline or the 80-char threshold. If
            // detectSearch is on, the whole reply might BE a SEARCH directive
            // (model told to emit only the SEARCH line, no trailing newline).
            if (detectSearch) {
                PromptParsing.parseSearchDirective(buffer)?.let { return StreamOutcome.Search(it) }
            }
            append(ChatMessage(ChatRole.Model, PromptParsing.stripStraySearchDirective(buffer)))
        }
        return StreamOutcome.Answered
    }

    // MARK: - Slash search

    private suspend fun runSearch(rawCommand: String, query: String) {
        if (query.isEmpty()) {
            lastError = "Empty search query."
            return
        }
        append(ChatMessage(ChatRole.User, rawCommand))
        _status.value = Status.Sending
        try {
            val results = searchRouter.search(query)
            val source = results.firstOrNull()?.source ?: "Web"
            append(ChatMessage(ChatRole.Tool, PromptParsing.formatResults(query, results), source = source))
        } catch (t: Throwable) {
            lastError = "Search failed: ${t.message ?: t::class.simpleName ?: "error"}"
        } finally {
            _status.value = Status.Idle
        }
    }

    // MARK: - Topic anchor

    private fun anchorTopicIfNeeded() {
        if (topic.isNotEmpty()) return
        val firstUser = _messages.value.firstOrNull { it.role == ChatRole.User }?.text ?: return
        topic = firstUser.trim().take(120)
    }

    // MARK: - Message list helpers

    private fun append(msg: ChatMessage) {
        _messages.update { it + msg }
    }

    private fun replace(id: Long, newMsg: ChatMessage) {
        _messages.update { msgs -> msgs.map { if (it.id == id) newMsg else it } }
    }

    private fun replaceText(id: Long, text: String) {
        _messages.update { msgs -> msgs.map { if (it.id == id) it.copy(text = text) else it } }
    }

    private fun messageText(id: Long): String =
        _messages.value.firstOrNull { it.id == id }?.text ?: ""

    private fun lastModelMessageId(): Long? =
        _messages.value.lastOrNull { it.role == ChatRole.Model }?.id

    private companion object {
        /**
         * Max silent retries when the post-decode fact check finds unverified
         * numbers. At temperature 0.7 each retry samples differently, so 1-2
         * retries gives a real shot at a faithful answer.
         */
        const val MAX_FACT_CHECK_RETRIES = 2
        const val TAG = "ChatStore"
    }
}

enum class ChatRole { User, Model, Tool }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val source: String? = null,
    val id: Long = idCounter.incrementAndGet(),
) {
    private companion object {
        val idCounter = AtomicLong(0)
    }
}
