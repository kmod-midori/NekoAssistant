package moe.reimu.nekoassistant.ai

/**
 * One turn of the conversation. Owned by [moe.reimu.nekoassistant.AgentService] rather than
 * by the screen, so the transcript outlives the activity that started it.
 */
data class ChatMessage(
    val sender: String,
    val text: String,
    /** Epoch millis, stamped once when the turn starts and kept across its stream updates. */
    val timestamp: Long,
    val fromUser: Boolean = false,
)
