package moe.reimu.nekoassistant.ai

import android.util.Log

data class AgentResponse(
    val think: String,
    val action: Action
) {
    companion object {
        private const val TAG = "AgentResponse"

        fun parse(text: String): AgentResponse? {
            return try {
                // Extract <message> and <answer> tags
                val messageRegex = """<message>(.*?)</message>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val answerRegex = """<answer>(.*?)</answer>""".toRegex(RegexOption.DOT_MATCHES_ALL)

                val messageMatch = messageRegex.find(text)
                val answerMatch = answerRegex.find(text)

                if (answerMatch == null) {
                    Log.e(TAG, "Could not find <answer> tags in response")
                    return null
                }

                val message = messageMatch?.groupValues?.get(1)?.trim().orEmpty()
                val answerText = answerMatch.groupValues[1].trim()

                val answerParts = answerText.split(":", limit = 2)

                val action = when (answerParts[0]) {
                    "Launch" -> Action.Launch(answerParts[1])
                    "Tap" -> {
                        val coordinate = answerParts[1].split(",", limit = 2)
                        Action.Tap(coordinate[0].toFloat(), coordinate[1].toFloat())
                    }

                    "Swipe" -> {
                        val coordinate = answerParts[1].split(",", limit = 4)
                        Action.Swipe(
                            coordinate[0].toFloat(),
                            coordinate[1].toFloat(),
                            coordinate[2].toFloat(),
                            coordinate[3].toFloat(),
                        )
                    }

                    "Type" -> {
                        Action.Type(answerParts[1])
                    }

                    "Back" -> Action.Back
                    "Note" -> {
                        Action.Note("")
                    }

                    "Wait" -> {
                        val seconds = answerParts[1].toInt()
                        Action.Wait(seconds)
                    }

                    "Summarize" -> {
                        Action.Summarize("")
                    }

                    "Finish" -> Action.Finish
                    else -> return null
                }

                AgentResponse(message, action)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse agent response: $text", e)
                null
            }
        }
    }
}