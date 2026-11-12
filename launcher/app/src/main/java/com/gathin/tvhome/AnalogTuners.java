package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which tuner inputs show_analog_tv lists as plain rows in the Inputs menu.
 *
 * The Live TV row takes one tuner per TV, preferring a digital one, and a tuner that lost the pick is otherwise
 * gone from the menu entirely. On most sets that is right: the analog ATV source is dead. On a set where it is
 * alive (snow on the stock Sources menu, e.g. Redmi L55R6-A), true brings the loser back as a plain row, with no
 * connection state, like the AV rows. The winner never appears twice: it stays the Live TV row, as on every other
 * set.
 */
final class AnalogTuners {

    private AnalogTuners() {}

    /**
     * The candidates to list, in the order given. Only the losers of the pick, and only ones the owner can tell
     * apart from a second digital tuner:
     * <ul>
     * <li>an id that names an analog service (MediaTek's AnalogInputService), or</li>
     * <li>an id that is not one of the framework's hardware tuner ids (".../HW&lt;n&gt;"), or</li>
     * <li>a hardware id the owner has named in input_names ("HW1=Analog TV"). That last test is what a set
     *     whose firmware registers both tuners on one service needs: MediaTek's TunerInputService hands out
     *     HW0 (DTMB) and HW1 (ATV) with the same label, in that order, so nothing in the id or the label says
     *     which is the analog one, and the pick keeps HW0. Naming the loser is the owner saying so.</li>
     * </ul>
     * An unnamed hardware id that lost (two digital tuners, or live_tv_input_id picking one) stays out: which of
     * those to surface is a different question with no answer that fits every set.
     *
     * @param tunerIds the system tuner input ids, in the order the pick saw them
     * @param winnerId the id the Live TV pick chose, or null when it chose none
     * @param named the "/HW&lt;n&gt;" tails the owner named in input_names, never null
     */
    static List<String> losers(List<String> tunerIds, String winnerId, Set<String> named) {
        List<String> result = new ArrayList<>();
        if (tunerIds == null) return result;
        for (String id : tunerIds) {
            if (id == null || id.equals(winnerId)) continue;
            boolean analog = id.toLowerCase(Locale.ROOT).contains("analog")
                    || !id.matches(".*/HW\\d+$")
                    || named.contains(hwTail(id));
            if (analog) result.add(id);
        }
        return result;
    }

    /** The "/HW&lt;n&gt;" tail of a framework input id, the key input_names uses; "" when there is none. */
    static String hwTail(String inputId) {
        if (inputId == null) return "";
        return inputId.substring(inputId.lastIndexOf('/') + 1);
    }
}
