package moe.reimu.nekoassistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.reimu.nekoassistant.data.LlmConfigurationRepository
import moe.reimu.nekoassistant.data.LlmModelEntity
import moe.reimu.nekoassistant.data.LlmProviderEntity
import moe.reimu.nekoassistant.data.LlmProviderWithModels

class ProviderManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LlmConfigurationRepository(application)

    val providers: StateFlow<List<LlmProviderWithModels>> = repository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createProvider(name: String, baseUrl: String, apiKey: String) = viewModelScope.launch {
        repository.createProvider(name, baseUrl, apiKey)
    }

    fun saveProvider(provider: LlmProviderEntity, name: String, baseUrl: String, apiKey: String) =
        viewModelScope.launch {
            repository.saveProvider(provider, name, baseUrl, apiKey)
        }

    fun deleteProvider(provider: LlmProviderEntity) = viewModelScope.launch {
        repository.deleteProvider(provider)
    }

    fun createModel(providerId: Long, name: String) = viewModelScope.launch {
        repository.createModel(providerId, name)
    }

    fun saveModel(model: LlmModelEntity, name: String) = viewModelScope.launch {
        repository.saveModel(model, name)
    }

    fun deleteModel(model: LlmModelEntity) = viewModelScope.launch {
        repository.deleteModel(model)
    }

    fun selectModel(modelId: Long) = viewModelScope.launch {
        repository.selectModel(modelId)
    }
}
