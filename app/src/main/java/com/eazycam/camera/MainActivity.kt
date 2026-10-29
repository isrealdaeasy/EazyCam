package com.eazycam.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: View
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedFilter = Filter.NATURAL
    private var exposure = 0
    private var zoom = 1f
    private var manualIso = 400
    private var manualShutterNs = 10_000_000L
    private var manualEnabled = false
    private var flashEnabled = false
    private var supportedIso: IntRange? = null
    private var supportedExposureNs: LongRange? = null
    private var whiteBalance = CaptureRequest.CONTROL_AWB_MODE_AUTO

    private enum class Filter(val label: String) {
        NATURAL("Natural"), VIVID("Vivid"), CLEAN("Clean White"), MONO("B&W"), CINEMATIC("Cinematic"), WARM("Warm")
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Camera permission is needed to use EazyCam.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val brand = TextView(this).apply {
            text = "EazyCam"; textSize = 23f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val pro = pill("PRO MODE", Color.rgb(185, 255, 69), Color.BLACK)
        top.addView(brand, LinearLayout.LayoutParams(0, dp(42), 1f))
        top.addView(pro)
        overlay.addView(top)

        val topTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8)) }
        topTools.addView(toolButton("⚡", "Flash") { flashEnabled = !flashEnabled; updateFlashUi(); applyCameraSettings() })
        topTools.addView(toolButton("1×", "Zoom") { cycleZoom() })
        topTools.addView(toolButton("AE", "Exposure") { showExposureControl() })
        topTools.addView(toolButton("M", "Manual") { manualEnabled = !manualEnabled; applyCameraSettings(); Toast.makeText(this, if (manualEnabled) "Manual exposure enabled where supported" else "Automatic exposure", Toast.LENGTH_SHORT).show() })
        overlay.addView(topTools)

        val spacer = View(this)
        overlay.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        statusText = TextView(this).apply {
            text = "READY  •  AUTO WB  •  ISO AUTO"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(Color.argb(150, 10, 10, 10), 30)
        }
        overlay.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val filtersScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        Filter.values().forEach { filter ->
            val b = TextView(this).apply {
                text = filter.label; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(11), dp(16), dp(11))
                setTextColor(if (filter == selectedFilter) Color.BLACK else Color.WHITE)
                background = rounded(if (filter == selectedFilter) Color.rgb(185, 255, 69) else Color.argb(155, 24, 24, 24), 30)
                setOnClickListener { selectedFilter = filter; refreshFilters(filters); statusText.text = "${filter.label.uppercase(Locale.getDefault())}  •  PHOTO PROFILE" }
            }
            b.tag = filter.name
            filters.addView(b, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        }
        filtersScroll.addView(filters)
        overlay.addView(filtersScroll, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(14) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
        controls.addView(toolButton("ISO", "ISO") { showIsoControl() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        controls.addView(toolButton("1/100", "Shutter") { showShutterControl() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        val shutterWrap = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(82), dp(82)).apply { leftMargin = dp(8); rightMargin = dp(8) } }
        captureButton = View(this).apply {
            background = ringDrawable()
            setOnClickListener { takePhoto() }
        }
        shutterWrap.addView(captureButton, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        controls.addView(shutterWrap)
        controls.addView(toolButton("WB", "White balance") { cycleWhiteBalance() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        controls.addView(toolButton("↻", "Flip camera") { switchCamera() }, LinearLayout.LayoutParams(0, dp(60), 1f))
        overlay.addView(controls)

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val c = camera
                if (c != null) {
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    Toast.makeText(this, "Focus set", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
    }

    private fun refreshFilters(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i) as TextView
            val f = Filter.valueOf(v.tag as String)
            v.setTextColor(if (f == selectedFilter) Color.BLACK else Color.WHITE)
            v.background = rounded(if (f == selectedFilter) Color.rgb(185, 255, 69) else Color.argb(155, 24, 24, 24), 30)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
            readCameraCapabilities()
            applyCameraSettings()
            statusText.text = "READY  •  ${if (supportedIso != null) "MANUAL ISO AVAILABLE" else "AUTO ISO ONLY"}"
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to start this camera: ${e.localizedMessage ?: "unsupported configuration"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readCameraCapabilities() {
        supportedIso = null; supportedExposureNs = null
        try {
            val info = camera?.cameraInfo ?: return
            val camera2Info = Camera2CameraInfo.from(info)
            val chars = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (chars != null) supportedIso = chars.lower..chars.upper
            val times = camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (times != null) supportedExposureNs = times.lower..times.upper
        } catch (_: Exception) { }
    }

    private fun applyCameraSettings() {
        val c = camera ?: return
        c.cameraControl.setExposureCompensationIndex(exposure)
        c.cameraControl.setZoomRatio(zoom)
        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        try {
            val opts = CaptureRequestOptions.Builder()
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalance)
            if (manualEnabled && supportedIso != null && supportedExposureNs != null) {
                val iso = manualIso.coerceIn(supportedIso!!.first, supportedIso!!.last)
                val shutter = manualShutterNs.coerceIn(supportedExposureNs!!.first, supportedExposureNs!!.last)
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                opts.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                statusText.text = "MANUAL  •  ISO $iso  •  ${formatShutter(shutter)}  •  ${wbLabel()}"
            } else {
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                statusText.text = "AUTO EXPOSURE  •  ${wbLabel()}  •  ${zoom}×"
            }
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(opts.build())
        } catch (_: Exception) {
            statusText.text = "AUTO EXPOSURE  •  SOME MANUAL CONTROLS UNSUPPORTED"
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run { Toast.makeText(this, "Camera is starting", Toast.LENGTH_SHORT).show(); return }
        capture.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        val file = File(cacheDir, "eazycam_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw IllegalStateException("Could not decode captured image")
                    val processed = processPhoto(bitmap, selectedFilter)
                    val saved = saveToGallery(processed)
                    file.delete()
                    runOnUiThread { Toast.makeText(this@MainActivity, if (saved != null) "Photo saved to Pictures/EazyCam" else "Could not save photo", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    file.delete()
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo processing failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun processPhoto(source: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.NATURAL) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cm = when (filter) {
            Filter.NATURAL -> android.graphics.ColorMatrix()
            Filter.VIVID -> android.graphics.ColorMatrix().apply { setSaturation(1.34f) }
            Filter.CLEAN -> android.graphics.ColorMatrix(floatArrayOf(
                1.07f,0f,0f,0f,5f, 0f,1.07f,0f,0f,5f, 0f,0f,1.07f,0f,5f, 0f,0f,0f,1f,0f
            ))
            Filter.MONO -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
            Filter.CINEMATIC -> android.graphics.ColorMatrix(floatArrayOf(
                1.12f,0.02f,0f,0f,-5f, 0f,1.02f,0.01f,0f,0f, 0.01f,0.02f,0.94f,0f,4f, 0f,0f,0f,1f,0f
            ))
            Filter.WARM -> android.graphics.ColorMatrix(floatArrayOf(
                1.08f,0f,0f,0f,8f, 0f,1.02f,0f,0f,2f, 0f,0f,0.91f,0f,-4f, 0f,0f,0f,1f,0f
            ))
        }
        if (filter == Filter.VIVID || filter == Filter.CINEMATIC) {
            val contrast = if (filter == Filter.VIVID) 1.07f else 1.10f
            val translate = 128f * (1f - contrast)
            val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
                contrast,0f,0f,0f,translate, 0f,contrast,0f,0f,translate,
                0f,0f,contrast,0f,translate, 0f,0f,0f,1f,0f
            ))
            cm.postConcat(contrastMatrix)
        }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, null, Rect(0, 0, out.width, out.height), paint)
        source.recycle()
        return out
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val name = "EazyCam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EazyCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw IllegalStateException("JPEG compression failed")
        }
        bitmap.recycle()
        return uri
    }

    private fun showExposureControl() = showSliderDialog("Exposure compensation", -4, 4, exposure + 4, "Stops") { v -> exposure = v - 4; applyCameraSettings() }
    private fun showIsoControl() {
        val range = supportedIso
        if (range == null) { Toast.makeText(this, "This camera does not expose manual ISO control.", Toast.LENGTH_LONG).show(); return }
        showSliderDialog("ISO sensitivity", 0, 100, isoToProgress(manualIso, range), "ISO") { p -> manualIso = progressToIso(p, range); manualEnabled = true; applyCameraSettings() }
    }
    private fun showShutterControl() {
        val range = supportedExposureNs
        if (range == null) { Toast.makeText(this, "This camera does not expose manual shutter control.", Toast.LENGTH_LONG).show(); return }
        showSliderDialog("Shutter speed", 0, 100, shutterToProgress(manualShutterNs, range), "speed") { p -> manualShutterNs = progressToShutter(p, range); manualEnabled = true; applyCameraSettings() }
    }

    private fun showSliderDialog(title: String, minValue: Int, maxValue: Int, initial: Int, suffix: String, onChange: (Int) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val valueText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = "$initial $suffix" }
        val bar = SeekBar(this).apply { max = maxValue - minValue; progress = initial - minValue }
        layout.addView(valueText)
        layout.addView(bar)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + minValue
                valueText.text = "$value $suffix"
                onChange(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setPositiveButton("Done", null).setNegativeButton("Auto") { _, _ -> manualEnabled = false; exposure = 0; applyCameraSettings() }
            .show().apply { window?.setBackgroundDrawableResource(android.R.color.black) }
    }

    private fun cycleWhiteBalance() {
        val modes = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
        whiteBalance = modes[(modes.indexOf(whiteBalance) + 1) % modes.size]
        applyCameraSettings()
    }
    private fun wbLabel() = when (whiteBalance) {
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT WB"
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY WB"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT WB"
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "TUNGSTEN WB"
        else -> "AUTO WB"
    }
    private fun cycleZoom() { zoom = when { zoom < 1.4f -> 1.5f; zoom < 1.9f -> 2f; else -> 1f }; applyCameraSettings() }
    private fun switchCamera() { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; bindCamera() }
    private fun updateFlashUi() { statusText.text = if (flashEnabled) "FLASH ON" else "FLASH OFF" }
    private fun formatShutter(ns: Long): String = if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}s" else "1/${max(1L, 1_000_000_000L / ns)}"
    private fun isoToProgress(iso: Int, range: IntRange) = (((iso - range.first).toFloat() / max(1, range.last - range.first)) * 100).toInt().coerceIn(0, 100)
    private fun progressToIso(p: Int, range: IntRange) = range.first + ((range.last - range.first) * p / 100)
    private fun shutterToProgress(ns: Long, range: LongRange): Int {
        val minL = kotlin.math.ln(range.first.toDouble()); val maxL = kotlin.math.ln(range.last.toDouble())
        return (((kotlin.math.ln(ns.toDouble()) - minL) / (maxL - minL)) * 100).toInt().coerceIn(0, 100)
    }
    private fun progressToShutter(p: Int, range: LongRange): Long = kotlin.math.exp(kotlin.math.ln(range.first.toDouble()) + (kotlin.math.ln(range.last.toDouble()) - kotlin.math.ln(range.first.toDouble())) * p / 100.0).toLong()

    private fun toolButton(symbol: String, label: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(3), dp(5), dp(3)); setOnClickListener { action() } }
        val icon = TextView(this).apply { text = symbol; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        val text = TextView(this).apply { this.text = label; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        box.addView(icon); box.addView(text)
        return box
    }
    private fun pill(textValue: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        text = textValue; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg); gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = rounded(bg, 40)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ringDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(5), Color.rgb(185, 255, 69))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
