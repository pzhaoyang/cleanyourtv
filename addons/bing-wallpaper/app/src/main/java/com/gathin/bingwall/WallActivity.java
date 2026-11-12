package com.gathin.bingwall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The module card's screen: the same slideshow, started with OK and left with Back. Holding OK (or pressing the
 * remote's Menu key) opens the settings instead, so the card can be used to judge and tune what the screensaver
 * will do.
 *
 * <p>Started with the {@link #EXTRA_SCREENSAVER} extra, it is the idle screen instead of the card (firmware without
 * the AOSP dreams service has no DreamService to do it), and then any key leaves it, as a screensaver should.
 */
public final class WallActivity extends Activity {

    /** Set by the launcher that opens this as the screensaver rather than as the add-on's card. */
    static final String EXTRA_SCREENSAVER = "screensaver";

    private Scene scene;
    private boolean screensaver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        screensaver = getIntent().getBooleanExtra(EXTRA_SCREENSAVER, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scene = new Scene(this);
        setContentView(scene.root);
        BingCache.cleanTemporaries(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // start() re-reads the settings, so a change made in them applies as soon as this screen comes back.
        if (scene != null) scene.start();
    }

    @Override
    protected void onPause() {
        if (scene != null) scene.stop();
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (scene != null && level >= TRIM_MEMORY_RUNNING_LOW) scene.trim();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (screensaver) {
            // Any key wakes the TV, and the TV was showing Home before this: leaving puts the viewer back there.
            finish();
            return true;
        }
        if (isLongPressTarget(keyCode)) {
            // Without startTracking() Android never delivers onKeyLongPress, so holding OK did nothing and the
            // settings were reachable only from the TV's screensaver menu.
            event.startTracking();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isLongPressTarget(keyCode)) {
            openSettings();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private static boolean isLongPressTarget(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void openSettings() {
        if (isFinishing()) return;
        startActivity(new Intent(this, SettingsActivity.class));
    }
}
