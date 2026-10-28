package com.gathin.bingwall;

import android.content.Context;
import android.content.SharedPreferences;

/** What the screensaver shows and how, saved on the TV and editable in its own settings screen. */
final class Options {
    private static final String NAME = "options";
    private static final String SECONDS = "seconds";
    private static final String CAPTION = "caption";

    /** 60 seconds a picture, as asked for. */
    static final int DEFAULT_SECONDS = 60;

    final int seconds;
    /** Show Bing's title and place under the picture. Off by default: the picture is the point. */
    final boolean caption;

    private Options(int seconds, boolean caption) {
        this.seconds = seconds;
        this.caption = caption;
    }

    static Options load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return new Options(seconds(prefs.getInt(SECONDS, DEFAULT_SECONDS)), prefs.getBoolean(CAPTION, false));
    }

    static void save(Context context, int seconds, boolean caption) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
                .putInt(SECONDS, seconds(seconds)).putBoolean(CAPTION, caption).apply();
    }

    /** 5 to 3600 seconds; anything else falls back to the default rather than freezing the screen. */
    static int seconds(int value) {
        return value < 5 || value > 3600 ? DEFAULT_SECONDS : value;
    }
}
