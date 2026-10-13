package com.xcology.livecamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the *processed* camera frames, not CameraX's unfiltered camera stream.
 * H.264 video only (no microphone/audio). 480p-ish, at most 15 input fps.
 * Encoder uses flexible YUV 420 input images, accommodating vendor-specific strides.
 */
class ProcessedVideoRecorder(private val context: Context) {
    private val queue = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    @Volatile private var accepting = false
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var mediaUri: Uri? = null
    private var muxerStarted = false
    private var track = -1
    private var framesEncoded = 0
    private var startedAtNs = 0L
    private var lastPresentationUs = -1L
    private var lastEnqueuedNs = 0L
    private var errorListener: ((String) -> Unit)? = null
    private var videoWidth = 0
    private var videoHeight = 0

    val isRecording: Boolean get() = accepting

    /** Call start from the UI; encoding only occurs on the dedicated queue thread. */
    fun start(firstFrame: Bitmap, onError: (String) -> Unit) {
        if (accepting) return
        require(firstFrame.width % 2 == 0 && firstFrame.height % 2 == 0)
        accepting = true
        errorListener = onError
        videoWidth = firstFrame.width
        videoHeight = firstFrame.height
        startedAtNs = SystemClock.elapsedRealtimeNanos()
        lastEnqueuedNs = 0L
        lastPresentationUs = -1L
        framesEncoded = 0
        queue.execute {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Xcology_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Xcology")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create video in MediaStore")
                mediaUri = uri
                descriptor = resolver.openFileDescriptor(uri, "rw")
                    ?: error("Cannot open output video file")
                muxer = MediaMuxer(descriptor!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, 1_600_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 15)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            } catch (e: Exception) {
                accepting = false
                cleanup(false)
                onError("Video encoder unavailable: ${e.message}")
            }
        }
        record(firstFrame)
    }

    /** Drop queued frames rather than freezing the live preview if encoder falls behind. */
    fun record(frame: Bitmap) {
        if (!accepting || frame.width != videoWidth || frame.height != videoHeight) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastEnqueuedNs != 0L && now - lastEnqueuedNs < 65_000_000L) return
        lastEnqueuedNs = now
        if (pending.incrementAndGet() > 2) {
            pending.decrementAndGet()
            return
        }
        queue.execute {
            try {
                if (codec != null) encodeFrame(frame)
            } catch (_: Exception) {
                accepting = false
                val report = errorListener
                cleanup(false)
                report?.invoke("Video recording stopped: encoder error")
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Stops in queue order, ensuring pending frames are encoded before MP4 finalization. */
    fun stop(onFinished: (Uri?, String?) -> Unit) {
        if (!accepting && codec == null) return
        accepting = false
        queue.execute {
            var failure: String? = null
            try {
                val encoder = codec ?: error("Encoder was not initialized")
                val index = encoder.dequeueInputBuffer(1_000_000)
                if (index >= 0) {
                    val ts = presentationTime()
                    encoder.queueInputBuffer(index, 0, 0, ts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(encoder, true)
                } else {
                    error("Encoder did not accept end-of-stream")
                }
                if (framesEncoded == 0 || !muxerStarted) error("No video frames were encoded")
            } catch (e: Exception) {
                failure = e.message ?: "Recording failed"
            }
            val result = if (failure == null) mediaUri else null
            cleanup(failure == null)
            onFinished(result, failure)
        }
    }

    private fun encodeFrame(frame: Bitmap) {
        val encoder = codec ?: return
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) { drain(encoder, false); return }
        val image = encoder.getInputImage(index) ?: error("Encoder did not provide YUV input")
        try {
            writeYuv420(frame, image)
        } finally {
            image.close()
        }
        encoder.queueInputBuffer(index, 0, videoWidth * videoHeight * 3 / 2, presentationTime(), 0)
        drain(encoder, false)
    }

    private fun presentationTime(): Long {
        val ts = (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1000L
        lastPresentationUs = maxOf(lastPresentationUs + 1L, ts)
        return lastPresentationUs
    }

    private fun writeYuv420(bitmap: Bitmap, image: android.media.Image) {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val planes = image.planes
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        fun clamp(v: Int): Byte = v.coerceIn(0, 255).toByte()
        for (row in 0 until h) {
            for (col in 0 until w) {
                val rgb = pixels[row * w + col]
                val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
                val yy = ((66*r + 129*g + 25*b + 128) shr 8) + 16
                yBuffer.put(row * yPlane.rowStride + col * yPlane.pixelStride, clamp(yy))
            }
        }
        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                var rs = 0; var gs = 0; var bs = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val rgb = pixels[(row + dy)*w + col + dx]
                    rs += (rgb shr 16) and 255; gs += (rgb shr 8) and 255; bs += rgb and 255
                }
                val r = rs / 4; val g = gs / 4; val b = bs / 4
                val cb = ((-38*r - 74*g + 112*b + 128) shr 8) + 128
                val cr = ((112*r - 94*g - 18*b + 128) shr 8) + 128
                val posU = (row/2) * uPlane.rowStride + (col/2) * uPlane.pixelStride
                val posV = (row/2) * vPlane.rowStride + (col/2) * vPlane.pixelStride
                uBuffer.put(posU, clamp(cb)); vBuffer.put(posV, clamp(cr))
            }
        }
    }

    private fun drain(encoder: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || waits++ > 300) return
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Video output format changed twice" }
                    track = muxer!!.addTrack(encoder.outputFormat)
                    muxer!!.start(); muxerStarted = true
                }
                else -> if (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted) { "Muxer hasn't started" }
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, buffer, info)
                        framesEncoded++
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun cleanup(success: Boolean) {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        if (muxerStarted) try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null; muxerStarted = false
        try { descriptor?.close() } catch (_: Exception) {}
        descriptor = null
        mediaUri?.let { uri ->
            try {
                if (success) {
                    context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                } else context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
        mediaUri = null
        errorListener = null
    }
}
