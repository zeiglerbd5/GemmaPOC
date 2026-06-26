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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            val prefs = remember { ThemePreferences(applicationContext) }
            var appTheme by remember { mutableStateOf(prefs.theme) }
            var detailed by remember { mutableStateOf(prefs.detailed) }
            var searchEnabled by remember { mutableStateOf(prefs.searchEnabled) }
            GemmaPOCTheme(appTheme = appTheme) {
                AppScaffold(
                    appTheme = appTheme,
                    onThemeChange = { picked ->
                        appTheme = picked
                        prefs.theme = picked
                    },
                    detailed = detailed,
                    onToggleDetailed = {
                        detailed = !detailed
                        prefs.detailed = detailed
                    },
                    searchEnabled = searchEnabled,
                    onToggleSearch = {
                        searchEnabled = !searchEnabled
                        prefs.searchEnabled = searchEnabled
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
    detailed: Boolean,
    onToggleDetailed: () -> Unit,
    searchEnabled: Boolean,
    onToggleSearch: () -> Unit,
    loader: ModelLoader = viewModel(),
) {
    val state by loader.state.collectAsState()

    // Mirror the iOS .task auto-load: kick off ModelLoader once on first
    // composition. The Failed-state retry path stays on the manual button.
    LaunchedEffect(Unit) {
        if (state is LoadState.Idle) loader.load()
    }

    // Same ChatStore instance ChatView resolves via viewModel() (the
    // Activity is the ViewModelStoreOwner for both) — lets the Clear action
    // in the top bar drive the conversation ChatView renders.
    val chatStore: ChatStore = viewModel()
    val messages by chatStore.messages.collectAsState()
    val status by chatStore.status.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(APP_TITLE) },
                navigationIcon = {
                    AppMenu(
                        appTheme = appTheme,
                        onThemeChange = onThemeChange,
                        detailed = detailed,
                        onToggleDetailed = onToggleDetailed,
                        searchEnabled = searchEnabled,
                        onToggleSearch = onToggleSearch,
                    )
                },
                actions = {
                    val canClear = messages.isNotEmpty() && status !is ChatStore.Status.Sending
                    TextButton(onClick = chatStore::clear, enabled = canClear) { Text("Clear") }
                },
            )
        },
    ) { innerPadding ->
        val ready = state as? LoadState.Ready
        if (ready != null) {
            Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                ChatView(
                    engine = ready.engine,
                    appTheme = appTheme,
                    detailed = detailed,
                    searchEnabled = searchEnabled,
                    chatStore = chatStore,
                )
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

/**
 * Top-left hamburger menu. Two top-level items — an "In Depth" toggle and
 * a "Theme" entry that drills into the palette list (content-swap rather
 * than a nested popup, which keeps anchoring reliable). Mirrors the iOS
 * toolbar menu shape.
 */
@Composable
private fun AppMenu(
    appTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    detailed: Boolean,
    onToggleDetailed: () -> Unit,
    searchEnabled: Boolean,
    onToggleSearch: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Text("☰", style = MaterialTheme.typography.titleLarge)
    }
    DropdownMenu(
        expanded = open,
        onDismissRequest = {
            open = false
            showThemes = false
        },
    ) {
        if (!showThemes) {
            DropdownMenuItem(
                text = { Text("In Depth") },
                onClick = onToggleDetailed,
                trailingIcon = { Text(if (detailed) "✓" else "") },
            )
            DropdownMenuItem(
                text = { Text("Web Search") },
                onClick = onToggleSearch,
                trailingIcon = { Text(if (searchEnabled) "✓" else "") },
            )
            DropdownMenuItem(
                text = { Text("Theme") },
                onClick = { showThemes = true },
                trailingIcon = { Text("›") },
            )
        } else {
            DropdownMenuItem(
                text = { Text("‹  Theme") },
                onClick = { showThemes = false },
            )
            HorizontalDivider()
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.displayName) },
                    onClick = {
                        onThemeChange(theme)
                        open = false
                        showThemes = false
                    },
                    trailingIcon = if (theme == appTheme) {
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
