package com.gathin.bingwall;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The feed address can be overridden by a {@code feed.txt} beside the pictures, so a moved endpoint is corrected
 * with a text file instead of a new APK. What must never happen is that a bad file stops the screensaver working.
 */
public class FeedOverrideTest {

    private static File folder() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "bingwall-feed-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void write(File dir, String text) throws Exception {
        Files.write(new File(dir, BingDay.OVERRIDE).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void cleanUp(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
        dir.delete();
    }

    @Test
    public void withNoFileTheBuiltInAddressIsUsed() throws Exception {
        File dir = folder();
        try {
            assertEquals(BingDay.FEED, BingDay.feed(dir));
            assertEquals(BingDay.FEED, BingDay.feed(null));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void aFileWithAnAddressOverridesIt() throws Exception {
        File dir = folder();
        try {
            write(dir, "https://example.test/feed.json?n=7");
            assertEquals("https://example.test/feed.json?n=7", BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void surroundingWhitespaceAndNewlinesAreDropped() throws Exception {
        // What an editor leaves behind is not part of the URL.
        File dir = folder();
        try {
            write(dir, "\r\n  https://example.test/feed.json  \r\n");
            assertEquals("https://example.test/feed.json", BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void aByteOrderMarkIsDropped() throws Exception {
        File dir = folder();
        try {
            write(dir, "\uFEFFhttps://example.test/feed.json");
            assertEquals("https://example.test/feed.json", BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void aFileThatIsNotAnAddressIsIgnored() throws Exception {
        File dir = folder();
        try {
            write(dir, "example.test/feed.json");       // no scheme
            assertEquals(BingDay.FEED, BingDay.feed(dir));
            write(dir, "ftp://example.test/feed.json"); // not http
            assertEquals(BingDay.FEED, BingDay.feed(dir));
            write(dir, "");
            assertEquals(BingDay.FEED, BingDay.feed(dir));
            write(dir, "   \n  ");
            assertEquals(BingDay.FEED, BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void aSchemeWithNoHostIsIgnored() throws Exception {
        File dir = folder();
        try {
            write(dir, "https://");
            assertEquals(BingDay.FEED, BingDay.feed(dir));
            write(dir, "https:///path");
            assertEquals(BingDay.FEED, BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void anAbsurdlyLongFileIsIgnored() throws Exception {
        File dir = folder();
        try {
            StringBuilder longText = new StringBuilder("https://example.test/");
            for (int i = 0; i < 3000; i++) longText.append('a');
            write(dir, longText.toString());
            assertEquals(BingDay.FEED, BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void anOverrideMayPointAtAPlainHttpAddress() throws Exception {
        // A TV behind a local proxy or a home server is a real setup; only https is not required.
        File dir = folder();
        try {
            write(dir, "http://192.168.1.10:8080/feed.json");
            assertEquals("http://192.168.1.10:8080/feed.json", BingDay.feed(dir));
        } finally {
            cleanUp(dir);
        }
    }

    @Test
    public void usableAcceptsRealAddressesAndRefusesNearlyAddresses() {
        assertTrue(BingDay.usable("https://www.bing.com/x"));
        assertTrue(BingDay.usable("http://local.test:8080/x"));
        assertFalse(BingDay.usable("https://"));
        assertFalse(BingDay.usable("www.bing.com"));
        assertFalse(BingDay.usable(""));
        assertFalse(BingDay.usable(null));
        assertFalse(BingDay.usable("file:///sdcard/feed.json"));
    }
}
