package moe.reimu.nekoassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import moe.reimu.nekoassistant.data.LlmModelEntity
import moe.reimu.nekoassistant.data.LlmProviderEntity
import moe.reimu.nekoassistant.data.LlmProviderWithModels

@Composable
fun SelectedLlmConfigurationCard(
    providers: List<LlmProviderWithModels>,
    onClick: () -> Unit,
) {
    val selected = providers.firstNotNullOfOrNull { providerWithModels ->
        providerWithModels.models.firstOrNull { it.isSelected }?.let { model ->
            providerWithModels.provider to model
        }
    }
    DefaultCard(onClick = onClick) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("LLM configuration", style = MaterialTheme.typography.titleMedium)
            if (selected == null) {
                Text("No model selected. Tap to manage providers.")
            } else {
                Text(selected.first.name, style = MaterialTheme.typography.bodyLarge)
                Text(selected.second.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagementPage(
    providers: List<LlmProviderWithModels>,
    onBack: () -> Unit,
    onCreateProvider: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onSaveProvider: (provider: LlmProviderEntity, name: String, baseUrl: String, apiKey: String) -> Unit,
    onDeleteProvider: (LlmProviderEntity) -> Unit,
    onCreateModel: (providerId: Long, name: String) -> Unit,
    onSaveModel: (model: LlmModelEntity, name: String) -> Unit,
    onDeleteModel: (LlmModelEntity) -> Unit,
    onSelectModel: (modelId: Long) -> Unit,
) {
    var providerBeingEdited by remember { mutableStateOf<LlmProviderEntity?>(null) }
    var isAddingProvider by remember { mutableStateOf(false) }
    var modelBeingEdited by remember { mutableStateOf<LlmModelEntity?>(null) }
    var providerForNewModel by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { isAddingProvider = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add provider",
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (providers.isEmpty()) {
                item {
                    Text(
                        "Add an OpenAI-compatible provider and model to use the agent.",
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(
                count = providers.size,
                key = { providers[it].provider.id },
            ) { index ->
                val providerWithModels = providers[index]
                ProviderCard(
                    providerWithModels = providerWithModels,
                    onEditProvider = { providerBeingEdited = it },
                    onAddModel = { providerForNewModel = it },
                    onSelectModel = onSelectModel,
                    onEditModel = { modelBeingEdited = it },
                    onDeleteModel = onDeleteModel,
                )
            }
        }
    }

    if (isAddingProvider) {
        ProviderDialog(
            onDismiss = { isAddingProvider = false },
            onSave = { name, baseUrl, apiKey ->
                onCreateProvider(name, baseUrl, apiKey)
                isAddingProvider = false
            },
        )
    }
    providerBeingEdited?.let { provider ->
        ProviderDialog(
            provider = provider,
            onDismiss = { providerBeingEdited = null },
            onSave = { name, baseUrl, apiKey ->
                onSaveProvider(provider, name, baseUrl, apiKey)
                providerBeingEdited = null
            },
            onDelete = {
                onDeleteProvider(provider)
                providerBeingEdited = null
            },
        )
    }
    providerForNewModel?.let { providerId ->
        ModelDialog(
            onDismiss = { providerForNewModel = null },
            onSave = { name ->
                onCreateModel(providerId, name)
                providerForNewModel = null
            },
        )
    }
    modelBeingEdited?.let { model ->
        ModelDialog(
            model = model,
            onDismiss = { modelBeingEdited = null },
            onSave = { name ->
                onSaveModel(model, name)
                modelBeingEdited = null
            },
        )
    }
}

@Composable
private fun ProviderCard(
    providerWithModels: LlmProviderWithModels,
    onEditProvider: (LlmProviderEntity) -> Unit,
    onAddModel: (Long) -> Unit,
    onSelectModel: (Long) -> Unit,
    onEditModel: (LlmModelEntity) -> Unit,
    onDeleteModel: (LlmModelEntity) -> Unit,
) {
    val provider = providerWithModels.provider
    DefaultCard(onClick = { onEditProvider(provider) }) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
            providerWithModels.models.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = model.isSelected,
                        onClick = { onSelectModel(model.id) },
                    )
                    Text(model.name, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEditModel(model) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit model",
                        )
                    }
                    IconButton(
                        onClick = { onDeleteModel(model) },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete model",
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = { onAddModel(provider.id) }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add model",
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDialog(
    provider: LlmProviderEntity? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(provider?.id) { mutableStateOf(provider?.name.orEmpty()) }
    var baseUrl by remember(provider?.id) { mutableStateOf(provider?.baseUrl.orEmpty()) }
    var apiKey by remember(provider?.id) { mutableStateOf(provider?.apiKey.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) "Add provider" else "Edit provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Provider name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    supportingText = { Text("Include the OpenAI-compatible API path, such as /v1/") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = { onSave(name, baseUrl, apiKey) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(
                        onClick = it,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ModelDialog(
    model: LlmModelEntity? = null,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(model?.id) { mutableStateOf(model?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model == null) "Add model" else "Edit model") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model ID") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
