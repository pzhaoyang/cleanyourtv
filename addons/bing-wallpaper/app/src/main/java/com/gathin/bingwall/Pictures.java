package com.gathin.bingwall;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * How big a picture is decoded, so a 4K wallpaper does not cost a 1 GB TV its memory. The numbers match what the
 * launcher and photo-screensaver use, so one rule covers every screen in this repo.
 */
final class Pictures {

    /** 4K UHD and a little more; anything bigger is not decoded at all. */
    static final long MAX_PIXELS = 8_400_000L;

    private Pictures() {}

    /** True when a picture of this size is small enough to decode on a TV. */
    static boolean accepted(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    /** The largest power-of-two sample size that still covers the screen in both directions. */
    static int sampleSize(int width, int height, int screenW, int screenH) {
        int sample = 1;
        while (width / (sample * 2) >= screenW && height / (sample * 2) >= screenH) sample *= 2;
        return sample;
    }

    /** The picture's {width, height} from its header alone; null when it is not a readable image. */
    static int[] size(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        return new int[]{bounds.outWidth, bounds.outHeight};
    }

    /**
     * Decodes the picture sampled down to roughly the screen, in RGB_565: 2 bytes a pixel rather than 4, which is
     * the difference between fitting and not on a 1 GB TV. Null when the file is not a usable picture.
     */
    static Bitmap decode(File file, int screenW, int screenH) {
        try {
            int[] size = size(file);
            if (size == null) return null;
            int sample = sampleSize(size[0], size[1], screenW, screenH);
            if (!accepted(size[0] / sample, size[1] / sample)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }
}
