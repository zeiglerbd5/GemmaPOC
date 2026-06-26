package com.zeiglerbd5.companion.gemmapoc

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Test seam for the inference runner. Lets [ChatStore] be exercised without
 * a real LiteRT-LM [Engine] — [PromptRunner] is the production conformer;
 * tests substitute an in-memory fake. Port of the iOS sibling's
 * `PromptRunning` protocol.
 *
 * [respondStream] returns a cold [Flow] of token chunks; each emission is
 * the incremental new text since the previous one. Consumers accumulate the
 * deltas into a buffer; the flow completes when the model reaches
 * end-of-turn.
 */
interface PromptRunning {
    /**
     * Discard the underlying conversation so the next [respondStream] call
     * rebuilds it from scratch. Called on "Clear".
     */
    suspend fun reset()

    fun respondStream(
        userMessage: String,
        context: String? = null,
        location: String? = null,
        topic: String? = null,
        detailed: Boolean = false,
        now: LocalDateTime = LocalDateTime.now(),
        ephemeralHints: List<String> = emptyList(),
    ): Flow<String>
}

/**
 * Thin wrapper around a LiteRT-LM [Conversation] that installs our persona +
 * tool description as the system message, and prepends a small dynamic
 * preamble (today's date, location, topic, style) to each user turn via
 * [PromptParsing.buildUserBody] so the model has fresh grounding without us
 * rebuilding the conversation.
 *
 * LiteRT-LM owns the chat-template formatting, the KV cache, and the on-disk
 * PLE paging — we just feed plain text. Blocking native generation runs on
 * [Dispatchers.IO] so it never touches the main thread.
 */
class PromptRunner(private val engine: Engine) : PromptRunning {

    private var conversation: Conversation? = null

    override suspend fun reset() {
        conversation?.close()
        conversation = null
    }

    override fun respondStream(
        userMessage: String,
        context: String?,
        location: String?,
        topic: String?,
        detailed: Boolean,
        now: LocalDateTime,
        ephemeralHints: List<String>,
    ): Flow<String> {
        val body = PromptParsing.buildUserBody(
            userMessage = userMessage,
            context = context,
            location = location,
            topic = topic,
            detailed = detailed,
            now = now,
            ephemeralHints = ephemeralHints,
        )
        // Cold flow: the conversation is created (and generation kicked off)
        // lazily when the consumer starts collecting, all on IO.
        return flow {
            val conv = ensureConversation()
            emitAll(conv.sendMessageAsync(body).map { it.toString() })
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun ensureConversation(): Conversation {
        conversation?.let { return it }
        val created = withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(PromptParsing.SYSTEM_PERSONA),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                ),
            )
        }
        conversation = created
        return created
    }
}
