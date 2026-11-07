package de.luftzeichnen.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {

    private lateinit var effectView: EffectView
    private lateinit var controls: View
    private lateinit var hint: TextView
    private lateinit var recLabel: TextView
    private lateinit var filterButton: TextView
    private lateinit var recordButton: View
    private lateinit var flash: View

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var processor: FrameProcessor
    private lateinit var recorder: VideoRecorder

    private var useFront = true
    private var cameraStarted = false
    private var controlsVisible = true

    private val pending = AtomicReference<Frame?>(null)
    private val deliver = Runnable { pending.getAndSet(null)?.let { onFrame(it) } }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                hint.setText(R.string.no_permission)
                hint.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        effectView = findViewById(R.id.effectView)
        controls = findViewById(R.id.controls)
        hint = findViewById(R.id.hint)
        recLabel = findViewById(R.id.recLabel)
        filterButton = findViewById(R.id.filterButton)
        recordButton = findViewById(R.id.recordButton)
        flash = findViewById(R.id.flash)

        cameraExecutor = Executors.newSingleThreadExecutor()
        recorder = VideoRecorder(applicationContext)
        processor = FrameProcessor(applicationContext) { frame ->
            if (pending.getAndSet(frame) == null) effectView.post(deliver)
        }
        processor.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, getString(R.string.tracker_failed, msg), Toast.LENGTH_LONG).show()
            }
        }

        filterButton.text = processor.filterMode.label
        filterButton.setOnClickListener {
            processor.filterMode = processor.filterMode.next()
            filterButton.text = processor.filterMode.label
        }
        findViewById<View>(R.id.photoButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.flipButton).setOnClickListener {
            useFront = !useFront
            processor.resetTracking()
            if (cameraStarted) bindCamera()
        }
        recordButton.setOnClickListener { toggleRecording() }
        effectView.setOnClickListener {
            controlsVisible = !controlsVisible
            controls.visibility = if (controlsVisible) View.VISIBLE else View.INVISIBLE
            if (!controlsVisible) hint.visibility = View.GONE
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        cameraStarted = true
        bindCamera()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Kamera nicht verfügbar", e)
                return@addListener
            }
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            val front = useFront
            analysis.setAnalyzer(cameraExecutor) { image -> processor.process(image, front) }

            var cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            if (!provider.hasCamera(cameraSelector)) {
                useFront = !front
                cameraSelector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val mirror = useFront
                analysis.setAnalyzer(cameraExecutor) { image -> processor.process(image, mirror) }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Kamera konnte nicht gestartet werden", e)
                Toast.makeText(this, e.message ?: "Kamera-Fehler", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(frame: Frame) {
        effectView.submit(frame)

        if (controlsVisible) {
            when (frame.hands.size) {
                0 -> { hint.setText(R.string.hint_two_hands); hint.visibility = View.VISIBLE }
                1 -> { hint.setText(R.string.hint_one_hand); hint.visibility = View.VISIBLE }
                else -> hint.visibility = View.GONE
            }
        }

        if (recorder.isRecording) {
            recorder.drawFrame { c, w, h -> effectView.render(c, w, h) }
            val sec = (SystemClock.elapsedRealtime() - recorder.startedAt) / 1000
            recLabel.text = String.format("● REC  %02d:%02d", sec / 60, sec % 60)
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecording()
            return
        }
        val w = effectView.width
        val h = effectView.height
        if (w == 0 || h == 0) return
        try {
            recorder.start(w, h)
            recordButton.setBackgroundResource(R.drawable.rec_active)
            recLabel.text = "● REC  00:00"
            recLabel.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Aufnahme fehlgeschlagen", e)
            Toast.makeText(this, getString(R.string.record_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        val where = recorder.stop()
        recordButton.setBackgroundResource(R.drawable.rec_idle)
        recLabel.visibility = View.GONE
        val msg = if (where != null) getString(R.string.video_saved, where) else getString(R.string.save_failed)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun takePhoto() {
        val w = effectView.width
        val h = effectView.height
        if (w == 0 || h == 0 || effectView.frame == null) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        effectView.render(Canvas(bmp), w, h)
        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(250).start()
        Thread {
            val msg = try {
                getString(R.string.photo_saved, savePhoto(applicationContext, bmp))
            } catch (e: Exception) {
                Log.e(TAG, "Foto speichern fehlgeschlagen", e)
                getString(R.string.save_failed)
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    override fun onPause() {
        if (recorder.isRecording) stopRecording()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.execute { processor.close() }
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "LuftZeichnen"
    }
}
