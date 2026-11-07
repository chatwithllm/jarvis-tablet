package net.npalakurla.jarvis.tablet

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ContextForge MCP client (native side only). The tablet no longer talks
 * to Home Assistant directly and never holds a raw HA long-lived token
 * (issue #10, owner direction 2026-11-05): all HA data comes through
 * the IBM ContextForge MCP gateway (`jarvis-ha` virtual server), which
 * holds the upstream HA credential server-side. The tablet stores only
 * the gateway URL + a scoped `jarvis-ha` bearer in Keystore-backed
 * EncryptedSharedPreferences. The bearer never enters the WebView, the
 * bridge, logcat, or any bundled asset.
 *
 * Transport is MCP streamable HTTP: each call is a JSON-RPC 2.0 POST to
 * the gateway's MCP endpoint. The gateway answers with a single JSON
 * object or an SSE stream; both are handled. The gateway is READ-ONLY
 * (ha-get-states / ha-get-state / ha-get-history / ha-get-config /
 * ha-get-logbook) — there is no write tool, so the FR-07 heartbeat is a
 * liveness probe (ha-get-config), not an HA sensor write.
 *
 * Phase 1 uses: liveness probe + read-only state fetches for the HUD.
 * Service calls and the conversation pipeline are Phase 2, behind
 * [PolicyController].
 */
class HaClient(private val context: Context) {

    /** ContextForge rejected the bearer (401) — re-auth via Settings (FR-54). */
    class Unauthorized : Exception("ContextForge bearer rejected (401)")

    private val mcpUrl: String? get() = TokenStore.getCfUrl(context)
    private val bearer: String? get() = TokenStore.getCfToken(context)

    private var initialized = false
    private var sessionId: String? = null
    private var nextId = 1

    /**
     * One JSON-RPC call over MCP streamable HTTP. Returns the `result`
     * object, or null when the transport itself failed (caller decides
     * how to surface it). Throws [Unauthorized] on HTTP 401.
     */
    private fun mcpCall(method: String, params: JSONObject?): JSONObject? {
        val url = mcpUrl ?: return null
        val token = bearer ?: return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.setRequestProperty("Authorization", "Bearer $token")
        sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", nextId++)
            put("method", method)
            if (params != null) put("params", params)
        }
        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
        // Keep the session id the gateway returns, if any.
        conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        if (conn.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            conn.disconnect()
            throw Unauthorized()
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        return parseRpcResult(text)
    }

    /**
     * Extract the `result` object from an MCP response body, which may
     * be a single JSON object or an SSE stream (`event:` / `data:` lines).
     * An SSE stream can carry several messages (result, notifications);
     * prefer the one carrying a `result` field, and fall back to the
     * last JSON data line. A JSON-RPC `error` is a failure.
     */
    private fun parseRpcResult(text: String): JSONObject? {
        val candidates: List<String> = if (text.contains("data:")) {
            text.lineSequence()
                .filter { it.trimStart().startsWith("data:") }
                .map { it.substringAfter("data:").trim() }
                .filter { it.startsWith("{") }
                .toList()
        } else {
            listOf(text.trim())
        }
        val parsed = candidates.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val chosen = parsed.lastOrNull { it.has("result") } ?: parsed.lastOrNull()
            ?: return null
        if (chosen.has("error")) return null
        return chosen.optJSONObject("result")
    }

    /** MCP initialize handshake (once per client instance). */
    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        val params = JSONObject().apply {
            put("protocolVersion", "2024-11-05")
            put("capabilities", JSONObject())
            put(
                "clientInfo",
                JSONObject().apply {
                    put("name", "jarvis-tablet")
                    put("version", BuildConfig.VERSION_NAME)
                },
            )
        }
        val result = mcpCall("initialize", params) ?: return false
        // The server's capabilities/serverInfo arrive in `result`; the
        // tablet needs nothing from them in Phase 1 — just the handshake.
        initialized = true
        // Fire-and-forget per the MCP spec; a notification has no id
        // and expects no response.
        runCatching {
            val url = mcpUrl ?: return@runCatching
            val token = bearer ?: return@runCatching
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer $token")
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
        }
        return true
    }

    /**
     * Read-only tools the voice client may call. Writes are not in this
     * set — a confirmed change goes through [callServiceBlocking] only
     * after the confirm gate, and only as `ha-call-service`.
     */
    private val readTools = setOf(
        "ha-get-states",
        "ha-get-state",
        "ha-get-history",
        "ha-get-config",
        "ha-get-logbook",
    )

    /**
     * Read-only MCP tool call for the direct voice client. Blocking;
     * callers already on [Dispatchers.IO]. The name must be one of
     * [readTools]. The bearer is not included in [arguments] and is not
     * logged.
     */
    internal fun readToolBlocking(name: String, arguments: JSONObject = JSONObject()): Result<String> =
        runCatching {
            if (name !in readTools) throw IllegalArgumentException("refusing non-read MCP tool")
            if (mcpUrl == null || bearer == null) throw IllegalStateException("ContextForge not configured")
            toolText(callTool(name, arguments))
                ?: throw IllegalStateException("$name returned no content")
        }.onFailure { e ->
            Log.w("HaClient", "readTool $name failed: ${e.message}")
        }

    /**
     * One HA service call through the ContextForge MCP tool
     * `ha-call-service`, after the confirm gate has accepted it.
     * [PolicyController] is checked again here. There is no raw HA token
     * on the tablet. The jarvis-ha virtual server is read-only today, so
     * this fails closed until IT Admin exposes a write tool — a failure
     * does not fall through to any other credential.
     */
    internal fun callServiceBlocking(domain: String, service: String, entityId: String): Result<Unit> =
        runCatching {
            if (PolicyController.isRefused(domain, entityId, service = service)) {
                throw IllegalStateException("refused by policy")
            }
            if (!service.matches(Regex("[a-z0-9_]{1,64}"))) {
                throw IllegalArgumentException("refusing service name")
            }
            if (mcpUrl == null || bearer == null) {
                throw IllegalStateException("ContextForge not configured")
            }
            val args = JSONObject().apply {
                put("domain", domain)
                put("service", service)
                put("entity_id", entityId)
            }
            callTool("ha-call-service", args)
                ?: throw IllegalStateException("ha-call-service failed")
            Unit
        }.onFailure { e ->
            Log.w("HaClient", "callService failed: ${e.message}")
        }

    /** Call an MCP tool; returns its `result` object or null on failure. */
    private fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject? {
        if (!ensureInitialized()) return null
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val result = mcpCall("tools/call", params) ?: return null
        if (result.optBoolean("isError", false)) {
            // The tool itself failed (gateway-side). Surface the tool's
            // error text (capped) instead of feeding it to the parser.
            val err = toolText(result)?.take(200) ?: "unknown tool error"
            throw IllegalStateException("$name tool error: $err")
        }
        return result
    }

    /** First text item of a tools/call result, or null. */
    private fun toolText(result: JSONObject?): String? {
        val content = result?.optJSONArray("content") ?: return null
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") == "text") return item.optString("text")
        }
        return null
    }

    /**
     * Raw JSON array of all HA states (via ha-get-states), or failure
     * (gateway unreachable / no auth / unexpected payload). The gateway
     * returns the states as a JSON text payload; it is normalized to a
     * JSON array string here so callers keep the same contract.
     */
    suspend fun getStates(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (mcpUrl == null || bearer == null) {
                throw IllegalStateException("ContextForge not configured")
            }
            val text = toolText(callTool("ha-get-states"))
                ?: throw IllegalStateException("ha-get-states returned no content")
            normalizeStates(text)
        }.onFailure { e ->
            // Log the reason (never the bearer) so a device test can
            // tell a transport failure from a payload-shape failure.
            Log.w("HaClient", "getStates failed: ${e.message}")
        }
    }

    /**
     * Normalize an ha-get-states text payload to a JSON-array string.
     * The real gateway's shape is not fixed: the text may be a bare
     * array, a JSON string holding JSON (double-encoded), or an object
     * with the array nested at any depth. Parse with JSONTokener, unwrap
     * one level of string encoding, then search recursively for the
     * states array. Anything else fails loudly instead of handing the
     * HUD an unparseable payload.
     */
    private fun normalizeStates(text: String): String {
        val trimmed = text.trim()
        var value: Any? = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
        // Double-encoded: the tool text is itself a JSON string holding JSON.
        if (value is String) {
            value = runCatching { JSONTokener(value.trim()).nextValue() }.getOrNull()
        }
        val arr = findStatesArray(value)
            ?: throw IllegalStateException("ha-get-states returned a non-array payload")
        return arr.toString()
    }

    /**
     * Find the HA states array inside a parsed value, at any depth. A
     * states array is an array whose first element is an object with an
     * `entity_id` (an empty array counts — zero entities is valid). The
     * obvious keys are tried first, then every other key, then array
     * elements, so nesting and wrapper objects both work.
     */
    private fun findStatesArray(value: Any?): JSONArray? {
        when (value) {
            is JSONArray -> {
                if (value.length() == 0) return value
                val first = value.opt(0)
                if (first is JSONObject && first.has("entity_id")) return value
                for (i in 0 until value.length()) {
                    findStatesArray(value.opt(i))?.let { return it }
                }
                return null
            }
            is JSONObject -> {
                for (key in listOf("states", "result", "data")) {
                    if (value.has(key)) {
                        findStatesArray(value.opt(key))?.let { return it }
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in listOf("states", "result", "data")) continue
                    findStatesArray(value.opt(k))?.let { return it }
                }
                return null
            }
            else -> return null
        }
    }

    /**
     * FR-07 heartbeat — now a liveness probe, not a sensor write. The
     * ContextForge gateway is read-only: it exposes no tool that writes
     * state, so `sensor.jarvis_tablet = online` cannot be set through
     * it. This calls ha-get-config every 60 s instead, which proves the
     * gateway + bearer are alive. A write-capable tool (or a separate
     * write path) is an IT Admin decision; until then the tablet's
     * online state is not published to HA.
     */
    suspend fun postHeartbeat(wakeState: String, modelState: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                // Wake/model state is intentionally not sent: there is
                // no write tool to receive it. The probe result is the
                // heartbeat — reachable + authorized, or not.
                callTool("ha-get-config")
            }
        }
    }
}

/**
 * ContextForge base URL + scoped `jarvis-ha` bearer, Keystore-backed
 * (EncryptedSharedPreferences). Created by [SettingsActivity] on first
 * run; the bearer is issued by IT Admin for this tablet. The upstream
 * HA credential lives in the gateway DB, never on the tablet.
 */
object TokenStore {
    private const val FILE = "jarvis_secure_prefs"
    private const val KEY_URL = "cf_url"
    private const val KEY_TOKEN = "cf_token"
    private const val KEY_VOICE_RELAY = "voice_relay_url"
    private const val KEY_LLM_URL = "llm_base_url"
    private const val KEY_LLM_TOKEN = "llm_token"
    private const val KEY_LLM_MODEL = "llm_model"

    /** Default gateway endpoint (LAN, Wi-Fi) — the tablet is on VLAN 20. */
    const val DEFAULT_CF_URL =
        "http://192.168.20.217:4444/servers/a1345fe8da0d4971bd35621e294baed3/mcp"

    /**
     * Mini voice relay on the LAN. Used when the LLM bearer is missing
     * and when /llm/v1 returns 401 or 403. Settings can replace it.
     */
    const val DEFAULT_VOICE_RELAY_URL = "http://192.168.20.217:8793"

    /** Shown in Settings instead of the real bearer. Never stored. */
    const val SAVED_TOKEN_MASK = "saved"

    /**
     * ContextForge OpenAI-compatible base (LAN). The chat call is
     * `{base}/chat/completions`. This bearer is `cf-jarvis-tablet-llm`,
     * a different secret from the MCP bearer — that one is rejected
     * with 403 on this route.
     */
    const val DEFAULT_LLM_URL = "http://192.168.20.217:4444/llm/v1"
    const val DEFAULT_LLM_MODEL = "grok-4.7"

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun hasCredentials(context: Context): Boolean =
        getCfUrl(context) != null && getCfToken(context) != null

    fun getCfUrl(context: Context): String? =
        runCatching { prefs(context).getString(KEY_URL, null) }.getOrNull()

    fun getCfToken(context: Context): String? =
        runCatching { prefs(context).getString(KEY_TOKEN, null) }.getOrNull()

    /**
     * Mini voice relay base URL. A blank stored value uses
     * [DEFAULT_VOICE_RELAY_URL] so a turn with no LLM token still has
     * a relay to call.
     */
    fun getVoiceRelayUrl(context: Context): String =
        runCatching { prefs(context).getString(KEY_VOICE_RELAY, null) }
            .getOrNull()?.trim()?.trimEnd('/')?.ifEmpty { null }
            ?: DEFAULT_VOICE_RELAY_URL

    /**
     * Save the MCP URL. A blank token, or the mask [SAVED_TOKEN_MASK],
     * keeps the bearer already stored. The real token is never written
     * back from the Settings field unless the person typed a new one.
     */
    fun save(context: Context, cfUrl: String, cfToken: String) {
        val edit = prefs(context).edit()
            .putString(KEY_URL, cfUrl.trim().trimEnd('/'))
        val token = cfToken.trim()
        if (token.isNotEmpty() && !token.equals(SAVED_TOKEN_MASK, ignoreCase = true)) {
            edit.putString(KEY_TOKEN, token)
        }
        edit.apply()
    }

    fun saveVoiceRelayUrl(context: Context, url: String) {
        prefs(context).edit()
            .putString(KEY_VOICE_RELAY, url.trim().trimEnd('/'))
            .apply()
    }

    /** True when the direct `/llm/v1` bearer is saved. The URL and model have defaults. */
    fun hasLlmCredentials(context: Context): Boolean = getLlmToken(context) != null

    fun getLlmBaseUrl(context: Context): String =
        runCatching { prefs(context).getString(KEY_LLM_URL, null) }.getOrNull()
            ?.trim()?.trimEnd('/')?.ifEmpty { null }
            ?: DEFAULT_LLM_URL

    fun getLlmModel(context: Context): String =
        runCatching { prefs(context).getString(KEY_LLM_MODEL, null) }.getOrNull()
            ?.trim()?.ifEmpty { null }
            ?: DEFAULT_LLM_MODEL

    /** LLM bearer (`cf-jarvis-tablet-llm`), or null when voice should use the relay. */
    fun getLlmToken(context: Context): String? =
        runCatching { prefs(context).getString(KEY_LLM_TOKEN, null) }.getOrNull()
            ?.trim()?.ifEmpty { null }

    /**
     * Save the LLM base URL and model. A blank [token] keeps the saved
     * bearer (the Settings field is not pre-filled). [commit] so a
     * following read in the restarted activity sees the value.
     */
    fun saveLlm(context: Context, baseUrl: String, token: String, model: String) {
        val edit = prefs(context).edit()
            .putString(KEY_LLM_URL, baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_LLM_URL })
            .putString(KEY_LLM_MODEL, model.trim().ifEmpty { DEFAULT_LLM_MODEL })
        val llmToken = token.trim()
        if (llmToken.isNotEmpty() && !llmToken.equals(SAVED_TOKEN_MASK, ignoreCase = true)) {
            edit.putString(KEY_LLM_TOKEN, llmToken)
        }
        edit.commit()
    }

    /** Drop the LLM bearer. The next voice turn uses the Mini relay. */
    fun clearLlmToken(context: Context) {
        prefs(context).edit().remove(KEY_LLM_TOKEN).commit()
    }
}
