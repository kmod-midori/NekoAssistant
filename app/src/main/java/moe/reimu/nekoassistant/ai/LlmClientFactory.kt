package moe.reimu.nekoassistant.ai

import com.aallam.openai.api.ExperimentalOpenAI
import com.aallam.openai.api.chat.ChatChunk
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.extension.mergeToChatMessage
import kotlinx.coroutines.flow.collect
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import kotlin.time.Duration.Companion.seconds

fun createLlmClient(configuration: ActiveLlmConfiguration) = OpenAI(
    token = configuration.apiKey,
    host = OpenAIHost(baseUrl = configuration.baseUrl),
    timeout = Timeout(
        request = 120.seconds,
        connect = 120.seconds,
        socket = 120.seconds,
    )
)

@OptIn(ExperimentalOpenAI::class)
suspend fun OpenAI.streamChatMessage(
    request: ChatCompletionRequest,
    onChunk: (ChatCompletionChunk) -> Unit = {},
    onStatus: (InferenceStatus) -> Unit = {},
): ChatMessage? {
    onStatus(InferenceStatus.WAITING)
    val chunks = mutableListOf<ChatChunk>()
    try {
        var streaming = false
        chatCompletions(request).collect { chunk ->
            if (!streaming) {
                streaming = true
                onStatus(InferenceStatus.STREAMING)
            }
            onChunk(chunk)
            chunks.addAll(chunk.choices)
        }
    } catch (e: Exception) {
        onStatus(InferenceStatus.ERROR)
        throw e
    }
    onStatus(InferenceStatus.IDLE)
    if (chunks.isEmpty()) return null
    return chunks.mergeToChatMessage()
}
