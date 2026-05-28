package com.crimson.deck.data.webrtc

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class H264Decoder(
    private val onResolutionChanged: (width: Int, height: Int) -> Unit,
    private val onFrameDecoded: (byteSize: Int) -> Unit,
    private val onDecoderRecovered: (() -> Unit)? = null  // Optional: request a keyframe from server
) {
    private var mediaCodec: MediaCodec? = null
    private var isInitialized = false
    private var width = 0
    private var height = 0
    private var activeSurface: Surface? = null

    // Consecutive error guard: prevent rapid crash-reinit loops
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 5
    private var lastErrorTimeMs = 0L
    private val errorCooldownMs = 2000L

    fun setSurface(surface: Surface?) {
        activeSurface = surface
        val codec = mediaCodec
        if (codec != null && surface != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i("H264Decoder", "Successfully updated MediaCodec output surface.")
            } catch (e: Exception) {
                Log.e("H264Decoder", "Failed to set output surface on active MediaCodec: ${e.message}")
                // Surface update failed — reset decoder so it reinitializes on next frame
                release()
            }
        }
    }

    fun decode(data: ByteArray) {
        val surface = activeSurface ?: return

        // Throttle rapid-fire error loops: if we've hit too many errors recently, pause
        if (consecutiveErrors >= maxConsecutiveErrors) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTimeMs < errorCooldownMs) {
                return // Cooldown active — skip frame
            } else {
                consecutiveErrors = 0 // Cooldown elapsed — try again
            }
        }

        if (!isInitialized) {
            initDecoder(1920, 1080, surface)
        }

        val codec = mediaCodec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    // Guard against oversized NAL units that exceed the input buffer
                    val safeLen = minOf(data.size, inputBuffer.capacity())
                    inputBuffer.put(data, 0, safeLen)
                    codec.queueInputBuffer(inputBufferIndex, 0, safeLen, System.nanoTime() / 1000, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

            while (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, true)
                onFrameDecoded(data.size)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                Log.i("H264Decoder", "Output format changed: ${w}x${h}")
                width = w
                height = h
                onResolutionChanged(w, h)
            }

            consecutiveErrors = 0 // Successful decode — reset error counter

        } catch (e: MediaCodec.CodecException) {
            consecutiveErrors++
            lastErrorTimeMs = System.currentTimeMillis()
            Log.e("H264Decoder", "MediaCodec.CodecException (error #$consecutiveErrors): ${e.diagnosticInfo}")
            if (e.isRecoverable) {
                try { codec.reset() } catch (_: Exception) {}
                Log.w("H264Decoder", "MediaCodec reset attempted for recoverable error.")
            } else {
                release()
                onDecoderRecovered?.invoke() // Notify ViewModel to request keyframe
            }
        } catch (e: IllegalStateException) {
            consecutiveErrors++
            lastErrorTimeMs = System.currentTimeMillis()
            Log.e("H264Decoder", "IllegalStateException in decode (error #$consecutiveErrors): ${e.message}")
            release()
            onDecoderRecovered?.invoke()
        } catch (e: Exception) {
            consecutiveErrors++
            lastErrorTimeMs = System.currentTimeMillis()
            Log.e("H264Decoder", "Unexpected decode exception (error #$consecutiveErrors): ${e.message}")
            release()
            onDecoderRecovered?.invoke()
        }
    }

    private fun initDecoder(w: Int, h: Int, surface: Surface) {
        try {
            width = w
            height = h
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            isInitialized = true
            consecutiveErrors = 0
            Log.i("H264Decoder", "MediaCodec H.264 Decoder initialized at ${width}x${height}")
        } catch (e: Exception) {
            Log.e("H264Decoder", "Failed to initialize MediaCodec: ${e.message}")
            mediaCodec?.release()
            mediaCodec = null
            isInitialized = false
        }
    }

    fun release() {
        try {
            if (isInitialized) mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Decoder", "Error releasing MediaCodec: ${e.message}")
        } finally {
            mediaCodec = null
            isInitialized = false
            activeSurface = null
            consecutiveErrors = 0
            Log.i("H264Decoder", "MediaCodec released and state fully reset.")
        }
    }
}
