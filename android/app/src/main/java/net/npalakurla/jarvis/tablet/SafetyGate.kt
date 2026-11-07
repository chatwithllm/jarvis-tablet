package net.npalakurla.jarvis.tablet

import android.util.Log

/**
 * Client-side hard stop before any Home Assistant call (relay or MCP).
 * Locks, garage doors, and the alarm are never voice-controllable.
 * A match logs `JarvisVoice: refused=safety` and the caller must not
 * send the audio, the transcript, or the tool call onward.
 */
object SafetyGate {

    private val openGarage = Regex("\\bopen\\s+(the\\s+)?garage\\b")
    private val lockEntity = Regex("\\block\\.[a-z0-9_]+")
    private val alarmEntity = Regex("\\balarm_control_panel\\.[a-z0-9_]+")
    private val garageCover = Regex("\\bcover\\.garage[a-z0-9_]*")

    fun refuses(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase()
        return t.contains("unlock") ||
            t.contains("disarm") ||
            openGarage.containsMatchIn(t) ||
            lockEntity.containsMatchIn(t) ||
            alarmEntity.containsMatchIn(t) ||
            garageCover.containsMatchIn(t) ||
            t.contains("alarm_control_panel")
    }

    fun refusesTool(domain: String, entityId: String, service: String?, summary: String?): Boolean {
        val d = domain.lowercase()
        val id = entityId.lowercase()
        val svc = service?.lowercase().orEmpty()
        if (d == "lock" || id.startsWith("lock.")) return true
        if (d == "alarm_control_panel" || id.startsWith("alarm_control_panel.")) return true
        if (id.startsWith("cover.garage")) return true
        if (d == "cover" && id.contains("garage")) return true
        if (svc == "unlock" || svc == "disarm" || svc.contains("unlock") || svc.contains("disarm")) {
            return true
        }
        if (PolicyController.isRefused(d, id, service = svc.ifEmpty { null })) return true
        if (refuses(summary)) return true
        if (refuses(id) || refuses(svc)) return true
        return false
    }

    /** Log the stable line and return true when [text] must not leave the tablet. */
    fun block(text: String?): Boolean {
        if (!refuses(text)) return false
        Log.i("JarvisVoice", "refused=safety")
        return true
    }
}
