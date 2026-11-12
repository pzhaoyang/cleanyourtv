package com.gathin.tvhome;

import android.content.Context;

/**
 * Who owns the idle screen: this launcher, or the TV's own screensaver.
 *
 * <p>MiTV's power manager broadcasts {@code com.duokan.intent.action.show.screensaver} when the idle timeout expires,
 * and it addresses that broadcast at the TV's screensaver package as well as at whoever else listens. That package
 * always answers it, so the choice here cannot be expressed by switching it off from inside an app (that needs the
 * signature permission CHANGE_COMPONENT_ENABLED_STATE). What the launcher can control is its own answer: it takes the
 * broadcast too, and starts its screensaver component a moment later so that it comes up over the TV's.
 *
 * <p>The choice is the launcher's own preference, so it needs no permission and survives without a settings table.
 */
final class SystemScreensaver {

    private static final String PREFS = "screensaver_state";
    private static final String OWNER_KEY = "owner";
    /** True when this launcher owns the idle screen; the TV's own screensaver is the fallback when unset. */
    private static final boolean OWNER_DEFAULT = true;

    private SystemScreensaver() {
    }

    /** Whether the idle screen is currently this launcher's rather than the TV's own. */
    static boolean launcherOwns(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(OWNER_KEY, OWNER_DEFAULT);
    }

    /** Hands the idle screen to the launcher ({@code own} true) or back to the TV's own screensaver. */
    static boolean setOn(Context ctx, boolean own) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(OWNER_KEY, own).commit();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
