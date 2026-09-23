package moe.reimu.nekoassistant

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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
import moe.reimu.nekoassistant.ai.InferenceStatus
import moe.reimu.nekoassistant.ui.DefaultCard
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

    Scaffold(topBar = {
        TopAppBar(title = { Text(text = stringResource(R.string.app_name)) })
    }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (!shizukuStatus.granted || !shizukuStatus.available) {
                item {
                    ShizukuCard(shizukuStatus)
                }
            }

            if (notificationPermissionState.status != PermissionStatus.Granted) {
                item {
                    DefaultCard(onClick = { notificationPermissionState.launchPermissionRequest() }) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Notification permission not granted",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            item {
                SelectedLlmConfigurationCard(
                    providers = uiState.llmProviders,
                    onClick = onManageProviders,
                )
            }

            item {
                DefaultCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Agent prompt",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = uiState.agentPrompt,
                            onValueChange = {
                                mainViewModel.updateAgentPrompt(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (uiState.isAgentRunning) {
                                Button(onClick = { mainViewModel.stopAgent() }) {
                                    Text("Stop")
                                }
                            } else {
                                Button(
                                    enabled = uiState.agentPrompt.isNotBlank() &&
                                        uiState.hasActiveLlmConfiguration,
                                    onClick = { mainViewModel.startAgent() }
                                ) {
                                    Text("Go")
                                }
                            }
                        }
                    }
                }
            }

            uiState.errorMessage?.let { message ->
                item {
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

            (uiState.agentMessages.keys + uiState.inferenceStatuses.keys).forEach { agent ->
                item {
                    DefaultCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = agent,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            uiState.inferenceStatuses[agent]
                                ?.takeUnless { it == InferenceStatus.IDLE }
                                ?.let { status ->
                                    Text(
                                        text = when (status) {
                                            InferenceStatus.WAITING -> "Waiting for response…"
                                            InferenceStatus.STREAMING -> "Streaming response…"
                                            InferenceStatus.ERROR -> "LLM inference failed"
                                            InferenceStatus.IDLE -> "Idle"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (status == InferenceStatus.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (status != InferenceStatus.ERROR) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        )
                                    }
                                }
                            uiState.agentMessages[agent]?.let { message ->
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            uiState.previewBitmap?.let { bitmap ->
                item {
                    DefaultCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Preview",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Virtual display preview",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
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
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
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
