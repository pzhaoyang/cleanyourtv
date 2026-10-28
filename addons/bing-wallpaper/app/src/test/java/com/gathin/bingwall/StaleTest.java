package com.gathin.bingwall;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * When a fetch is worth doing. Bing publishes a market's picture for the day before, so "the newest picture must
 * be today" would call the cache stale on every run: these cases pin the age-based rule that replaced it.
 */
public class StaleTest {

    @Test
    public void yesterdaysPictureIsFresh() {
        // The real shape of the feed: on 28 October the newest picture is 20261027.
        assertEquals(1, BingCache.daysBetween("20261027", "20261028"));
    }

    @Test
    public void aWeekOldIsNotOldEnoughToFetch() {
        // Six days back still leaves seven pictures worth watching.
        assertEquals(6, BingCache.daysBetween("20261022", "20261028"));
    }

    @Test
    public void aPictureFromTheFutureIsNotStale() {
        // A clock that jumped backwards must not make the TV fetch on every wake-up.
        assertTrue(BingCache.daysBetween("20261029", "20261028") < 0);
    }

    @Test
    public void aRealDateParsesAndJunkDoesNot() {
        assertEquals(LocalDate.of(2026, 10, 27), BingCache.parse("20261027"));
        assertNull(BingCache.parse("2026102"));
        assertNull(BingCache.parse("20261032"));   // day 32
        assertNull(BingCache.parse("20261301"));   // month 13
        assertNull(BingCache.parse("not-a-date"));
        assertNull(BingCache.parse(null));
    }

    @Test
    public void anUnreadableDateCountsAsLongAgo() {
        // So a corrupt name cannot keep the cache looking fresh for ever.
        assertEquals(Integer.MAX_VALUE, BingCache.daysBetween("junk", "20261028"));
        assertEquals(Integer.MAX_VALUE, BingCache.daysBetween("20261027", "junk"));
    }

    @Test
    public void theStaleBoundaryIsTheWeekTheCacheKeeps() {
        // age <  KEEP: fresh (no fetch)
        assertTrue(BingCache.daysBetween("20261024", "20261028") < BingCache.KEEP);
        // age >= KEEP: stale (fetch)
        assertTrue(BingCache.daysBetween("20261021", "20261028") >= BingCache.KEEP);
    }
}
