package moe.reimu.nekoassistant.ai

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.getPlannerSystemPrompt
import java.time.LocalDate

class PlannerAgent(
    userPrompt: String,
    private val configuration: ActiveLlmConfiguration,
    private val client: OpenAI,
    private val onStatus: (InferenceStatus) -> Unit = {},
    private val onStream: (String) -> Unit = {},
) {
    private val chatHistory = mutableListOf(
        ChatMessage(
            role = ChatRole.System,
            content = getPlannerSystemPrompt(
                LocalDate.now().toString(),
            )
        ),
        ChatMessage(
            role = ChatRole.User,
            content = userPrompt
        )
    )

    suspend fun plan(screenshotDataUri: String?, currentAppName: String?): String? {
        val textPrompt = """
            === 任务状态信息 ===
            当前前台应用：$currentAppName
        """.trimIndent()
        val messageContent = mutableListOf<ContentPart>(
            TextPart(textPrompt)
        )
        if (screenshotDataUri != null) {
            messageContent.add(ImagePart(screenshotDataUri))
        }
        chatHistory.add(ChatMessage(role = ChatRole.User, content = messageContent))

        val chatRequest = ChatCompletionRequest(
            model = ModelId(configuration.modelName),
            messages = chatHistory,
        )

        val streamedText = StringBuilder()
        val assistantMessage = client.streamChatMessage(
            request = chatRequest,
            onChunk = { chunk ->
                chunk.choices.forEach { choice ->
                    choice.delta?.content?.let { delta ->
                        Log.d(TAG, "Planner stream: $delta")
                        streamedText.append(delta)
                        onStream(streamedText.toString())
                    }
                }
            },
            onStatus = onStatus,
        ) ?: return null

        chatHistory.add(assistantMessage)

        // Remove images
        chatHistory.replaceAll { message ->
            val content = message.messageContent ?: return@replaceAll message
            when (content) {
                is TextContent -> message
                is ListContent -> {
                    message.copy(
                        messageContent = ListContent(content.content.filter { it !is ImagePart })
                    )
                }
            }
        }
        return assistantMessage.content?.replace(thinkingRegex, "")
    }

    /** The call as well as its result: the result alone reads the same whether the executor
     *  tapped (100,200) or swiped across it. */
    fun addActionResult(action: Action, prompt: String) {
        chatHistory.add(
            ChatMessage(
                role = ChatRole.User,
                content = "上一步操作：$action\n上一步操作执行结果：$prompt"
            )
        )
    }

    companion object {
        private const val TAG = "PlannerAgent"
        private val thinkingRegex = """<think>(.*?)</think>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}
