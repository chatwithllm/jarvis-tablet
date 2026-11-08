package net.npalakurla.jarvis.tablet

/**
 * Authorizes one turn to ask for chat and one cloud TTS.
 * The chat client itself may post at most [LeanChat.MAX_POSTS]
 * completions. This permit is not that HTTP count.
 *
 * [tryBegin] is the only way to become eligible. Ambient audio, a
 * rejected phrase, and a second callback do not get a token. [revoke]
 * (cancel, timeout, Talk tapped again, background, or a new activity)
 * makes that token unable to start a later call. A call already inside
 * the HTTP client is not killed from here.
 *
 * After our own playback, a spoken wake waits out a short speaker
 * ring. An explicit Talk tap is not held back. Room speech is not
 * blocked for the rest of the conversation.
 */
class TurnPermit(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Source { WAKE, TALK, DEBUG }

    private val lock = Any()
    private var generation = 0L
    private var openToken = 0L
    private var echoUntil = 0L
    private var chatClaimed = false
    private var ttsClaimed = false

    val isOpen: Boolean
        get() = synchronized(lock) { openToken != 0L }

    fun tryBegin(source: Source): Long? = synchronized(lock) {
        if (openToken != 0L) return null
        if (source == Source.WAKE && clock() < echoUntil) return null
        generation += 1L
        openToken = generation
        chatClaimed = false
        ttsClaimed = false
        openToken
    }

    fun revoke() {
        synchronized(lock) {
            generation += 1L
            openToken = 0L
            chatClaimed = true
            ttsClaimed = true
        }
    }

    fun finish(token: Long) {
        synchronized(lock) {
            if (token == 0L || token != openToken) return
            openToken = 0L
            echoUntil = clock() + ECHO_HOLD_MS
        }
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) {
        token != 0L && token == openToken
    }

    fun allowChat(token: Long): Boolean = synchronized(lock) {
        if (token == 0L || token != openToken || chatClaimed) return false
        chatClaimed = true
        true
    }

    fun allowTts(token: Long): Boolean = synchronized(lock) {
        if (token == 0L || token != openToken || ttsClaimed) return false
        ttsClaimed = true
        true
    }

    companion object {
        const val ECHO_HOLD_MS = WakeGate.RING_MS
    }
}
