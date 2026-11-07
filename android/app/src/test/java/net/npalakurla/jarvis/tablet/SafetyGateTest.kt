package net.npalakurla.jarvis.tablet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {

    @Test
    fun refusesSpokenLocksGarageAndAlarmIncludingMishears() {
        val refused = listOf(
            "unlock the front door",
            "Unlock the Front Door!",
            "un lock the front door",
            "un-lock the front door",
            "and lock the front door",
            "on lock the front door",
            "andlock the front door",
            "onlock",
            "lock the door",
            "locks",
            "deadbolt",
            "dead bolt",
            "open the front door",
            "open the garage",
            "open the garage door",
            "please open the garage",
            "disarm the alarm",
            "Disarm the alarm.",
            "dis arm the alarm",
            "dis-arm the alarm",
            "the arm",
            "this arm",
            "arming",
            "security system",
            "alarm",
            "lock.front_door",
            "cover.garage_door",
            "alarm_control_panel.home",
        )
        for (phrase in refused) {
            assertTrue(phrase, SafetyGate.refuses(phrase))
        }
    }

    @Test
    fun allowsOrdinaryRequests() {
        val allowed = listOf(
            "turn on the kitchen lights",
            "what time is it",
            "turn on the clock",
            "close the blinds",
            "open the blinds",
        )
        for (phrase in allowed) {
            assertFalse(phrase, SafetyGate.refuses(phrase))
        }
        assertFalse(SafetyGate.refuses(null))
        assertFalse(SafetyGate.refuses("   "))
    }

    @Test
    fun joinsSplitSafetyWords() {
        assertTrue(SafetyGate.normalize("un lock the front door").contains("unlock"))
        assertTrue(SafetyGate.normalize("dis arm the alarm").contains("disarm"))
        assertTrue(SafetyGate.normalize("dead bolt").contains("deadbolt"))
    }

    @Test
    fun refusesToolTargetsAndAllowsLights() {
        assertTrue(SafetyGate.refusesTool("lock", "lock.front_door", "unlock", "unlock the door"))
        assertTrue(SafetyGate.refusesTool("cover", "cover.garage_door", "open_cover", "open the garage"))
        assertTrue(
            SafetyGate.refusesTool(
                "alarm_control_panel",
                "alarm_control_panel.home",
                "alarm_disarm",
                "disarm the alarm",
            ),
        )
        assertTrue(
            SafetyGate.refusesTool("light", "light.kitchen", "turn_on", "unlock the front door"),
        )
        assertFalse(
            SafetyGate.refusesTool("light", "light.kitchen", "turn_on", "turn on the kitchen lights"),
        )
        assertFalse(
            SafetyGate.refusesTool("sensor", "sensor.time", null, "what time is it"),
        )
    }
}
