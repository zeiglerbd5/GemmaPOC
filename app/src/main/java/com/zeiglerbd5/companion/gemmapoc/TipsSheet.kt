package com.zeiglerbd5.companion.gemmapoc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * In-app help/expectations sheet, surfaced from the toolbar menu's
 * "Information" item. Content mirrors the iOS sibling's `TipsView.swift`
 * word-for-word: users who don't know small on-device models can't match
 * cloud-scale ones read "wrong answer" as "broken app".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Information", style = MaterialTheme.typography.titleLarge)

            BulletSection(
                heading = "What OnHand_AI is best at",
                bullets = listOf(
                    "Reading and summarizing text you paste in",
                    "Drafting emails, texts, and short messages",
                    "Rewriting something in a different tone or style",
                    "Brainstorming and quick edits",
                    "Relaying quick facts from its knowledge base or web search",
                ),
            )

            BulletSection(
                heading = "Where it struggles",
                bullets = listOf(
                    "Long, multi-step reasoning",
                    "Math beyond basic arithmetic",
                    "Obscure historical, scientific, or local facts when Web Search is off",
                    "Strictly following an exact format you describe",
                    "Single inputs longer than ~1,500 words / ~10,000 characters — break them across turns",
                ),
            )

            BodySection(
                heading = "Why these limitations?",
                body = "OnHand_AI runs entirely on your phone — no cloud, no server, " +
                    "no data sent anywhere. To work on a battery-powered device, the " +
                    "model has to be small. That's what keeps your conversation " +
                    "private; it's also why OnHand_AI is less capable than the giant " +
                    "cloud AIs you might be used to. Think of it as a fast, private " +
                    "on-device helper — not a research-grade assistant.",
            )

            BulletSection(
                heading = "Tips",
                bullets = listOf(
                    "If you have a long article or essay to discuss, paste it across two or three turns rather than all at once",
                    "Leave Web Search on for any factual question — it lets the model look things up instead of guessing",
                    "Tap Clear if a chat starts repeating itself or losing track",
                    "Use Report a Problem if anything's wrong or feels off",
                ),
            )
        }
    }
}

@Composable
private fun BulletSection(heading: String, bullets: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        bullets.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BodySection(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
