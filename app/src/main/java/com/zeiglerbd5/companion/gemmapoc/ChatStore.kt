package com.zeiglerbd5.companion.gemmapoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the conversation between the user and the model. Mirrors the iOS
 * sibling's `ConversationStore` shape — owns a single LiteRT-LM
 * [Conversation] across many turns so the KV cache survives, exposes the
 * message list as a [StateFlow] for Compose to observe, and serialises
 * sends through [Status] so the UI can disable input mid-generation.
 *
 * Single-shot inference (the old [PromptRunner]) is gone — this class
 * subsumes it. Streaming token-by-token output is a planned follow-up;
 * `sendMessageAsync(...)` returning `Flow<Message>` is the LiteRT-LM hook
 * for that.
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

    private var conversation: Conversation? = null

    fun send(engine: Engine, text: String) {
        if (_status.value is Status.Sending) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val userMsg = ChatMessage(role = ChatRole.User, text = trimmed)
        val pending = ChatMessage(role = ChatRole.Model, text = "")
        _messages.update { it + userMsg + pending }
        _status.value = Status.Sending

        viewModelScope.launch {
            val reply = try {
                withContext(Dispatchers.IO) {
                    val conv = conversation ?: engine.createConversation().also { conversation = it }
                    conv.sendMessage(trimmed).toString()
                }
            } catch (t: Throwable) {
                "⚠️ ${t.message ?: t::class.simpleName ?: "error"}"
            }
            _messages.update { msgs ->
                msgs.dropLast(1) + pending.copy(text = reply)
            }
            _status.value = Status.Idle
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

enum class ChatRole { User, Model }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val id: Long = idCounter.incrementAndGet(),
) {
    private companion object {
        val idCounter = AtomicLong(0)
    }
}
