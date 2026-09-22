package moe.reimu.nekoassistant.ai

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.getSystemPrompt
import java.time.LocalDate

class ExecutorAgent(
    private val screenshotWidth: Int,
    private val screenshotHeight: Int,
    private val configuration: ActiveLlmConfiguration,
    private val client: OpenAI,
) {
    suspend fun execute(
        plan: String,
        screenshotDataUri: String?,
        currentAppName: String?
    ): AgentResponse? {
        val textPrompt = """
                ## 任务状态信息
                当前前台应用：$currentAppName
                屏幕右下角坐标：(${screenshotWidth - 1},${screenshotHeight - 1})
                ## 任务计划
                $plan
            """.trimIndent()
        val messageContent = mutableListOf<ContentPart>(
            TextPart(textPrompt)
        )
        if (screenshotDataUri != null) {
            messageContent.add(ImagePart(screenshotDataUri))
        }

        val chatRequest = ChatCompletionRequest(
            model = ModelId(configuration.modelName),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.System,
                    content = getSystemPrompt(
                        LocalDate.now().toString(),
                        screenshotWidth - 1, screenshotHeight - 1
                    )
                ),
                ChatMessage(role = ChatRole.User, content = messageContent)
            ),
            temperature = 0.7,
        )
        val completion: ChatCompletion = client.chatCompletion(chatRequest)

        val retMessage = completion.choices.first().message
        return AgentResponse.parse(retMessage.content ?: return null)
    }

}
