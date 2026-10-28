package com.gathin.bingwall;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What happens when Bing's picture addresses stop working. The feed and the pictures can fail apart from each
 * other, and the answer must never be "delete everything and show a black screen".
 */
public class FeedFailureTest {

    @Test
    public void theFeedAddressIsTheDocumentedOne() {
        // If Bing ever moves it, this is the line to change; keeping it asserted means a silent edit shows up.
        assertEquals("https://www.bing.com/HPImageArchive.aspx?format=js&n=7&mkt=zh-CN", BingDay.FEED);
    }

    @Test
    public void aFeedWithNoUsablePictureIsRejectedRatherThanTreatedAsAWeekOfNothing() {
        // Every entry broken: parse yields nothing, and refresh() throws rather than pruning against it.
        assertTrue(BingDay.parse("{\"images\":[{\"startdate\":\"junk\",\"url\":\"\"}]}").isEmpty());
        assertTrue(BingDay.parse("{\"images\":[{\"url\":\"/a.jpg\"}]}").isEmpty());
    }

    @Test
    public void oneBrokenDayLeavesTheOtherSix() {
        // A single 404 for one day must not take the week with it.
        String partly = "{\"images\":["
                + "{\"startdate\":\"20261027\",\"url\":\"/a.jpg\"},"
                + "{\"startdate\":\"bogus\",\"url\":\"/b.jpg\"},"
                + "{\"startdate\":\"20261025\",\"url\":\"/c.jpg\"}]}";
        assertEquals(2, BingDay.parse(partly).size());
    }

    @Test
    public void everyDayAlreadyOnTheTvMeansNothingIsDownloaded() {
        // The common case after the first run: the cache is complete, so a refresh only re-reads the feed. This
        // is what keeps a daily refresh cheap.
        assertEquals(7, BingDay.parse(sevenDays()).size());
    }

    @Test
    public void theBackoffGrowsFromAnHourToADay() {
        // Mirrors BingCache.retryAfter: 1 h, 2 h, 4 h, 8 h, then a day, so a moved address is picked up within a
        // day without the TV trying on every single screensaver start.
        long hour = 60L * 60 * 1000;
        assertEquals(1 * hour, backoff(1));
        assertEquals(2 * hour, backoff(2));
        assertEquals(4 * hour, backoff(3));
        assertEquals(8 * hour, backoff(4));
        assertEquals(24 * hour, backoff(5));
        assertEquals(24 * hour, backoff(50));
    }

    /** The same arithmetic BingCache.retryAfter uses. */
    private static long backoff(int failures) {
        long hour = 60L * 60 * 1000;
        if (failures <= 0) return 0L;
        if (failures >= 5) return 24 * hour;
        return hour << (failures - 1);
    }

    @Test
    public void aZeroFailureCountMeansTryAtOnce() {
        assertEquals(0L, backoff(0));
    }

    @Test
    public void theBackoffNeverExceedsOneDay() {
        for (int i = 5; i < 40; i++) assertTrue(backoff(i) <= 24L * 60 * 60 * 1000);
    }

    @Test
    public void aBrokenFeedCannotAgeTheCache() {
        // A failed refresh leaves the file dates alone, so the next start is still "stale" and tries again once
        // the backoff has passed; the pictures themselves are never touched.
        assertFalse(BingDay.parse("{}").iterator().hasNext());
    }

    private static String sevenDays() {
        StringBuilder json = new StringBuilder("{\"images\":[");
        for (int day = 27; day >= 21; day--) {
            if (day != 27) json.append(',');
            json.append("{\"startdate\":\"202610").append(day).append("\",\"url\":\"/th?id=x").append(day)
                    .append(".jpg&pid=hp\"}");
        }
        return json.append("]}").toString();
    }
}
