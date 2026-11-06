package net.npalakurla.jarvis.tablet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
     * be a single JSON object or an SSE stream (`event:` / `data:` lines
     * — take the last `data:` payload, which carries the final result).
     */
    private fun parseRpcResult(text: String): JSONObject? {
        val jsonText = if (text.contains("data:")) {
            text.lineSequence()
                .filter { it.trimStart().startsWith("data:") }
                .map { it.substringAfter("data:").trim() }
                .filter { it.startsWith("{") }
                .lastOrNull() ?: return null
        } else {
            text.trim()
        }
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (obj.has("error")) return null
        return obj.optJSONObject("result")
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

    /** Call an MCP tool; returns its `result` object or null on failure. */
    private fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject? {
        if (!ensureInitialized()) return null
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        return mcpCall("tools/call", params)
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
        }
    }

    /**
     * Normalize an ha-get-states text payload to a JSON-array string:
     * a bare array passes through; an object is searched for an array
     * under `states`, `result`, or `data`; anything else fails loudly
     * instead of handing the HUD an unparseable payload.
     */
    private fun normalizeStates(text: String): String {
        val trimmed = text.trim()
        runCatching { JSONArray(trimmed) }.onSuccess { return trimmed }
        runCatching {
            val obj = JSONObject(trimmed)
            for (key in listOf("states", "result", "data")) {
                obj.optJSONArray(key)?.let { return it.toString() }
            }
        }
        throw IllegalStateException("ha-get-states returned a non-array payload")
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

    /** Default gateway endpoint (LAN, Wi-Fi) — the tablet is on VLAN 20. */
    const val DEFAULT_CF_URL =
        "http://192.168.20.217:4444/servers/a1345fe8da0d4971bd35621e294baed3/mcp"

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

    fun save(context: Context, cfUrl: String, cfToken: String) {
        prefs(context).edit()
            .putString(KEY_URL, cfUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, cfToken.trim())
            .apply()
    }
}
