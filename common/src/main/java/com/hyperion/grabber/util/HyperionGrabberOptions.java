package com.hyperion.grabber.common.util;

import android.util.Log;

public class HyperionGrabberOptions {
    private static final boolean DEBUG = false;
    private static final String TAG = "HyperionGrabberOptions";

    // Upper bound for a single capture dimension. The screen is grabbed at exactly the configured
    // LED grid (times the multiplier); this clamp is only a safety net against absurd values that
    // would make the VirtualDisplay/ImageReader allocations and per-frame copy too expensive.
    private static final int MAX_OUTPUT_DIMENSION = 480;

    private final int OUTPUT_WIDTH;  // captured/transmitted image width  (horizontal LEDs * multiplier)
    private final int OUTPUT_HEIGHT; // captured/transmitted image height (vertical LEDs   * multiplier)
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

        // The image is divided into exactly horizontalLED x verticalLED cells, and the multiplier
        // refines that grid (x2 = twice the cells per axis). The GPU downscales the screen to these
        // dimensions, so each captured pixel is the average of one cell. Hyperion then maps this grid
        // onto the user's actual LED layout. Dimensions are clamped to a sane range and forced even
        // (the ImageReader/VirtualDisplay path expects even sizes elsewhere in the encoder).
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

    /** @return the width of the image to capture/transmit (horizontal LED count * multiplier). */
    public int getOutputWidth() { return OUTPUT_WIDTH; }

    /** @return the height of the image to capture/transmit (vertical LED count * multiplier). */
    public int getOutputHeight() { return OUTPUT_HEIGHT; }

    public int getBlackThreshold() { return BLACK_THRESHOLD; }

    /** @return how long (ms) an all-black screen is sent before the last non-black frame is held. */
    public int getBlackHoldMs() { return BLACK_HOLD_MS; }
}
