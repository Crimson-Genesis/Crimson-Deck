package com.crimson.deck.data.webrtc

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class H264Decoder(
    private val onResolutionChanged: (width: Int, height: Int) -> Unit,
    private val onFrameDecoded: (byteSize: Int) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var isInitialized = false
    private var width = 0
    private var height = 0
    private var activeSurface: Surface? = null

    fun setSurface(surface: Surface?) {
        activeSurface = surface
        val codec = mediaCodec
        if (codec != null && surface != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i("H264Decoder", "Successfully updated MediaCodec output surface.")
            } catch (e: Exception) {
                Log.e("H264Decoder", "Failed to set output surface on active MediaCodec: ${e.message}")
            }
        }
    }

    fun decode(data: ByteArray) {
        val surface = activeSurface ?: return // Cannot decode without an active surface

        if (!isInitialized) {
            initDecoder(1920, 1080, surface)
        }

        val codec = mediaCodec ?: return

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data)
                    codec.queueInputBuffer(inputBufferIndex, 0, data.size, System.nanoTime() / 1000, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

            while (outputBufferIndex >= 0) {
                // Render directly to surface (second parameter true renders the buffer to the surface)
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
        } catch (e: Exception) {
            Log.e("H264Decoder", "Decoding exception: ${e.message}")
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
            Log.i("H264Decoder", "MediaCodec H.264 Decoder successfully initialized at ${width}x${height}")
        } catch (e: Exception) {
            Log.e("H264Decoder", "Failed to initialize MediaCodec: ${e.message}")
        }
    }

    fun release() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Decoder", "Error releasing MediaCodec: ${e.message}")
        }
        mediaCodec = null
        isInitialized = false
    }
}
