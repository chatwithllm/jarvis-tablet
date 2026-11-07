package net.npalakurla.jarvis.tablet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.provider.Settings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase 1 — HUD shell (PRD v2.2).
 *
 * Full-screen immersive WebView over the bundled Jarvis HUD
 * (assets/web/index.html). Owns: immersive mode (FR-01), screen-on
 * (FR-03), back-reloads-HUD (FR-04), render-process recovery (FR-05),
 * battery exemption (FR-06), 60 s heartbeat to HA (FR-07), and the
 * native side of the bridge contract (docs/bridge-contract.md).
 *
 * The WebView never holds a token or a key. All HA traffic goes through
 * [HaClient] on the native side; the bridge carries UI events only.
 * Voice (tap-to-talk, wake word, confirm cards) is Phase 2 — the bridge
 * events for it exist here so the HUD can already render them, but no
 * voice pipeline runs until Phase 2.
 */
class MainActivity : AppCompatActivity(), JarvisBridge.Host {

    private lateinit var webView: WebView
    private lateinit var bridge: JarvisBridge
    private var heartbeatJob: Job? = null
    private var wakeEnabled = true
    private lateinit var voiceTurn: VoiceTurn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FR-01: immersive sticky, no system bars. FR-03: screen stays on.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildWebView()
        voiceTurn = VoiceTurn(
            this,
            bridge,
            RoutingVoiceTransport(this),
            isQuietHours = { isQuietHours() },
        )
        // A wake word starts a turn (the HUD event goes out too).
        bridge.onWakeDetected = { voiceTurn.start() }
        VoiceDebug.handler = { path -> voiceTurn.startFromWav(path) }
        maybeRequestBatteryExemption()
        maybeRequestMicPermission()
        startWakeServiceIfConfigured()
        startHeartbeat()
    }

    /** (Re)creates the WebView — also the FR-05 crash-recovery path. */
    private fun buildWebView() {
        webView = WebView(this)
        setContentView(webView)
        bridge = JarvisBridge(this, webView)
        WakeService.listener = bridge
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pushStatus()
            }

            // FR-05: render-process crash recreates the WebView and
            // reloads the HUD without restarting the wake service.
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                buildWebView()
                return true
            }
        }
        webView.addJavascriptInterface(bridge, "JarvisBridge")
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    // FR-04: Back reloads the HUD instead of exiting.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onResume() {
        super.onResume()
        WakeService.listener = bridge
        pushStatus()
    }

    override fun onPause() {
        super.onPause()
        if (WakeService.listener === bridge) WakeService.listener = null
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        VoiceDebug.handler = null
        if (::voiceTurn.isInitialized) voiceTurn.close()
        super.onDestroy()
    }

    // -- first-run setup -------------------------------------------------

    private fun startWakeServiceIfConfigured() {
        if (!TokenStore.hasCredentials(this)) {
            // No ContextForge URL/bearer yet: collect them natively first. The HUD
            // cannot do anything useful without a hub to talk to.
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        WakeService.start(this)
    }

    /** FR-54: rejected bearer -> re-auth prompt, voice disabled, no loop. */
    fun promptReauth() {
        bridge.sendEvent(
            "error",
            JSONObject()
                .put("code", "auth_required")
                .put("message", "ContextForge sign-in expired. Voice is off until you re-enter the bearer token."),
        )
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // -- Phase 2: microphone permission (issue #17) ----------------------
    //
    // RECORD_AUDIO is a dangerous permission: declaring it in the
    // manifest is not enough on Android 11 — it must be granted at
    // runtime. Phase 1 never asked, so the tablet reported
    // RECORD_AUDIO denied and the wake service could not open the mic.
    // Ask once on launch; if the user denies, the HUD shows the
    // mic_permission error (the WakeService sends it too) and
    // tap-to-talk stays off until the permission is granted in Settings.

    private fun maybeRequestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MIC_PERMISSION,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC_PERMISSION) return
        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // Mic granted after launch: the wake service must (re)start now
            // so its AudioRecord opens. If the service already started
            // before the grant, its startMicLoop() returned early (no
            // permission) and the service is running with no recorder —
            // the old `!isRunning` guard skipped the restart, so the mic
            // never opened until a force-stop + relaunch (issue #18).
            // Restart it so onCreate() -> startMicLoop() runs again with
            // the permission granted and opens the AudioRecord.
            if (TokenStore.hasCredentials(this)) {
                if (WakeService.isRunning) {
                    WakeService.stop(this)
                }
                WakeService.start(this)
            }
            pushStatus()
        } else {
            bridge.sendEvent(
                "error",
                JSONObject()
                    .put("code", "mic_permission")
                    .put("message", "Microphone permission denied — voice is off. Grant it in Settings to enable Hey Jack and tap-to-talk."),
            )
        }
    }

    // -- FR-06: battery-optimization exemption, asked once ---------------

    private fun maybeRequestBatteryExemption() {
        val prefs = getSharedPreferences("jarvis_setup", MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_exemption_asked", true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    // -- FR-07: heartbeat to HA every 60 s -------------------------------

    private fun startHeartbeat() {
        heartbeatJob = lifecycleScope.launch {
            while (isActive) {
                HaClient(this@MainActivity).postHeartbeat(
                    wakeState = if (WakeService.isRunning) "running" else "stopped",
                    modelState = VoskWakeEngine.state,
                )
                delay(60_000)
            }
        }
    }

    // -- native -> WebView ------------------------------------------------

    private fun pushStatus() {
        val payload = JSONObject().apply {
            put("ha", TokenStore.hasCredentials(this@MainActivity))
            put("wake", WakeService.isRunning && wakeEnabled)
            put("quiet", isQuietHours())
            put("mic", VoskWakeEngine.state)
        }
        bridge.sendEvent("status", payload)
    }

    /** Quiet hours 21:00-07:00 America/New_York (PRD locked, FR-26). */
    fun isQuietHours(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("America/New_York"))): Boolean {
        val h = now.hour
        return h >= 21 || h < 7
    }

    // -- JarvisBridge.Host (WebView -> native) ---------------------------

    override fun onTalkPressed() {
        bridge.sendEvent("thinking", JSONObject().put("step", "listening"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("JarvisVoice", "result=mic_permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MIC_PERMISSION,
            )
            bridge.sendEvent(
                "error",
                JSONObject()
                    .put("code", "mic_permission")
                    .put("message", "Microphone permission missing — voice is off."),
            )
            return
        }
        voiceTurn.start()
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onCancel(id: String?) {
        if (id != null) {
            // Cancel a pending confirm: resolve it as declined (FR-24/FR-51).
            PolicyController.resolve(id, confirmed = false, sourceDevice = DEVICE_ID)
            voiceTurn.onConfirmResolved(id, false)
            bridge.sendEvent(
                "confirm_resolved",
                JSONObject().put("id", id).put("outcome", "declined"),
            )
        } else {
            // Cancel the current turn (orb tapped while listening).
            voiceTurn.cancel()
        }
    }

    override fun onConfirmYes(id: String) {
        // Phase 2: PolicyController.resolve enforces source binding
        // (FR-55) and the 10 s expiry (FR-24/FR-51). The waiting turn
        // learns the outcome and executes the action if confirmed.
        val ok = PolicyController.resolve(id, confirmed = true, sourceDevice = DEVICE_ID)
        voiceTurn.onConfirmResolved(id, ok)
        bridge.sendEvent(
            "confirm_resolved",
            JSONObject().put("id", id)
                .put("outcome", if (ok) "confirmed" else "expired"),
        )
    }

    override fun onConfirmNo(id: String) {
        PolicyController.resolve(id, confirmed = false, sourceDevice = DEVICE_ID)
        voiceTurn.onConfirmResolved(id, false)
        bridge.sendEvent(
            "confirm_resolved",
            JSONObject().put("id", id).put("outcome", "declined"),
        )
    }

    override fun onUiReady() {
        // HUD loaded: push the current status so it can render.
        pushStatus()
    }

    override fun onDataRequest(scope: String, from: String?, to: String?) {
        // from/to bound the overview calendar window; other scopes ignore them.
        // There is NO data_response event in the PRD: the answer goes back
        // via JarvisBridge.pushData (a direct window.Jarvis.onData call).
        lifecycleScope.launch {
            val known = setOf("overview", "ha", "pve", "updates", "cams", "bots", "weather")
            if (scope == "grocery") {
                // Grocery proxy is Phase 2/3 (mini/); not served in Phase 1.
                bridge.pushData(
                    scope,
                    JSONObject().put("ok", false)
                        .put("error", "grocery arrives in Phase 3"),
                )
                return@launch
            }
            if (scope !in known) {
                bridge.pushData(
                    scope,
                    JSONObject().put("ok", false)
                        .put("error", "unknown data scope: $scope"),
                )
                return@launch
            }
            val client = HaClient(this@MainActivity)
            val states = client.getStates()
            if (states.isFailure && states.exceptionOrNull() is HaClient.Unauthorized) {
                promptReauth()
                return@launch
            }
            val payload = JSONObject().apply {
                put("ok", states.isSuccess)
                put("states", states.getOrNull()?.let { JSONArray(it) } ?: JSONArray())
                if (scope == "overview") {
                    // Pickup banner: HA calendar entities carry the
                    // Matrix kid-pickup events (summary/start/end).
                    val events = JSONArray()
                    states.getOrNull()?.let { raw ->
                        val arr = JSONArray(raw)
                        for (i in 0 until arr.length()) {
                            val entity = arr.getJSONObject(i)
                            if (entity.optString("entity_id").startsWith("calendar.")) {
                                events.put(entity)
                            }
                        }
                    }
                    put("events", events)
                }
            }
            bridge.pushData(scope, payload)
        }
    }

    companion object {
        /** This device's id for FR-55 source binding of confirmations. */
        const val DEVICE_ID = "lenovo-tab-p11"

        /** Request code for the runtime RECORD_AUDIO permission (Phase 2). */
        const val REQUEST_MIC_PERMISSION = 1001
    }
}
