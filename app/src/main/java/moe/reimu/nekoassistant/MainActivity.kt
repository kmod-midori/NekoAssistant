package moe.reimu.nekoassistant

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.drop
import moe.reimu.nekoassistant.ui.AgentStatusRow
import moe.reimu.nekoassistant.ui.ChatBubble
import moe.reimu.nekoassistant.ui.ChatInputBar
import moe.reimu.nekoassistant.ui.DefaultCard
import moe.reimu.nekoassistant.ui.LiveScreenPreview
import moe.reimu.nekoassistant.ui.ProviderManagementPage
import moe.reimu.nekoassistant.ui.SelectedLlmConfigurationCard
import moe.reimu.nekoassistant.ui.theme.NekoAssistantTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NekoAssistantTheme {
                MainActivityContent()
            }
        }
    }
}

@Composable
fun MainActivityContent() {
    val navController = rememberNavController()

    Surface {
        NavHost(
            navController = navController,
            startDestination = "main",
        ) {
            composable("main") {
                MainPage(onManageProviders = { navController.navigate("providers") })
            }
            composable("providers") {
                ProviderManagementPage(onBack = { navController.popBackStack() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MainPage(mainViewModel: MainViewModel = viewModel(), onManageProviders: () -> Unit) {
    val shizukuStatus = useShizukuStatus()
    val uiState by mainViewModel.uiState.collectAsState()
    val notificationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        object : PermissionState {
            override val permission = "NOT_NEEDED"
            override val status = PermissionStatus.Granted
            override fun launchPermissionRequest() {}
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mainViewModel.setErrorListenerActive(true)
                Lifecycle.Event.ON_PAUSE -> mainViewModel.setErrorListenerActive(false)
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val listState = rememberLazyListState()

    // Follow the newest message, unless the user scrolled up to read something — an agent
    // turn streams for a while, and yanking the transcript away mid-read is unusable.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { scrolling ->
                if (scrolling) {
                    return@collect
                }
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                followTail = info.totalItemsCount == 0 || last?.index == info.totalItemsCount - 1
            }
    }
    LaunchedEffect(uiState.messages.lastOrNull()?.text, followTail) {
        if (followTail && uiState.messages.isNotEmpty()) {
            listState.scrollToItem(uiState.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = mainViewModel::newChat,
                        enabled = uiState.messages.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddComment,
                            contentDescription = "New chat",
                        )
                    }
                    IconButton(onClick = onManageProviders) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "LLM providers",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                uiState.previewBitmap?.let { LiveScreenPreview(it) }
                // Only while something is running: a finished job leaves its last status,
                // including a failed one, sitting in the map.
                if (uiState.isAgentRunning) {
                    AgentStatusRow(uiState.inferenceStatuses)
                }
                ChatInputBar(
                    value = uiState.agentPrompt,
                    onValueChange = mainViewModel::updateAgentPrompt,
                    isRunning = uiState.isAgentRunning,
                    canSend = uiState.agentPrompt.isNotBlank() && uiState.hasActiveLlmConfiguration,
                    onSend = mainViewModel::startAgent,
                    onStop = mainViewModel::stopAgent,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Anything the app needs before it can run sits above the transcript, the way a
            // chat app shows a setup banner rather than a card in the middle of the thread.
            val needsSetup = !shizukuStatus.granted || !shizukuStatus.available ||
                notificationPermissionState.status != PermissionStatus.Granted ||
                !uiState.hasActiveLlmConfiguration || uiState.errorMessage != null
            if (needsSetup) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!shizukuStatus.granted || !shizukuStatus.available) {
                        ShizukuCard(shizukuStatus)
                    }

                    if (notificationPermissionState.status != PermissionStatus.Granted) {
                        DefaultCard(onClick = { notificationPermissionState.launchPermissionRequest() }) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Notification permission not granted",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }

                    if (!uiState.hasActiveLlmConfiguration) {
                        SelectedLlmConfigurationCard(
                            providers = uiState.llmProviders,
                            onClick = onManageProviders,
                        )
                    }

                    uiState.errorMessage?.let { message ->
                        DefaultCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Error",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Tell the agent what to do on the virtual display.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                // Selectable, so agent output can be copied out of the transcript.
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(uiState.messages) { message ->
                            ChatBubble(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShizukuCard(status: ShizukuStatus) {
    DefaultCard(onClick = {
        if (!status.granted) {
            try {
                Shizuku.requestPermission(0)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (status.available) {
                    if (status.granted) {
                        "Shizuku granted"
                    } else {
                        "Shizuku not granted"
                    }
                } else {
                    "Shizuku unavailable"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Shizuku permission required for virtual displays",
            )
        }
    }
}

data class ShizukuStatus(val granted: Boolean, val available: Boolean)

@Composable
fun useShizukuStatus(): ShizukuStatus {
    var shizukuGranted by remember {
        mutableStateOf(false)
    }

    var shizukuAvailable by remember {
        mutableStateOf(false)
    }

    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
        }

        val binderRecvListener = Shizuku.OnBinderReceivedListener {
            shizukuAvailable = true
            shizukuGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }

        val binderDeadReceiver = Shizuku.OnBinderDeadListener {
            shizukuAvailable = false
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderRecvListener)
        Shizuku.addBinderDeadListener(binderDeadReceiver)

        onDispose {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderRecvListener)
            Shizuku.removeBinderDeadListener(binderDeadReceiver)
        }
    }

    return ShizukuStatus(granted = shizukuGranted, available = shizukuAvailable)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MainActivityContent()
}
