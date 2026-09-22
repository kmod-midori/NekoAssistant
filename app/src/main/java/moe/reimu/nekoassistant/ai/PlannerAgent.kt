package moe.reimu.nekoassistant.ai

import com.aallam.openai.api.chat.ChatCompletion
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
) {
    private val chatHistory = mutableListOf<ChatMessage>(
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
        val completion: ChatCompletion = client.chatCompletion(chatRequest)
        val message = completion.choices.first().message
        chatHistory.add(message)

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
        return message.content?.replace(thinkingRegex, "")
    }

    companion object {
        private val thinkingRegex = """<think>(.*?)</think>""".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
}
