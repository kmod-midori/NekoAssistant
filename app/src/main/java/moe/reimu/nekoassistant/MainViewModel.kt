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
import moe.reimu.nekoassistant.ai.AgentChatMessage
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.nekoassistant.data.LlmModelEntity
import moe.reimu.nekoassistant.data.LlmProviderEntity
import moe.reimu.nekoassistant.data.LlmProviderWithModels

data class UiState(
    val isServiceConnected: Boolean = false,
    val previewBitmap: Bitmap? = null,
    val agentPrompt: String = "",
    val latestMessage: AgentChatMessage? = null,
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

    private val previewListener = AgentService.PreviewListener { bitmap ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        } else {
            mainHandler.post {
                _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
            }
        }
    }

    private val conversationListener = AgentService.ConversationListener { message ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _uiState.value = _uiState.value.copy(latestMessage = message)
        } else {
            mainHandler.post {
                _uiState.value = _uiState.value.copy(latestMessage = message)
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private var agentService: AgentService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName, p1: IBinder) {
            val service = (p1 as AgentService.LocalBinder).getService()
            agentService = service
            _uiState.value = _uiState.value.copy(isServiceConnected = true)
            registerPreviewListener(service)
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            agentService = null
            _uiState.value = _uiState.value.copy(
                isServiceConnected = false,
                previewBitmap = null,
                latestMessage = null,
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

    private fun registerPreviewListener(service: AgentService) {
        service.addPreviewListener(previewListener)
        service.addConversationListener(conversationListener)
    }

    fun updateAgentPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(agentPrompt = prompt)
    }

    fun createProvider(name: String, baseUrl: String, apiKey: String) = viewModelScope.launch {
        llmConfigurationRepository.createProvider(name, baseUrl, apiKey)
    }

    fun saveProvider(provider: LlmProviderEntity, name: String, baseUrl: String, apiKey: String) =
        viewModelScope.launch {
            llmConfigurationRepository.saveProvider(provider, name, baseUrl, apiKey)
        }

    fun deleteProvider(provider: LlmProviderEntity) = viewModelScope.launch {
        llmConfigurationRepository.deleteProvider(provider)
    }

    fun createModel(providerId: Long, name: String) = viewModelScope.launch {
        llmConfigurationRepository.createModel(providerId, name)
    }

    fun saveModel(model: LlmModelEntity, name: String) = viewModelScope.launch {
        llmConfigurationRepository.saveModel(model, name)
    }

    fun deleteModel(model: LlmModelEntity) = viewModelScope.launch {
        llmConfigurationRepository.deleteModel(model)
    }

    fun selectModel(modelId: Long) = viewModelScope.launch {
        llmConfigurationRepository.selectModel(modelId)
    }

    fun startAgent() {
        val prompt = _uiState.value.agentPrompt
        if (prompt.isBlank()) {
            return
        }

        val intent = Intent(getApplication(), AgentService::class.java).apply {
            putExtra(AgentService.EXTRA_AGENT_PROMPT, prompt)
        }
        application.startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        agentService?.removePreviewListener(previewListener)
        agentService?.removeConversationListener(conversationListener)
        if (agentService != null) {
            application.unbindService(serviceConnection)
            agentService = null
        }
    }
}
