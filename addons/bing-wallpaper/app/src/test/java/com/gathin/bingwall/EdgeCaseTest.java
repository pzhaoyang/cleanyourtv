package com.gathin.bingwall;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge cases and limits: what a broken feed, an implausible date, a huge picture or an odd clock does to the
 * screensaver. These are the states a set in the field actually reaches, not the happy path.
 */
public class EdgeCaseTest {

    // ---------------------------------------------------------------- the feed

    @Test
    public void aFeedThatIsNotEvenAnObjectYieldsNothing() {
        assertTrue(BingDay.parse("null").isEmpty());
        assertTrue(BingDay.parse("\"text\"").isEmpty());
        assertTrue(BingDay.parse("42").isEmpty());
        assertTrue(BingDay.parse("{\"images\":null}").isEmpty());
        assertTrue(BingDay.parse("{\"images\":[]}").isEmpty());
    }

    @Test
    public void anImageEntryThatIsNotAnObjectIsSkippedOnItsOwn() {
        List<BingDay> days = BingDay.parse("{\"images\":[null,1,\"x\",{\"startdate\":\"20261027\","
                + "\"url\":\"/a.jpg\"}]}");
        assertEquals(1, days.size());
    }

    @Test
    public void anImplausibleDateIsDroppedAtParseTime() {
        // The date becomes a file name that is compared and sorted, so a day the calendar does not have would
        // sort as the newest picture for ever and never age out. It is refused where it is read.
        assertTrue(BingDay.parse("{\"images\":[{\"startdate\":\"20261399\",\"url\":\"/a.jpg\"}]}").isEmpty());
        assertTrue(BingDay.parse("{\"images\":[{\"startdate\":\"20260230\",\"url\":\"/a.jpg\"}]}").isEmpty());
        assertTrue(BingDay.parse("{\"images\":[{\"startdate\":\"20261027\",\"url\":\"/a.jpg\"}]}").size() == 1);
    }

    @Test
    public void aRealDayIsAcceptedAndAnImpossibleOneIsNot() {
        assertTrue(BingDay.isRealDay("20261027"));
        assertTrue(BingDay.isRealDay("20240229"));    // leap year
        assertTrue(!BingDay.isRealDay("20230229"));   // not a leap year
        assertTrue(!BingDay.isRealDay("20261301"));   // month 13
        assertTrue(!BingDay.isRealDay("20260100"));   // day 0
        assertTrue(!BingDay.isRealDay("2026102"));    // seven digits
        assertTrue(!BingDay.isRealDay(null));
    }

    @Test
    public void anEmptyTitleAndCopyrightStillMakeADay() {
        List<BingDay> days = BingDay.parse("{\"images\":[{\"startdate\":\"20261027\",\"url\":\"/a.jpg\","
                + "\"title\":\"\",\"copyright\":\"\"}]}");
        assertEquals(1, days.size());
        assertEquals("", days.get(0).caption());
    }

    @Test
    public void aWhitespaceOnlyTitleIsTreatedAsMissing() {
        List<BingDay> days = BingDay.parse("{\"images\":[{\"startdate\":\"20261027\",\"url\":\"/a.jpg\","
                + "\"title\":\"   \",\"copyright\":\"  Somewhere  \"}]}");
        assertEquals("Somewhere", days.get(0).caption());
    }

    @Test
    public void aProtocolRelativeUrlIsMadeAbsolute() {
        List<BingDay> days = BingDay.parse(
                "{\"images\":[{\"startdate\":\"20261027\",\"url\":\"th?id=a.jpg\"}]}");
        assertTrue(days.get(0).url.startsWith("https://www.bing.com/"));
    }

    // ---------------------------------------------------------------- the walk

    @Test
    public void aNameThatIsNoLongerOnTheTVStartsAtTheNewest() {
        List<String> week = Arrays.asList("20261027.jpg", "20261026.jpg");
        assertEquals(0, Rotation.next(week, "20250101.jpg"));
    }

    @Test
    public void theWalkIsStableAcrossManyPictures() {
        // Real cache names are unique, one per day; a hundred of them, walking the whole circle and back.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(String.format("2026%02d%02d.jpg", (i / 28) + 1, (i % 28) + 1));
        String last = many.get(many.size() - 1);
        assertEquals(0, Rotation.next(many, last));          // wrap from the last back to the first
        assertEquals(1, Rotation.next(many, many.get(0)));   // and on to the second
    }

    @Test
    public void everyPositionIsReachedOnceInAFullCircle() {
        List<String> week = Arrays.asList("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg", "g.jpg");
        List<String> seen = new ArrayList<>();
        String last = null;
        for (int i = 0; i < week.size(); i++) {
            int at = Rotation.next(week, last);
            last = week.get(at);
            seen.add(last);
        }
        assertEquals(week, seen);
    }

    // ---------------------------------------------------------------- the clock and the age

    @Test
    public void aClockFarInTheFutureMakesTheCacheLookOldRatherThanFreshForEver() {
        // Age is negative: the pictures are "from the future". stale() reports not stale, which is the safe
        // answer (do not fetch); the important part is that it does not crash or loop.
        assertTrue(BingCache.daysBetween("20200101", "20991231") > 0);
        assertTrue(BingCache.daysBetween("20991231", "20200101") < 0);
    }

    @Test
    public void theLeapDayIsARealDate() {
        assertNotNull(BingCache.parse("20240229"));
        assertEquals(null, BingCache.parse("20230229"));   // 2023 was not a leap year
    }

    @Test
    public void aDateWithAMonthOrDayOfZeroIsRefused() {
        assertEquals(null, BingCache.parse("20260010"));
        assertEquals(null, BingCache.parse("20260100"));
    }

    // ---------------------------------------------------------------- picture limits

    @Test
    public void theBiggestAcceptedPictureIsOne4KFrame() {
        assertTrue(Pictures.accepted(3840, 2160));      // 8,294,400
        assertTrue(Pictures.accepted(4096, 2048));      // 8,388,608, just under the limit
        assertFalse(Pictures.accepted(4096, 2160));     // 8,847,360, over it
    }

    @Test
    public void aZeroOrNegativeSizeIsRefused() {
        assertFalse(Pictures.accepted(0, 0));
        assertFalse(Pictures.accepted(-1, 100));
        assertFalse(Pictures.accepted(100, -1));
    }

    @Test
    public void aTinyPictureIsNeverSampled() {
        // A 1x1 picture against a 1920x1080 screen: sample is 1, and the loop must not run away.
        assertEquals(1, Pictures.sampleSize(1, 1, 1920, 1080));
    }

    @Test
    public void anEnormousPictureIsSampledDownWithoutOverflowing() {
        // 30000x20000 would overflow a naive w * h; the sampler divides first.
        int sample = Pictures.sampleSize(30000, 20000, 1920, 1080);
        assertTrue(sample >= 8);
        assertFalse(Pictures.accepted(30000 / sample, 20000 / sample));
    }

    // ---------------------------------------------------------------- options

    @Test
    public void everyOutOfRangeIntervalFallsBackToAMinute() {
        int[] bad = {Integer.MIN_VALUE, -1, 0, 4, 3601, 100000, Integer.MAX_VALUE};
        for (int value : bad) assertEquals(60, Options.seconds(value));
    }

    @Test
    public void theTwoIntervalBoundsAreKept() {
        assertEquals(5, Options.seconds(5));
        assertEquals(3600, Options.seconds(3600));
    }

    // ---------------------------------------------------------------- captions

    @Test
    public void aCaptionWithTabsKeepsEverythingAfterTheFirstTab() {
        // The split is on the first tab only; a tab inside the text must not truncate it.
        assertEquals("a\tb", splitCaption("20261027\ta\tb"));
    }

    private static String splitCaption(String line) {
        int tab = line.indexOf('\t');
        return tab > 0 ? line.substring(tab + 1) : "";
    }

    @Test
    public void aCaptionLineWithNoTabIsIgnored() {
        assertEquals("", splitCaption("20261027 no tab here"));
    }
}
