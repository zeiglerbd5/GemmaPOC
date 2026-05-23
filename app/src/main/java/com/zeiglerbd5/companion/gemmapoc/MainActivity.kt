package com.zeiglerbd5.companion.gemmapoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeiglerbd5.companion.gemmapoc.ModelLoader.LoadState
import com.zeiglerbd5.companion.gemmapoc.ui.theme.GemmaPOCTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GemmaPOCTheme {
                AppScaffold()
            }
        }
    }
}

private const val SMOKE_TEST_PROMPT = "What is the capital of France?"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("GemmaPOC") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SetupSection()
        }
    }
}

@Composable
private fun SetupSection(loader: ModelLoader = viewModel()) {
    val state by loader.state.collectAsState()

    StatusCard(state = state, modelFilePath = loader.modelFile().absolutePath)

    Button(
        onClick = { loader.load() },
        enabled = state is LoadState.Idle || state is LoadState.Failed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when (state) {
                is LoadState.Idle -> "Load model"
                is LoadState.Failed -> "Retry"
                is LoadState.Ready -> "Loaded"
                else -> "Loading…"
            }
        )
    }

    (state as? LoadState.Ready)?.let { ready ->
        PromptSection(engine = ready.engine)
    }
}

@Composable
private fun StatusCard(state: LoadState, modelFilePath: String) {
    val statusText = when (state) {
        is LoadState.Idle -> "idle — tap Load model"
        is LoadState.Locating -> "locating weights…"
        is LoadState.Loading -> state.message
        is LoadState.Ready -> "ready"
        is LoadState.Failed -> "FAILED — ${state.message}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Loader: $statusText",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Model file:",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = modelFilePath,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PromptSection(engine: com.google.ai.edge.litertlm.Engine) {
    var response by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Test prompt:",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = SMOKE_TEST_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Button(
        onClick = {
            scope.launch {
                running = true
                response = "running…"
                response = try {
                    PromptRunner.run(engine, SMOKE_TEST_PROMPT)
                } catch (t: Throwable) {
                    "FAILED — ${t.message}\n\n$t"
                }
                running = false
            }
        },
        enabled = !running,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (running) "Running…" else "Run prompt")
    }

    if (response.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Response:",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = response,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
