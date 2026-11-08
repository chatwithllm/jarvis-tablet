package net.npalakurla.jarvis.tablet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct ContextForge `/llm/v1` conversation client (OpenAI-compatible
 * `POST {base}/chat/completions`). This is the Phase 4 voice path: the
 * tablet talks to the gateway itself when the `cf-jarvis-tablet-llm`
 * bearer is saved. A turn does not fall back to the Mini relay.
 *
 * The LLM bearer is not the MCP bearer (the MCP one is rejected with
 * 403 on this route). Home Assistant reads go through [HaClient] and the
 * MCP bearer. Nothing here holds a raw HA token.
 *
 * Tool policy (defense in depth, same rules as [PolicyController]):
 * the model may only *propose* a change. Locks, garage, and alarm are
 * refused. A proposed change is stored locally and executed only after
 * [VoiceTurn] has passed the confirm gate, and then only via the MCP
 * tool `ha-call-service`. Unknown tools are not forwarded.
 */
class ContextForgeLlmTransport(private val context: Context) {

    private data class ToolCall(val id: String, val name: String, val args: JSONObject)

    private data class StoredCall(
        val domain: String,
        val service: String,
        val entityId: String,
    )

    private class TurnState {
        var proposed: VoiceTurn.ProposedAction? = null
    }

    /** Action ids this client created and has not executed or discarded. */
    private val pending = ConcurrentHashMap<String, StoredCall>()

    fun owns(actionId: String): Boolean = pending.containsKey(actionId)

    fun discard(actionId: String) {
        pending.remove(actionId)
    }

    /**
     * One conversation turn from a transcript. Speech-to-text has already
     * happened on the device; this method never sees audio.
     */
    suspend fun complete(transcript: String): Result<VoiceTurn.TurnResult> =
        withContext(Dispatchers.IO) {
            runCatching { completeBlocking(transcript) }
        }

    /** Run a stored call after the confirm gate. Fails closed. */
    suspend fun execute(actionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val call = pending.remove(actionId)
            ?: return@withContext Result.failure(
                IllegalStateException("unknown or already-executed action"),
            )
        if (PolicyController.isRefused(call.domain, call.entityId, service = call.service)) {
            return@withContext Result.failure(IllegalStateException("refused by policy"))
        }
        HaClient(context).callServiceBlocking(
            domain = call.domain,
            service = call.service,
            entityId = call.entityId,
        )
    }

    private fun completeBlocking(transcript: String): VoiceTurn.TurnResult {
        // Noun gate again, in case a caller skipped VoiceTurn. No HTTP.
        if (SafetyGate.refuses(transcript)) {
            Log.i("JarvisVoice", "refused=safety")
            return VoiceTurn.TurnResult(
                transcript = transcript,
                responseText = "That is not allowed by voice.",
                ttsAudio = null,
                proposedAction = null,
            )
        }
        val token = TokenStore.getLlmToken(context)
            ?: throw LlmCallFailed(0, "llm token missing")
        val model = TokenStore.getLlmModel(context)
        val url = chatCompletionsUrl(TokenStore.getLlmBaseUrl(context))
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            put(JSONObject().put("role", "user").put("content", transcript.take(MAX_USER_CHARS)))
        }
        val tools = toolDefinitions()
        val state = TurnState()
        val ha = HaClient(context)
        try {
            return completeRounds(
                transcript, url, token, model, messages, tools, state, ha,
            )
        } catch (e: Exception) {
            // The id was never shown. Don't leave an executable call behind
            // if the chat fails after propose_action was stored.
            state.proposed?.let { pending.remove(it.id) }
            throw e
        }
    }

    private fun completeRounds(
        transcript: String,
        url: String,
        token: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray,
        state: TurnState,
        ha: HaClient,
    ): VoiceTurn.TurnResult {
        val outcome = LeanChat.run(
            post = { postChat(url, token, model, messages, tools) },
            onTools = { message, calls ->
                if (message.isNull("content")) message.put("content", "")
                messages.put(message)
                for (call in calls) {
                    messages.put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.id)
                            put(
                                "content",
                                handleTool(ToolCall(call.id, call.name, call.args), ha, state)
                                    .take(MAX_TOOL_CHARS),
                            )
                        },
                    )
                }
            },
        )
        return VoiceTurn.TurnResult(
            transcript = transcript,
            responseText = outcome.text,
            ttsAudio = null,
            proposedAction = state.proposed,
        )
    }

    private fun handleTool(call: ToolCall, ha: HaClient, state: TurnState): String {
        val name = call.name.trim().replace('-', '_')
        return when (name) {
            "propose_action" -> propose(call.args, state)
            "ha_get_states" -> readTool(ha, "ha-get-states", call.args)
            "ha_get_state" -> readTool(ha, "ha-get-state", call.args)
            "ha_get_history" -> readTool(ha, "ha-get-history", call.args)
            "ha_get_config" -> readTool(ha, "ha-get-config", call.args)
            "ha_get_logbook" -> readTool(ha, "ha-get-logbook", call.args)
            else ->
                "That tool is not available. To change a device, call propose_action. " +
                    "It does not run until the person confirms. Locks, garage doors, and " +
                    "the alarm cannot be controlled by voice."
        }
    }

    private fun readTool(ha: HaClient, mcpName: String, args: JSONObject): String {
        // Only small scalar arguments are forwarded. The MCP bearer never
        // goes in this payload, and unknown tools never reach this method.
        val forwarded = JSONObject()
        val keys = args.keys()
        var count = 0
        while (keys.hasNext() && count < 8) {
            val key = keys.next()
            if (!key.matches(SAFE_KEY)) continue
            when (val value = args.opt(key)) {
                is String -> forwarded.put(key, value.take(200))
                is Number, is Boolean -> forwarded.put(key, value)
            }
            count++
        }
        val entity = forwarded.optString("entity_id")
        if (SafetyGate.refuses(forwarded.toString()) ||
            SafetyGate.refusesTool(entity.substringBefore('.'), entity, null, entity)
        ) {
            Log.i("JarvisVoice", "refused=safety")
            return "Refused. Locks, garage doors, and the alarm cannot be controlled by voice."
        }
        val result = ha.readToolBlocking(mcpName, forwarded)
        if (result.isFailure) {
            return if (result.exceptionOrNull() is HaClient.Unauthorized) {
                "The tablet MCP bearer was rejected. Say you cannot reach Home Assistant."
            } else {
                "Home Assistant read failed."
            }
        }
        val text = result.getOrNull().orEmpty()
        if (text.length <= MAX_TOOL_CHARS) return text
        return text.take(MAX_TOOL_CHARS) +
            "\n…(truncated). Ask ha_get_state with an entity_id for one entity."
    }

    private fun propose(args: JSONObject, state: TurnState): String {
        if (state.proposed != null) return "Only one change can be proposed per turn."
        val summary = args.optString("summary").trim().take(200)
        val domain = args.optString("domain").trim().lowercase()
        val entityId = args.optString("entity_id").trim().lowercase()
        val newState = args.optString("new_state").trim().take(64)
        val room = args.optString("room").trim().take(80).ifEmpty { null }
        if (summary.isEmpty() || domain.isEmpty() || entityId.isEmpty() || newState.isEmpty()) {
            return "propose_action needs summary, domain, entity_id, and new_state."
        }
        if (!entityId.matches(ENTITY_ID)) {
            return "entity_id must look like domain.object (lowercase)."
        }
        val hinted = args.optString("service").trim().ifEmpty { newState }
        if (SafetyGate.refusesTool(domain, entityId, hinted, "$summary $newState") ||
            SafetyGate.refuses(newState)
        ) {
            Log.i("JarvisVoice", "refused=safety")
            return "Refused. Locks, garage doors, and the alarm cannot be controlled by voice. " +
                "Tell the user it is not allowed. Do not suggest a workaround."
        }
        val service = serviceFor(domain, newState, args.optString("service"))
            ?: return "That service name is not allowed."
        if (SafetyGate.refusesTool(domain, entityId, service, summary) || SafetyGate.refuses(summary)) {
            Log.i("JarvisVoice", "refused=safety")
            return "Refused. Locks, garage doors, and the alarm cannot be controlled by voice. " +
                "Tell the user it is not allowed. Do not suggest a workaround."
        }
        val action = VoiceTurn.ProposedAction(
            id = UUID.randomUUID().toString(),
            summary = summary,
            domain = domain,
            entityId = entityId,
            newState = newState,
            room = room,
            service = service,
        )
        // First proposal wins, including a refused one, so a later tool
        // call in this turn cannot replace it with something else.
        state.proposed = action
        if (PolicyController.isRefused(domain, entityId, service = service)) {
            // Not stored, so a confirm cannot execute it. VoiceTurn still
            // shows the refused card because the action is returned.
            return "Refused. Locks, garage doors, and the alarm cannot be " +
                "controlled by voice. Tell the user it is not allowed. Do not " +
                "suggest a workaround that would still change the device."
        }
        pending[action.id] = StoredCall(domain = domain, service = service, entityId = entityId)
        return "Queued for confirmation. Tell the user what will change and that " +
            "you are waiting for a yes. Do not say it is done."
    }

    private fun postChat(
        url: String,
        token: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?,
    ): JSONObject {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", false)
            put("temperature", 0.2)
            // Short spoken replies. This is a request cap, not a measured latency.
            put("max_tokens", 160)
            if (tools != null) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            // Do not follow redirects: that would send the LLM bearer to another host.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            val started = System.nanoTime()
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val chatMs = (System.nanoTime() - started) / 1_000_000L
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // The body is parsed, never logged. It can echo the prompt.
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().let { text ->
                Log.i(TAG, "chat HTTP $code chat_ms=$chatMs")
                if (code == 401 || code == 403) throw LlmCallFailed(code, "llm HTTP $code")
                if (code !in 200..299) throw LlmCallFailed(code, "llm HTTP $code")
                val trimmed = text.trim()
                if (!trimmed.startsWith("{")) {
                    throw LlmCallFailed(code, "llm returned a non-JSON body")
                }
                val json = JSONObject(trimmed)
                if (json.has("error") && json.optJSONArray("choices") == null) {
                    throw LlmCallFailed(code, "llm error")
                }
                json
            }
        } catch (e: LlmCallFailed) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "chat transport failed: ${e.javaClass.simpleName}")
            throw LlmCallFailed(0, "llm unreachable")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ContextForgeLlm"
        private const val MAX_TOOL_CHARS = 12_000
        private const val MAX_USER_CHARS = 2_000
        private val SAFE_KEY = Regex("[A-Za-z0-9_]{1,40}")
        private val ENTITY_ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")
        private val SERVICE_NAME = Regex("[a-z0-9_]{1,64}")

        internal const val SYSTEM_PROMPT =
            "You are Jarvis on the household tablet. Speak as a calm, refined butler: " +
                "dry, polite, slightly crisp, never chatty, and never an imitation of a " +
                "named actor. The user text is a speech transcript and may contain " +
                "recognition mistakes. Reply in one or two short sentences meant to be " +
                "spoken aloud. Never state the clock time. Never state a temperature, " +
                "a sky condition, or a forecast unless a tool result in this conversation " +
                "contains that reading. If it does not, say weather is unavailable. " +
                "Use the ha_* tools for Home Assistant questions. " +
                "Prefer ha_get_state when you know the entity_id. To change a device, call " +
                "propose_action. That only asks the person to confirm; it does not run the " +
                "change. Never say a change is already done. Never control locks, garage " +
                "doors, or the alarm — refuse those. A status question may be answered, " +
                "but do not propose unlocking, disarming, or opening a garage. " +
                "Do not invent device state. If a tool fails, say you cannot reach Home Assistant."

        /** `{base}/chat/completions`, without doubling the path. */
        internal fun chatCompletionsUrl(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed
            else "$trimmed/chat/completions"
        }

        /**
         * HA service for a proposed change. Null when the name is not a
         * plain service token (rejected, not coerced into turn_on).
         */
        internal fun serviceFor(domain: String, newState: String, explicit: String?): String? {
            val chosen = explicit?.trim().orEmpty().ifEmpty {
                when (newState.trim().lowercase()) {
                    "on", "turn_on" -> "turn_on"
                    "off", "turn_off" -> "turn_off"
                    "toggle" -> "toggle"
                    "open" -> if (domain == "cover") "open_cover" else "turn_on"
                    "close", "closed" -> if (domain == "cover") "close_cover" else "turn_off"
                    else -> newState.trim().lowercase()
                }
            }.lowercase()
            if (!chosen.matches(SERVICE_NAME)) return null
            return chosen
        }

        private fun toolDefinitions(): JSONArray = JSONArray().apply {
            put(
                functionTool(
                    "ha_get_state",
                    "Read one Home Assistant entity. Prefer this over ha_get_states.",
                    JSONObject().put("entity_id", strProp("Entity id, for example light.kitchen")),
                    listOf("entity_id"),
                ),
            )
            put(
                functionTool(
                    "ha_get_states",
                    "Read Home Assistant states. The result is truncated. Prefer ha_get_state.",
                    JSONObject(),
                    emptyList(),
                ),
            )
            put(
                functionTool(
                    "ha_get_history",
                    "Read recent Home Assistant history for one entity.",
                    JSONObject().put("entity_id", strProp("Entity id")),
                    listOf("entity_id"),
                ),
            )
            put(
                functionTool(
                    "ha_get_config",
                    "Read Home Assistant configuration (liveness and names).",
                    JSONObject(),
                    emptyList(),
                ),
            )
            put(
                functionTool(
                    "ha_get_logbook",
                    "Read the Home Assistant logbook.",
                    JSONObject().put("entity_id", strProp("Optional entity id")),
                    emptyList(),
                ),
            )
            put(
                functionTool(
                    "propose_action",
                    "Ask the person to confirm one Home Assistant change. Does not run it. " +
                        "Locks, garage doors, and the alarm are refused.",
                    JSONObject().apply {
                        put("summary", strProp("Short confirmation, for example Turn on the kitchen light"))
                        put("domain", strProp("HA domain: light, switch, cover, climate, media_player"))
                        put("entity_id", strProp("Entity id, for example light.kitchen"))
                        put("new_state", strProp("on, off, or another target state"))
                        put("service", strProp("Optional HA service, for example turn_on or open_cover"))
                        put("room", strProp("Optional room name"))
                    },
                    listOf("summary", "domain", "entity_id", "new_state"),
                ),
            )
        }

        private fun functionTool(
            name: String,
            description: String,
            properties: JSONObject,
            required: List<String>,
        ): JSONObject = JSONObject().apply {
            put("type", "function")
            put(
                "function",
                JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put(
                        "parameters",
                        JSONObject().apply {
                            put("type", "object")
                            put("properties", properties)
                            put("required", JSONArray(required))
                        },
                    )
                },
            )
        }

        private fun strProp(description: String): JSONObject =
            JSONObject().put("type", "string").put("description", description)

        private fun assistantText(message: JSONObject): String {
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

        private fun toolCallsOf(message: JSONObject): List<ToolCall> {
            val arr = message.optJSONArray("tool_calls") ?: return emptyList()
            val out = ArrayList<ToolCall>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val fn = obj.optJSONObject("function") ?: continue
                val name = fn.optString("name").trim()
                if (name.isEmpty()) continue
                val id = obj.optString("id").ifBlank { "call_$i" }
                out.add(ToolCall(id, name, parseArgs(fn.opt("arguments"))))
            }
            return out
        }

        private fun parseArgs(raw: Any?): JSONObject = when (raw) {
            is JSONObject -> raw
            is String ->
                if (raw.isBlank()) JSONObject()
                else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            else -> JSONObject()
        }
    }
}

/** Chat-completions call failed. [http] is 0 for a transport failure. The message never includes a token. */
class LlmCallFailed(val http: Int, message: String) : Exception(message)
