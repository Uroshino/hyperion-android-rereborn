package com.hyperion.regrabber.common;

import android.content.res.Configuration;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import com.hyperion.regrabber.common.network.HyperionThread;
import com.hyperion.regrabber.common.util.BorderProcessor;
import com.hyperion.regrabber.common.util.HyperionGrabberOptions;

abstract class HyperionScreenEncoderBase {
    private static final String TAG = "ScreenEncoderBase";
    static final boolean DEBUG = false;
    
    private static final int CLEAR_DELAY_MS = 100;

    // Configuration (immutable after construction)
    protected final int mDensity;
    protected final int mFrameRate;
    protected final boolean mAvgColor;
    protected final boolean mRemoveBorders = false; // Disabled for now
    private final int mInitOrientation;
    private final int mWidthScaled;
    private final int mHeightScaled;

    // Components
    protected final MediaProjection mMediaProjection;
    protected final HyperionThread.HyperionThreadListener mListener;
    protected final BorderProcessor mBorderProcessor;
    protected final Handler mHandler;
    
    // Mutable state
    protected volatile int mCurrentOrientation;
    private volatile boolean mIsCapturing;

    HyperionScreenEncoderBase(HyperionThread.HyperionThreadListener listener,
                              MediaProjection projection,
                              int width, int height,
                              int density,
                              HyperionGrabberOptions options) {
        mListener = listener;
        mMediaProjection = projection;
        mDensity = density;
        mFrameRate = options.getFrameRate();
        mAvgColor = options.useAverageColor();
        mBorderProcessor = new BorderProcessor(options.getBlackThreshold());

        // Determine orientation
        mCurrentOrientation = mInitOrientation = 
                width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;

        // Capture at an integer downscale of the screen, so the capture preserves the screen's EXACT
        // aspect ratio. If the surface aspect didn't match the display, the VirtualDisplay would
        // letterbox/pillarbox it, and those bar regions (left un-cleared between frames) leak
        // stale/garbage data into the captured edges — exactly what Hyperion samples for the border
        // LEDs. The configured LED grid (counts × multiplier) only selects which downscale to use.
        final int[] capture = options.computeCaptureSize(width, height);
        mWidthScaled = capture[0];
        mHeightScaled = capture[1];

        // Handler thread for callbacks
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        if (DEBUG) {
            Log.d(TAG, "Init: " + width + "x" + height + " -> " + mWidthScaled + "x" + mHeightScaled);
        }
    }

    public void clearLights() {
        new Thread(() -> {
            sleep(CLEAR_DELAY_MS);
            mListener.clear();
        }).start();
    }

    protected void clearAndDisconnect() {
        new Thread(() -> {
            sleep(CLEAR_DELAY_MS);
            mListener.clear();
            mListener.disconnect();
        }).start();
    }
    
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isCapturing() {
        return mIsCapturing;
    }

    protected void setCapturing(boolean capturing) {
        mIsCapturing = capturing;
    }

    public void sendStatus() {
        mListener.sendStatus(mIsCapturing);
    }

    protected int getGrabberWidth() {
        return mInitOrientation != mCurrentOrientation ? mHeightScaled : mWidthScaled;
    }

    protected int getGrabberHeight() {
        return mInitOrientation != mCurrentOrientation ? mWidthScaled : mHeightScaled;
    }

    public abstract void stopRecording();
    public abstract void pauseRecording();
    public abstract void resumeRecording();
    public abstract void setOrientation(int orientation);
}
