package com.vea.cementbagcounter

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private val industrialBlack = Color.rgb(23, 25, 28)
    private val industrialGold = Color.rgb(181, 138, 55)
    private val industrialLightGold = Color.rgb(229, 189, 105)
    private val industrialButton = Color.rgb(41, 43, 47)
    private val industrialBorder = Color.rgb(85, 87, 91)

    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var guideImage: ImageView

    private var guideBitmap: Bitmap? = null

    private var guideVisible = true
    private var guideOpacity = 48
    private var guideZoom = 100

    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var detector: YoloDetector

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = true

    @Volatile
    private var setupMode = true

    private var totalCount = 0
    private var sessionSaved = false

    private val tracker = SimpleLineCounter()

    private val prefs by lazy {
        getSharedPreferences("sugar_count_history", MODE_PRIVATE)
    }

    private var pendingExcelData: ByteArray? = null

    private val isEnglish: Boolean
        get() = prefs.getString("language", "fa") == "en"

    private fun tr(fa: String, en: String): String {
        return if (isEnglish) en else fa
    }

    private val excelLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->
            if (uri != null) {
                saveExcelFile(uri)
            } else {
                pendingExcelData = null
            }
        }

    private val permission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else if (::statusText.isInitialized) {
                statusText.text = tr(
                    "مجوز دوربین داده نشد",
                    "Camera permission denied"
                )
            }
        }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!LicenseManager.isActivated(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }

        try {
            detector = YoloDetector(this)
        } catch (e: Exception) {
            Log.e("SugarCounter", "Model initialization failed", e)

            AlertDialog.Builder(this)
                .setTitle(tr("خطا در بارگذاری مدل", "Model Loading Error"))
                .setMessage(
                    tr(
                        "فایل مدل best.onnx در برنامه یافت نشد یا قابل بارگذاری نیست.\n\n${e.message}",
                        "The best.onnx model was not found or could not be loaded.\n\n${e.message}"
                    )
                )
                .setPositiveButton(tr("بستن", "Close")) { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()

            return
        }

        buildUi()
        restoreLineSettings()

        running = true
        setupMode = true
        overlay.isSetup = true

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    // --------------------------------------------------
    // دکمه‌ها
    // --------------------------------------------------

    private fun createIndustrialButton(
        title: String,
        primary: Boolean = false
    ): Button {

        return Button(this).apply {

            text = title
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD

            setTextColor(
                if (primary) industrialBlack else industrialLightGold
            )

            background = GradientDrawable().apply {
                setColor(if (primary) industrialGold else industrialButton)
                cornerRadius = dp(14).toFloat()
                setStroke(
                    dp(1),
                    if (primary) industrialGold else industrialBorder
                )
            }

            elevation = dp(3).toFloat()
            minHeight = dp(52)
            minimumHeight = dp(52)
        }
    }

    // --------------------------------------------------
    // رابط کاربری
    // --------------------------------------------------

    private fun buildUi() {

        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(industrialBlack)
        }

        val header = TextView(this).apply {
            text = tr(
                "شمارشگر هوشمند کیسه‌های قند",
                "Smart Sugar Bag Counter"
            )
            textSize = 21f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
            typeface = Typeface.DEFAULT_BOLD
        }

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        guideVisible = prefs.getBoolean("guide_visible", true)
        guideOpacity = prefs.getInt("guide_opacity", 48).coerceIn(0, 255)
        guideZoom = prefs.getInt("guide_zoom", 100).coerceIn(50, 200)

        guideImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = guideOpacity / 255f
            visibility = if (guideVisible) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
            contentDescription = tr(
                "تصویر راهنمای تنظیم دوربین",
                "Camera alignment guide"
            )
        }

        loadGuideImage()

        overlay = DetectionOverlay(this).apply {
            isSetup = true
        }

        val cameraFrame = FrameLayout(this)

        cameraFrame.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        cameraFrame.addView(
            guideImage,
            FrameLayout.LayoutParams(
                dp(280),
                dp(220),
                Gravity.CENTER
            )
        )

        cameraFrame.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        cameraFrame.addOnLayoutChangeListener {
                _, left, top, right, bottom, _, _, _, _ ->

            val width = right - left
            val height = bottom - top

            if (width > 0 && height > 0) {

                val guideWidth =
                    (width * 0.88f * guideZoom / 100f).toInt()

                val guideHeight =
                    (height * 0.82f * guideZoom / 100f).toInt()

                guideImage.layoutParams = FrameLayout.LayoutParams(
                    guideWidth,
                    guideHeight,
                    Gravity.CENTER
                )

                guideImage.requestLayout()
            }
        }

        countText = TextView(this).apply {
            text = tr("تعداد: 0", "Count: 0")
            textSize = 30f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(4))
            typeface = Typeface.DEFAULT_BOLD
        }

        statusText = TextView(this).apply {
            text = tr(
                "خطوط شمارش را تنظیم کنید",
                "Configure counting lines"
            )
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(12))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun addActionButton(
            parent: LinearLayout,
            label: String,
            action: () -> Unit
        ) {

            val button = createIndustrialButton(
                title = label,
                primary = label == tr("شروع شمارش", "Start Counting")
            ).apply {
                setOnClickListener { action() }
            }

            parent.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(52),
                    1f
                ).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            )
        }

        addActionButton(firstRow, tr("تنظیم خطوط", "Setup Lines")) {
            enterSetupMode()
        }

        addActionButton(firstRow, tr("شروع شمارش", "Start Counting")) {
            startCounting()
        }

        addActionButton(firstRow, tr("توقف", "Stop")) {
            stopCounting()
        }

        addActionButton(secondRow, tr("ریست", "Reset")) {
            resetCounting()
        }

        addActionButton(secondRow, tr("تاریخچه", "History")) {
            showHistory()
        }

        addActionButton(secondRow, tr("آمار", "Statistics")) {
            showStatistics()
        }

        addActionButton(secondRow, "Excel") {
            exportExcel()
        }

        val guideSettingsButton =
            createIndustrialButton(
                tr("تنظیم تصویر راهنما", "Guide Image Settings")
            ).apply {
                setOnClickListener {
                    showGuideSettings()
                }
            }

        val languageButton =
            createIndustrialButton(
                tr("زبان / Language", "Language / زبان")
            ).apply {
                setOnClickListener {
                    showLanguageSettings()
                }
            }

        val aboutButton =
            createIndustrialButton(
                tr("درباره برنامه", "About")
            ).apply {
                setOnClickListener {
                    showAbout()
                }
            }

        controls.addView(firstRow)
        controls.addView(secondRow)

        controls.addView(
            guideSettingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        )

        controls.addView(
            languageButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        )

        controls.addView(
            aboutButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        )

        base.addView(header)

        base.addView(
            cameraFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        base.addView(countText)
        base.addView(statusText)
        base.addView(controls)

        setContentView(base)
    }

    // --------------------------------------------------
    // زبان
    // --------------------------------------------------

    private fun showLanguageSettings() {

        val options = arrayOf(
            "فارسی",
            "English"
        )

        AlertDialog.Builder(this)
            .setTitle("Language / زبان")
            .setSingleChoiceItems(
                options,
                if (isEnglish) 1 else 0
            ) { dialog, which ->

                prefs.edit()
                    .putString(
                        "language",
                        if (which == 1) "en" else "fa"
                    )
                    .apply()

                dialog.dismiss()

                recreate()
            }
            .setNegativeButton(
                tr("انصراف", "Cancel"),
                null
            )
            .show()
    }

    // --------------------------------------------------
    // تنظیم خطوط و محدوده ROI
    // --------------------------------------------------

    private fun enterSetupMode() {

        if (totalCount > 0 && !sessionSaved) {
            saveCurrentSession()
            sessionSaved = true
        }

        running = true
        setupMode = true
        overlay.isSetup = true

        tracker.reset()

        restoreLineSettings()

        statusText.text = tr(
            "خطوط افقی و محدوده شمارش را تنظیم کنید",
            "Adjust the counting lines and counting area"
        )
    }

    private fun startCounting() {

        saveLineSettings()

        tracker.reset()

        tracker.setLines(
            overlay.lineTop,
            overlay.lineBottom
        )

        tracker.setRoi(
            overlay.roiLeft,
            overlay.roiRight
        )

        setupMode = false
        overlay.isSetup = false
        running = true

        sessionSaved = false

        statusText.text = tr(
            "شمارش فعال است",
            "Counting is active"
        )
    }

    private fun saveLineSettings() {

        prefs.edit()
            .putFloat("line_top", overlay.lineTop)
            .putFloat("line_bottom", overlay.lineBottom)
            .putFloat("roi_left", overlay.roiLeft)
            .putFloat("roi_right", overlay.roiRight)
            .apply()
    }

    private fun restoreLineSettings() {

        if (!::overlay.isInitialized) return

        overlay.lineTop =
            prefs.getFloat("line_top", overlay.lineTop)

        overlay.lineBottom =
            prefs.getFloat("line_bottom", overlay.lineBottom)

        overlay.roiLeft =
            prefs.getFloat("roi_left", overlay.roiLeft)

        overlay.roiRight =
            prefs.getFloat("roi_right", overlay.roiRight)

        overlay.invalidate()
    }

    // --------------------------------------------------
    // تصویر راهنما
    // --------------------------------------------------

    private fun loadGuideImage() {

        try {

            guideBitmap = assets.open("guide_overlay.jpg").use { stream ->
                BitmapFactory.decodeStream(stream)
            }

            if (guideBitmap == null) {
                guideImage.visibility = View.GONE
                return
            }

            guideImage.setImageBitmap(guideBitmap)
            guideImage.alpha = guideOpacity / 255f

            guideImage.visibility =
                if (guideVisible) View.VISIBLE else View.GONE

        } catch (e: Exception) {

            guideBitmap = null
            guideImage.visibility = View.GONE

            Log.e(
                "SugarCounter",
                "Could not load guide_overlay.jpg",
                e
            )
        }
    }

    // --------------------------------------------------
    // تنظیمات تصویر راهنما
    // --------------------------------------------------

    private fun showGuideSettings() {

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
            setBackgroundColor(industrialBlack)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = tr(
                "تنظیم تصویر راهنما",
                "Guide Image Settings"
            )
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
        }

        content.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(45)
            )
        )

        val guideSwitch = Switch(this).apply {
            text = tr(
                "نمایش تصویر راهنما",
                "Show Guide Image"
            )
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = guideVisible
        }

        content.addView(
            guideSwitch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(55)
            )
        )

        // شفافیت

        val opacityLabel = TextView(this).apply {
            text = tr(
                "شفافیت تصویر راهنما: ${guideOpacity * 100 / 255}%",
                "Guide opacity: ${guideOpacity * 100 / 255}%"
            )
            textSize = 14f
            setTextColor(industrialLightGold)
            setPadding(0, dp(12), 0, dp(5))
        }

        content.addView(opacityLabel)

        val opacitySeekBar = SeekBar(this).apply {
            max = 100
            progress = guideOpacity * 100 / 255
        }

        content.addView(
            opacitySeekBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        // بزرگ‌نمایی

        val zoomLabel = TextView(this).apply {
            text = tr(
                "بزرگ‌نمایی تصویر: ${guideZoom}%",
                "Guide zoom: ${guideZoom}%"
            )
            textSize = 14f
            setTextColor(industrialLightGold)
            setPadding(0, dp(12), 0, dp(5))
        }

        content.addView(zoomLabel)

        val zoomSeekBar = SeekBar(this).apply {
            max = 150
            progress = guideZoom - 50
        }

        content.addView(
            zoomSeekBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val helpText = TextView(this).apply {
            text = tr(
                "تصویر راهنما متناسب با صفحه دوربین تنظیم می‌شود. بزرگ‌نمایی از ۵۰ تا ۲۰۰ درصد قابل تغییر است.",
                "The guide image adjusts to the camera screen. Zoom can be changed from 50% to 200%."
            )
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }

        content.addView(helpText)

        guideSwitch.setOnCheckedChangeListener { _, checked ->

            guideVisible = checked

            guideImage.visibility =
                if (checked) View.VISIBLE else View.GONE

            prefs.edit()
                .putBoolean("guide_visible", checked)
                .apply()
        }

        opacitySeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    guideOpacity = progress * 255 / 100
                    guideImage.alpha = guideOpacity / 255f

                    opacityLabel.text = tr(
                        "شفافیت تصویر راهنما: $progress%",
                        "Guide opacity: $progress%"
                    )
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {

                    prefs.edit()
                        .putInt("guide_opacity", guideOpacity)
                        .apply()
                }
            }
        )

        zoomSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {

                    guideZoom = progress + 50

                    zoomLabel.text = tr(
                        "بزرگ‌نمایی تصویر: ${guideZoom}%",
                        "Guide zoom: ${guideZoom}%"
                    )

                    updateGuideSize()
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {
                }

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {

                    prefs.edit()
                        .putInt("guide_zoom", guideZoom)
                        .apply()
                }
            }
        )

        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton(
                tr("ذخیره و بستن", "Save and Close")
            ) { _, _ ->

                prefs.edit()
                    .putBoolean("guide_visible", guideVisible)
                    .putInt("guide_opacity", guideOpacity)
                    .putInt("guide_zoom", guideZoom)
                    .apply()
            }
            .setNegativeButton(
                tr("بستن", "Close"),
                null
            )
            .show()
    }

    private fun updateGuideSize() {

        if (!::guideImage.isInitialized) return

        val parent = guideImage.parent as? FrameLayout ?: return

        val width = parent.width
        val height = parent.height

        if (width <= 0 || height <= 0) return

        val guideWidth =
            (width * 0.88f * guideZoom / 100f).toInt()

        val guideHeight =
            (height * 0.82f * guideZoom / 100f).toInt()

        guideImage.layoutParams = FrameLayout.LayoutParams(
            guideWidth,
            guideHeight,
            Gravity.CENTER
        )

        guideImage.requestLayout()
    }

    // --------------------------------------------------
    // دوربین
    // --------------------------------------------------

    private fun startCamera() {

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({

            try {

                val provider = future.get()

                val previewUseCase = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(executor) { image ->

                    if (!running || isFinishing || isDestroyed) {
                        image.close()
                        return@setAnalyzer
                    }

                    var bitmap: Bitmap? = null

                    try {

                        bitmap = ImageUtil.toBitmap(image)

                        if (bitmap == null || bitmap.isRecycled) {
                            return@setAnalyzer
                        }

                        val detections = detector.detect(bitmap)

                        val frameWidth = bitmap.width
                        val frameHeight = bitmap.height

                        runOnUiThread {

                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }

                            if (setupMode) {

                                overlay.setDetections(
                                    detections,
                                    frameWidth,
                                    frameHeight
                                )

                            } else {

                                val trackingResult =
                                    tracker.updateWithTracks(detections)

                                overlay.setTrackedDetections(
                                    trackingResult.trackedDetections,
                                    frameWidth,
                                    frameHeight
                                )

                                if (trackingResult.newCounts > 0) {

                                    totalCount += trackingResult.newCounts

                                    countText.text = tr(
                                        "تعداد: $totalCount",
                                        "Count: $totalCount"
                                    )
                                }
                            }
                        }

                    } catch (e: Exception) {

                        Log.e(
                            "SugarCounter",
                            "Detection error",
                            e
                        )

                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                statusText.text = tr(
                                    "خطا در پردازش تصویر",
                                    "Image processing error"
                                )
                            }
                        }

                    } finally {

                        bitmap?.let {
                            if (!it.isRecycled) {
                                it.recycle()
                            }
                        }

                        image.close()
                    }
                }

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysis
                )

            } catch (e: Exception) {

                Log.e(
                    "SugarCounter",
                    "Camera initialization failed",
                    e
                )

                if (::statusText.isInitialized) {
                    statusText.text = tr(
                        "خطای راه‌اندازی دوربین",
                        "Camera initialization error"
                    )
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // --------------------------------------------------
    // توقف شمارش
    // --------------------------------------------------

    private fun stopCounting() {

        if (!running) {
            statusText.text = tr(
                "شمارش قبلاً متوقف شده است",
                "Counting is already stopped"
            )
            return
        }

        running = false

        saveLineSettings()

        if (totalCount > 0 && !sessionSaved) {
            saveCurrentSession()
            sessionSaved = true
        }

        statusText.text = tr(
            "شمارش متوقف شد",
            "Counting stopped"
        )
    }

    // --------------------------------------------------
    // ریست
    // --------------------------------------------------

    private fun resetCounting() {

        AlertDialog.Builder(this)
            .setTitle(tr("ریست شمارش", "Reset Counter"))
            .setMessage(
                tr(
                    "آیا شمارش فعلی از صفر شروع شود؟",
                    "Do you want to reset the current count?"
                )
            )
            .setNegativeButton(
                tr("انصراف", "Cancel"),
                null
            )
            .setPositiveButton(
                tr("ریست", "Reset")
            ) { _, _ ->

                totalCount = 0

                tracker.reset()

                countText.text = tr(
                    "تعداد: 0",
                    "Count: 0"
                )

                sessionSaved = false
                running = true

                statusText.text =
                    if (setupMode) {
                        tr(
                            "خطوط شمارش را تنظیم کنید",
                            "Configure counting lines"
                        )
                    } else {
                        tr(
                            "شمارش از صفر شروع شد",
                            "Counting restarted from zero"
                        )
                    }
            }
            .show()
    }

    // --------------------------------------------------
    // تاریخچه
    // --------------------------------------------------

    private fun saveCurrentSession() {

        val history = getHistory()

        val item = JSONObject().apply {
            put("count", totalCount)
            put("timestamp", System.currentTimeMillis())
            put("date", getPersianDate())
            put("time", getCurrentTime())
        }

        history.put(item)

        prefs.edit()
            .putString("records", history.toString())
            .apply()
    }

    private fun getHistory(): JSONArray {

        val saved = prefs.getString("records", "[]") ?: "[]"

        return try {
            JSONArray(saved)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun showHistory() {

        val history = getHistory()

        if (history.length() == 0) {

            AlertDialog.Builder(this)
                .setTitle(tr("تاریخچه شمارش", "Counting History"))
                .setMessage(
                    tr(
                        "هنوز سابقه‌ای ثبت نشده است.",
                        "No records have been saved yet."
                    )
                )
                .setPositiveButton(
                    tr("باشه", "OK"),
                    null
                )
                .show()

            return
        }

        val items = ArrayList<String>()

        for (i in history.length() - 1 downTo 0) {

            val item = history.getJSONObject(i)

            items.add(
                tr(
                    "تاریخ: ${item.optString("date")}\n" +
                        "ساعت: ${item.optString("time")}\n" +
                        "تعداد کیسه: ${item.optInt("count")}",
                    "Date: ${item.optString("date")}\n" +
                        "Time: ${item.optString("time")}\n" +
                        "Bag count: ${item.optInt("count")}"
                )
            )
        }

        val text = items.joinToString("\n\n──────────────\n\n")

        AlertDialog.Builder(this)
            .setTitle(
                tr(
                    "تاریخچه شمارش‌ها",
                    "Counting History"
                )
            )
            .setMessage(text)
            .setPositiveButton(
                tr("بستن", "Close"),
                null
            )
            .setNeutralButton(
                tr("پاک کردن تاریخچه", "Clear History")
            ) { _, _ ->

                AlertDialog.Builder(this)
                    .setTitle(
                        tr("حذف تاریخچه", "Delete History")
                    )
                    .setMessage(
                        tr(
                            "آیا از حذف تمام سوابق مطمئن هستید؟",
                            "Are you sure you want to delete all records?"
                        )
                    )
                    .setNegativeButton(
                        tr("انصراف", "Cancel"),
                        null
                    )
                    .setPositiveButton(
                        tr("حذف", "Delete")
                    ) { _, _ ->

                        prefs.edit()
                            .putString("records", "[]")
                            .apply()
                    }
                    .show()
            }
            .show()
    }

    // --------------------------------------------------
    // آمار
    // --------------------------------------------------

    private fun showStatistics() {

        val history = getHistory()

        val now = System.currentTimeMillis()

        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val weekStart = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.SATURDAY
            set(Calendar.DAY_OF_WEEK, Calendar.SATURDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var daily = 0
        var weekly = 0
        var monthly = 0
        var all = 0

        for (i in 0 until history.length()) {

            val item = history.getJSONObject(i)

            val count = item.optInt("count")
            val timestamp = item.optLong("timestamp")

            all += count

            if (timestamp in dayStart..now) {
                daily += count
            }

            if (timestamp in weekStart..now) {
                weekly += count
            }

            if (timestamp in monthStart..now) {
                monthly += count
            }
        }

        val message = if (isEnglish) {

            """
            Bag Counting Statistics

            Today: $daily bags

            This week: $weekly bags

            This month: $monthly bags

            Total records: $all bags
            """.trimIndent()

        } else {

            """
            آمار شمارش کیسه‌ها

            امروز: $daily کیسه

            این هفته: $weekly کیسه

            این ماه: $monthly کیسه

            مجموع کل سوابق: $all کیسه
            """.trimIndent()
        }

        AlertDialog.Builder(this)
            .setTitle(
                tr("آمار عملکرد", "Statistics")
            )
            .setMessage(message)
            .setPositiveButton(
                tr("بستن", "Close"),
                null
            )
            .show()
    }

    // --------------------------------------------------
    // اکسل
    // --------------------------------------------------

    private fun exportExcel() {

        val history = getHistory()

        if (history.length() == 0) {

            AlertDialog.Builder(this)
                .setMessage(
                    tr(
                        "برای ساخت گزارش، ابتدا باید سابقه شمارش داشته باشید.",
                        "You need counting records before creating a report."
                    )
                )
                .setPositiveButton(
                    tr("باشه", "OK"),
                    null
                )
                .show()

            return
        }

        try {

            pendingExcelData = createExcelFile(history)

            excelLauncher.launch(
                if (isEnglish) {
                    "Sugar_Bag_Count_Report.xlsx"
                } else {
                    "گزارش_شمارش_کیسه_قند.xlsx"
                }
            )

        } catch (e: Exception) {

            pendingExcelData = null

            Log.e(
                "SugarCounter",
                "Excel creation error",
                e
            )

            AlertDialog.Builder(this)
                .setTitle(
                    tr("خطای ساخت فایل", "File Creation Error")
                )
                .setMessage(
                    tr(
                        "ساخت فایل Excel با خطا مواجه شد.",
                        "Failed to create the Excel file."
                    )
                )
                .setPositiveButton(
                    tr("باشه", "OK"),
                    null
                )
                .show()
        }
    }

    private fun saveExcelFile(uri: Uri) {

        val data = pendingExcelData ?: return

        try {

            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Output stream unavailable")

            output.use {
                it.write(data)
                it.flush()
            }

            AlertDialog.Builder(this)
                .setTitle(
                    tr("ذخیره موفق", "Saved Successfully")
                )
                .setMessage(
                    tr(
                        "فایل Excel با موفقیت ذخیره شد.",
                        "Excel file saved successfully."
                    )
                )
                .setPositiveButton(
                    tr("باشه", "OK"),
                    null
                )
                .show()

        } catch (e: Exception) {

            Log.e(
                "SugarCounter",
                "Excel save error",
                e
            )

            AlertDialog.Builder(this)
                .setMessage(
                    tr(
                        "خطا در ذخیره فایل Excel",
                        "Error saving Excel file"
                    )
                )
                .setPositiveButton(
                    tr("باشه", "OK"),
                    null
                )
                .show()

        } finally {
            pendingExcelData = null
        }
    }

    private fun createExcelFile(history: JSONArray): ByteArray {

        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->

            fun addEntry(
                name: String,
                content: String
            ) {

                zip.putNextEntry(ZipEntry(name))

                zip.write(
                    content.toByteArray(Charsets.UTF_8)
                )

                zip.closeEntry()
            }

            var total = 0

            for (i in 0 until history.length()) {
                total += history.getJSONObject(i).optInt("count")
            }

            val totalRow = history.length() + 3
            val lastColumn = "D"

            val rows = StringBuilder()

            val title = xmlEscape(
                tr(
                    "گزارش شمارش کیسه‌های قند",
                    "Sugar Bag Counting Report"
                )
            )

            val rowHeaders = if (isEnglish) {
                """
                <row r="2"><c r="A2" t="inlineStr"><is><t>No.</t></is></c><c r="B2" t="inlineStr"><is><t>Date</t></is></c><c r="C2" t="inlineStr"><is><t>Time</t></is></c><c r="D2" t="inlineStr"><is><t>Bag Count</t></is></c></row>
                """.trimIndent()
            } else {
                """
                <row r="2"><c r="A2" t="inlineStr"><is><t>ردیف</t></is></c><c r="B2" t="inlineStr"><is><t>تاریخ شمسی</t></is></c><c r="C2" t="inlineStr"><is><t>ساعت</t></is></c><c r="D2" t="inlineStr"><is><t>تعداد کیسه</t></is></c></row>
                """.trimIndent()
            }

            rows.append(
                """<row r="1"><c r="A1" t="inlineStr"><is><t>$title</t></is></c></row>"""
            )

            rows.append(rowHeaders)

            for (i in 0 until history.length()) {

                val item = history.getJSONObject(i)

                val row = i + 3

                val date = xmlEscape(item.optString("date"))
                val time = xmlEscape(item.optString("time"))
                val count = item.optInt("count")

                rows.append(
                    """<row r="$row"><c r="A$row" t="n"><v>${i + 1}</v></c><c r="B$row" t="inlineStr"><is><t>$date</t></is></c><c r="C$row" t="inlineStr"><is><t>$time</t></is></c><c r="D$row" t="n"><v>$count</v></c></row>"""
                )
            }

            val totalLabel = xmlEscape(
                tr("مجموع کل", "Grand Total")
            )

            rows.append(
                """<row r="$totalRow"><c r="C$totalRow" t="inlineStr"><is><t>$totalLabel</t></is></c><c r="D$totalRow" t="n"><v>$total</v></c></row>"""
            )

            addEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
            )

            addEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )

            val sheetName = xmlEscape(
                tr("گزارش شمارش", "Counting Report")
            )

            addEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="$sheetName" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )

            addEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
            )

            addEntry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:$lastColumn$totalRow"/><sheetViews><sheetView workbookViewId="0" rightToLeft="${if (isEnglish) 0 else 1}"/></sheetViews><sheetFormatPr defaultRowHeight="20"/><cols><col min="1" max="1" width="12" customWidth="1"/><col min="2" max="2" width="22" customWidth="1"/><col min="3" max="3" width="18" customWidth="1"/><col min="4" max="4" width="20" customWidth="1"/></cols><sheetData>$rows</sheetData></worksheet>"""
            )
        }

        return output.toByteArray()
    }

    private fun xmlEscape(value: String): String {

        val validXml = buildString {

            for (character in value) {

                val code = character.code

                val isValid =
                    code == 0x9 ||
                    code == 0xA ||
                    code == 0xD ||
                    code in 0x20..0xD7FF ||
                    code in 0xE000..0xFFFD ||
                    code in 0x10000..0x10FFFF

                if (isValid) {
                    append(character)
                }
            }
        }

        return validXml
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // --------------------------------------------------
    // درباره برنامه
    // --------------------------------------------------

    private fun showAbout() {

        val white = industrialBlack
        val dark = industrialLightGold
        val green = industrialGold
        val muted = Color.rgb(190, 190, 190)
        val border = industrialBorder

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackground(aboutBackground(white, 22, border))
            layoutDirection = if (isEnglish) {
                View.LAYOUT_DIRECTION_LTR
            } else {
                View.LAYOUT_DIRECTION_RTL
            }
        }

        val accent = View(this).apply {
            setBackground(aboutBackground(green, 10, green))
        }

        content.addView(
            accent,
            LinearLayout.LayoutParams(dp(48), dp(5)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(18)
            }
        )

        val title = TextView(this).apply {
            text = tr("درباره برنامه", "About")
            textSize = 25f
            setTextColor(dark)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        content.addView(title)

        val subtitle = TextView(this).apply {
            text = tr(
                "معرفی سامانه و عوامل توسعه",
                "System and Development Team"
            )
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(22))
        }

        content.addView(subtitle)

        val logos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        logos.addView(
            createAboutLogoCard(
                R.drawable.university_logo,
                tr("دانشگاه آزاد اسلامی", "Islamic Azad University"),
                tr("واحد همدان", "Hamedan Branch")
            ),
            LinearLayout.LayoutParams(0, dp(155), 1f).apply {
                marginEnd = dp(6)
            }
        )

        logos.addView(
            createAboutLogoCard(
                R.drawable.factory_logo,
                tr("شرکت قند هکمتان", "Hekmataneh Sugar Company"),
                tr("همدان", "Hamedan, Iran")
            ),
            LinearLayout.LayoutParams(0, dp(155), 1f).apply {
                marginStart = dp(6)
            }
        )

        content.addView(logos)

        addAboutText(
            content,
            tr(
                "سامانه هوشمند شمارش کیسه‌های قند",
                "Smart Sugar Bag Counting System"
            ),
            20f,
            true
        )

        val description = TextView(this).apply {
            text = tr(
                "طراحی و توسعه سامانه هوشمند مبتنی بر بینایی ماشین",
                "An intelligent machine-vision-based counting system"
            )
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(20))
        }

        content.addView(description)

        val divider = View(this).apply {
            setBackgroundColor(border)
        }

        content.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                bottomMargin = dp(18)
            }
        )

        val teamTitle = TextView(this).apply {
            text = "CRYSTAL.BIN"
            textSize = 19f
            setTextColor(green)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(5))
        }

        content.addView(teamTitle)

        val membersTitle = TextView(this).apply {
            text = tr(
                "هسته فناور کریستال‌بین",
                "Crystal.BIN Technology Team"
            )
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(13))
        }

        content.addView(membersTitle)

        val members = listOf(
            tr("محمدمهدی شیرمحمدی", "Mohammad Mehdi ShirMohammadi") to
                tr("استاد راهنما", "Supervisor"),

            tr("دکتر معین نظری", "Dr. Moin Nazari") to
                tr("همکار علمی", "Academic Collaborator"),

            tr("زهرا ده‌ده‌سی", "Zahra Dehdehsi") to
                tr("عضو تیم توسعه", "Development Team"),

            tr("علی قاسمی", "Ali Qasemi") to
                tr("عضو تیم توسعه", "Development Team"),

            tr("سینا بالائی", "Sina Balaei") to
                tr("عضو تیم توسعه", "Development Team")
        )

        members.forEach { member ->

            content.addView(
                createAboutMemberCard(
                    member.first,
                    member.second
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(58)
                ).apply {
                    topMargin = dp(6)
                }
            )
        }

        addAboutText(
            content,
            tr(
                "اطلاعات تماس",
                "Contact Information"
            ),
            17f,
            true
        )

        val professorEmail = TextView(this).apply {
            text = "Mmshirmohammadi@iauh.ac.ir"
            textSize = 12f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        }

        professorEmail.setOnClickListener {
            openEmail("Mmshirmohammadi@iauh.ac.ir")
        }

        content.addView(professorEmail)

        val userEmail = TextView(this).apply {
            text = "zahradhdhsi@gmail.com"
            textSize = 12f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
        }

        userEmail.setOnClickListener {
            openEmail("zahradhdhsi@gmail.com")
        }

        content.addView(userEmail)

        val footer = TextView(this).apply {
            text = "CRYSTAL.BIN"
            textSize = 11f
            letterSpacing = 2f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(0, dp(17), 0, dp(3))
            typeface = Typeface.DEFAULT_BOLD
        }

        content.addView(footer)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(white)
            addView(content)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton(
                tr("بستن", "Close"),
                null
            )
            .create()

        dialog.show()

        dialog.window?.setBackgroundDrawable(ColorDrawable(white))

        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(32),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(green)
    }

    private fun openEmail(email: String) {

        try {

            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:$email")
            )

            startActivity(intent)

        } catch (e: Exception) {

            Log.e("SugarCounter", "Email app unavailable", e)
        }
    }

    private fun createAboutLogoCard(
        imageResource: Int,
        heading: String,
        subheading: String
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
            setBackground(
                aboutBackground(
                    industrialButton,
                    16,
                    industrialBorder
                )
            )
        }

        val image = ImageView(this).apply {
            setImageResource(imageResource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            contentDescription = heading
        }

        card.addView(
            image,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(85)
            )
        )

        val mainText = TextView(this).apply {
            text = heading
            textSize = 12f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        card.addView(mainText)

        val secondaryText = TextView(this).apply {
            text = subheading
            textSize = 11f
            setTextColor(Color.rgb(190, 190, 190))
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }

        card.addView(secondaryText)

        return card
    }

    private fun createAboutMemberCard(
        name: String,
        role: String
    ): LinearLayout {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(6), dp(13), dp(6))
            setBackground(
                aboutBackground(
                    industrialButton,
                    12,
                    industrialBorder
                )
            )
            layoutDirection = if (isEnglish) {
                View.LAYOUT_DIRECTION_LTR
            } else {
                View.LAYOUT_DIRECTION_RTL
            }
        }

        val indicator = View(this).apply {
            setBackground(
                aboutBackground(
                    industrialGold,
                    50,
                    industrialGold
                )
            )
        }

        card.addView(
            indicator,
            LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(10)
            }
        )

        val nameText = TextView(this).apply {
            text = name
            textSize = 14f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
        }

        card.addView(
            nameText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        val roleText = TextView(this).apply {
            text = role
            textSize = 10f
            setTextColor(industrialGold)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackground(
                aboutBackground(
                    industrialBlack,
                    8,
                    industrialGold
                )
            )
        }

        card.addView(roleText)

        return card
    }

    private fun aboutBackground(
        color: Int,
        radius: Int,
        strokeColor: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun addAboutText(
        parent: LinearLayout,
        value: String,
        size: Float,
        bold: Boolean
    ) {

        val text = TextView(this).apply {

            this.text = value
            textSize = size

            setTextColor(
                if (bold) industrialGold
                else Color.rgb(210, 210, 210)
            )

            gravity = Gravity.CENTER

            setPadding(
                dp(4),
                dp(12),
                dp(4),
                dp(5)
            )

            typeface = if (bold) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
        }

        parent.addView(text)
    }

    // --------------------------------------------------
    // تاریخ
    // --------------------------------------------------

    private fun getCurrentTime(): String {

        return SimpleDateFormat(
            "HH:mm:ss",
            Locale.US
        ).format(Date())
    }

    private fun getPersianDate(): String {

        val calendar = Calendar.getInstance()

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        val jalali = gregorianToJalali(year, month, day)

        return String.format(
            Locale.US,
            "%04d/%02d/%02d",
            jalali[0],
            jalali[1],
            jalali[2]
        )
    }

    private fun gregorianToJalali(
        gyInput: Int,
        gmInput: Int,
        gdInput: Int
    ): IntArray {

        val gDays = intArrayOf(
            0, 31, 59, 90, 120, 151,
            181, 212, 243, 273, 304, 334
        )

        val gy = gyInput
        val gm = gmInput
        val gd = gdInput

        val gy2 = if (gm > 2) gy + 1 else gy

        var days = 355666 +
            (365 * gy) +
            ((gy2 + 3) / 4) -
            ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) +
            gd +
            gDays[gm - 1]

        var jy = -1595 + 33 * (days / 12053)

        days %= 12053

        jy += 4 * (days / 1461)

        days %= 1461

        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        val jm: Int
        val jd: Int

        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }

        return intArrayOf(jy, jm, jd)
    }

    // --------------------------------------------------
    // پایان فعالیت
    // --------------------------------------------------

    override fun onDestroy() {

        running = false

        executor.shutdown()

        if (::detector.isInitialized) {
            detector.close()
        }

        if (::guideImage.isInitialized) {
            guideImage.setImageDrawable(null)
        }

        guideBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }

        guideBitmap = null

        super.onDestroy()
    }
}
