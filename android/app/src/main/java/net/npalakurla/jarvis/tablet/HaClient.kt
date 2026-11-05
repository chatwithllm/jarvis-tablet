package net.npalakurla.jarvis.tablet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HA REST client (native side only). The token never enters the WebView,
 * the bridge, logcat, or any bundled asset.
 *
 * Phase 1 uses: liveness/heartbeat (POST /api/states/sensor.jarvis_tablet)
 * and read-only state fetches for the HUD. Service calls and the
 * conversation pipeline are Phase 2, behind [PolicyController].
 */
class HaClient(private val context: Context) {

    /** HA answered 401 — token revoked/expired (FR-54). */
    class Unauthorized : Exception("Home Assistant token rejected (401)")

    private val baseUrl: String? get() = TokenStore.getHaUrl(context)
    private val token: String? get() = TokenStore.getToken(context)

    private fun connection(path: String, method: String): HttpURLConnection? {
        val url = baseUrl ?: return null
        val token = token ?: return null
        val conn = URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn
    }

    /** Raw JSON array of all states, or failure (HA unreachable / no auth). */
    suspend fun getStates(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = connection("/api/states", "GET")
                ?: throw IllegalStateException("HA not configured")
            if (conn.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                conn.disconnect()
                throw Unauthorized()
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }
    }

    /** FR-07 heartbeat: sensor.jarvis_tablet = online + wake/model state. */
    suspend fun postHeartbeat(wakeState: String, modelState: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = connection("/api/states/sensor.jarvis_tablet", "POST") ?: return@runCatching
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("state", "online")
                    put(
                        "attributes",
                        JSONObject().apply {
                            put("wake_service", wakeState)
                            put("wake_model", modelState)
                            put("app_version", BuildConfig.VERSION_NAME)
                            put("friendly_name", "Jarvis Tablet")
                        },
                    )
                }
                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
            }
        }
    }
}

/**
 * HA URL + long-lived token, Keystore-backed (EncryptedSharedPreferences).
 * Created by [SettingsActivity] on first run; revocable from HA
 * (dedicated non-admin user `jarvis-tablet`, Phase 0 item 5).
 */
object TokenStore {
    private const val FILE = "jarvis_secure_prefs"
    private const val KEY_URL = "ha_url"
    private const val KEY_TOKEN = "ha_token"

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
        getHaUrl(context) != null && getToken(context) != null

    fun getHaUrl(context: Context): String? =
        runCatching { prefs(context).getString(KEY_URL, null) }.getOrNull()

    fun getToken(context: Context): String? =
        runCatching { prefs(context).getString(KEY_TOKEN, null) }.getOrNull()

    fun save(context: Context, haUrl: String, token: String) {
        prefs(context).edit()
            .putString(KEY_URL, haUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }
}
