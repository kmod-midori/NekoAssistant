package moe.reimu.nekoassistant.ai

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
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
