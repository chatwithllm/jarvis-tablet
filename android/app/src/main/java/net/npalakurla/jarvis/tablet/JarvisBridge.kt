package net.npalakurla.jarvis.tablet

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Native side of the bridge contract (PRD v2.2 bridge section, read
 * verbatim from the live PRD page 2026-11-05; repo copy in
 * docs/bridge-contract.md).
 *
 * WebView -> Native (PRD v2.2, exactly 6): talk_pressed, confirm_yes,
 * confirm_no, cancel, ui_ready, data_request.
 * Native -> WebView (PRD v2.2, exactly 13): wake_detected,
 * listening_level, transcript_partial/final, thinking, tts_started/
 * finished, confirm_requested/resolved, refused, error, status,
 * navigate.
 *
 * Data: there is NO data_response event in the PRD. Native answers a
 * data_request by calling window.Jarvis.onData({scope, payload}) directly
 * ([pushData]) — a native-initiated JS call, not a bridge event.
 *
 * No credential ever crosses the bridge: the HA token lives in
 * [TokenStore] (Keystore-backed) and is only used by [HaClient].
 */
class JarvisBridge(
    private val host: Host,
    private val webView: WebView,
) {

    /** Phase 2: invoked when a wake word fires (before the HUD event). MainActivity starts the turn. */
    var onWakeDetected: (() -> Unit)? = null

    interface Host {
        fun onTalkPressed()
        fun onConfirmYes(id: String)
        fun onConfirmNo(id: String)
        /** Cancel the current turn, or the pending confirm when id is given. */
        fun onCancel(id: String?)
        fun onUiReady()
        /** Read-only data fetch. Scope per the PRD; from/to are ISO timestamps. */
        fun onDataRequest(scope: String, from: String?, to: String?)
    }

    /** Single entry point the HUD calls: JarvisBridge.postMessage(json). */
    @JavascriptInterface
    fun postMessage(json: String) {
        runCatching {
            val msg = JSONObject(json)
            when (msg.optString("type")) {
                "talk_pressed" -> host.onTalkPressed()
                "confirm_yes" -> host.onConfirmYes(msg.optString("id"))
                "confirm_no" -> host.onConfirmNo(msg.optString("id"))
                "cancel" -> host.onCancel(msg.optString("id").ifEmpty { null })
                "ui_ready" -> host.onUiReady()
                "data_request" -> host.onDataRequest(
                    msg.optString("scope"),
                    msg.optString("from").ifEmpty { null },
                    msg.optString("to").ifEmpty { null },
                )
                else -> {
                    // Unknown event: ignore rather than crash the shell.
                }
            }
        }
    }

    /** Native -> WebView: one event object, dispatched to window.Jarvis. */
    fun sendEvent(type: String, payload: JSONObject) {
        if (type == "wake_detected") onWakeDetected?.invoke()
        val envelope = JSONObject().apply {
            put("type", type)
            put("ts", System.currentTimeMillis())
            put("payload", payload)
        }
        val script = "window.Jarvis && Jarvis.onNativeEvent($envelope)"
        webView.post { webView.evaluateJavascript(script, null) }
    }

    /**
     * Answer a data_request. NOT a bridge event (the PRD has no
     * data_response): native invokes window.Jarvis.onData({scope, payload})
     * directly. Payload: {ok, states?, events?, error?}.
     */
    fun pushData(scope: String, payload: JSONObject) {
        val call = JSONObject().apply {
            put("scope", scope)
            put("payload", payload)
        }
        val script = "window.Jarvis && Jarvis.onData($call)"
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
