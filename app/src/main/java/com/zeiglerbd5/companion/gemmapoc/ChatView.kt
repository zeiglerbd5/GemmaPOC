package com.zeiglerbd5.companion.gemmapoc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.litertlm.Engine
import com.zeiglerbd5.companion.gemmapoc.ui.theme.AppTheme

/**
 * Multi-turn chat surface. Renders a scrolling list of [MessageBubble]
 * over a per-theme background, plus a sticky input row at the bottom.
 * Mirrors the iOS sibling's `ChatView.swift` shape — same bubble color
 * mapping (user/model bubble + bubbleText), same input-field theming
 * (inputBackground + inputBorder + inputText), same auto-scroll on new
 * messages.
 */
@Composable
fun ChatView(
    engine: Engine,
    appTheme: AppTheme,
    detailed: Boolean,
    searchEnabled: Boolean,
    chatStore: ChatStore = viewModel(),
) {
    val messages by chatStore.messages.collectAsState()
    val status by chatStore.status.collectAsState()
    val listState = rememberLazyListState()

    // Re-scroll on new messages AND as the last bubble's text grows during
    // streaming (message count is unchanged then, only the text length).
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 12.dp,
            ),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, appTheme)
            }
        }
        InputRow(
            appTheme = appTheme,
            sending = status is ChatStore.Status.Sending,
            onSend = { text ->
                // The toggles live on the store; push the current UI values in
                // before each send so a mid-chat flip takes effect next turn.
                chatStore.detailedMode = detailed
                chatStore.searchEnabled = searchEnabled
                chatStore.send(engine, text)
            },
            // Keyboard/nav-bar spacing is owned by the host Scaffold's
            // contentWindowInsets — adding imePadding() here as well
            // double-counted the keyboard height and floated the input row
            // to mid-screen (Galaxy Fold report).
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, appTheme: AppTheme) {
    val isUser = msg.role == ChatRole.User
    val isTool = msg.role == ChatRole.Tool
    val dark = appTheme.isDark ?: isSystemInDarkTheme()
    val bubbleColor = when (msg.role) {
        ChatRole.User -> appTheme.userBubble(dark)
        ChatRole.Model -> appTheme.modelBubble(dark)
        ChatRole.Tool -> appTheme.toolBubble(dark)
    }
    val textColor = appTheme.bubbleText ?: LocalContentColor.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column {
                if (isTool && msg.source != null) {
                    Text(
                        text = "From ${msg.source}",
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = msg.text.ifEmpty { "…" },
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun InputRow(
    appTheme: AppTheme,
    sending: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val dark = appTheme.isDark ?: isSystemInDarkTheme()

    Row(
        modifier = modifier
            .background(appTheme.inputBackground(dark))
            .border(1.dp, appTheme.inputBorder(dark))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            enabled = !sending,
            placeholder = { Text("Ask anything…") },
            singleLine = false,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (!sending && draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = appTheme.inputBackground(dark),
                unfocusedContainerColor = appTheme.inputBackground(dark),
                disabledContainerColor = appTheme.inputBackground(dark),
            ),
        )
        Button(
            onClick = {
                onSend(draft)
                draft = ""
            },
            enabled = !sending && draft.isNotBlank(),
        ) {
            Text(if (sending) "…" else "Send")
        }
    }
}
