package net.npalakurla.jarvis.tablet

import android.util.Log

/**
 * Client-side hard stop before any Home Assistant call (relay, /llm/v1, or MCP).
 * A match logs `JarvisVoice: refused=safety` and the caller must not send
 * the audio, the transcript, or the tool call onward.
 *
 * The transcript gate is noun-based. Vosk mishears the verb ("unlock" →
 * "block", "lock the back door" → "the back door") far more often than
 * the noun, so a protected target with an action, a near-miss verb, or
 * no verb at all is refused. A clear status question such as
 * "is the front door locked?" may still go to the model. False refusals
 * are acceptable.
 */
object SafetyGate {

    /** Words we rejoin when Vosk splits them ("dead bolt", "un lock"). */
    private val joinable = setOf(
        "lock", "locks", "locked", "locking",
        "unlock", "unlocks", "unlocked", "unlocking",
        "deadbolt", "deadbolts",
        "alarm", "alarms",
        "disarm", "disarms", "disarmed", "disarming",
        "garage", "garages",
        "onlock", "andlock", "unblock",
    )

    private val doorQualifiers = setOf("front", "back", "side", "garage", "main")

    /**
     * Command verbs and Vosk near-misses. Present on a status question,
     * these keep the question from being treated as read-only.
     * "locked" is intentionally absent so "is the front door locked?"
     * can pass.
     */
    private val commandOrNearMiss = setOf(
        "open", "opens", "opening",
        "close", "closes", "closing",
        "shut", "shuts", "shutting",
        "lock", "locks", "locking",
        "unlock", "unlocks", "unlocking",
        "arm", "arming",
        "disarm", "disarms", "disarming",
        "turn", "turns", "turning",
        "set", "sets", "setting",
        "switch", "activate", "deactivate",
        "enable", "disable", "trigger",
        "block", "blocks", "blocking",
        "clock", "clocks",
        "unblock", "unblocks",
        "bolt", "unbolt",
        "onlock", "andlock",
    )

    private val questionHeads = setOf("is", "are", "was", "were", "whats", "hows")

    /** Lowercase, strip punctuation, collapse spaces, join split safety words. */
    internal fun normalize(raw: String): String {
        val tokens = raw.lowercase()
            .replace("'", "")
            .replace("\u2019", "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(Regex(" +"))
            .filter { it.isNotEmpty() }
            .toMutableList()
        var i = 0
        while (i < tokens.size - 1) {
            val joined = tokens[i] + tokens[i + 1]
            if (joined in joinable) {
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
        if (!mentionsProtected(tokens, normalized)) return false
        // Protected noun with a command, a near-miss, or no verb: refuse.
        // The only pass is a leading status question with no command.
        if (isReadOnlyQuestion(tokens)) return false
        return true
    }

    private fun mentionsProtected(tokens: List<String>, normalized: String): Boolean {
        if (normalized.contains("security system")) return true
        if (tokens.any { it == "garage" || it == "garages" || it.contains("garage") }) return true
        if (tokens.any { it == "alarm" || it == "alarms" || it.contains("alarm") }) return true
        if (tokens.any { it == "deadbolt" || it == "deadbolts" || it.contains("deadbolt") }) return true
        if (tokens.any { isLockWord(it) }) return true
        // Bare "arm" / "disarm" is how Vosk renders the alarm, even with the noun dropped.
        if (tokens.any { it == "arm" || it == "arms" || it == "armed" || it.startsWith("arming") || it.contains("disarm") }) {
            return true
        }
        val door = tokens.any { it == "door" || it == "doors" }
        val qualified = tokens.any { it in doorQualifiers }
        return door && qualified
    }

    private fun isLockWord(token: String): Boolean {
        if (token == "lock" || token == "locks" || token == "locked" || token == "locking") return true
        if (token.startsWith("unlock") || token.startsWith("unblock")) return true
        if (token == "onlock" || token == "andlock") return true
        return false
    }

    private fun isReadOnlyQuestion(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val head = tokens[0]
        val question = head in questionHeads ||
            (tokens.size >= 2 && head == "what" && tokens[1] == "is") ||
            (tokens.size >= 2 && head == "how" && (tokens[1] == "is" || tokens[1] == "are"))
        if (!question) return false
        if (tokens.any { it in commandOrNearMiss }) return false
        for (i in 0 until tokens.size - 1) {
            val pair = "${tokens[i]} ${tokens[i + 1]}"
            if (pair == "on lock" || pair == "and lock" || pair == "un lock" || pair == "dis arm") {
                return false
            }
        }
        return true
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
