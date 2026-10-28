package com.gathin.bingwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pictures on the TV: at most {@link #KEEP} JPEGs in the app's own files/bing/ folder, one per day, newest
 * kept and the oldest deleted. A caption file beside them holds the Chinese title and place for each day.
 *
 * All of this is plain files and one HTTPS GET; nothing runs unless the screensaver or the card asks for it.
 */
final class BingCache {
    private static final String TAG = "BingWall";

    /** Days kept on the TV: "最多7天". */
    static final int KEEP = 7;
    static final String DIR = "bing";
    private static final String CAPTIONS = "captions.txt";
    /** A picture this small is a failed download, not a photo. */
    private static final int MIN_BYTES = 8 * 1024;
    /** How long an 8-digit picture name may be to be treated as one of ours. */
    private static final String NAME = "\\d{8}\\.jpg";
    /** Serialises writes to captions.txt: two refreshes can run at once (see writeCaptions). */
    private static final Object CAPTIONS_LOCK = new Object();
    /** A .part file this old is one a killed download left; anything newer may still be being written. */
    private static final long STALE_TEMP_MS = 10L * 60 * 1000;
    /** Where the failure count and its time live, so a broken feed is not retried on every start. */
    private static final String PREFS = "feed";
    private static final String FAILURES = "failures";
    private static final String FAILED_AT = "failed_at";
    /** The day of the last successful fetch, so a completed day is not fetched again and a missed one is. */
    private static final String SUCCEEDED_ON = "succeeded_on";

    private BingCache() {}

    /** The folder the pictures live in, creating it if needed; null when external storage is unavailable. */
    static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) return null;
        File dir = new File(base, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    /**
     * The pictures to work with: at most {@link #KEEP}, newest first.
     *
     * The folder can hold more than seven — pictures added by hand, or left over from a run that was killed
     * between a download and {@link #prune} — and {@link #stale} would then see a recent picture and never fetch,
     * so nothing would ever tidy it up. Trimming here means the limit holds whatever the file system arrived at.
     */
    static List<File> pictures(Context context) {
        List<File> all = allPictures(context);
        return all.size() <= KEEP ? all : new ArrayList<>(all.subList(0, KEEP));
    }

    /** Every cached picture, newest first, with no cap; empty when there are none. */
    private static List<File> allPictures(Context context) {
        File dir = dir(context);
        File[] entries = dir == null ? null : dir.listFiles();
        List<File> result = new ArrayList<>();
        if (entries == null) return result;
        for (File entry : entries) {
            if (!entry.isFile()) continue;
            if (!entry.getName().matches(NAME)) continue;
            if (entry.length() < MIN_BYTES) continue;
            result.add(entry);
        }
        // Newest first: 8-digit names sort by date as text.
        result.sort(Comparator.comparing(File::getName).reversed());
        return result;
    }

    /**
     * Deletes the pictures past the newest {@link #KEEP}, on the TV. Called on every start, not only after a
     * fetch: pictures can arrive without a fetch (a hand-copied file, a run that ended early) and the folder is
     * meant to stay at seven.
     *
     * Returns how many files were removed.
     */
    static int trimSurplus(Context context) {
        File dir = dir(context);
        List<File> all = allPictures(context);
        if (dir == null || all.size() <= KEEP) return 0;
        int removed = 0;
        for (File file : all.subList(KEEP, all.size())) {
            if (file.delete()) removed++;
            else Log.w(TAG, "could not remove " + file.getName());
        }
        return removed;
    }

    /**
     * True when a fetch is worth doing.
     *
     * Asked in this order, because each answer is a different question:
     *
     * <ol>
     *   <li>a recent failure is being backed off from: no;</li>
     *   <li>the feed was already read successfully today: no — this is what makes a finished day stay finished
     *       instead of going back to the network on every wake-up. It is recorded, not inferred: Bing serves the
     *       previous day's picture, so "the newest file is yesterday's" is the healthy state and cannot stand in
     *       for "today has been done";</li>
     *   <li>nothing on the TV: yes;</li>
     *   <li>the newest picture is older than yesterday: yes — the week has moved on;</li>
     *   <li>fewer than {@link #KEEP} pictures: yes — a partly failed day is filled in on the next attempt rather
     *       than waiting for the next day.</li>
     * </ol>
     *
     * A clock that is not set cannot answer any of this, so a fetch is allowed and the feed decides.
     */
    static boolean stale(Context context) {
        if (backingOff(context)) return false;
        String today = today();
        if (today.isEmpty()) return true;
        // A day that was fetched in full is done, even though Bing's newest picture is still yesterday's.
        if (today.equals(lastSuccess(context))) {
            // Unless the TV has meanwhile lost pictures (they were deleted, or a file went bad): then top it up.
            return needsTopUp(context);
        }
        List<File> pictures = pictures(context);
        if (pictures.isEmpty()) return true;
        String newest = pictures.get(0).getName().replace(".jpg", "");
        int age = daysBetween(newest, today);
        // A picture dated in the future (the clock jumped back) is not stale.
        if (age < 0) return false;
        // Yesterday is what a healthy week ends on; older than that means the week really has moved on.
        if (age > 1) return true;
        return needsTopUp(context);
    }

    /** True when the folder holds fewer pictures than a full week, whatever their dates. */
    private static boolean needsTopUp(Context context) {
        return pictures(context).size() < KEEP;
    }

    /**
     * True while a recent failure is still being backed off from.
     *
     * Without this, a feed address that stops working (Bing moves it, a market is dropped) makes the TV try again
     * every single time the screensaver starts, for ever, each attempt waiting out the connect timeout. The
     * backoff is kept short enough that a fixed address is picked up within the day.
     */
    static boolean backingOff(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long failedAt = prefs.getLong(FAILED_AT, 0L);
        if (failedAt == 0L) return false;
        long waited = System.currentTimeMillis() - failedAt;
        // A clock that jumped back reads as "just failed"; keep the backoff rather than treat it as expired.
        if (waited < 0) return true;
        return waited < retryAfter(context);
    }

    /** How long to wait before trying the feed again: doubles with each failure in a row, up to a day. */
    private static long retryAfter(Context context) {
        int failures = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(FAILURES, 0);
        long hour = 60L * 60 * 1000;
        if (failures <= 0) return 0L;
        if (failures >= 5) return 24 * hour;
        return hour << (failures - 1);   // 1 h, 2 h, 4 h, 8 h, then a day
    }

    /** Notes that the feed could not be read, so the next attempt is spaced out. */
    static void noteFailure(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int failures = Math.min(prefs.getInt(FAILURES, 0) + 1, 32);
        prefs.edit().putInt(FAILURES, failures).putLong(FAILED_AT, System.currentTimeMillis()).apply();
    }

    /**
     * Notes that the feed was read and its pictures are on the TV, and on which day.
     *
     * The day is recorded rather than left to be inferred from the newest file name, because the two are not the
     * same thing: Bing always serves a market's picture for the day before, so "newest file is yesterday's" is the
     * normal, healthy state and says nothing about whether today's fetch has happened.
     */
    static void noteSuccess(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(FAILURES).remove(FAILED_AT)
                .putString(SUCCEEDED_ON, today()).apply();
    }

    /** The day the last successful fetch happened, as 20261027, or "" when none has. */
    static String lastSuccess(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SUCCEEDED_ON, "");
    }

    /** Failures in a row, for the settings screen to say why nothing is updating. */
    static int failures(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(FAILURES, 0);
    }

    /** Whole days from {@code from} to {@code to}, both written as 20261027; an unreadable date counts as old. */
    static int daysBetween(String from, String to) {
        java.time.LocalDate a = parse(from);
        java.time.LocalDate b = parse(to);
        if (a == null || b == null) return Integer.MAX_VALUE;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(a, b);
    }

    /** 20261027 to a date, or null when the text is not eight digits forming a real day. */
    static java.time.LocalDate parse(String date) {
        if (date == null || !date.matches("\\d{8}")) return null;
        try {
            return java.time.LocalDate.of(
                    Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(4, 6)),
                    Integer.parseInt(date.substring(6, 8)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Today as 20261027, or "" when the TV's clock is not set to a believable date. */
    static String today() {
        java.time.LocalDate now = java.time.LocalDate.now();
        if (now.getYear() < 2000 || now.getYear() > 2100) return "";
        return String.format(java.util.Locale.ROOT, "%04d%02d%02d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth());
    }

    /**
     * Fetches the feed and the days it names, writes the new ones, then drops everything past {@link #KEEP}.
     *
     * Throws when the feed itself cannot be read, and records the failure so the next attempt is spaced out: an
     * address that stops working must not put the TV on the network every time the screensaver starts. A single
     * picture that fails to download is only logged, and the rest still happen.
     *
     * Returns how many pictures are on the TV afterwards.
     */
    static int refresh(Context context) throws Exception {
        File dir = dir(context);
        if (dir == null) throw new Exception("no external files dir");
        List<BingDay> days;
        try {
            // feed.txt beside the pictures, when it holds an address, so a moved endpoint can be fixed on the TV
            // without a new APK; the built-in address otherwise.
            String feed = get(BingDay.feed(dir), 64 * 1024);
            if (feed == null || feed.trim().isEmpty()) throw new Exception("no feed body");
            days = BingDay.parse(feed);
            if (days.isEmpty()) throw new Exception("empty feed");
        } catch (Exception e) {
            noteFailure(context);
            throw e;
        }

        List<BingDay> wanted = days.subList(0, Math.min(KEEP, days.size()));
        // Captions are merged rather than rebuilt: a picture kept from an earlier run (a short feed, or one
        // whose download failed today) must not lose the line that names it.
        Map<String, String> captions = readCaptions(dir);
        int before = captions.size();
        int landed = 0;
        for (BingDay day : wanted) {
            File target = new File(dir, day.fileName());
            if (target.isFile() && target.length() >= MIN_BYTES && !looksTruncated(target)) {
                captions.put(day.date, day.caption());
                landed++;
                continue;
            }
            try {
                byte[] jpeg = getBytes(day.url, 12 * 1024 * 1024);
                if (jpeg != null && jpeg.length >= MIN_BYTES && isJpeg(jpeg)) {
                    writeAtomic(target, jpeg);
                    captions.put(day.date, day.caption());
                    landed++;
                }
            } catch (Exception e) {
                // One bad day must not lose the other six, and must not delete what is already on the TV.
                Log.w(TAG, "could not fetch " + day.date + ": " + e.getMessage());
            }
        }
        // Written whenever the file gained anything, not only when a picture was downloaded: an install that
        // already had pictures under it (an update, or a cache filled before captions existed) learns the names
        // from the feed even though every picture was already on the TV.
        if (captions.size() > before) writeCaptions(dir, captions);
        // Prune only when the feed's own days are actually on the TV. If Bing moved the picture addresses (every
        // day in the feed is now a 404) nothing landed, and pruning against those names would delete the pictures
        // the owner can still watch, leaving a blank screensaver. Nothing is lost by waiting: the next attempt
        // tries again, and the pictures already downloaded stay.
        if (landed == 0) {
            // The feed parsed but not one picture came down from it: back off like a failed feed, so a moved
            // picture host does not put the TV on the network on every start either.
            noteFailure(context);
            return pictures(context).size();
        }
        prune(dir, wanted);
        int onTv = pictures(context).size();
        // A week that is short of pictures is not a finished day: the missing ones are fetched on the next
        // attempt rather than waited for until tomorrow, so a single 404 does not cost a whole day of pictures.
        if (onTv < KEEP) noteFailure(context);
        else noteSuccess(context);
        return onTv;
    }

    /**
     * A JPEG that stops before its end-of-image marker. A download cut off by standby or a dropped connection
     * can be larger than {@link #MIN_BYTES} yet still unreadable, and one such file would otherwise stay in the
     * list for good.
     */
    private static boolean looksTruncated(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long length = raf.length();
            if (length < 4) return true;
            raf.seek(length - 2);
            return !(raf.readByte() == (byte) 0xFF && raf.readByte() == (byte) 0xD9);
        } catch (Exception e) {
            return true;   // Unreadable: fetch it again.
        }
    }

    /** The caption for a picture file, from captions.txt; empty when it has none. */
    static String caption(Context context, File picture) {
        return readCaptions(dir(context)).getOrDefault(picture.getName().replace(".jpg", ""), "");
    }

    /** date -> "title — place", from captions.txt; empty when the file is missing or unreadable. */
    static Map<String, String> readCaptions(File dir) {
        Map<String, String> captions = new HashMap<>();
        File file = dir == null ? null : new File(dir, CAPTIONS);
        if (file == null || !file.isFile() || file.length() > 64 * 1024) return captions;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) captions.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
            }
        } catch (Exception ignored) {
            // No captions: the pictures still show.
        }
        return captions;
    }

    /**
     * Deletes pictures that are neither in the feed nor among the newest {@link #KEEP} on the TV, so the folder
     * stays at seven.
     *
     * A short feed must not empty the cache: when Bing answers with only a few days (a partly broken response,
     * or a market with less history) the pictures already on the TV are the only thing the owner can watch, so
     * those are kept and only the overflow past seven is removed.
     */
    private static void prune(File dir, List<BingDay> wanted) {
        List<String> keep = new ArrayList<>();
        for (BingDay day : wanted) keep.add(day.fileName());
        // Fill the remaining places with what is already on the TV, newest first, so a short feed keeps the
        // older pictures instead of deleting them.
        for (String name : newest(names(dir))) {
            if (keep.size() >= KEEP) break;
            if (!keep.contains(name)) keep.add(name);
        }
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if (name.matches(NAME) && !keep.contains(name)) {
                if (!entry.delete()) Log.w(TAG, "could not remove " + name);
            }
        }
    }

    /** The cached picture file names, newest first. */
    private static List<String> names(File dir) {
        List<String> names = new ArrayList<>();
        File[] entries = dir == null ? null : dir.listFiles();
        if (entries == null) return names;
        for (File entry : entries) {
            String name = entry.getName();
            if (entry.isFile() && name.matches(NAME) && entry.length() >= MIN_BYTES) names.add(name);
        }
        names.sort(Comparator.reverseOrder());
        return names;
    }

    /**
     * Writes the whole caption file, one line per day.
     *
     * The settings screen's Refresh now and the screensaver's own refresh can be in flight together, so the
     * write is serialised: two writers racing would leave the last one's view only, dropping a line the other
     * had just learned.
     */
    private static void writeCaptions(File dir, Map<String, String> captions) {
        List<String> lines = new ArrayList<>();
        for (String date : captions.keySet()) lines.add(date + "\t" + captions.get(date));
        lines.sort(Comparator.reverseOrder());   // newest first, as the folder is
        synchronized (CAPTIONS_LOCK) {
            try {
                writeAtomic(new File(dir, CAPTIONS), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "could not write captions: " + e.getMessage());
            }
        }
    }

    /**
     * Writes through a temporary file and renames it into place, so a download cut off by standby never leaves a
     * half a picture under the real name. The temporary name has no .jpg extension, so a stray one is never
     * listed as a picture.
     *
     * The temporary name carries the thread's id: the settings screen's Refresh now and the screensaver's own
     * refresh can run at the same time, and a shared temporary name would let them interleave their bytes into
     * one broken file that then renames over a good picture.
     */
    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File temp = new File(target.getPath() + "." + Thread.currentThread().getId() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
            // Flushed to the card before the rename, so a picture is either whole or absent.
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new Exception("could not rename " + temp.getName());
        }
    }

    /** JPEGs start FF D8 FF; anything else saved over a good file would break the slideshow. */
    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    /** A small text response, or the exception that stopped it. */
    private static String get(String address, int max) throws Exception {
        byte[] bytes = getBytes(address, max);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** The response body, or null when it is missing, empty or larger than {@code max}. */
    private static byte[] getBytes(String address, int max) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) BingWallpaper/1.0");
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = connection.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0, n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > max) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The newest {@link #KEEP} of the given names, which is what the cache leaves on the TV. */
    static List<String> newest(List<String> names) {
        String[] sorted = names.toArray(new String[0]);
        Arrays.sort(sorted, Comparator.reverseOrder());
        return new ArrayList<>(Arrays.asList(sorted).subList(0, Math.min(KEEP, sorted.length)));
    }

    /**
     * Removes a temporary file a killed download left behind.
     *
     * Only ones that have been sitting untouched for a while: the settings screen's Refresh now can already be
     * writing one when the screensaver starts, and deleting that file would strand the write it belongs to.
     */
    static void cleanTemporaries(Context context) {
        File dir = dir(context);
        File[] entries = dir == null ? null : dir.listFiles();
        if (entries == null) return;
        long old = System.currentTimeMillis() - STALE_TEMP_MS;
        for (File entry : entries) {
            if (entry.getName().endsWith(".part") && entry.lastModified() < old) entry.delete();
        }
    }
}
