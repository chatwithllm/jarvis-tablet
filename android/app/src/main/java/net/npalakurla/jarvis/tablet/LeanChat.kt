package net.npalakurla.jarvis.tablet

import org.json.JSONArray
import org.json.JSONObject

/**
 * One intentional turn's chat completions.
 *
 * [MAX_POSTS] is 2. The second post happens only when the first reply
 * is tool calls and has no sentence, so a Home Assistant read can still
 * be spoken. A tools-rejected HTTP error is not retried. A third post
 * is not made. This count is chat completions only: speech-to-text,
 * speech synthesis, and the Mini relay are separate and are not called
 * from here.
 */
internal object LeanChat {
    const val MAX_POSTS = 2

    data class ToolCall(val id: String, val name: String, val args: JSONObject)

    data class Outcome(val text: String, val posts: Int, val toolCalls: List<ToolCall>)

    fun run(post: () -> JSONObject, onTools: (JSONObject, List<ToolCall>) -> Unit): Outcome {
        var posts = 0
        fun once(): JSONObject {
            check(posts < MAX_POSTS) { "chat post cap" }
            posts += 1
            return post()
        }
        val first = messageOf(once())
        val calls = toolCallsOf(first)
        val text = assistantText(first)
        if (calls.isNotEmpty()) onTools(first, calls)
        if (calls.isEmpty() || text.isNotBlank()) {
            return Outcome(text, posts, calls)
        }
        val follow = messageOf(once())
        return Outcome(assistantText(follow), posts, calls)
    }

    private fun messageOf(response: JSONObject): JSONObject =
        response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: throw LlmCallFailed(200, "llm returned no message")

    internal fun assistantText(message: JSONObject): String {
        val content = message.opt("content") ?: return ""
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i)
                    if (part != null) append(part.optString("text"))
                    else append(content.optString(i))
                }
            }.trim()
            else -> ""
        }
    }

    internal fun toolCallsOf(message: JSONObject): List<ToolCall> {
        val arr = message.optJSONArray("tool_calls") ?: return emptyList()
        val out = ArrayList<ToolCall>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val fn = obj.optJSONObject("function") ?: continue
            val name = fn.optString("name").trim()
            if (name.isEmpty()) continue
            val id = obj.optString("id").ifBlank { "call_$i" }
            val raw = fn.opt("arguments")
            val args = when (raw) {
                is JSONObject -> raw
                is String ->
                    if (raw.isBlank()) JSONObject()
                    else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
            out.add(ToolCall(id, name, args))
        }
        return out
    }
}
