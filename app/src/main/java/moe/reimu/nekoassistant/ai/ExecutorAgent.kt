package moe.reimu.nekoassistant.ai

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextContent
import com.aallam.openai.api.chat.TextPart
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import moe.reimu.nekoassistant.data.ActiveLlmConfiguration
import moe.reimu.nekoassistant.getSystemPrompt
import java.time.LocalDate

class ExecutorAgent(
    private val screenshotWidth: Int,
    private val screenshotHeight: Int,
    private val configuration: ActiveLlmConfiguration,
    private val client: OpenAI,
    private val onStatus: (InferenceStatus) -> Unit = {},
    private val onStream: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

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
            tools = tools,
            toolChoice = ToolChoice.Required,
        )

        val streamedText = StringBuilder()
        val message = client.streamChatMessage(
            request = chatRequest,
            onChunk = { chunk ->
                chunk.choices.forEach { choice ->
                    choice.delta?.content?.let { delta ->
                        Log.d(TAG, "Executor stream: $delta")
                        streamedText.append(delta)
                        onStream(streamedText.toString())
                    }
                    choice.delta?.toolCalls?.forEach { toolCall ->
                        toolCall.function?.argumentsOrNull?.let { Log.d(TAG, "Executor tool args: $it") }
                    }
                }
            },
            onStatus = onStatus,
        ) ?: return null

        val toolCall = message.toolCalls?.firstOrNull() as? ToolCall.Function ?: return null
        val action = toAction(toolCall.function) ?: return null
        val think = (message.messageContent as? TextContent)?.content.orEmpty()
        return AgentResponse(think, action)
    }

    private fun toAction(call: FunctionCall): Action? = try {
        when (call.name) {
            "SearchApp" -> json.decodeFromString(Action.SearchApp.serializer(), call.arguments)
            "ListApps" -> json.decodeFromString(Action.ListApps.serializer(), call.arguments)
            "Launch" -> json.decodeFromString(Action.Launch.serializer(), call.arguments)
            "Tap" -> json.decodeFromString(Action.Tap.serializer(), call.arguments)
            "Type" -> json.decodeFromString(Action.Type.serializer(), call.arguments)
            "Swipe" -> json.decodeFromString(Action.Swipe.serializer(), call.arguments)
            "LongPress" -> json.decodeFromString(Action.LongPress.serializer(), call.arguments)
            "DoubleTap" -> json.decodeFromString(Action.DoubleTap.serializer(), call.arguments)
            "TakeOver" -> json.decodeFromString(Action.TakeOver.serializer(), call.arguments)
            "Back" -> Action.Back
            "Wait" -> json.decodeFromString(Action.Wait.serializer(), call.arguments)
            "Finish" -> Action.Finish
            else -> null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode tool call ${call.name}", e)
        null
    }

    companion object {
        private const val TAG = "ExecutorAgent"

        private fun obj(vararg props: Pair<String, String>): Parameters =
            Parameters.buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    props.forEach { (name, type) ->
                        putJsonObject(name) { put("type", type) }
                    }
                }
                putJsonArray("required") {
                    props.forEach { (name, _) -> add(JsonPrimitive(name)) }
                }
            }

        private val tools = listOf(
            Tool.function(
                name = "SearchApp",
                description = "按名称搜索已安装应用，返回应用的名称和包名，可能返回多个结果。query 不能为空。",
                parameters = obj("query" to "string"),
            ),
            Tool.function(
                name = "ListApps",
                description = "列出可启动的应用，返回应用名称和包名，每页 50 个。" +
                    "page 从 0 开始，第一次调用传 page=0，返回值会说明是否有下一页。当 SearchApp 找不到或需要浏览全部应用时使用。",
                parameters = obj("page" to "integer"),
            ),
            Tool.function(
                name = "Launch",
                description = "按包名启动目标应用（不是显示名称）。这是启动应用的唯一方式，如果不知道包名，需先通过 SearchApp 或 ListApps 获取，不要尝试用其他方法打开应用。",
                parameters = obj("packageName" to "string"),
            ),
            Tool.function(
                name = "Tap",
                description = "点击屏幕上的特定点，用于点击按钮、选择项目或与任何可点击的 UI 元素交互。",
                parameters = obj("x" to "number", "y" to "number"),
            ),
            Tool.function(
                name = "Type",
                description = "在当前聚焦的输入框中输入文本。使用前请确保输入框已被聚焦（先 Tap 点击它）。文本中不要出现转义字符。",
                parameters = obj("text" to "string"),
            ),
            Tool.function(
                name = "Swipe",
                description = "通过从起始坐标拖动到结束坐标来执行滑动手势，用于滚动内容、在屏幕间导航、下拉通知栏等。",
                parameters = obj(
                    "startX" to "number", "startY" to "number",
                    "endX" to "number", "endY" to "number",
                ),
            ),
            Tool.function(
                name = "LongPress",
                description = "在屏幕上的特定点长按，用于触发上下文菜单、选择文本或激活长按交互。",
                parameters = obj("x" to "integer", "y" to "integer"),
            ),
            Tool.function(
                name = "DoubleTap",
                description = "在屏幕上的特定点快速连续点按两次，用于激活双击交互。",
                parameters = obj("x" to "integer", "y" to "integer"),
            ),
            Tool.function(
                name = "TakeOver",
                description = "接管操作，表示在登录和验证阶段需要用户协助。message 尽量简洁。",
                parameters = obj("message" to "string"),
            ),
            Tool.function(
                name = "Back",
                description = "返回到上一个屏幕或关闭当前对话框，相当于按下 Android 返回按钮。",
                parameters = Parameters.Empty,
            ),
            Tool.function(
                name = "Wait",
                description = "等待页面加载，seconds 为需要等待的秒数。",
                parameters = obj("seconds" to "integer"),
            ),
            Tool.function(
                name = "Finish",
                description = "结束任务，表示准确完整地完成了任务。",
                parameters = Parameters.Empty,
            ),
        )
    }
}
