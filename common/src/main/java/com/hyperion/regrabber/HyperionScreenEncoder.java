package com.hyperion.regrabber.common;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import com.hyperion.regrabber.common.network.HyperionThread;
import com.hyperion.regrabber.common.util.BorderProcessor;
import com.hyperion.regrabber.common.util.HyperionGrabberOptions;

import java.nio.ByteBuffer;

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

    // Rotating pool of frame buffers. Each frame is extracted straight into the next pooled buffer
    // and that buffer is handed to the network thread, so the capture thread never overwrites a
    // buffer the network thread is still serializing (the send is coalesced to the latest frame, so
    // only the most-recently-handed buffer is ever read — rotating a few buffers keeps it stable
    // until the serialization, which is far faster than the frame interval, has finished).
    private static final int FRAME_POOL_SIZE = 3;

    // Average-color mode renders into this tiny surface and lets the GPU do the downscale, so the
    // CPU only averages a few hundred pixels and the readback is ~2KB instead of ~36KB per frame.
    private static final int AVG_CAPTURE_WIDTH = 32;
    private static final int AVG_CAPTURE_HEIGHT = 18;

    // Capture components
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private volatile boolean mRunning;
    private int mCaptureWidth;
    private int mCaptureHeight;
    private final byte[][] mFramePool = new byte[FRAME_POOL_SIZE][];
    private int mFramePoolIdx;
    private byte[] mRowBuffer;
    private int mBorderX;
    private int mBorderY;
    private int mFrameCount;

    // The last frame actually transmitted, black or not. The heartbeat resends THIS, so the LEDs
    // hold whatever is currently on screen — including black, so a black scene stays dark instead of
    // snapping back to the last bright colour. Points at a pooled buffer (or mLastGoodFrame while
    // holding), never the per-row scratch.
    private byte[] mLastSentFrame;
    private int mLastSentWidth;
    private int mLastSentHeight;

    // Last non-black frame (stable copy). Only used by the "hold on black" feature to keep the LEDs
    // lit when a video player blanks the screen; only populated when black-hold is enabled.
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
    // 0 disables holding entirely — black is always shown as black and the LEDs go dark.
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
            if (mLastSentFrame != null && now - mLastSentMs >= HEARTBEAT_MS) {
                mListener.sendFrame(mLastSentFrame, mLastSentWidth, mLastSentHeight);
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
        if (mAvgColor) {
            // Let the GPU collapse the whole screen into a tiny surface; orientation only sets aspect.
            final boolean portrait = getGrabberHeight() > getGrabberWidth();
            mCaptureWidth = (portrait ? AVG_CAPTURE_HEIGHT : AVG_CAPTURE_WIDTH) & ~1;
            mCaptureHeight = (portrait ? AVG_CAPTURE_WIDTH : AVG_CAPTURE_HEIGHT) & ~1;
            return;
        }
        // Aspect-preserving capture size derived from the configured grid (see
        // HyperionScreenEncoderBase). Already even; clamp to a 4px floor and keep even.
        mCaptureWidth = Math.max(4, getGrabberWidth()) & ~1;
        mCaptureHeight = Math.max(4, getGrabberHeight()) & ~1;
    }

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
        // Border detection is only meaningful for full-resolution pixel capture; the average-color
        // path now renders into a tiny GPU-downscaled surface where per-edge analysis is pointless.
        if (!mRemoveBorders) return;

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

        final int frameSize = effWidth * effHeight * BYTES_PER_PIXEL_RGB;
        // Extract straight into a pooled buffer (no intermediate copy) and hand that buffer off.
        final byte[] rgb = nextFrameBuffer(frameSize);
        extractRgb(rgb, buffer, width, height, rowStride, pixelStride, bx, by, effWidth, effHeight);
        dispatchFrame(rgb, frameSize, effWidth, effHeight, isRgbBlack(rgb, frameSize));
    }

    /** Returns the next buffer in the rotating pool, (re)allocating it to {@code length} if needed. */
    private byte[] nextFrameBuffer(int length) {
        byte[] buf = mFramePool[mFramePoolIdx];
        if (buf == null || buf.length != length) {
            buf = new byte[length];
            mFramePool[mFramePoolIdx] = buf;
        }
        mFramePoolIdx = (mFramePoolIdx + 1) % FRAME_POOL_SIZE;
        return buf;
    }

    private void clearFramePool() {
        for (int i = 0; i < mFramePool.length; i++) {
            mFramePool[i] = null;
        }
        mFramePoolIdx = 0;
    }

    /**
     * Decides what to actually transmit for a freshly captured frame. {@code data} is already a
     * stable pooled buffer, so it can be handed off without copying.
     * <p>Non-black frames are forwarded as-is. Black frames are forwarded so the LEDs go dark with
     * the screen. The optional "hold on black" feature ({@link #mBlackHoldMs} &gt; 0) instead holds
     * the last non-black frame once the screen has been black for the configured grace period, so a
     * paused/blanked video player doesn't drop the LEDs; when it is 0 the feature is disabled and
     * black is always shown as black.
     * <p>Whatever is chosen becomes {@link #mLastSentFrame}, which the heartbeat resends so the LEDs
     * hold the current content (black included) even after a static screen stops producing frames.
     */
    private void dispatchFrame(byte[] data, int length, int width, int height, boolean black) {
        final long now = SystemClock.uptimeMillis();

        if (black) {
            if (mBlackSinceMs == 0) mBlackSinceMs = now;
            if (mBlackHoldMs > 0 && mLastGoodFrame != null
                    && (now - mBlackSinceMs) >= mBlackHoldMs) {
                // Sustained black with holding enabled: keep the LEDs on the last non-black frame.
                markSent(mLastGoodFrame, mLastGoodWidth, mLastGoodHeight, now);
                return;
            }
            markSent(data, width, height, now);
            return;
        }

        mBlackSinceMs = 0;
        if (mBlackHoldMs > 0) {
            // Hold-on-black is enabled: keep a stable copy of this frame, since the pooled buffer it
            // lives in will be recycled within a few frames. Skipped entirely when holding is off.
            mLastGoodFrame = copyStable(mLastGoodFrame, data, length);
            mLastGoodWidth = width;
            mLastGoodHeight = height;
        }
        markSent(data, width, height, now);
    }

    /** Records the given stable buffer as the current frame, transmits it, and arms the heartbeat. */
    private void markSent(byte[] frame, int width, int height, long now) {
        mLastSentFrame = frame;
        mLastSentWidth = width;
        mLastSentHeight = height;
        mListener.sendFrame(frame, width, height);
        mLastSentMs = now;
    }

    /** Copies {@code length} bytes of {@code data} into {@code target}, reallocating only if needed. */
    private static byte[] copyStable(byte[] target, byte[] data, int length) {
        if (target == null || target.length != length) {
            target = new byte[length];
        }
        System.arraycopy(data, 0, target, 0, length);
        return target;
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

    private void extractRgb(byte[] dst, ByteBuffer buffer, int width, int height,
                            int rowStride, int pixelStride,
                            int bx, int by, int effWidth, int effHeight) {
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
                    dst[rgbIdx++] = mRowBuffer[i];
                    dst[rgbIdx++] = mRowBuffer[i + 1];
                    dst[rgbIdx++] = mRowBuffer[i + 2];
                    dst[rgbIdx++] = mRowBuffer[i + 4];
                    dst[rgbIdx++] = mRowBuffer[i + 5];
                    dst[rgbIdx++] = mRowBuffer[i + 6];
                    dst[rgbIdx++] = mRowBuffer[i + 8];
                    dst[rgbIdx++] = mRowBuffer[i + 9];
                    dst[rgbIdx++] = mRowBuffer[i + 10];
                    dst[rgbIdx++] = mRowBuffer[i + 12];
                    dst[rgbIdx++] = mRowBuffer[i + 13];
                    dst[rgbIdx++] = mRowBuffer[i + 14];
                }
                for (; i < rowBytes; i += BYTES_PER_PIXEL_RGBA) {
                    dst[rgbIdx++] = mRowBuffer[i];
                    dst[rgbIdx++] = mRowBuffer[i + 1];
                    dst[rgbIdx++] = mRowBuffer[i + 2];
                }
            }

            buffer.position(savedPos);
        } else {
            for (int y = by; y < endY; y++) {
                final int rowOff = y * rowStride;
                for (int x = bx; x < endX; x++) {
                    final int off = rowOff + x * pixelStride;
                    dst[rgbIdx++] = buffer.get(off);
                    dst[rgbIdx++] = buffer.get(off + 1);
                    dst[rgbIdx++] = buffer.get(off + 2);
                }
            }
        }
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

        // The surface is already tiny (≈32x18) thanks to the GPU downscale, so average every pixel.
        for (int y = startY; y < endY; y++) {
            final int rowOff = y * rowStride;
            for (int x = startX; x < endX; x++) {
                final int off = rowOff + x * pixelStride;
                r += buffer.get(off) & 0xFF;
                g += buffer.get(off + 1) & 0xFF;
                b += buffer.get(off + 2) & 0xFF;
                count++;
            }
        }

        if (count > 0) {
            final byte[] avg = nextFrameBuffer(BYTES_PER_PIXEL_RGB);
            avg[0] = (byte) (r / count);
            avg[1] = (byte) (g / count);
            avg[2] = (byte) (b / count);
            final boolean black = (r + g + b) / (count * 3L) < BLACK_FRAME_AVG;
            dispatchFrame(avg, BYTES_PER_PIXEL_RGB, 1, 1, black);
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

        clearFramePool();
        mRowBuffer = null;
        mLastSentFrame = null;
        mLastSentWidth = 0;
        mLastSentHeight = 0;
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

        clearFramePool();
        mRowBuffer = null;
        mLastSentFrame = null;
        mLastGoodFrame = null;

        startCapture();
    }

    @Override
    public void clearLights() {
        super.clearLights();
    }
}
