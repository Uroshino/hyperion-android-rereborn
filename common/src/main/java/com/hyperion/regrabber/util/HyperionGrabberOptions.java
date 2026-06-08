package com.hyperion.regrabber.common.util;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class HyperionGrabberOptions {
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionGrabberOptions";

    // Upper bound for a single capture dimension. The capture is an integer downscale of the screen
    // (see computeCaptureSize); this clamp is only a safety net against absurd values that would make
    // the VirtualDisplay/ImageReader allocations and per-frame copy too expensive.
    private static final int MAX_OUTPUT_DIMENSION = 480;

    private final int OUTPUT_WIDTH;  // target capture width  (horizontal LEDs * multiplier)
    private final int OUTPUT_HEIGHT; // target capture height (vertical LEDs   * multiplier)
    private final int FRAME_RATE;
    private final boolean USE_AVERAGE_COLOR;
    private final int BLACK_THRESHOLD = 5; // The limit each RGB value must be under to be considered a black pixel [0-255]
    private final int BLACK_HOLD_MS; // how long a black screen is sent before the last colour is held instead

    public HyperionGrabberOptions(int horizontalLED, int verticalLED, int frameRate, boolean useAvgColor) {
        this(horizontalLED, verticalLED, 1, frameRate, useAvgColor, 2000);
    }

    public HyperionGrabberOptions(int horizontalLED, int verticalLED, int multiplier, int frameRate,
                                  boolean useAvgColor, int blackHoldMs) {
        BLACK_HOLD_MS = Math.max(0, blackHoldMs);

        // Target capture grid: horizontalLED x verticalLED cells, refined by the multiplier (x2 =
        // twice the cells per axis). This is the DESIRED detail — computeCaptureSize() then picks the
        // closest integer downscale of the actual screen, which preserves the screen's exact aspect
        // ratio (so the VirtualDisplay never letterboxes garbage into the edges the border LEDs
        // sample). Hyperion maps the resulting image onto the user's actual LED layout.
        final int mult = Math.max(1, multiplier);
        OUTPUT_WIDTH = sanitizeDimension(horizontalLED * mult);
        OUTPUT_HEIGHT = sanitizeDimension(verticalLED * mult);
        FRAME_RATE = frameRate;
        USE_AVERAGE_COLOR = useAvgColor;

        if (DEBUG) {
            Log.d(TAG, "Horizontal LED Count: " + horizontalLED);
            Log.d(TAG, "Vertical LED Count: " + verticalLED);
            Log.d(TAG, "Multiplier: " + mult);
            Log.d(TAG, "Output image: " + OUTPUT_WIDTH + "x" + OUTPUT_HEIGHT);
        }
    }

    private static int sanitizeDimension(int value) {
        int clamped = Math.max(2, Math.min(value, MAX_OUTPUT_DIMENSION));
        return clamped & ~1; // force even
    }

    public int getFrameRate() { return FRAME_RATE; }

    public boolean useAverageColor() { return USE_AVERAGE_COLOR; }

    /** @return target capture width (horizontal LED count * multiplier); the desired detail, not the literal size. */
    public int getOutputWidth() { return OUTPUT_WIDTH; }

    /** @return target capture height (vertical LED count * multiplier); the desired detail, not the literal size. */
    public int getOutputHeight() { return OUTPUT_HEIGHT; }

    /**
     * Picks the capture dimensions for the given screen size. The result is an integer downscale of
     * the screen ({@code screenWidth/k × screenHeight/k}), so it preserves the screen's EXACT aspect
     * ratio — the VirtualDisplay then fills the surface edge-to-edge with no letterbox/pillarbox bars
     * (those bars leak stale/garbage data into the captured edges that drive the border LEDs). Among
     * the divisors that keep both dimensions even and within {@link #MAX_OUTPUT_DIMENSION}, the one
     * whose resolution is closest to the configured detail target (LED counts × multiplier) wins.
     *
     * @return {@code {width, height}}, both even, preserving the screen aspect ratio.
     */
    public int[] computeCaptureSize(int screenWidth, int screenHeight) {
        int bestW = 0, bestH = 0, bestDiff = Integer.MAX_VALUE;
        for (int k : getCommonDivisors(screenWidth, screenHeight)) {
            final int w = screenWidth / k;
            final int h = screenHeight / k;
            if ((w & 1) != 0 || (h & 1) != 0) continue;          // keep even (no re-rounding -> no skew)
            if (w > MAX_OUTPUT_DIMENSION || h > MAX_OUTPUT_DIMENSION) continue;
            final int diff = Math.abs(w - OUTPUT_WIDTH) + Math.abs(h - OUTPUT_HEIGHT);
            if (diff < bestDiff) { bestDiff = diff; bestW = w; bestH = h; }
        }

        if (bestW >= 2 && bestH >= 2) {
            if (DEBUG) Log.d(TAG, "Capture " + bestW + "x" + bestH + " (target " + OUTPUT_WIDTH + "x" + OUTPUT_HEIGHT + ")");
            return new int[]{bestW, bestH};
        }

        // Fallback (no suitable even integer divisor, e.g. odd/prime screen dimensions): scale to fit
        // the target box, preserving aspect as closely as even rounding allows.
        final double scale = Math.min((double) OUTPUT_WIDTH / screenWidth, (double) OUTPUT_HEIGHT / screenHeight);
        final int w = Math.max(2, (int) Math.round(screenWidth * scale)) & ~1;
        final int h = Math.max(2, (int) Math.round(screenHeight * scale)) & ~1;
        return new int[]{w, h};
    }

    /** Common divisors of {@code num1} and {@code num2}, ascending. */
    private static List<Integer> getCommonDivisors(int num1, int num2) {
        final List<Integer> list = new ArrayList<>();
        final int min = Math.min(num1, num2);
        for (int i = 1; i <= min; i++) {
            if (num1 % i == 0 && num2 % i == 0) list.add(i);
        }
        return list;
    }

    public int getBlackThreshold() { return BLACK_THRESHOLD; }

    /** @return how long (ms) an all-black screen is sent before the last non-black frame is held. */
    public int getBlackHoldMs() { return BLACK_HOLD_MS; }
}
