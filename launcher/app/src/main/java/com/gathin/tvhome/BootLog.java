package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Boot diagnostics: a line per boot event, kept on external storage so it outlives the reboot that logcat
 * does not. A TV that comes up on its stock launcher instead of Home cannot be inspected over adb when that
 * firmware also turns adb off on a cold boot, so the app writes down what it did and the file is read later.
 *
 * /sdcard/Android/data/<package>/files/boot.log, one line per event, capped at MAX_BYTES.
 */
final class BootLog {
    private static final String TAG = "TvHomeBoot";
    private static final String FILE = "boot.log";
    private static final long MAX_BYTES = 64 * 1024;

    private BootLog() {
    }

    static void write(Context context, String event) {
        Log.i(TAG, event);
        File dir;
        try {
            // Null before the unlock, when this runs from a receiver at a direct boot.
            dir = context.getExternalFilesDir(null);
        } catch (RuntimeException ignored) {
            return;
        }
        if (dir == null) return;
        StringBuilder line = new StringBuilder();
        line.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()))
                .append(" up=").append(SystemClock.elapsedRealtime() / 1000).append('s')
                .append(" boot=").append(bootCount(context))
                .append(' ').append(event)
                .append('\n');
        File file = new File(dir, FILE);
        if (file.length() > MAX_BYTES) {
            File old = new File(dir, FILE + ".old");
            if (old.exists()) old.delete();
            file.renameTo(old);
        }
        try (FileWriter out = new FileWriter(file, true)) {
            out.write(line.toString());
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics only.
        }
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /** The launcher that CATEGORY_HOME resolves to, so the log says who the system picked at boot. */
    static String resolvedHome(Context context) {
        try {
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = context.getPackageManager()
                    .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null ? "none" : ri.activityInfo.packageName + "/" + ri.activityInfo.name;
        } catch (RuntimeException ignored) {
            return "error";
        }
    }
}
