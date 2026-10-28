package com.gathin.bingwall;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Parsing the feed, which needs no TV and is where a wrong assumption would blank the screensaver. */
public class BingDayTest {

    private static final String FEED = "{"
            + "\"images\":["
            + "{\"startdate\":\"20261027\",\"url\":\"/th?id=OHR.KuneticeCastle_ZH-CN8895858920_1920x1080.jpg"
            + "&rf=LaDigue_1920x1080.jpg&pid=hp\",\"title\":\"历史守望之地\","
            + "\"copyright\":\"库涅季采山城堡 (© Marek Saroch/Alamy)\"},"
            + "{\"startdate\":\"20261026\",\"url\":\"/th?id=OHR.PondCypress_ZH-CN9472607734_1920x1080.jpg\","
            + "\"title\":\"大自然的金色明镜\",\"copyright\":\"秋叶缤纷的池杉林\"},"
            + "{\"startdate\":\"not-a-date\",\"url\":\"/th?id=x.jpg\"},"
            + "{\"startdate\":\"20261024\"},"
            + "{\"url\":\"/th?id=missing-date.jpg\"}"
            + "]}";

    @Test
    public void keepsTheDaysWithADateAndAnAddress() {
        List<BingDay> days = BingDay.parse(FEED);
        assertEquals(2, days.size());
        assertEquals("20261027", days.get(0).date);
        assertEquals("20261026", days.get(1).date);
    }

    @Test
    public void turnsASiteRelativeUrlIntoAnAddress() {
        List<BingDay> days = BingDay.parse(FEED);
        assertTrue(days.get(0).url.startsWith("https://www.bing.com/th?id="));
        // The parameters Bing signs the download with survive untouched.
        assertTrue(days.get(0).url.contains("&rf=LaDigue_1920x1080.jpg"));
        assertTrue(days.get(0).url.contains("&pid=hp"));
    }

    @Test
    public void keepsAnAbsoluteUrlAsItIs() {
        List<BingDay> days = BingDay.parse(
                "{\"images\":[{\"startdate\":\"20261027\",\"url\":\"https://example.test/a.jpg\"}]}");
        assertEquals("https://example.test/a.jpg", days.get(0).url);
    }

    @Test
    public void namesTheFileAfterTheDate() {
        assertEquals("20261027.jpg", BingDay.parse(FEED).get(0).fileName());
    }

    @Test
    public void captionsWithTheTitleAndThePlace() {
        assertEquals("历史守望之地 — 库涅季采山城堡 (© Marek Saroch/Alamy)",
                BingDay.parse(FEED).get(0).caption());
    }

    @Test
    public void captionsWithWhicheverHalfIsThere() {
        BingDay noPlace = BingDay.parse(
                "{\"images\":[{\"startdate\":\"20261027\",\"url\":\"/a.jpg\",\"title\":\"只有标题\"}]}").get(0);
        assertEquals("只有标题", noPlace.caption());
        BingDay neither = BingDay.parse(
                "{\"images\":[{\"startdate\":\"20261027\",\"url\":\"/a.jpg\"}]}").get(0);
        assertEquals("", neither.caption());
    }

    @Test
    public void aBrokenFeedIsEmptyRatherThanAnException() {
        assertTrue(BingDay.parse(null).isEmpty());
        assertTrue(BingDay.parse("").isEmpty());
        assertTrue(BingDay.parse("not json").isEmpty());
        assertTrue(BingDay.parse("{}").isEmpty());
        assertTrue(BingDay.parse("{\"images\":\"nope\"}").isEmpty());
        assertTrue(BingDay.parse("[1,2,3]").isEmpty());
    }

    @Test
    public void theNewestSevenIsWhatStays() {
        List<String> all = Arrays.asList("20261027.jpg", "20261026.jpg", "20261025.jpg", "20261024.jpg",
                "20261023.jpg", "20261022.jpg", "20261021.jpg", "20261020.jpg", "20261019.jpg");
        assertEquals(Arrays.asList("20261027.jpg", "20261026.jpg", "20261025.jpg", "20261024.jpg",
                "20261023.jpg", "20261022.jpg", "20261021.jpg"), BingCache.newest(all));
    }

    @Test
    public void secondsOutOfRangeFallBackToTheDefault() {
        assertEquals(60, Options.seconds(60));
        assertEquals(60, Options.seconds(0));
        assertEquals(60, Options.seconds(-5));
        assertEquals(60, Options.seconds(5000));
        assertEquals(5, Options.seconds(5));
        assertEquals(3600, Options.seconds(3600));
    }

    @Test
    public void aPictureIsSampledDownToTheScreen() {
        // A 1920x1080 picture on a 1920x1080 screen is decoded as it is.
        assertEquals(1, Pictures.sampleSize(1920, 1080, 1920, 1080));
        // A 4K picture on a 1080p screen: a quarter in each direction.
        assertEquals(2, Pictures.sampleSize(3840, 2160, 1920, 1080));
    }

    @Test
    public void tooLargeAPictureIsRefusedRatherThanDecoded() {
        assertTrue(Pictures.accepted(3840, 2160));
        assertTrue(!Pictures.accepted(7680, 4320));
        assertTrue(!Pictures.accepted(0, 1080));
    }

    @Test
    public void aPictureSmallerThanTheScreenIsNotSampledUp() {
        // A small picture is shown as it is rather than blown up into a bigger decode.
        assertEquals(1, Pictures.sampleSize(640, 360, 1920, 1080));
    }

    @Test
    public void todaysDateIsEightDigits() {
        // The cache compares this against a file name, so the format is part of the contract.
        String today = BingCache.today();
        assertTrue(today.isEmpty() || today.matches("\\d{8}"));
    }

    @Test
    public void theCaptionFileIsReadIntoADateToTextMap() throws Exception {
        // A caption is looked up by date, and a line without a tab is ignored rather than breaking the rest.
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "bingwall-test-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            java.io.File captions = new java.io.File(dir, "captions.txt");
            java.nio.file.Files.write(captions.toPath(),
                    "20261027\t历史守望之地 — 库涅季采山城堡\nnot a caption\n20261026\t大自然的金色明镜\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.Map<String, String> read = BingCache.readCaptions(dir);
            assertEquals("历史守望之地 — 库涅季采山城堡", read.get("20261027"));
            assertEquals("大自然的金色明镜", read.get("20261026"));
            assertEquals(2, read.size());
        } finally {
            for (java.io.File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    @Test
    public void aMissingCaptionsFileIsAnEmptyMapRatherThanAnError() {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "bingwall-none-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            assertTrue(BingCache.readCaptions(dir).isEmpty());
            assertTrue(BingCache.readCaptions(null).isEmpty());
        } finally {
            dir.delete();
        }
    }
}
