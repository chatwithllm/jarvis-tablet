package net.npalakurla.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mocked activation gate. Counts stay at zero until a real wake phrase
 * or an explicit Talk begin. No model and no speech client is created.
 *
 * Passing these cases does not mean the recognizer never false-wakes.
 */
class ActivationGateTest {

    private class Upstream {
        var chat = 0
        var tts = 0
        var stt = 0
        var models = 0
    }

    @Test
    fun aliases_silence_and_partials_do_not_wake() {
        val rejected = listOf(
            "",
            " ",
            "[unk]",
            "noise",
            "hey",
            "tablet",
            "hey tab",
            "hey tablet please",
            "please hey tablet",
            "okay computer",
            "ok computer",
            "hey computer",
            "hey jarvis",
            "hi jarvis",
            "okay jarvis",
            "hey jack",
            "hi tablet",
            "okay tablet",
        )
        rejected.forEach { phrase ->
            assertFalse(phrase, WakeAccept.accept(phrase, listOf(0.99f, 0.99f), 0.85f))
        }
    }

    @Test
    fun hey_tablet_needs_both_words_above_the_floor() {
        assertTrue(WakeAccept.accept("Hey Tablet", listOf(0.9f, 0.86f), 0.85f))
        assertFalse(WakeAccept.accept("hey tablet", listOf(0.99f, 0.2f), 0.85f))
        assertFalse(WakeAccept.accept("hey tablet", listOf(0.99f), 0.85f))
    }

    @Test
    fun nothing_is_spent_before_activation() {
        val upstream = Upstream()
        val permit = TurnPermit(clock = { 0L })
        assertFalse(permit.allowChat(1L))
        assertFalse(permit.allowTts(1L))
        assertEquals(0, upstream.chat + upstream.tts + upstream.stt + upstream.models)
        assertNull(spend(permit, TurnPermit.Source.WAKE, "okay computer", upstream))
        assertEquals(0, upstream.chat)
        assertEquals(0, upstream.tts)
        assertEquals(0, upstream.stt)
        assertEquals(0, upstream.models)
    }

    @Test
    fun talk_spends_one_turn_and_a_second_chat_is_refused() {
        val upstream = Upstream()
        val permit = TurnPermit(clock = { 0L })
        val token = spend(permit, TurnPermit.Source.TALK, "okay computer", upstream)
        assertTrue(token != null)
        assertEquals(1, upstream.chat)
        assertEquals(1, upstream.tts)
        assertEquals(0, upstream.stt)
        assertFalse(permit.allowChat(token!!))
        assertEquals(1, upstream.chat)
    }

    @Test
    fun one_wake_one_turn_and_duplicates_do_not_spend_again() {
        val upstream = Upstream()
        val permit = TurnPermit(clock = { 0L })
        val first = permit.tryBegin(TurnPermit.Source.WAKE)
        assertTrue(first != null)
        assertNull(permit.tryBegin(TurnPermit.Source.WAKE))
        assertNull(permit.tryBegin(TurnPermit.Source.TALK))
        if (permit.allowChat(first!!)) upstream.chat++
        if (permit.allowTts(first)) upstream.tts++
        assertFalse(permit.allowChat(first))
        permit.finish(first)
        assertEquals(1, upstream.chat)
        assertEquals(1, upstream.tts)
        assertEquals(0, upstream.stt + upstream.models)
    }

    @Test
    fun echo_blocks_wake_but_talk_still_starts_one_turn() {
        var now = 1_000L
        val upstream = Upstream()
        val permit = TurnPermit(clock = { now })
        spend(permit, TurnPermit.Source.WAKE, "hey tablet", upstream)
        now = 1_100L
        assertNull(spend(permit, TurnPermit.Source.WAKE, "hey tablet", upstream))
        assertEquals(1, upstream.chat)
        val talk = spend(permit, TurnPermit.Source.TALK, "hey tablet", upstream)
        assertTrue(talk != null)
        assertEquals(2, upstream.chat)
        now = 1_100L + TurnPermit.ECHO_HOLD_MS
        val again = spend(permit, TurnPermit.Source.WAKE, "hey tablet", upstream)
        assertTrue(again != null)
        assertEquals(3, upstream.chat)
    }

    @Test
    fun revoke_timeout_and_recreation_drop_the_old_token() {
        val upstream = Upstream()
        val permit = TurnPermit(clock = { 0L })
        val token = permit.tryBegin(TurnPermit.Source.TALK)
        assertTrue(token != null)
        permit.revoke()
        assertFalse(permit.allowChat(token!!))
        assertFalse(permit.allowTts(token))
        assertFalse(permit.isCurrent(token))
        assertEquals(0, upstream.chat + upstream.tts)
        val next = spend(permit, TurnPermit.Source.TALK, "", upstream)
        assertTrue(next != null)
        assertFalse(permit.allowChat(token))
        assertEquals(1, upstream.chat)
    }

    @Test
    fun late_callback_after_finish_cannot_spend() {
        val upstream = Upstream()
        val permit = TurnPermit(clock = { 0L })
        val token = permit.tryBegin(TurnPermit.Source.WAKE)
        permit.finish(token!!)
        if (permit.allowChat(token)) upstream.chat++
        if (permit.allowTts(token)) upstream.tts++
        assertEquals(0, upstream.chat)
        assertEquals(0, upstream.tts)
    }

    @Test
    fun production_sources_do_not_call_cloud_stt_or_auto_relay() {
        val root = File("src/main/java/net/npalakurla/jarvis/tablet")
        assertTrue(root.isDirectory)
        val speech = File(root, "DeviceSpeech.kt").readText()
        val turn = File(root, "VoiceTurn.kt").readText()
        val cloud = File(root, "CloudVoice.kt").readText()
        assertFalse(speech.contains("CloudVoice.transcribeWav"))
        assertFalse(turn.contains("CloudVoice.transcribeWav"))
        assertFalse(turn.contains("relayTurn(announce = true"))
        assertFalse(cloud.contains("\"stt\""))
        assertFalse(cloud.contains("grok-voice-transcribe"))
    }

    private fun spend(
        permit: TurnPermit,
        source: TurnPermit.Source,
        phrase: String,
        upstream: Upstream,
    ): Long? {
        if (source == TurnPermit.Source.WAKE && !WakeAccept.isExactPhrase(phrase)) return null
        val token = permit.tryBegin(source) ?: return null
        if (permit.allowChat(token)) upstream.chat++
        if (permit.allowTts(token)) upstream.tts++
        permit.finish(token)
        return token
    }
}
