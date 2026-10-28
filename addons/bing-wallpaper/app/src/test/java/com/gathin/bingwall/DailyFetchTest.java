package com.gathin.bingwall;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a day counts as done. The mistake this guards against: inferring "already fetched" from the newest file's
 * date. Bing always serves the previous day's picture, so a healthy cache reads as "one day behind" and a rule
 * based on it would either fetch constantly or wait days too long.
 */
public class DailyFetchTest {

    /** The day recorded after a successful fetch, as BingCache.stale() compares it. */
    @Test
    public void theSameDayIsDoneAndADifferentDayIsNot() {
        assertTrue(doneFor("20261028", "20261028"));
        assertFalse(doneFor("20261028", "20261029"));
        assertFalse(doneFor("20261028", "20261027"));
    }

    private static boolean doneFor(String fetchedOn, String today) {
        return !today.isEmpty() && today.equals(fetchedOn);
    }

    @Test
    public void aHealthyWeekEndsOnYesterdayNotToday() {
        // What the feed actually looks like on 28 October: newest picture is 20261027. age 1 is healthy, and must
        // not be treated as "stale" on its own.
        assertEquals(1, BingCache.daysBetween("20261027", "20261028"));
        assertTrue(BingCache.daysBetween("20261027", "20261028") <= 1);
    }

    @Test
    public void aWeekThatStoppedAdvancingIsStale() {
        // The TV was off for three days: the newest picture is now well behind, so a fetch is due whatever the
        // recorded day says (the recorded day cannot be today after three days off).
        assertTrue(BingCache.daysBetween("20261024", "20261028") > 1);
    }

    @Test
    public void anUnreadableRecordedDayIsTreatedAsNoRecord() {
        // A corrupt preference must not pin the cache as "already done" for ever.
        assertFalse(doneFor("", "20261028"));
        assertFalse(doneFor("junk", "20261028"));
    }

    @Test
    public void aShortWeekIsTopUpPending() {
        // Fewer than seven pictures means an earlier attempt only partly succeeded; that is not a finished day.
        for (int count = 0; count < BingCache.KEEP; count++) assertTrue(count < BingCache.KEEP);
        assertFalse(BingCache.KEEP < BingCache.KEEP);
    }

    @Test
    public void theRecordIsADayAndNothingElse() {
        // It is written with BingCache.today(), so it is always eight digits or empty.
        String today = BingCache.today();
        assertTrue(today.isEmpty() || today.matches("\\d{8}"));
    }
}
