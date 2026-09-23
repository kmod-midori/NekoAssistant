package moe.reimu.nekoassistant

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.reimu.nekoassistant.ai.ChatMessage
import moe.reimu.nekoassistant.ai.InferenceStatus
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.nekoassistant.data.LlmProviderWithModels

data class UiState(
    val isServiceConnected: Boolean = false,
    val isAgentRunning: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val agentPrompt: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inferenceStatuses: Map<String, InferenceStatus> = emptyMap(),
    val errorMessage: String? = null,
    val llmProviders: List<LlmProviderWithModels> = emptyList(),
) {
    val hasActiveLlmConfiguration: Boolean
        get() = llmProviders.any { providerWithModels -> providerWithModels.models.any { it.isSelected } }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val llmConfigurationRepository = LlmConfigurationRepository(application)

    private fun updateUi(block: (UiState) -> UiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = block(_uiState.value)
        } else {
            mainHandler.post { _uiState.value = block(_uiState.value) }
        }
    }

    private val previewListener = AgentService.PreviewListener { bitmap ->
        updateUi { it.copy(previewBitmap = bitmap) }
    }

    private val transcriptListener = AgentService.TranscriptListener { messages ->
        updateUi { it.copy(messages = messages) }
    }

    private val inferenceStatusListener = AgentService.InferenceStatusListener { agent, status ->
        updateUi { it.copy(inferenceStatuses = it.inferenceStatuses + (agent to status)) }
    }

    private val jobStateListener = AgentService.JobStateListener { running ->
        updateUi {
            it.copy(
                isAgentRunning = running,
                errorMessage = if (running) null else it.errorMessage,
            )
        }
    }

    private val errorListener = AgentService.ErrorListener { message ->
        updateUi { it.copy(errorMessage = message) }
    }

    @SuppressLint("StaticFieldLeak")
    private var agentService: AgentService? = null
    private var errorListenerActive = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
            val service = (p1 as AgentService.LocalBinder).getService()
            agentService = service
            _uiState.value = _uiState.value.copy(
                isServiceConnected = true,
                isAgentRunning = service.isAgentRunning(),
                // A run may have started before this screen existed, so take whatever the
                // service has rather than starting from an empty transcript.
                messages = service.getTranscript(),
                inferenceStatuses = service.getInferenceStatuses(),
            )
            registerServiceListeners(service)
            if (errorListenerActive) {
                service.addErrorListener(errorListener)
            }
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            agentService = null
            _uiState.value = _uiState.value.copy(
                isServiceConnected = false,
                isAgentRunning = false,
                previewBitmap = null,
                inferenceStatuses = emptyMap(),
                errorMessage = null,
            )
        }
    }

    init {
        bindAgentService()
        viewModelScope.launch {
            llmConfigurationRepository.observeProviders().collect { providers ->
                _uiState.value = _uiState.value.copy(llmProviders = providers)
            }
        }
    }

    private fun bindAgentService() {
        val intent = Intent(getApplication(), AgentService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun registerServiceListeners(service: AgentService) {
        service.addPreviewListener(previewListener)
        service.addTranscriptListener(transcriptListener)
        service.addInferenceStatusListener(inferenceStatusListener)
        service.addJobStateListener(jobStateListener)
    }

    private fun removeServiceListeners(service: AgentService) {
        service.removePreviewListener(previewListener)
        service.removeTranscriptListener(transcriptListener)
        service.removeInferenceStatusListener(inferenceStatusListener)
        service.removeJobStateListener(jobStateListener)
    }

    fun updateAgentPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(agentPrompt = prompt)
    }

    fun startAgent() {
        val prompt = _uiState.value.agentPrompt.trim()
        if (prompt.isBlank()) {
            return
        }

        // The bubble comes back from the service with the rest of the transcript.
        _uiState.value = _uiState.value.copy(agentPrompt = "")

        val intent = Intent(getApplication(), AgentService::class.java).apply {
            putExtra(AgentService.EXTRA_AGENT_PROMPT, prompt)
        }
        application.startService(intent)
    }

    fun stopAgent() {
        agentService?.stopAgent()
    }

    fun newChat() {
        agentService?.clearTranscript()
    }

    fun setErrorListenerActive(active: Boolean) {
        errorListenerActive = active
        val service = agentService ?: return
        if (active) service.addErrorListener(errorListener) else service.removeErrorListener(errorListener)
    }

    override fun onCleared() {
        agentService?.let { removeServiceListeners(it) }
        agentService?.removeErrorListener(errorListener)
        if (agentService != null) {
            application.unbindService(serviceConnection)
            agentService = null
        }
    }
}
