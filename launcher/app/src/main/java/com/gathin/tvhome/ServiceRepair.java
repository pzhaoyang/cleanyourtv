package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Android takes an app's accessibility services off the enabled list when the app is force-stopped (Settings, a
 * cleaner app, or am force-stop), and the remote's Home, Input and app buttons then stop working until the helper
 * is turned on again over ADB. With restore_key_services on, a running helper records itself here and drops the
 * record when Android unbinds it (the user turned it off); a force stop kills the process before that, so the
 * record stays. Home starting again puts recorded helpers back on the list, when the installer granted
 * WRITE_SECURE_SETTINGS. With the flag off nothing is recorded and nothing is restored.
 */
final class ServiceRepair {
    private static final String PREFS = "accessibility_helpers";

    private ServiceRepair() {
    }

    static void connected(Context context, Class<?> service) {
        prefs(context).edit().putBoolean(service.getName(), true).apply();
    }

    static void disconnected(Context context, Class<?> service) {
        prefs(context).edit().remove(service.getName()).apply();
    }

    /** A force stop clears the enabled list a moment later, asynchronously; wait before reading it. */
    static final long SETTLE_MS = 1500L;

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        final List<ComponentName> recorded = new ArrayList<>();
        for (String service : prefs(context).getAll().keySet()) {
            recorded.add(new ComponentName(context.getPackageName(), service));
        }
        if (recorded.isEmpty()) return;
        final ContentResolver cr = context.getContentResolver();
        // A killed helper also lands on Android's "Crashed services" list (dumpsys accessibility), and a crashed
        // service is never bound again, so putting it back on the list alone binds nothing and the switch falls
        // back to 0 (Hisense songshan, Android 11, whose standby kills third-party apps). Android drops the crashed
        // mark when it sees the helper leave the enabled list, so add, remove, add. Off the main thread, so Home
        // does not wait for the pauses. Each write first checks the list still reads what the previous step left,
        // and the cycle stops otherwise: a service turned on or off during a pause stays that way instead of being
        // overwritten by a snapshot.
        new Thread(() -> {
            try {
                // Xiaomi MiTV force stops this app about a minute after boot and starts it again as Home within
                // the same second. Reading the list right then shows the entries the system is about to remove,
                // so the repair would find nothing missing and return; the clear lands after that and the helper
                // stays off. Read it once the clear has landed instead.
                Thread.sleep(SETTLE_MS);
                String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                // Not force stopped: the list still has every recorded helper and the switch is on. Nothing to do.
                if (listed(now, recorded) && Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1)
                    return;
                String without = others(now, recorded);
                StringBuilder updated = new StringBuilder(without);
                for (ComponentName name : recorded) {
                    if (updated.length() > 0) updated.append(':');
                    updated.append(name.flattenToShortString());
                }
                String with = updated.toString();
                if (!replaceList(cr, now, with)) return;
                Thread.sleep(1000);
                if (!replaceList(cr, with, without)) return;
                Thread.sleep(1000);
                if (!replaceList(cr, without, with)) return;
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (RuntimeException | InterruptedException ignored) {
                // The setting is the system's; a refusal leaves the helper off, as before.
            }
        }, "ServiceRepair").start();
    }

    /** Whether {@code list} names every one of {@code names}; an absent list names nothing. */
    static boolean listed(String list, List<ComponentName> names) {
        if (list == null || list.isEmpty()) return names.isEmpty();
        List<ComponentName> enabled = new ArrayList<>();
        for (String entry : list.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (name != null) enabled.add(name);
        }
        return enabled.containsAll(names);
    }

    /** {@code list} without the recorded helpers, ready to be written: the add, remove, add cycle needs it. */
    static String others(String list, List<ComponentName> recorded) {
        StringBuilder others = new StringBuilder();
        if (list == null) return "";
        for (String entry : list.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(entry);
            if (name == null || recorded.contains(name)) continue;
            if (others.length() > 0) others.append(':');
            others.append(name.flattenToShortString());
        }
        return others.toString();
    }

    /** Writes the enabled list only while it still reads {@code expected}; an absent list counts as empty. */
    private static boolean replaceList(ContentResolver cr, String expected, String updated) {
        String now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return Objects.equals(now == null ? "" : now, expected == null ? "" : expected)
                && Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
