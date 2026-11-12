package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The only code of this launcher that the system runs at a cold start: Xiaomi MiTV firmware, at every power-on,
 * opens com.mitv.tvhome/.NewMyAppActivity through its private HOME_MY_APP action instead of a HOME intent, so this
 * launcher never comes to the front and the TV sits on the stock home until the remote's Home button is pressed.
 * A soft reboot (adb reboot) does not do this, which is why it only shows on a real power cycle.
 *
 * At boot this receiver writes what happened to BootLog, puts the remote's accessibility helpers back on the
 * enabled list (ServiceRepair, which the firmware's force stop of this app takes them off), and starts Home a few
 * times while the TV comes up, so the power-on ends on this launcher. Starting Home while it is already in front
 * costs nothing, and the last attempt is early enough not to fight anything the owner opened afterwards; the
 * firmware's own force stop about a minute after boot is covered by HomeKeyService, which probes again when
 * Android restarts this app as Home.
 */
public class BootReceiver extends BroadcastReceiver {
    /**
     * When to start Home after power-on, in milliseconds of uptime. The firmware opens its own launcher at some
     * point during the power-on, and force stops this app about a minute in, so the attempts run past that: the
     * ones after a force stop are dropped with the process' alarms, which is itself recorded in BootLog.
     */
    private static final long[] STARTS_MS = {3000L, 10_000L, 25_000L, 45_000L, 70_000L, 100_000L};

    private static final String ACTION_REASSERT = "com.gathin.tvhome.REASSERT";
    private static final String EXTRA_STEP = "step";
    private static final String EXTRA_AT_BOOT = "at_boot";
    private static final int REQUEST_REASSERT = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_REASSERT.equals(action)) {
            int step = intent.getIntExtra(EXTRA_STEP, 0);
            // Carried in the alarm: the process may have been started again since the boot broadcast.
            long atBoot = intent.getLongExtra(EXTRA_AT_BOOT, SystemClock.elapsedRealtime());
            BootLog.write(context, "start " + step + ": Home, resolved=" + BootLog.resolvedHome(context));
            startHome(context);
            schedule(context, step + 1, atBoot);
            return;
        }
        boolean locked = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        BootLog.write(context, "boot: action=" + action
                + " resolved=" + BootLog.resolvedHome(context)
                + " accEnabled=" + accEnabled(context));
        // The note about the helpers sits in credential-protected storage, so it reads only after the unlock.
        if (locked) return;
        // The firmware's force stop took the helpers off the enabled list; put them back before anything else.
        ServiceRepair.restore(context);
        startHome(context);
        schedule(context, 1, SystemClock.elapsedRealtime());
    }

    /** Schedules the start at {@code STARTS_MS[step]}, counted from the boot broadcast at {@code atBoot}. */
    private static void schedule(Context context, int step, long atBoot) {
        if (step < 0 || step >= STARTS_MS.length) return;
        long delay = STARTS_MS[step] - SystemClock.elapsedRealtime() + atBoot;
        if (delay < 0) return;   // The boot already got that far: later steps would land even later.
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent later = PendingIntent.getBroadcast(context, REQUEST_REASSERT + step,
                new Intent(context, BootReceiver.class).setAction(ACTION_REASSERT)
                        .putExtra(EXTRA_STEP, step).putExtra(EXTRA_AT_BOOT, atBoot),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay, later);
        } catch (RuntimeException ignored) {
            // Not worth failing the boot over: the accessibility redirect still covers the stock home window.
        }
    }

    private static void startHome(Context context) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(open);
        } catch (RuntimeException e) {
            BootLog.write(context, "start refused: " + e);
        }
    }

    private static int accEnabled(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, -1);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }
}
