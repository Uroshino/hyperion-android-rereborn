package com.hyperion.grabber.common;

import android.annotation.TargetApi;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import android.util.Log;

import com.hyperion.grabber.common.network.HyperionThread;
import com.hyperion.grabber.common.util.BorderProcessor;
import com.hyperion.grabber.common.util.HyperionGrabberOptions;

import java.nio.ByteBuffer;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class HyperionScreenEncoder extends HyperionScreenEncoderBase {
    private static final String TAG = "HyperionScreenEncoder";
    private static final boolean DEBUG = false;

    // Constants
    private static final int IMAGE_READER_IMAGES = 3;
    private static final int BORDER_CHECK_FRAMES = 60;
    private static final int BYTES_PER_PIXEL_RGBA = 4;
    private static final int BYTES_PER_PIXEL_RGB = 3;

    // How often the last good frame is resent while the screen is idle/static, so the Hyperion
    // priority channel never times out and the LEDs hold. Far cheaper than the old 60Hz resend.
    private static final int HEARTBEAT_MS = 250;
    // A frame whose sampled average channel value is below this is treated as "black".
    private static final int BLACK_FRAME_AVG = 6;
    // Sample stride (in pixels) used for the cheap black-frame test.
    private static final int BLACK_SAMPLE_STRIDE = 16;

    // Capture components
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private volatile boolean mRunning;
    private int mCaptureWidth;
    private int mCaptureHeight;
    private byte[] mRgbBuffer;
    private byte[] mRowBuffer;
    private final byte[] mAvgColorResult = new byte[3];
    private int mBorderX;
    private int mBorderY;
    private int mFrameCount;

    // Last non-black frame, reused both as the stable buffer handed to the network thread and as the
    // frame resent by the heartbeat while the screen is idle.
    private byte[] mLastGoodFrame;
    private int mLastGoodWidth;
    private int mLastGoodHeight;

    // Timestamps (uptimeMillis); only touched on the capture thread.
    private long mLastSentMs;
    private long mLastProcessedMs;
    private long mBlackSinceMs;

    private final long mFrameIntervalMs;
    // User-configurable: how long a sustained black screen is forwarded before the last non-black
    // frame is held instead (so a paused/blanked video player doesn't drop the LEDs to default).
    private final long mBlackHoldMs;

    /**
     * Resends the last good frame on a steady cadence whenever no live frame has been sent recently.
     * This keeps the LEDs lit during static screens and paused video without spamming the network at
     * the full frame rate.
     */
    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning || mCaptureHandler == null) return;

            final long now = SystemClock.uptimeMillis();
            if (mLastGoodFrame != null && now - mLastSentMs >= HEARTBEAT_MS) {
                mListener.sendFrame(mLastGoodFrame, mLastGoodWidth, mLastGoodHeight);
                mLastSentMs = now;
            }
            mCaptureHandler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    /**
     * Event-driven capture: the VirtualDisplay pushes a new image only when the screen content
     * actually changes, so we process frames the instant they are produced instead of polling.
     */
    private final ImageReader.OnImageAvailableListener mImageListener =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                if (!mRunning) return; // paused — just drain to free the buffer

                final long now = SystemClock.uptimeMillis();
                if (now - mLastProcessedMs < mFrameIntervalMs) return; // throttle to target fps
                mLastProcessedMs = now;

                processImage(img);
            } catch (Exception e) {
                if (DEBUG) Log.w(TAG, "Capture error", e);
            } finally {
                if (img != null) {
                    img.close();
                }
            }
        }
    };

    private final VirtualDisplay.Callback mDisplayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            if (DEBUG) Log.d(TAG, "Display paused");
        }

        @Override
        public void onResumed() {
            if (DEBUG) Log.d(TAG, "Display resumed");
            if (!mRunning) startCapture();
        }

        @Override
        public void onStopped() {
            if (DEBUG) Log.d(TAG, "Display stopped");
            mRunning = false;
            setCapturing(false);
        }
    };

    /**
     * Creates a new screen encoder.
     */
    HyperionScreenEncoder(HyperionThread.HyperionThreadListener listener,
                          MediaProjection projection,
                          int screenWidth, int screenHeight,
                          int density,
                          HyperionGrabberOptions options) {
        super(listener, projection, screenWidth, screenHeight, density, options);

        mFrameIntervalMs = 1000L / mFrameRate;
        mBlackHoldMs = options.getBlackHoldMs();
        initCaptureDimensions();

        if (DEBUG) Log.d(TAG, "Capture: " + mCaptureWidth + "x" + mCaptureHeight + " @ " + mFrameRate + "fps");

        try {
            init();
        } catch (MediaCodec.CodecException e) {
            Log.e(TAG, "Init failed", e);
        }
    }

    private void initCaptureDimensions() {
        int w = Math.max(4, Math.min(getGrabberWidth(), 128));
        int h = Math.max(4, Math.min(getGrabberHeight(), 72));
        mCaptureWidth = w & ~1;
        mCaptureHeight = h & ~1;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void init() throws MediaCodec.CodecException {
        // Capture must keep up with video playback, so it runs at display priority rather than
        // background priority — a background-priority thread gets starved while the TV decodes a
        // high-bitrate video, which is the main cause of dropped frames.
        mCaptureThread = new HandlerThread(TAG, android.os.Process.THREAD_PRIORITY_DISPLAY);
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);
        mImageReader.setOnImageAvailableListener(mImageListener, mCaptureHandler);

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopRecording();
            }
        }, mHandler);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                TAG,
                mCaptureWidth, mCaptureHeight, mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                mDisplayCallback,
                mHandler);

        startCapture();
    }

    private void startCapture() {
        mRunning = true;
        setCapturing(true);
        mFrameCount = 0;
        mBlackSinceMs = 0;
        mLastProcessedMs = 0;
        mLastSentMs = 0;
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacks(mHeartbeatRunnable);
            mCaptureHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_MS);
        }
    }

    private void processImage(Image img) {
        final Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return;

        final Image.Plane plane = planes[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();

        updateBorderDetection(buffer, width, height, rowStride, pixelStride);

        if (mAvgColor) {
            sendAverageColor(buffer, width, height, rowStride, pixelStride);
        } else {
            sendPixelData(buffer, width, height, rowStride, pixelStride);
        }
    }

    private void updateBorderDetection(ByteBuffer buffer, int width, int height,
                                        int rowStride, int pixelStride) {
        if (!mRemoveBorders && !mAvgColor) return;

        if (++mFrameCount >= BORDER_CHECK_FRAMES) {
            mFrameCount = 0;
            mBorderProcessor.parseBorder(buffer, width, height, rowStride, pixelStride);
            final BorderProcessor.BorderObject border = mBorderProcessor.getCurrentBorder();
            if (border != null && border.isKnown()) {
                mBorderX = border.getHorizontalBorderIndex();
                mBorderY = border.getVerticalBorderIndex();
            }
        }
    }

    private void sendPixelData(ByteBuffer buffer, int width, int height,
                               int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int effWidth = width - (bx << 1);
        final int effHeight = height - (by << 1);

        if (effWidth <= 0 || effHeight <= 0) return;

        final byte[] rgb = extractRgb(buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight);
        final int frameSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;
        dispatchFrame(rgb, frameSize, effWidth, effHeight, isRgbBlack(rgb, frameSize));
    }

    /**
     * Decides what to actually transmit for a freshly captured frame.
     * <p>Non-black frames are copied into {@link #mLastGoodFrame} (which doubles as a stable buffer
     * for the network thread and the heartbeat) and sent. Black frames are forwarded for a short
     * grace period — long enough for genuine fades — but a sustained black screen is suppressed and
     * the heartbeat holds the last good frame instead, so a paused/blanked video player does not
     * drop the LEDs to their default colour.
     */
    private void dispatchFrame(byte[] data, int length, int width, int height, boolean black) {
        final long now = SystemClock.uptimeMillis();

        if (black) {
            if (mBlackSinceMs == 0) mBlackSinceMs = now;
            final boolean sustained = (now - mBlackSinceMs) >= mBlackHoldMs;
            if (sustained && mLastGoodFrame != null) {
                // Hold the last good frame: don't send black and don't refresh mLastSentMs, so the
                // heartbeat keeps the LEDs on the last bright frame.
                return;
            }
            mListener.sendFrame(data, width, height);
            mLastSentMs = now;
            return;
        }

        mBlackSinceMs = 0;
        storeLastGood(data, length, width, height);
        mListener.sendFrame(mLastGoodFrame, width, height);
        mLastSentMs = now;
    }

    private void storeLastGood(byte[] data, int length, int width, int height) {
        if (mLastGoodFrame == null || mLastGoodFrame.length != length) {
            mLastGoodFrame = new byte[length];
        }
        System.arraycopy(data, 0, mLastGoodFrame, 0, length);
        mLastGoodWidth = width;
        mLastGoodHeight = height;
    }

    /** Cheap sampled test for an (almost) entirely black frame. */
    private boolean isRgbBlack(byte[] rgb, int length) {
        final int step = BYTES_PER_PIXEL_RGB * BLACK_SAMPLE_STRIDE;
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 2 < length; i += step) {
            sum += (rgb[i] & 0xFF) + (rgb[i + 1] & 0xFF) + (rgb[i + 2] & 0xFF);
            samples += 3;
        }
        return samples > 0 && (sum / samples) < BLACK_FRAME_AVG;
    }

    private byte[] extractRgb(ByteBuffer buffer, int width, int height,
                              int rowStride, int pixelStride,
                              int bx, int by, int effWidth, int effHeight) {
        final int rgbSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;

        if (mRgbBuffer == null || mRgbBuffer.length < rgbSize) {
            mRgbBuffer = new byte[rgbSize];
        }

        final int endY = height - by;
        final int endX = width - bx;
        int rgbIdx = 0;

        if (pixelStride == BYTES_PER_PIXEL_RGBA && rowStride == width * BYTES_PER_PIXEL_RGBA) {
            final int rowBytes = effWidth * BYTES_PER_PIXEL_RGBA;

            if (mRowBuffer == null || mRowBuffer.length < rowBytes) {
                mRowBuffer = new byte[rowBytes];
            }

            final int savedPos = buffer.position();

            for (int y = by; y < endY; y++) {
                buffer.position(y * rowStride + bx * BYTES_PER_PIXEL_RGBA);
                buffer.get(mRowBuffer, 0, rowBytes);

                int i = 0;
                final int unrollLimit = rowBytes - 15;
                for (; i < unrollLimit; i += 16) {
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 1];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 2];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 4];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 5];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 6];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 8];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 9];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 10];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 12];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 13];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 14];
                }
                for (; i < rowBytes; i += BYTES_PER_PIXEL_RGBA) {
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 1];
                    mRgbBuffer[rgbIdx++] = mRowBuffer[i + 2];
                }
            }

            buffer.position(savedPos);
        } else {
            for (int y = by; y < endY; y++) {
                final int rowOff = y * rowStride;
                for (int x = bx; x < endX; x++) {
                    final int off = rowOff + x * pixelStride;
                    mRgbBuffer[rgbIdx++] = buffer.get(off);
                    mRgbBuffer[rgbIdx++] = buffer.get(off + 1);
                    mRgbBuffer[rgbIdx++] = buffer.get(off + 2);
                }
            }
        }

        return mRgbBuffer;
    }

    private void sendAverageColor(ByteBuffer buffer, int width, int height,
                                  int rowStride, int pixelStride) {
        final int bx = mBorderX;
        final int by = mBorderY;
        final int startX = bx;
        final int startY = by;
        final int endX = width - bx;
        final int endY = height - by;

        if (endX <= startX || endY <= startY) return;

        long r = 0, g = 0, b = 0;
        int count = 0;

        for (int y = startY; y < endY; y += 4) {
            final int rowOff = y * rowStride;
            for (int x = startX; x < endX; x += 4) {
                final int off = rowOff + x * pixelStride;
                r += buffer.get(off) & 0xFF;
                g += buffer.get(off + 1) & 0xFF;
                b += buffer.get(off + 2) & 0xFF;
                count++;
            }
        }

        if (count > 0) {
            mAvgColorResult[0] = (byte) (r / count);
            mAvgColorResult[1] = (byte) (g / count);
            mAvgColorResult[2] = (byte) (b / count);
            final boolean black = ((r + g + b) / count) < BLACK_FRAME_AVG;
            dispatchFrame(mAvgColorResult, 3, 1, 1, black);
        }
    }

    @Override
    public void stopRecording() {
        if (DEBUG) Log.i(TAG, "Stopping");
        mRunning = false;
        setCapturing(false);

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            mCaptureThread = null;
            mCaptureHandler = null;
        }

        mRgbBuffer = null;
        mRowBuffer = null;
        mLastGoodFrame = null;
        mLastGoodWidth = 0;
        mLastGoodHeight = 0;
        mBorderX = 0;
        mBorderY = 0;
        mFrameCount = 0;
        mBlackSinceMs = 0;

        mHandler.getLooper().quit();
        clearAndDisconnect();

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    @Override
    public void pauseRecording() {
        if (DEBUG) Log.i(TAG, "Pausing");
        mRunning = false;
        setCapturing(false);
        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacks(mHeartbeatRunnable);
        }
    }

    @Override
    public void resumeRecording() {
        if (DEBUG) Log.i(TAG, "Resuming");
        if (!isCapturing() && mImageReader != null) {
            startCapture();
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void setOrientation(int orientation) {
        if (mVirtualDisplay == null || orientation == mCurrentOrientation) return;

        mCurrentOrientation = orientation;
        mRunning = false;
        setCapturing(false);

        final int tmp = mCaptureWidth;
        mCaptureWidth = mCaptureHeight;
        mCaptureHeight = tmp;

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }

        mVirtualDisplay.resize(mCaptureWidth, mCaptureHeight, mDensity);

        if (mImageReader != null) {
            mImageReader.setOnImageAvailableListener(null, null);
            mImageReader.close();
        }

        mImageReader = ImageReader.newInstance(
                mCaptureWidth, mCaptureHeight,
                PixelFormat.RGBA_8888,
                IMAGE_READER_IMAGES);
        mImageReader.setOnImageAvailableListener(mImageListener, mCaptureHandler);

        mVirtualDisplay.setSurface(mImageReader.getSurface());

        mRgbBuffer = null;
        mRowBuffer = null;
        mLastGoodFrame = null;

        startCapture();
    }

    @Override
    public void clearLights() {
        super.clearLights();
    }
}
