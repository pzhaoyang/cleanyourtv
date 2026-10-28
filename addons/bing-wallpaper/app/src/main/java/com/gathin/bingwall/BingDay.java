package com.gathin.bingwall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One day's picture from Bing's HPImageArchive feed: the file name to save it under, the address to fetch it
 * from, and the Chinese title and place to caption it with.
 *
 * Parsing is kept separate from the network so it can be unit tested against a saved response.
 */
final class BingDay {

    /**
     * Bing's own archive: the last {@code n} days, newest first. No key, no account.
     *
     * This is only the default. A {@code feed.txt} beside the pictures overrides it (see {@link #feed}), so a
     * moved or changed address can be corrected on the TV with a text file instead of a new APK.
     */
    static final String FEED = "https://www.bing.com/HPImageArchive.aspx?format=js&n=7&mkt=zh-CN";
    private static final String HOST = "https://www.bing.com";
    /** The optional file that overrides {@link #FEED}, in the pictures folder. */
    static final String OVERRIDE = "feed.txt";
    /** A feed address is short; anything longer is a mistake or a file that is not one. */
    private static final int MAX_URL = 2_048;

    /** 20261027: the date the picture was the day's picture. */
    final String date;
    /** The title Bing gives the picture, in Chinese; may be empty. */
    final String title;
    /** Where and by whom, in Chinese; may be empty. */
    final String place;
    /** The full-size JPEG address. */
    final String url;

    private BingDay(String date, String title, String place, String url) {
        this.date = date;
        this.title = title;
        this.place = place;
        this.url = url;
    }

    /** The file this day is cached as, e.g. 20261027.jpg. */
    String fileName() {
        return date + ".jpg";
    }

    /**
     * The feed to read: {@code feed.txt} beside the pictures when it holds a usable http(s) address, else
     * {@link #FEED}.
     *
     * This is the correction path for a moved or changed endpoint. It is a plain text file in the folder the
     * pictures already live in, so it can be written over ADB (`tools/tv.py` or `adb push`), or through the
     * launcher's theme mechanism, without building a new APK. A file that is missing, empty, too long, or not an
     * http(s) address is ignored rather than breaking the screensaver, and the built-in address is used.
     */
    static String feed(File dir) {
        File file = dir == null ? null : new File(dir, OVERRIDE);
        if (file == null || !file.isFile() || file.length() > MAX_URL) return FEED;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            // Editors add a BOM and Windows adds CRLF; neither belongs in a URL.
            if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
            return usable(text) ? text : FEED;
        } catch (Exception e) {
            return FEED;
        }
    }

    /** True for an http or https address with something after the scheme. */
    static boolean usable(String url) {
        if (url == null) return false;
        String text = url.trim();
        if (text.isEmpty() || text.length() > MAX_URL) return false;
        if (!text.startsWith("https://") && !text.startsWith("http://")) return false;
        // A bare scheme, or one with no host, would fail in a way that reads like a broken feed.
        String rest = text.substring(text.indexOf("://") + 3);
        return !rest.isEmpty() && !rest.startsWith("/");
    }

    /**
     * The days in the feed, newest first. A day without a usable date or address is left out on its own, and a
     * feed that is not the shape Bing documents yields an empty list rather than an exception.
     *
     * The date is checked against the calendar, not just for eight digits: it becomes a file name that is later
     * compared and sorted, so a "20261399" would sort as the newest picture for ever and would never age out.
     */
    static List<BingDay> parse(String json) {
        List<BingDay> days = new ArrayList<>();
        if (json == null) return days;
        try {
            JSONArray images = new JSONObject(json).optJSONArray("images");
            if (images == null) return days;
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) continue;
                String date = image.optString("startdate", "").trim();
                String path = image.optString("url", "").trim();
                if (!isRealDay(date) || path.isEmpty()) continue;
                if (!path.startsWith("http://") && !path.startsWith("https://")) {
                    // The feed gives a site-relative /th?id=... path.
                    path = path.startsWith("/") ? HOST + path : HOST + "/" + path;
                }
                // The URL's own parameters are what Bing signs the download with, so they are kept verbatim.
                days.add(new BingDay(date, image.optString("title", "").trim(),
                        image.optString("copyright", "").trim(), path));
            }
        } catch (Exception ignored) {
            // Not JSON, or not an object: the caller keeps whatever is already on the TV.
        }
        return days;
    }

    /** True for 20261027 and false for 20261399: eight digits that also form a day the calendar has. */
    static boolean isRealDay(String date) {
        if (date == null || !date.matches("\\d{8}")) return false;
        try {
            java.time.LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)),
                    Integer.parseInt(date.substring(6, 8)));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "Title — place", or whichever half is there; empty when Bing sent neither. */
    String caption() {
        if (title.isEmpty()) return place;
        if (place.isEmpty()) return title;
        return title + " — " + place;
    }
}
