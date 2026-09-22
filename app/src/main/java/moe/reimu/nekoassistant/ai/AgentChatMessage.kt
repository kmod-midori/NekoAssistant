package moe.reimu.nekoassistant.ai

data class AgentChatMessage(
    val plan: String,
    val agentResponse: AgentResponse,
)
