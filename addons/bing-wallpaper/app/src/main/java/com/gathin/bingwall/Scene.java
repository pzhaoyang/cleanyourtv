package com.gathin.bingwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the screensaver shows: Bing's last few days full screen, held still for the configured time and then
 * crossfading to the next. Two screen-sized bitmaps at most, in RGB_565.
 *
 * The walk through the week is remembered by <b>name</b>, not by position: {@link Rotation} explains why, and
 * what happens when the picture that was on screen has been deleted since (it starts again at the newest).
 *
 * Two executors, deliberately: fetching a week of pictures takes seconds to tens of seconds, and decoding the
 * next one must not queue behind it, or the screen would sit black on the first run and stop advancing during
 * every later refresh.
 *
 * Both hosts ({@link BingDream} and {@link WallActivity}) call start and stop.
 */
final class Scene {

    private static final long FADE_MS = 1200L;
    /** How far the caption may wander so it does not burn in. */
    private static final int NUDGE_DP = 16;
    /** A picture that will not decode is skipped after this long. */
    private static final long SKIP_MS = 1500L;
    /** Memory pressure comes in bursts; the picture is decoded again only this long after the last trim. */
    private static final long TRIM_SETTLE_MS = 4000L;
    /** How often the retry tick runs while the screen is up; maybeRefresh() still applies the backoff. */
    private static final long RETRY_MS = 60_000L;

    private static final String PREFS = "rotation";
    private static final String LAST_SHOWN = "last_shown";

    final FrameLayout root;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView front;
    private final ImageView back;
    private final TextView caption;
    private final TextView status;
    private final int width;
    private final int height;
    private final SharedPreferences preferences;

    private Options options;
    private List<File> pictures = new ArrayList<>();
    /** date -> "title — place", read once per run so switching a picture never touches the disk on the UI thread. */
    private Map<String, String> captions = new java.util.HashMap<>();
    /** Name of the picture on screen, which is what is remembered between runs. */
    private String showing = "";
    private Bitmap bitmap;
    /** Decodes only; a fetch never blocks it. */
    private ExecutorService decoder;
    /** Fetches only; may run for tens of seconds without holding up a picture change. */
    private ExecutorService fetcher;
    private boolean running;
    private boolean fetching;
    private boolean frontIsFront = true;
    /** Pictures in a row that would not decode, so a folder of bad files stops instead of spinning. */
    private int failed;
    /** When trim() last ran, so a burst of memory callbacks does not flicker the screen. */
    private long lastTrim;

    private final Runnable advance = this::next;
    /** Re-asks whether a fetch is due, so a backoff that expired while the screen was up is picked up. */
    private final Runnable retry = this::retryTick;

    Scene(Context context) {
        this.context = context;
        options = Options.load(context);
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        width = context.getResources().getDisplayMetrics().widthPixels;
        height = context.getResources().getDisplayMetrics().heightPixels;

        root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        back = imageView();
        front = imageView();
        root.addView(back, full());
        root.addView(front, full());

        caption = new TextView(context);
        caption.setTextColor(0xF2FFFFFF);
        caption.setTextSize(20f);
        caption.setShadowLayer(6f, 0f, 2f, 0xB3000000);
        caption.setMaxLines(2);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(dp(48), dp(10), dp(48), dp(10));
        caption.setVisibility(options.caption ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        captionParams.bottomMargin = dp(48);
        root.addView(caption, captionParams);

        status = new TextView(context);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(16f);
        status.setShadowLayer(6f, 0f, 2f, 0xB3000000);
        status.setPadding(dp(32), dp(12), dp(32), dp(12));
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private ImageView imageView() {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setAlpha(0f);
        return view;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    void start() {
        if (running) return;
        running = true;
        // Settings may have changed while this Scene was paused in the card, so they are read again.
        options = Options.load(context);
        caption.setVisibility(options.caption ? View.VISIBLE : View.GONE);
        // Fresh executors per run: a pool a previous stop() shut down would reject every later task in silence.
        decoder = Executors.newSingleThreadExecutor();
        fetcher = Executors.newSingleThreadExecutor();
        failed = 0;
        // Pictures can pile up without a fetch (hand-copied files, a run killed between download and prune), and
        // a recent leftover would also make the cache look fresh, so the folder is trimmed on every start.
        BingCache.trimSurplus(context);
        pictures = BingCache.pictures(context);
        captions = BingCache.readCaptions(BingCache.dir(context));
        if (pictures.isEmpty()) {
            showStatus(context.getString(R.string.loading));
        } else {
            // Continue the walk where the last run left it: this is what makes all seven days reachable on a TV
            // whose screensaver is up for only a few minutes at a time.
            int at = Rotation.next(names(pictures), preferences.getString(LAST_SHOWN, null));
            if (at >= 0) show(at, false);
        }
        maybeRefresh();
        // A TV can sit on this screen for hours. Nothing else would ever retry a feed that failed while the
        // screensaver was up, so the retry is scheduled here: once a minute at most, and maybeRefresh() itself
        // decides whether the backoff has expired.
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_MS);
    }

    /** Wakes the refresh path up again, so a backoff that has expired is noticed without a restart. */
    private void retryTick() {
        if (!running) return;
        maybeRefresh();
        handler.postDelayed(retry, RETRY_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (decoder != null) decoder.shutdownNow();
        if (fetcher != null) fetcher.shutdownNow();
        decoder = null;
        fetcher = null;
        fetching = false;
        release();
    }

    /**
     * Drops the bitmaps under memory pressure, keeping the picture that was showing so it can be decoded again
     * and the walk does not jump forward. Reloading waits {@link #TRIM_SETTLE_MS} after the last call, so a burst
     * of callbacks cannot recycle and reload the same picture repeatedly and make the screen flicker.
     */
    void trim() {
        handler.removeCallbacksAndMessages(null);
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        // showing is deliberately kept: it names the picture to bring back. Clearing it would make the reload
        // ask Rotation for the picture after the remembered one, skipping whatever was on screen.
        lastTrim = System.currentTimeMillis();
        if (running && !pictures.isEmpty()) {
            handler.postDelayed(this::restoreAfterTrim, TRIM_SETTLE_MS);
        }
        // removeCallbacksAndMessages(null) above also dropped the retry tick; put it back, or a failed feed would
        // never be retried for the rest of this run.
        if (running) {
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, RETRY_MS);
        }
    }

    private void restoreAfterTrim() {
        if (!running || pictures.isEmpty()) return;
        // A later trim moved the deadline; that call has posted its own reload.
        if (System.currentTimeMillis() - lastTrim < TRIM_SETTLE_MS) return;
        if (bitmap != null) return;   // already back
        int at = showing.isEmpty() ? 0 : indexOf(showing);
        if (at < 0) at = 0;
        show(at, false);
    }

    /** Where a picture name sits in the current list, or -1 when it is no longer on the TV. */
    private int indexOf(String name) {
        for (int i = 0; i < pictures.size(); i++) {
            if (pictures.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void release() {
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }

    private static List<String> names(List<File> files) {
        List<String> names = new ArrayList<>();
        for (File file : files) names.add(file.getName());
        return names;
    }

    /** Fetches when the week on the TV is old enough; pictures already showing stay up until it succeeds. */
    private void maybeRefresh() {
        if (fetching || !running) return;
        if (!BingCache.stale(context)) return;
        if (!Online.likely(context)) {
            // Nothing to show and no network: say so rather than wait out a connection timeout.
            if (pictures.isEmpty()) showStatus(context.getString(R.string.offline));
            return;
        }
        ExecutorService run = fetcher;
        if (run == null) return;
        fetching = true;
        // The strings are resolved here, on the UI thread, before the worker starts: Resources is not something
        // to lean on from a background thread on every TV.
        final String failedText = context.getString(R.string.refresh_failed);
        final String emptyText = context.getString(R.string.empty_body);
        try {
            run.execute(() -> {
                boolean reached;
                try {
                    // The count is not carried out of here: onFetched lists the folder itself, which is the one
                    // answer that also covers a picture another refresh wrote in the meantime.
                    BingCache.refresh(context);
                    reached = true;
                } catch (Exception e) {
                    // Offline, or Bing did not answer: keep what is on the TV.
                    reached = false;
                }
                final boolean ok = reached;
                handler.post(() -> onFetched(ok, ok ? emptyText : failedText));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            fetching = false;
        }
    }

    /**
     * @param reached  the feed answered, whether or not it named any picture
     * @param whenNone what to say when the folder is still empty
     */
    private void onFetched(boolean reached, String whenNone) {
        fetching = false;
        if (!running) return;
        List<File> fresh = BingCache.pictures(context);
        if (fresh.isEmpty()) {
            // A feed that answered but yielded nothing and one that could not be reached read differently.
            showStatus(whenNone);
            return;
        }
        boolean changed = !names(fresh).equals(names(pictures));
        boolean wasEmpty = pictures.isEmpty();
        pictures = fresh;
        captions = BingCache.readCaptions(BingCache.dir(context));
        hideStatus();
        // Only move on when there is something new to show, so a refresh mid-picture does not jump away from it.
        if (wasEmpty || changed) {
            int at = Rotation.next(names(fresh), preferences.getString(LAST_SHOWN, null));
            if (at >= 0 && !fresh.get(at).getName().equals(showing)) show(at, false);
        }
    }

    private void next() {
        if (!running || pictures.isEmpty()) return;
        int at = Rotation.next(names(pictures), showing);
        if (at >= 0) show(at, true);
    }

    /** Decodes picture {@code at} on the decode executor and fades it in, replacing the one before. */
    private void show(int at, boolean fade) {
        if (!running || at < 0 || at >= pictures.size()) return;
        ExecutorService run = decoder;
        if (run == null) return;
        final File file = pictures.get(at);
        final boolean crossfade = fade;
        try {
            run.execute(() -> {
                Bitmap decoded = Pictures.decode(file, width, height);
                handler.post(() -> attach(file, decoded, crossfade));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Stopped meanwhile.
        }
    }

    private void attach(File file, Bitmap decoded, boolean crossfade) {
        if (!running) {
            if (decoded != null) decoded.recycle();
            return;
        }
        if (decoded == null) {
            // A file that will not decode is remembered as skipped and stepped over. Counting the run keeps a
            // folder of nothing but bad files from walking in circles: after one full pass it stops and says so,
            // instead of retrying the same picture every two seconds for ever.
            showing = file.getName();
            if (++failed >= Math.max(1, pictures.size())) {
                failed = 0;
                showStatus(context.getString(R.string.empty_body));
                return;
            }
            handler.postDelayed(advance, SKIP_MS);
            return;
        }
        failed = 0;
        ImageView incoming = frontIsFront ? back : front;
        ImageView outgoing = frontIsFront ? front : back;
        incoming.setImageBitmap(decoded);
        incoming.setAlpha(crossfade ? 0f : 1f);
        incoming.animate().alpha(1f).setDuration(crossfade ? FADE_MS : 0L).start();
        if (crossfade) outgoing.animate().alpha(0f).setDuration(FADE_MS).start();
        frontIsFront = !frontIsFront;

        if (bitmap != null && bitmap != decoded) {
            final Bitmap old = bitmap;
            // The outgoing view still holds it for the length of the fade; release it after that.
            handler.postDelayed(() -> { if (bitmap != old && !old.isRecycled()) old.recycle(); }, FADE_MS + 200);
        }
        bitmap = decoded;
        showing = file.getName();
        // Remembered at once: a TV switched off mid-week picks up here next time. apply() writes it off the UI
        // thread, which is why this does not need to wait.
        preferences.edit().putString(LAST_SHOWN, showing).apply();

        caption.setText(options.caption ? captions.getOrDefault(showing.replace(".jpg", ""), "") : "");
        caption.setVisibility(options.caption && caption.getText().length() > 0 ? View.VISIBLE : View.GONE);
        nudgeCaption();
        handler.removeCallbacks(advance);
        handler.postDelayed(advance, Options.seconds(options.seconds) * 1000L);
    }

    private void nudgeCaption() {
        if (caption.getVisibility() != View.VISIBLE) return;
        float shift = ((float) Math.random() * 2f - 1f) * dp(NUDGE_DP);
        caption.animate().translationX(shift).setDuration(1200L).start();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        caption.setVisibility(View.GONE);
        handler.removeCallbacks(advance);
    }

    /**
     * Takes the message away and puts the caption back the way the picture on screen wants it.
     *
     * showStatus hides the caption while a message is up; leaving it hidden here would keep the caption off the
     * picture that is still showing, for as long as that picture lasts.
     */
    private void hideStatus() {
        status.setVisibility(View.GONE);
        boolean wanted = options.caption && caption.getText().length() > 0 && showing.length() > 0;
        caption.setVisibility(wanted ? View.VISIBLE : View.GONE);
    }
}
