package com.gathin.bingwall;

import java.util.List;

/**
 * Which picture comes after the one on screen, worked out by <b>name</b> rather than by position.
 *
 * A position cannot be remembered across runs: the cache changes as days arrive and old ones are deleted from
 * the front and the back, so the same number would point at a different picture, or past the end. The name of
 * the picture that was showing is stable, and the lookup below answers the three cases that matter:
 *
 * <ul>
 *   <li>nothing was shown yet: the newest (index 0);</li>
 *   <li>the picture is still on the TV: the next one, wrapping from the oldest back to the newest;</li>
 *   <li>the picture has been deleted (it aged out of the seven, or was removed): the newest again. That is the
 *       same answer the wrap gives, because the picture that ages out is always the oldest.</li>
 * </ul>
 */
final class Rotation {

    private Rotation() {}

    /** The index to show next, or -1 when there is nothing to show. */
    static int next(List<String> names, String last) {
        if (names == null || names.isEmpty()) return -1;
        if (last == null || last.isEmpty()) return 0;
        int at = names.indexOf(last);
        // Gone from the TV: start again at the newest. In a circular walk this is the right neighbour of the
        // oldest picture, which is the one that ages out first.
        if (at < 0) return 0;
        return (at + 1) % names.size();
    }
}
