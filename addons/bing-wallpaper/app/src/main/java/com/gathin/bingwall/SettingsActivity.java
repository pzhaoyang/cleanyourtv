package com.gathin.bingwall;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's settings, reached from the TV's screensaver menu (xml/dream.xml) or by holding OK in the
 * card's slideshow: how long each picture stays, whether its title and place show, and a Refresh now button.
 */
public final class SettingsActivity extends Activity {

    private static final int[] SECONDS = {15, 30, 60, 120, 300};
    private static final int[] IDS = {R.id.sec15, R.id.sec30, R.id.sec60, R.id.sec120, R.id.sec300};

    private final TextView[] choices = new TextView[SECONDS.length];
    private TextView captionChoice;
    private TextView count;
    private int seconds;
    private boolean caption;
    /** A fetch is running; a second press is ignored until it answers. */
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        Options options = Options.load(this);
        seconds = options.seconds;
        caption = options.caption;
        count = findViewById(R.id.count);

        for (int i = 0; i < IDS.length; i++) {
            final int value = SECONDS[i];
            choices[i] = findViewById(IDS[i]);
            choices[i].setOnClickListener(v -> {
                if (seconds == value) return;
                seconds = value;
                save();
                refresh();
            });
        }
        captionChoice = findViewById(R.id.captionChoice);
        captionChoice.setOnClickListener(v -> {
            caption = !caption;
            save();
            refresh();
        });
        findViewById(R.id.refreshChoice).setOnClickListener(v -> refreshNow());
        refresh();
        choices[index()].requestFocus();
    }

    @Override
    protected void onPause() {
        // A fetch already handed to its executor finishes on its own; the run's callback checks whether this
        // activity is still here before touching it.
        refreshing = false;
        super.onPause();
    }

    private void save() {
        Options.save(this, seconds, caption);
    }

    private void refresh() {
        for (int i = 0; i < choices.length; i++) choices[i].setSelected(seconds == SECONDS[i]);
        captionChoice.setText(getString(R.string.option_caption) + ": "
                + getString(caption ? android.R.string.yes : android.R.string.no));
        String pictures = getString(R.string.picture_count, BingCache.pictures(this).size());
        int failures = BingCache.failures(this);
        // A feed that has been failing says so here, so a TV stuck on old pictures is explained rather than
        // looking broken.
        count.setText(failures > 0 ? pictures + " · " + getString(R.string.feed_failing, failures) : pictures);
    }

    private int index() {
        for (int i = 0; i < SECONDS.length; i++) if (SECONDS[i] == seconds) return i;
        return 2;   // 60 seconds, the default
    }

    /**
     * Fetches now, in the background, so a slow network never freezes the settings screen. Anything already on
     * the TV stays if the fetch fails.
     *
     * The executor is made per run and released in a finally block, so a second press works as well as the
     * first: keeping one executor field meant the flag was never cleared and every later press did nothing.
     */
    private void refreshNow() {
        if (refreshing) return;
        refreshing = true;
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show();
        // Both texts are resolved on the UI thread before the worker starts: Resources is not something to lean
        // on from a background thread on every TV.
        final String failed = getString(R.string.refresh_failed);
        final String template = getString(R.string.refreshed);
        ExecutorService run = Executors.newSingleThreadExecutor();
        try {
            run.execute(() -> {
                String message;
                try {
                    message = String.format(java.util.Locale.getDefault(), template, BingCache.refresh(this));
                } catch (Exception e) {
                    message = failed;
                }
                final String shown = message;
                runOnUiThread(() -> {
                    refreshing = false;
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
                    refresh();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The activity went away between the press and the hand-off.
            refreshing = false;
        } finally {
            run.shutdown();
        }
    }
}
