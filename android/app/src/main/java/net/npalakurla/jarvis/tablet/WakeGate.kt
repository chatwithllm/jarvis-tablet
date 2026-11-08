package net.npalakurla.jarvis.tablet

/**
 * A payable wake is a fresh "hey tablet", not a grammar assignment.
 *
 * Closed-grammar Vosk can report that phrase at confidence 1.0 for audio
 * that was not those words. Word timing has to look like two short words.
 * Another alternative, including [unk], rejects the hit. Confidence alone
 * does not pass. This does not remove every environmental false wake.
 */
internal object WakeGate {
    /** Speaker ring after our own playback. Not a conversation cooldown. */
    const val RING_MS = 400L

    data class Word(val word: String, val startSec: Double, val endSec: Double, val conf: Float)

    data class Hypothesis(
        val text: String,
        val words: List<Word>,
        val alternatives: List<String> = emptyList(),
    )

    fun genuine(hypothesis: Hypothesis, threshold: Float): Boolean {
        val text = hypothesis.text.trim()
        if (!WakeAccept.accept(text, hypothesis.words.map { it.conf }, threshold)) return false
        val others = hypothesis.alternatives.map { normalize(it) }.filter { it.isNotEmpty() }
        if (others.any { it == "unk" || it != WakeAccept.PHRASE }) return false
        if (hypothesis.words.size != 2) return false
        val hey = hypothesis.words[0]
        val tablet = hypothesis.words[1]
        if (hey.word != "hey" || tablet.word != "tablet") return false
        val heyDur = hey.endSec - hey.startSec
        val tabletDur = tablet.endSec - tablet.startSec
        val gap = tablet.startSec - hey.endSec
        val span = tablet.endSec - hey.startSec
        if (heyDur !in 0.08..0.70) return false
        if (tabletDur !in 0.12..0.90) return false
        if (gap !in -0.05..0.45) return false
        if (span !in 0.35..1.60) return false
        return true
    }

    fun blockedByOwnAudio(nowMs: Long, ownAudioUntilMs: Long): Boolean = nowMs < ownAudioUntilMs

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
