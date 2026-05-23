package com.zeiglerbd5.companion.gemmapoc

import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stateless one-shot prompt runner. Takes a loaded [Engine] and a prompt
 * string, opens a fresh [com.google.ai.edge.litertlm.Conversation], sends
 * one user turn, returns the model's full reply as a String.
 *
 * No chat history, no streaming, no system prompt — by design for the
 * Phase 0 smoke test. The iOS sibling's PromptRunner.swift is the target
 * shape (stateful Conversation, streaming chunks, persona-bearing system
 * message); we'll mirror those once the smoke test proves the runtime
 * end-to-end.
 */
object PromptRunner {

    suspend fun run(engine: Engine, prompt: String): String =
        withContext(Dispatchers.IO) {
            engine.createConversation().use { conversation ->
                // sendMessage is blocking. Message.toString() concatenates
                // text-typed Content; fine for our text-only smoke test.
                conversation.sendMessage(prompt).toString()
            }
        }
}
