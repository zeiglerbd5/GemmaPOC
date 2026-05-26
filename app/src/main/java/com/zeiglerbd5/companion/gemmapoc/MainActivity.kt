package com.zeiglerbd5.companion.gemmapoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeiglerbd5.companion.gemmapoc.ModelLoader.LoadState
import com.zeiglerbd5.companion.gemmapoc.ui.theme.AppTheme
import com.zeiglerbd5.companion.gemmapoc.ui.theme.GemmaPOCTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs = remember { ThemePreferences(applicationContext) }
            var appTheme by remember { mutableStateOf(themePrefs.theme) }
            GemmaPOCTheme(appTheme = appTheme) {
                AppScaffold(
                    appTheme = appTheme,
                    onThemeChange = { picked ->
                        appTheme = picked
                        themePrefs.theme = picked
                    },
                )
            }
        }
    }
}

private const val APP_TITLE = "OnBoard AI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    loader: ModelLoader = viewModel(),
) {
    val state by loader.state.collectAsState()

    // Mirror the iOS .task auto-load: kick off ModelLoader once on first
    // composition. The Failed-state retry path stays on the manual button.
    LaunchedEffect(Unit) {
        if (state is LoadState.Idle) loader.load()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(APP_TITLE) },
                actions = { ThemeMenu(current = appTheme, onPick = onThemeChange) },
            )
        },
    ) { innerPadding ->
        val ready = state as? LoadState.Ready
        if (ready != null) {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                ChatView(engine = ready.engine, appTheme = appTheme)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SetupSection(
                    state = state,
                    modelFilePath = loader.modelFile().absolutePath,
                    onLoad = loader::load,
                )
            }
        }
    }
}

@Composable
private fun ThemeMenu(current: AppTheme, onPick: (AppTheme) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Theme") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.displayName) },
                    onClick = {
                        onPick(theme)
                        open = false
                    },
                    trailingIcon = if (theme == current) {
                        { Text("✓") }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun SetupSection(
    state: LoadState,
    modelFilePath: String,
    onLoad: () -> Unit,
) {
    StatusCard(state = state, modelFilePath = modelFilePath)

    Button(
        onClick = onLoad,
        enabled = state is LoadState.Idle || state is LoadState.Failed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when (state) {
                is LoadState.Failed -> "Retry"
                is LoadState.Idle -> "Load model"
                else -> "Loading…"
            }
        )
    }
}

@Composable
private fun StatusCard(state: LoadState, modelFilePath: String) {
    val statusText = when (state) {
        is LoadState.Idle -> "starting up…"
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
