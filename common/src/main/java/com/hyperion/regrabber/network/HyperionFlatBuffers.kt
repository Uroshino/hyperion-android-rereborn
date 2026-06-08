package com.hyperion.regrabber.common.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.*
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class HyperionFlatBuffers(address: String?, port: Int, priority: Int) : HyperionClient {
    private val TIMEOUT = 1000
    private val mSocket: Socket = Socket()
    private val mPriority: Int
    private val mBuilder: FlatBufferBuilder
    private val mOut: OutputStream
    // Reused per-frame so the 60fps send loop allocates nothing for the length prefix.
    private val mHeader = ByteArray(4)
    private val mFallbackBuffer = ByteArray(4096)

    init {
        mSocket.tcpNoDelay = true // Disable Nagle's algorithm for low latency
        mSocket.sendBufferSize = 8192 // Smaller buffer for faster sends
        mSocket.receiveBufferSize = 4096
        mSocket.connect(InetSocketAddress(address, port), TIMEOUT)
        mSocket.soTimeout = 10 // Very short timeout for non-blocking behavior
        // Buffer the output so the 4-byte header and a whole capture frame (max 128*72*3 ≈ 27KB
        // plus flatbuffer overhead) are flushed as a single write per frame.
        mOut = BufferedOutputStream(mSocket.getOutputStream(), 49152)
        mPriority = priority
        mBuilder = FlatBufferBuilder(1024)
        register()
    }

    @Throws(IOException::class)
    private fun register() {
        mBuilder.clear()
        val originOffset = mBuilder.createString("HyperionAndroidGrabber")
        val registerOffset = Register.createRegister(mBuilder, originOffset, mPriority)
        val requestOffset = Request.createRequest(mBuilder, Command.Register, registerOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    override fun isConnected(): Boolean {
        return mSocket.isConnected && !mSocket.isClosed
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (isConnected()) {
            mSocket.close()
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        mBuilder.clear()
        val clearOffset = Clear.createClear(mBuilder, priority)
        val requestOffset = Request.createRequest(mBuilder, Command.Clear, clearOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(-1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        mBuilder.clear()
        val colorOffset = Color.createColor(mBuilder, color, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Color, colorOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        mBuilder.clear()
        // Bulk copy of the pixel array into the FlatBuffer. The generated createDataVector() appends
        // one byte at a time (tens of thousands of calls per frame at high detail); createByteVector()
        // does the same as a single bulk put.
        val dataOffset = mBuilder.createByteVector(data)
        val rawImageOffset = RawImage.createRawImage(mBuilder, dataOffset, width, height)
        val imageOffset = Image.createImage(mBuilder, ImageType.RawImage, rawImageOffset, duration_ms)
        val requestOffset = Request.createRequest(mBuilder, Command.Image, imageOffset)
        Request.finishRequestBuffer(mBuilder, requestOffset)
        sendRequest(mBuilder.dataBuffer())
    }

    @Throws(IOException::class)
    private fun sendRequest(bb: ByteBuffer) {
        if (!isConnected()) return

        val size = bb.remaining()
        mHeader[0] = ((size shr 24) and 0xFF).toByte()
        mHeader[1] = ((size shr 16) and 0xFF).toByte()
        mHeader[2] = ((size shr 8) and 0xFF).toByte()
        mHeader[3] = (size and 0xFF).toByte()
        mOut.write(mHeader, 0, 4)

        if (bb.hasArray()) {
            // Write the FlatBuffer's backing array straight out — no per-frame copy.
            mOut.write(bb.array(), bb.arrayOffset() + bb.position(), size)
        } else {
            // Direct buffer fallback: stream through a reused scratch buffer.
            val dup = bb.duplicate()
            var remaining = size
            while (remaining > 0) {
                val chunk = if (remaining < mFallbackBuffer.size) remaining else mFallbackBuffer.size
                dup.get(mFallbackBuffer, 0, chunk)
                mOut.write(mFallbackBuffer, 0, chunk)
                remaining -= chunk
            }
        }

        // The BufferedOutputStream coalesces header + payload into a single segment on flush.
        mOut.flush()

        // Don't wait for reply - fire and forget for minimal latency.
        // Replies are drained separately via cleanReplies().
    }

    fun cleanReplies() {
        receiveReply()
    }

    private fun receiveReply() {
        // Non-blocking reply consumption to keep socket clean
        // This is called separately and doesn't block frame sending
        try {
            while (mSocket.getInputStream().available() >= 4) {
                val header = ByteArray(4)
                val read = mSocket.getInputStream().read(header, 0, 4)
                if (read == 4) {
                    val size = (header[0].toInt() and 0xFF shl 24) or
                            (header[1].toInt() and 0xFF shl 16) or
                            (header[2].toInt() and 0xFF shl 8) or
                            (header[3].toInt() and 0xFF)
                    if (size > 0 && mSocket.getInputStream().available() >= size) {
                        val data = ByteArray(size)
                        mSocket.getInputStream().read(data, 0, size)
                    } else {
                        break // Not enough data yet, will consume later
                    }
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            // Ignore - non-blocking read
        }
    }
}