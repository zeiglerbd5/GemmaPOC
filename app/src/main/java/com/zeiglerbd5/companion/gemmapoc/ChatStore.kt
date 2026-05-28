package com.zeiglerbd5.companion.gemmapoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import com.zeiglerbd5.companion.gemmapoc.search.SearchRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the conversation between the user and the model and orchestrates
 * the agentic-search loop. Mirrors the iOS sibling's `ConversationStore` +
 * `PromptRunner`: owns a single LiteRT-LM [Conversation] (system persona +
 * sampler installed once), exposes the message list as a [StateFlow], and
 * runs the SEARCH: tool loop when the model asks to look something up.
 *
 * Loop: user turn → model reply. If the reply is `SEARCH: <query>`, run
 * both providers, fold the results back as a follow-up user turn, re-ask,
 * and render the grounded answer. Otherwise render the first reply.
 *
 * Streaming output and the iOS hardening passes (fact-check, best-of-N,
 * prompt-extraction defense, ephemeral hints) are deferred — see
 * POC-NOTES-android.md.
 */
class ChatStore : ViewModel() {

    sealed interface Status {
        data object Idle : Status
        data object Sending : Status
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val router = SearchRouter()
    private var conversation: Conversation? = null

    fun send(engine: Engine, text: String, detailed: Boolean) {
        if (_status.value is Status.Sending) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Display the raw text in the bubble, but fold the answer-length
        // style into what the model actually receives (the "In Depth"
        // toggle). The system prompt can't carry this — it's fixed at
        // conversation creation and the toggle changes mid-chat.
        _messages.update { it + ChatMessage(ChatRole.User, trimmed) }
        _status.value = Status.Sending
        val modelInput = "$trimmed\n\n(${styleFor(detailed)})"

        viewModelScope.launch {
            val pending = ChatMessage(ChatRole.Model, "")
            _messages.update { it + pending }
            try {
                val conv = withContext(Dispatchers.IO) { ensureConversation(engine) }

                // First turn streams, but display is gated: the reply might be
                // a `SEARCH:` directive, which is a tool instruction we must
                // NOT show. Buffer silently until the head can no longer be a
                // search directive, then stream live into the bubble.
                var streaming = false
                val first = streamReply(conv, modelInput) { soFar ->
                    if (!streaming) {
                        val head = soFar.trimStart().lowercase()
                        if (!("search:".startsWith(head) || head.startsWith("search:"))) {
                            streaming = true
                        }
                    }
                    if (streaming) replace(pending.id, pending.copy(text = soFar))
                }

                val query = PromptParsing.parseSearchDirective(first)
                if (query == null) {
                    replace(pending.id, pending.copy(text = first))
                } else {
                    runSearchTurn(conv, pendingId = pending.id, query = query, detailed = detailed)
                }
            } catch (t: Throwable) {
                fillLastEmpty("⚠️ ${t.message ?: t::class.simpleName ?: "error"}")
            }
            _status.value = Status.Idle
        }
    }

    private fun styleFor(detailed: Boolean): String =
        if (detailed) PromptParsing.DETAILED_STYLE else PromptParsing.CONCISE_STYLE

    /**
     * Send [input] and stream the reply. [onText] is invoked on each token
     * with the full accumulated text so far (the Flow emits incremental
     * deltas; we accumulate). Returns the complete reply. Runs on IO so the
     * blocking native generation never touches the main thread; UNLIMITED
     * buffer so fast token bursts can't drop via trySend.
     */
    private suspend fun streamReply(
        conv: Conversation,
        input: String,
        onText: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        conv.sendMessageAsync(input)
            .buffer(Channel.UNLIMITED)
            .collect { msg ->
                sb.append(msg.toString())
                onText(sb.toString())
            }
        sb.toString()
    }

    /**
     * The model emitted `SEARCH: query`. Swap the pending bubble for a tool
     * breadcrumb, run the providers, fold results back into the same
     * Conversation as a follow-up turn, and render the grounded reply.
     */
    private suspend fun runSearchTurn(
        conv: Conversation,
        pendingId: Long,
        query: String,
        detailed: Boolean,
    ) {
        val tool = ChatMessage(ChatRole.Tool, "🔍 Searching: $query")
        replace(pendingId, tool)

        val results = withContext(Dispatchers.IO) {
            runCatching { router.searchBoth(query) }.getOrDefault(emptyList())
        }
        replace(
            tool.id,
            tool.copy(
                text = PromptParsing.toolBreadcrumb(query, results),
                source = results.firstOrNull()?.source ?: "web",
            ),
        )

        val answer = ChatMessage(ChatRole.Model, "")
        _messages.update { it + answer }
        val context = PromptParsing.formatSearchContext(results) + "\n(${styleFor(detailed)})"
        // Second turn always streams live — it can't be a SEARCH directive
        // (the context block instructs the model not to emit another one).
        val second = streamReply(conv, context) { soFar ->
            replace(answer.id, answer.copy(text = soFar))
        }
        replace(answer.id, answer.copy(text = PromptParsing.stripStraySearchDirective(second)))
    }

    private fun ensureConversation(engine: Engine): Conversation =
        conversation ?: engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PromptParsing.SYSTEM_PERSONA),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            ),
        ).also { conversation = it }

    private fun replace(id: Long, newMsg: ChatMessage) {
        _messages.update { msgs -> msgs.map { if (it.id == id) newMsg else it } }
    }

    private fun fillLastEmpty(errorText: String) {
        _messages.update { msgs ->
            if (msgs.isNotEmpty() && msgs.last().text.isEmpty()) {
                msgs.dropLast(1) + msgs.last().copy(role = ChatRole.Model, text = errorText)
            } else {
                msgs + ChatMessage(ChatRole.Model, errorText)
            }
        }
    }

    fun clear() {
        conversation?.close()
        conversation = null
        _messages.value = emptyList()
        _status.value = Status.Idle
    }

    override fun onCleared() {
        super.onCleared()
        conversation?.close()
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
