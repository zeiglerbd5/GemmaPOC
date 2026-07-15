package com.zeiglerbd5.companion.gemmapoc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private const val APP_TITLE = "OnHand_AI"

/**
 * Public URL of OnHand_AI's privacy policy. Surfaced in-app (Custom Tab)
 * because App Store guideline 5.1.1(i) and Play policy both require the
 * policy be reachable from inside the app.
 */
private const val PRIVACY_POLICY_URL = "https://stillwaterai.ai/privacy"

/** Public support address + subject used by "Report a Problem". */
private const val SUPPORT_ADDRESS = "support@stillwaterai.ai"
private const val SUPPORT_SUBJECT = "Feedback"

/** Opens the privacy policy in a Chrome Custom Tab so the user stays in-app. */
private fun openPrivacyPolicy(context: Context) {
    val url = Uri.parse(PRIVACY_POLICY_URL)
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url)
    } catch (_: ActivityNotFoundException) {
        // No Custom-Tabs-capable browser — fall back to any browser at all.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "No browser found. Visit $PRIVACY_POLICY_URL",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * Opens a pre-addressed email draft (to: and subject: only — body left
 * for the user). The user reviews and sends through their own mail app;
 * this app sends nothing itself.
 */
private fun reportAProblem(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse(
            "mailto:$SUPPORT_ADDRESS?subject=${Uri.encode(SUPPORT_SUBJECT)}"
        )
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "No email app found. Email $SUPPORT_ADDRESS",
            Toast.LENGTH_LONG,
        ).show()
    }
}

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
    val downloadProgress by loader.downloadProgress.collectAsState()

    // Auto-load only when the model file already exists locally — that's a
    // fast local-file open, not a network download. First launch (no cache)
    // shows a consent card and waits for the button tap instead of
    // auto-starting the 2.6 GB pull (App Store guideline 4.2.3(ii); Play
    // has the same expectation).
    LaunchedEffect(Unit) {
        if (state is LoadState.Idle && loader.isModelCached()) loader.load()
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
                    downloadProgress = downloadProgress,
                    needsConsent = state is LoadState.Idle && !loader.isModelCached(),
                    modelFilePath = loader.modelFile().absolutePath,
                    onLoad = loader::load,
                )
            }
        }
    }
}

/**
 * Top-left hamburger menu. Toggle + theme items up top, then the
 * informational group (Information, Privacy Policy, Report a Problem).
 * "Theme" drills into the palette list (content-swap rather than a nested
 * popup, which keeps anchoring reliable). Mirrors the iOS toolbar menu
 * shape and ordering.
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
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Text("☰", style = MaterialTheme.typography.titleLarge)
    }
    if (showTips) {
        TipsSheet(onDismiss = { showTips = false })
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Information") },
                onClick = {
                    open = false
                    showTips = true
                },
            )
            DropdownMenuItem(
                text = { Text("Privacy Policy") },
                onClick = {
                    open = false
                    openPrivacyPolicy(context)
                },
            )
            DropdownMenuItem(
                text = { Text("Report a Problem") },
                onClick = {
                    open = false
                    reportAProblem(context)
                },
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
    downloadProgress: ModelLoader.DownloadProgress?,
    needsConsent: Boolean,
    modelFilePath: String,
    onLoad: () -> Unit,
) {
    when {
        downloadProgress != null -> DownloadCard(downloadProgress)
        needsConsent -> ConsentCard()
        else -> StatusCard(state = state, modelFilePath = modelFilePath)
    }

    Button(
        onClick = onLoad,
        enabled = state is LoadState.Idle || state is LoadState.Failed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                state is LoadState.Failed -> "Retry"
                needsConsent -> "Download AI Model (~2.6 GB)"
                state is LoadState.Idle -> "Load model"
                else -> "Loading…"
            }
        )
    }
}

/**
 * First-launch consent card. Nothing downloads until the user taps the
 * button below it — explicit size disclosure and opt-in are required for
 * a multi-GB pull (App Store guideline 4.2.3(ii); Play policy likewise).
 */
@Composable
private fun ConsentCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Download Required",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "OnHand_AI runs entirely on your device — no cloud, no " +
                    "server, nothing sent anywhere. To work, it needs to " +
                    "download the AI model once.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Download size:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "~2.6 GB",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Wi-Fi is recommended. The download typically takes 5–20 " +
                    "minutes depending on your connection. Tap the button below " +
                    "to begin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Live download progress: linear bar, percentage, and byte readout. */
@Composable
private fun DownloadCard(progress: ModelLoader.DownloadProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Downloading AI Model",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "First-time setup. OnHand_AI runs entirely on your " +
                    "device, so it needs to download the model once (~2.6 GB). " +
                    "Please keep the app open on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { progress.fractionCompleted.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Server didn't send Content-Length — no fraction to show.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${(progress.fractionCompleted * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (progress.bytesTotal > 0) {
                        ModelLoader.formatBytes(progress.bytesDone) +
                            " / " + ModelLoader.formatBytes(progress.bytesTotal)
                    } else {
                        ModelLoader.formatBytes(progress.bytesDone)
                    },
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
