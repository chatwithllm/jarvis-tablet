package net.npalakurla.jarvis.tablet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Wake and intent gates. Counts are chat posts that would be allowed
 * after the gate. No network and no audio.
 */
class WakeGuardTest {

    @Test
    fun a_short_hey_tablet_passes_and_a_stretched_or_unk_result_does_not() {
        assertTrue(WakeGate.genuine(spokenHey(), 0.85f))
        val stretched = WakeGate.Hypothesis(
            "hey tablet",
            listOf(
                WakeGate.Word("hey", 0.0, 2.4, 1f),
                WakeGate.Word("tablet", 2.4, 6.0, 1f),
            ),
        )
        assertFalse(WakeGate.genuine(stretched, 0.85f))
        val forced = spokenHey(alts = listOf("hey tablet", "[unk]"))
        assertFalse(WakeGate.genuine(forced, 0.85f))
        assertFalse(
            WakeGate.genuine(
                spokenHey().copy(words = listOf(
                    WakeGate.Word("hey", 0.0, 0.0, 1f),
                    WakeGate.Word("tablet", 0.0, 0.0, 1f),
                )),
                0.85f,
            ),
        )
    }

    @Test
    fun own_playback_blocks_and_a_later_wake_is_not_on_a_long_cooldown() {
        val done = 1_000L
        val until = done + WakeGate.RING_MS
        assertTrue(WakeGate.blockedByOwnAudio(done + 100, until))
        assertFalse(WakeGate.blockedByOwnAudio(done + 13_000, until))
    }

    @Test
    fun unrelated_speech_is_not_replaced_by_the_time_grammar() {
        val unrelated = listOf(
            "they'll be a short fun fact",
            "tell me your phone",
            "gave me a fun fact",
            "almost stand up and running",
            "turn on the kitchen light",
        )
        unrelated.forEach { heard ->
            val chosen = SpokenFacts.chooseTranscript(heard, "what time is it")
            assertEquals(heard, chosen)
            assertFalse(SpokenFacts.isTimeQuestion(chosen!!))
            assertFalse(SpokenFacts.isWeatherQuestion(chosen))
        }
        assertEquals("what time is it", SpokenFacts.chooseTranscript(null, "what time is it"))
        assertEquals("that time in it", SpokenFacts.chooseTranscript("that time in it", "what is the weather"))
    }

    @Test
    fun a_rejected_wake_does_not_open_chat_and_one_genuine_wake_opens_once() {
        var posts = 0
        val permit = TurnPermit(clock = { 0L })
        if (WakeGate.genuine(spokenHey(alts = listOf("hey tablet", "[unk]")), 0.85f)) {
            val token = permit.tryBegin(TurnPermit.Source.WAKE)
            if (token != null && permit.allowChat(token)) posts += 1
        }
        assertEquals(0, posts)
        if (WakeGate.genuine(spokenHey(), 0.85f)) {
            val token = permit.tryBegin(TurnPermit.Source.WAKE)
            if (token != null && permit.allowChat(token)) posts += 1
            assertFalse(permit.allowChat(token!!))
        }
        assertEquals(1, posts)
        assertEquals(0, posts - 1)
    }

    @Test
    fun a_later_weather_question_reads_the_one_cached_entity() {
        val now = Instant.parse("2026-11-08T02:30:00Z")
        val one = """{"entity_id":"weather.home","state":"cloudy","last_updated":"2026-11-08T02:20:00Z","attributes":{"temperature":12,"temperature_unit":"°C"}}"""
        val all = "[$one]"
        var ones = 0
        var alls = 0
        val read = WeatherRead()
        val first = read.load(now, { ones += 1; one }, { alls += 1; all })
        assertEquals("ha-get-states", first.tool)
        assertEquals(0, ones)
        assertEquals(1, alls)
        assertTrue(first.detail.fresh)
        assertEquals("weather.home", first.detail.entityId)
        val second = read.load(now, { ones += 1; one }, { alls += 1; all })
        assertEquals("ha-get-state", second.tool)
        assertEquals(1, ones)
        assertEquals(1, alls)
        assertTrue(second.detail.sentence.contains("weather.home"))
    }

    private fun spokenHey(alts: List<String> = emptyList()) = WakeGate.Hypothesis(
        "hey tablet",
        listOf(
            WakeGate.Word("hey", 0.20, 0.38, 0.97f),
            WakeGate.Word("tablet", 0.42, 0.80, 0.95f),
        ),
        alts,
    )
}
