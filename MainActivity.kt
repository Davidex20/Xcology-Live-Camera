package com.xcology.livecamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live preview + locally processed saved photos and MP4s.
 * Prefers MediaPipe multi-class skin semantics (face skin + body skin).
 * Falls back to ML Kit person matte + color heuristic if the advanced model fails.
 * Not a camera replacement for third-party video chat apps.
 */
class MainActivity : ComponentActivity() {
    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var segmenter: Segmenter
    private lateinit var faceDetector: FaceDetector
    private var advancedSegmenter: ImageSegmenter? = null
    private var segmenterFailure: String? = null
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var recorder: ProcessedVideoRecorder
    @Volatile private var latestProcessed: Bitmap? = null
    @Volatile private var intensity = 0.88f
    @Volatile private var warmth = 0.50f
    @Volatile private var lightness = 0.75f
    private var faceArea: FaceArea? = null // read and written on processingExecutor
    private var frameNumber = 0
    private var frontCamera = true
    private var provider: ProcessCameraProvider? = null
    private var lastSavedVideo: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = ProcessedVideoRecorder(applicationContext)
        segmenter = Segmentation.getClient(SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build())
        faceDetector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build())
        buildLayout()
        processingExecutor.execute { initializeAdvancedSegmenter() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 15, 24))
            setPadding(dp(12), dp(18), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "XCOLOGY  •  LIVE CAMERA"
            textSize = 20f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(42)))
        status = TextView(this).apply {
            text = "Starting AI camera…"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(157, 210, 255))
            textSize = 12f
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(28)))
        preview = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun slider(label: String, initial: Int, changed: (Int) -> Unit) {
            root.addView(TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 14f
                setPadding(0, dp(8), 0, 0)
            })
            root.addView(SeekBar(this).apply {
                max = 100; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        changed(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            })
        }
        slider("Transformation strength", 88) { intensity = it / 100f }
        slider("Target lightness  •  tan ← → fair", 75) { lightness = it / 100f }
        slider("Skin undertone  •  cool ← → warm", 50) { warmth = it / 100f }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(title: String, action: () -> Unit): Button = Button(this).apply {
            text = title; textSize = 12f; isAllCaps = false
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        button("Photo") { takePhoto() }
        recordButton = button("● Record") { toggleRecording() }
        button("Flip") {
            if (recorder.isRecording) {
                toast("Stop recording before switching cameras")
            } else {
                frontCamera = !frontCamera
                startCamera()
            }
        }
        root.addView(buttons)
        val bottomButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bottomButtons)
        bottomButtons.addView(Button(this).apply {
            text = "Original / FX"
            setOnClickListener {
                if (intensity > 0f) {
                    intensity = 0f; status.text = "Original view (effect off)"
                } else {
                    intensity = 0.88f; status.text = "Skin-tone effect on"
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottomButtons.addView(Button(this).apply {
            text = "Share last video"
            setOnClickListener { shareLatestVideo() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(TextView(this).apply {
            text = "On-device AI • Silent video • Video-call camera replacement not enabled"
            setTextColor(Color.LTGRAY); textSize = 10f; gravity = Gravity.CENTER
        })
        setContentView(root)
    }

    @Deprecated("Uses camera permission result for compatibility with Android 10+")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (requestCode == CAMERA_PERMISSION) {
            status.text = "Camera permission is required to continue"
        }
    }

    private fun startCamera() {
        status.text = "Starting ${if (frontCamera) "front" else "rear"} camera…"
        val cameraFuture = ProcessCameraProvider.getInstance(this)
        cameraFuture.addListener({
            try {
                val cameraProvider = cameraFuture.get()
                provider = cameraProvider
                cameraProvider.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                frameNumber = 0
                faceArea = null
                analysis.setAnalyzer(processingExecutor) { proxy -> analyze(proxy) }
                val facing = if (frontCamera) CameraSelector.LENS_FACING_FRONT
                    else CameraSelector.LENS_FACING_BACK
                cameraProvider.bindToLifecycle(this, CameraSelector.Builder()
                    .requireLensFacing(facing).build(), analysis)
            } catch (e: Exception) {
                status.text = "Camera startup failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val oriented: Bitmap
        try {
            val original = proxy.toBitmap()
            val rotation = Matrix().apply {
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                if (frontCamera) postScale(-1f, 1f)
            }
            oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, rotation, true)
        } catch (e: Exception) {
            busy.set(false)
            runOnUiThread { status.text = "Unable to read camera frame: ${e.message}" }
            return
        } finally {
            proxy.close()
        }
        val input = InputImage.fromBitmap(oriented, 0)
        frameNumber++
        if (frameNumber % 6 == 1) {
            faceDetector.process(input)
                .addOnSuccessListener(processingExecutor) { faces ->
                    faceArea = faces.firstOrNull()?.let { buildFaceArea(it, oriented) }
                }
                .addOnFailureListener(processingExecutor) { faceArea = null }
        }
        val advanced = advancedSegmenter
        if (advanced == null) {
            processFallback(oriented, input)
            return
        }
        try {
            val result = advanced.segmentForVideo(BitmapImageBuilder(oriented).build(), SystemClock.uptimeMillis())
            val mask = result.categoryMask().orElseThrow {
                IllegalStateException("Multi-class skin segmentation returned no category mask")
            }
            val raw = ByteBufferExtractor.extract(mask)
            val mWidth = mask.width
            val mHeight = mask.height
            val confidence = FloatArray(mWidth * mHeight)
            for (i in confidence.indices) {
                // Official SelfieMulticlass labels: 2 = body-skin, 3 = face-skin.
                val category = raw.get(i).toInt() and 0xff
                confidence[i] = if (category == 2 || category == 3) 1f else 0f
            }
            publishProcessed(oriented, confidence, mWidth, mHeight, true)
        } catch (e: Exception) {
            // MediaPipe delegate failures are recoverable via the older person+chroma model.
            segmenterFailure = e.message
            try { advanced.close() } catch (_: Exception) {}
            advancedSegmenter = null
            processFallback(oriented, input)
            return
        } finally {
            if (advancedSegmenter != null) busy.set(false)
        }
    }

    private fun initializeAdvancedSegmenter() {
        fun tryDelegate(delegate: Delegate): ImageSegmenter {
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("selfie_multiclass_256x256.tflite")
                    .setDelegate(delegate)
                    .build())
                .setRunningMode(RunningMode.VIDEO)
                .setOutputCategoryMask(true)
                .setOutputConfidenceMasks(false)
                .build()
            return ImageSegmenter.createFromOptions(applicationContext, options)
        }
        try {
            advancedSegmenter = try {
                tryDelegate(Delegate.GPU)
            } catch (_: Exception) {
                tryDelegate(Delegate.CPU)
            }
            runOnUiThread { status.text = "Advanced face + body skin AI ready" }
        } catch (e: Exception) {
            advancedSegmenter = null
            segmenterFailure = e.message
            runOnUiThread { status.text = "Using fallback skin segmentation" }
        }
    }

    private fun processFallback(oriented: Bitmap, input: InputImage) {
        segmenter.process(input)
            .addOnSuccessListener(processingExecutor) { segmentation ->
                val mWidth = segmentation.width
                val mHeight = segmentation.height
                val confidence = FloatArray(mWidth * mHeight)
                segmentation.buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(confidence)
                publishProcessed(oriented, confidence, mWidth, mHeight, false)
            }
            .addOnFailureListener(processingExecutor) { e ->
                latestProcessed = oriented
                runOnUiThread {
                    preview.setImageBitmap(oriented)
                    status.text = "Segmentation unavailable: ${e.message}"
                }
            }
            .addOnCompleteListener(processingExecutor) { busy.set(false) }
    }

    private fun publishProcessed(
        oriented: Bitmap, confidence: FloatArray, maskW: Int, maskH: Int,
        semanticMask: Boolean
    ) {
        val w = oriented.width; val h = oriented.height
        val source = IntArray(w * h)
        oriented.getPixels(source, 0, w, 0, 0, w, h)
        val transformed = SkinToneEngine.transform(
            source, w, h, confidence, maskW, maskH,
            ToneSettings(intensity, warmth, lightness), faceArea, semanticMask
        )
        val bitmap = Bitmap.createBitmap(transformed, w, h, Bitmap.Config.ARGB_8888)
        latestProcessed = bitmap
        if (recorder.isRecording) recorder.record(bitmap)
        runOnUiThread {
            if (!isDestroyed) {
                preview.setImageBitmap(bitmap)
                if (!status.text.startsWith("● Recording") && !status.text.startsWith("Saving")) {
                    status.text = if (semanticMask) "Advanced face + body skin AI active" else
                        "Fallback person+skin-color detection active"
                }
            }
        }
    }

    private fun buildFaceArea(face: Face, frame: Bitmap): FaceArea {
        val b = face.boundingBox
        fun landmark(which: Int): Point2? = face.getLandmark(which)?.position?.let { Point2(it.x, it.y) }
        val cheekPoints = listOfNotNull(
            landmark(FaceLandmark.LEFT_CHEEK), landmark(FaceLandmark.RIGHT_CHEEK)
        ).ifEmpty {
            listOf(Point2(b.left + b.width() * 0.30f, b.top + b.height() * 0.62f),
                Point2(b.left + b.width() * 0.70f, b.top + b.height() * 0.62f))
        }
        val pix = IntArray(frame.width * frame.height)
        frame.getPixels(pix, 0, frame.width, 0, 0, frame.width, frame.height)
        val ref = SkinToneEngine.sampleCheeks(pix, frame.width, frame.height, cheekPoints)
        val mouth = landmark(FaceLandmark.MOUTH_BOTTOM) ?: Point2(
            b.left + b.width() * 0.50f, b.top + b.height() * 0.80f)
        return FaceArea(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            landmark(FaceLandmark.LEFT_EYE), landmark(FaceLandmark.RIGHT_EYE), mouth,
            ref?.first ?: 149f, ref?.second ?: 111f
        )
    }

    private fun takePhoto() {
        val frame = latestProcessed ?: return toast("Camera isn't ready")
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Xcology_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Xcology")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create image")
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    check(frame.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                } ?: error("Could not write image")
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                toast("Photo saved to Pictures/Xcology")
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            toast("Photo failed: ${e.message}")
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            recordButton.isEnabled = false
            status.text = "Saving video…"
            recorder.stop { uri, error ->
                runOnUiThread {
                    recordButton.isEnabled = true
                    recordButton.text = "● Record"
                    if (uri != null) {
                        lastSavedVideo = uri
                        status.text = "Video saved to Movies/Xcology"
                        toast("Saved silent MP4 in Movies/Xcology")
                    } else {
                        status.text = "Recording failed: ${error ?: "unknown error"}"
                    }
                }
            }
        } else {
            val first = latestProcessed ?: return toast("Wait for camera preview")
            try {
                recorder.start(first) { message -> runOnUiThread {
                    status.text = message
                    recordButton.text = "● Record"
                    toast(message)
                } }
                recordButton.text = "■ Stop"
                status.text = "● Recording processed video • no audio"
            } catch (e: Exception) {
                status.text = "Can't record: ${e.message}"
            }
        }
    }

    private fun shareLatestVideo() {
        val uri = lastSavedVideo ?: return toast("Record a video first")
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share saved Xcology video"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop { _, _ -> }
        provider?.unbindAll()
        segmenter.close()
        faceDetector.close()
        processingExecutor.execute { try { advancedSegmenter?.close() } catch (_: Exception) {} }
        processingExecutor.shutdown()
        super.onDestroy()
    }
    companion object { private const val CAMERA_PERMISSION = 1001 }
}
