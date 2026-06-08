package com.hyperion.regrabber.common.util;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import com.hyperion.regrabber.common.R;

/**
 * Builds the human-readable "Display … / LEDs … / Sent …" summary shown in the settings UIs, so the
 * user can see how their LED counts and the capture-detail factor turn into the resolution actually
 * streamed to Hyperion. The "sent" size is computed with the exact same {@link HyperionGrabberOptions}
 * logic the capture pipeline uses, so what the UI shows is what the grabber sends.
 */
public final class CaptureResolutionInfo {

    private CaptureResolutionInfo() {}

    /** @return the real (full) display size as {@code {width, height}} in pixels. */
    public static int[] displaySize(Context context) {
        final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final DisplayMetrics metrics = new DisplayMetrics();
        final Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    /** Builds the summary from the values currently stored in preferences. */
    public static String describe(Context context, Preferences prefs) {
        return describe(context,
                prefs.getInt(R.string.pref_key_x_led),
                prefs.getInt(R.string.pref_key_y_led),
                Math.max(1, prefs.getInt(R.string.pref_key_led_multiplier)));
    }

    /**
     * Builds the summary from explicit values (so the TV wizard can preview values that haven't been
     * saved to preferences yet).
     */
    public static String describe(Context context, int horizontal, int vertical, int factor) {
        final int[] screen = displaySize(context);
        final int f = Math.max(1, factor);
        // Frame rate / avg-color / black-hold don't affect the capture size, so any value is fine here.
        final HyperionGrabberOptions options =
                new HyperionGrabberOptions(horizontal, vertical, f, 30, false, 0);
        final int[] sent = options.computeCaptureSize(screen[0], screen[1]);
        return context.getString(R.string.resolution_info_format,
                screen[0], screen[1], horizontal, vertical, f, sent[0], sent[1]);
    }
}
