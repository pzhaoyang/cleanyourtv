package com.gathin.bingwall;

import android.content.ComponentCallbacks2;
import android.service.dreams.DreamService;

/**
 * The system screensaver: Bing's last few days of pictures, until any key wakes the TV back to where it was.
 *
 * It downloads by itself on first run; with no network it shows the pictures already on the TV.
 */
public class BingDream extends DreamService {

    private Scene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        scene = new Scene(this);
        setContentView(scene.root);
        BingCache.cleanTemporaries(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }

    /** A 1 GB TV under pressure: drop the decoded pictures rather than be killed with them held. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (scene != null && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) scene.trim();
    }
}
