package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Which tuners show_analog_tv lists, and which stay out. */
public class AnalogTunersTest {

    private static final String DIGITAL = "com.mediatek.tvinput/.tuner.TunerInputService/HW0";
    private static final String ANALOG = "com.mediatek.tvinput/.analog.AnalogInputService/HW9";
    private static final String ANALOG_NO_HW = "com.mediatek.tvinput/.analog.AnalogInputService";
    private static final String DVB_S = "com.mediatek.tvinput/.tuner.TunerInputService/HW2";

    private static Set<String> named(String... tails) {
        return new HashSet<>(Arrays.asList(tails));
    }

    @Test public void theAnalogLoserIsListed() {
        List<String> out = AnalogTuners.losers(Arrays.asList(DIGITAL, ANALOG), DIGITAL, named());
        assertEquals(Collections.singletonList(ANALOG), out);
    }

    @Test public void theWinnerNeverAppears() {
        // One analog tuner alone: it IS the Live TV row, so there is nothing to list.
        assertTrue(AnalogTuners.losers(Collections.singletonList(ANALOG), ANALOG, named()).isEmpty());
        // A digital winner and its own id: never listed twice.
        assertTrue(AnalogTuners.losers(Collections.singletonList(DIGITAL), DIGITAL, named()).isEmpty());
    }

    @Test public void anAnalogIdWithoutTheHwSuffixStillCounts() {
        // Some frameworks register the analog service without the /HW<n> tail.
        List<String> out = AnalogTuners.losers(Arrays.asList(DIGITAL, ANALOG_NO_HW), DIGITAL, named());
        assertEquals(Collections.singletonList(ANALOG_NO_HW), out);
    }

    @Test public void aDigitalLoserStaysOut() {
        // Two digital tuners (DVB-T and DVB-S, say): which of those to surface is a different question, and the
        // Live TV row still covers the one that won.
        assertTrue(AnalogTuners.losers(Arrays.asList(DIGITAL, DVB_S), DIGITAL, named()).isEmpty());
    }

    @Test public void aNamedHardwareLoserIsListed() {
        // MediaTek's China tuner service hands out both tuners as /HW0 (DTMB) and /HW1 (ATV) with the same
        // label; naming the loser in input_names is what says which of the two is the analog one.
        List<String> out = AnalogTuners.losers(Arrays.asList(DIGITAL, DVB_S), DIGITAL, named("HW2"));
        assertEquals(Collections.singletonList(DVB_S), out);
        // The winner stays the Live TV row even when it is named too.
        assertTrue(AnalogTuners.losers(Arrays.asList(DIGITAL, DVB_S), DIGITAL, named("HW0", "HW2")).size() == 1);
    }

    @Test public void noPickLeavesTheAnalogTunerEligible() {
        // No live_tv_package: there is no Live TV row to lose, and a tuner that fails the passthrough filter is
        // otherwise invisible, so the analog one stays eligible as a plain row. The digital one is left out: it
        // already shows as a plain row of its own when it passes the filter, and which non-analog tuners to
        // surface is a different question.
        List<String> out = AnalogTuners.losers(Arrays.asList(DIGITAL, ANALOG), null, named());
        assertEquals(Collections.singletonList(ANALOG), out);
        assertTrue(AnalogTuners.losers(Collections.singletonList(DIGITAL), null, named()).isEmpty());
    }

    @Test public void nullAndEmptyInputsAreSafe() {
        assertTrue(AnalogTuners.losers(null, DIGITAL, named()).isEmpty());
        assertTrue(AnalogTuners.losers(Collections.emptyList(), DIGITAL, named()).isEmpty());
        assertTrue(AnalogTuners.losers(null, DIGITAL, null).isEmpty());
    }

    @Test public void orderFollowsTheInputList() {
        // The row order matches the order the system reported the inputs in.
        List<String> out = AnalogTuners.losers(Arrays.asList(ANALOG, DIGITAL), DIGITAL, named());
        assertEquals(Collections.singletonList(ANALOG), out);
    }

    @Test public void hwTailIsTheKeyInputNamesUses() {
        assertEquals("HW5", AnalogTuners.hwTail("com.mediatek.tvinput/.tuner.TunerInputService/HW5"));
        assertEquals("", AnalogTuners.hwTail(null));
    }
}
