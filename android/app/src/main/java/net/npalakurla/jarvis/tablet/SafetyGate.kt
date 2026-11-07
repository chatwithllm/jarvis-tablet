package net.npalakurla.jarvis.tablet

import android.util.Log

/**
 * Client-side hard stop before any Home Assistant call (relay or MCP).
 * Locks, garage doors, and the alarm are never voice-controllable.
 * A match logs `JarvisVoice: refused=safety` and the caller must not
 * send the audio, the transcript, or the tool call onward.
 *
 * The transcript check is fuzzy on purpose. Vosk often splits or
 * mishears "unlock" / "disarm" ("un lock", "and lock", "the arm").
 * A false refusal is acceptable. A missed refusal is not.
 */
object SafetyGate {

    private val banned = setOf(
        "lock", "locks", "locked", "locking",
        "unlock", "unlocks", "unlocked", "unlocking",
        "deadbolt", "deadbolts",
        "alarm", "alarms", "alarmed",
        "arm", "arms", "armed", "arming",
        "disarm", "disarms", "disarmed", "disarming",
        "garage", "garages",
    )

    /** Whole tokens that merely end in "lock" and are not a lock. */
    private val lockFalseFriends = setOf(
        "clock", "clocks", "oclock", "block", "blocks",
    )

    private val openWords = setOf("open", "opens", "opened", "opening")

    /** Lowercase, strip punctuation, collapse spaces, join split safety words. */
    internal fun normalize(raw: String): String {
        val tokens = raw.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex(" +"))
            .filter { it.isNotEmpty() }
            .toMutableList()
        var i = 0
        while (i < tokens.size - 1) {
            val joined = tokens[i] + tokens[i + 1]
            if (joined in banned) {
                tokens[i] = joined
                tokens.removeAt(i + 1)
            } else {
                i++
            }
        }
        return tokens.joinToString(" ")
    }

    fun refuses(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        val tokens = normalized.split(" ")
        if (tokens.any { mentionsSafety(it) }) return true
        if (normalized.contains("security system")) return true
        val door = tokens.any { it == "door" || it == "doors" || it.startsWith("door") }
        val openOrUnlock = tokens.any { it in openWords || it.startsWith("open") || it.contains("unlock") }
        return door && openOrUnlock
    }

    private fun mentionsSafety(token: String): Boolean {
        if (token in banned) return true
        if (token in lockFalseFriends) return false
        if (token.startsWith("unlock") || token.startsWith("lock")) return true
        if (token.endsWith("lock") || token.endsWith("locks")) return true
        if (token.contains("disarm") || token.contains("deadbolt")) return true
        if (token.contains("garage") || token.contains("alarm")) return true
        if (token == "arm" || token == "arms" || token == "armed" || token.startsWith("arming")) return true
        return false
    }

    fun refusesTool(domain: String, entityId: String, service: String?, summary: String?): Boolean {
        val d = domain.lowercase()
        val id = entityId.lowercase()
        val svc = service?.lowercase().orEmpty()
        if (d == "lock" || id.startsWith("lock.")) return true
        if (d == "alarm_control_panel" || id.startsWith("alarm_control_panel.")) return true
        if (id.startsWith("cover.garage")) return true
        if (d == "cover" && id.contains("garage")) return true
        if (svc.contains("unlock") || svc.contains("disarm") || svc == "lock" || svc.contains("alarm")) {
            return true
        }
        if (PolicyController.isRefused(d, id, service = svc.ifEmpty { null })) return true
        if (refuses(summary) || refuses(id) || refuses(svc) || refuses(d)) return true
        return false
    }

    /**
     * Debug builds log `JarvisVoice: heard="..."` for the recognized
     * transcript. A refusal also logs `JarvisVoice: refused=safety`.
     * Returns true when [text] must not leave the tablet.
     */
    fun block(text: String?): Boolean {
        if (BuildConfig.DEBUG) {
            val shown = text.orEmpty().replace("\"", "'").replace("\n", " ")
            Log.i("JarvisVoice", "heard=\"$shown\"")
        }
        if (!refuses(text)) return false
        Log.i("JarvisVoice", "refused=safety")
        return true
    }
}
