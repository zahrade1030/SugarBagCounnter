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
                statusText.text = "مجوز دوربین داده نشد"
            }
        }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // License verification gate
        if (!LicenseManager.isActivated(this)) {
            startActivity(
                Intent(this, LicenseActivity::class.java)
            )
            finish()
            return
        }

        try {
            detector = YoloDetector(this)
        } catch (e: Exception) {
            Log.e("SugarCounter", "Model initialization failed", e)

            AlertDialog.Builder(this)
                .setTitle("خطا در بارگذاری مدل")
                .setMessage(
                    "فایل مدل best.onnx در برنامه یافت نشد یا قابل بارگذاری نیست.\n\n${e.message}"
                )
                .setPositiveButton("بستن") { _, _ ->
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
                setColor(
                    if (primary) industrialGold else industrialButton
                )
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
            text = "شمارشگر هوشمند کیسه‌های قند"
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

        guideImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = guideOpacity / 255f
            visibility = if (guideVisible) View.VISIBLE else View.GONE
            isClickable = false
            isFocusable = false
            contentDescription = "تصویر راهنمای تنظیم دوربین"
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

                val guideWidth = (width * 0.88f).toInt()
                val guideHeight = (height * 0.82f).toInt()

                guideImage.layoutParams = FrameLayout.LayoutParams(
                    guideWidth,
                    guideHeight,
                    Gravity.CENTER
                )

                guideImage.requestLayout()
            }
        }

        countText = TextView(this).apply {
            text = "تعداد: 0"
            textSize = 30f
            setTextColor(industrialLightGold)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(4))
            typeface = Typeface.DEFAULT_BOLD
        }

        statusText = TextView(this).apply {
            text = "خطوط شمارش را تنظیم کنید"
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
                primary = label == "شروع شمارش"
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

        addActionButton(firstRow, "تنظیم خطوط") {
            enterSetupMode()
        }

        addActionButton(firstRow, "شروع شمارش") {
            startCounting()
        }

        addActionButton(firstRow, "توقف") {
            stopCounting()
        }

        addActionButton(secondRow, "ریست") {
            resetCounting()
        }

        addActionButton(secondRow, "تاریخچه") {
            showHistory()
        }

        addActionButton(secondRow, "آمار") {
            showStatistics()
        }

        addActionButton(secondRow, "Excel") {
            exportExcel()
        }

        val guideSettingsButton =
            createIndustrialButton("تنظیم تصویر راهنما").apply {
                setOnClickListener {
                    showGuideSettings()
                }
            }

        val aboutButton =
            createIndustrialButton("درباره برنامه").apply {
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

        statusText.text =
            "خطوط افقی و محدوده شمارش را تنظیم کنید"
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

        statusText.text = "شمارش فعال است"
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
            text = "تنظیم تصویر راهنما"
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
            text = "نمایش تصویر راهنما"
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

        val opacityLabel = TextView(this).apply {
            text = "شفافیت تصویر راهنما: ${guideOpacity * 100 / 255}%"
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

        val helpText = TextView(this).apply {
            text = "تصویر راهنما به صورت خودکار متناسب با صفحه دوربین تنظیم می‌شود."
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

                    opacityLabel.text =
                        "شفافیت تصویر راهنما: $progress%"
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

        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("ذخیره و بستن", null)
            .setNegativeButton("بستن", null)
            .show()
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

                                    countText.text =
                                        "تعداد: $totalCount"
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
                                statusText.text =
                                    "خطا در پردازش تصویر"
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
                    statusText.text = "خطای راه‌اندازی دوربین"
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // --------------------------------------------------
    // توقف شمارش
    // --------------------------------------------------

    private fun stopCounting() {

        if (!running) {
            statusText.text = "شمارش قبلاً متوقف شده است"
            return
        }

        running = false

        saveLineSettings()

        if (totalCount > 0 && !sessionSaved) {
            saveCurrentSession()
            sessionSaved = true
        }

        statusText.text = "شمارش متوقف شد"
    }

    // --------------------------------------------------
    // ریست
    // --------------------------------------------------

    private fun resetCounting() {

        AlertDialog.Builder(this)
            .setTitle("ریست شمارش")
            .setMessage("آیا شمارش فعلی از صفر شروع شود؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ریست") { _, _ ->

                totalCount = 0

                tracker.reset()

                countText.text = "تعداد: 0"

                sessionSaved = false
                running = true

                statusText.text =
                    if (setupMode) {
                        "خطوط شمارش را تنظیم کنید"
                    } else {
                        "شمارش از صفر شروع شد"
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
                .setTitle("تاریخچه شمارش")
                .setMessage("هنوز سابقه‌ای ثبت نشده است.")
                .setPositiveButton("باشه", null)
                .show()

            return
        }

        val items = ArrayList<String>()

        for (i in history.length() - 1 downTo 0) {

            val item = history.getJSONObject(i)

            items.add(
                "تاریخ: ${item.optString("date")}\n" +
                    "ساعت: ${item.optString("time")}\n" +
                    "تعداد کیسه: ${item.optInt("count")}"
            )
        }

        val text = items.joinToString("\n\n──────────────\n\n")

        AlertDialog.Builder(this)
            .setTitle("تاریخچه شمارش‌ها")
            .setMessage(text)
            .setPositiveButton("بستن", null)
            .setNeutralButton("پاک کردن تاریخچه") { _, _ ->

                AlertDialog.Builder(this)
                    .setTitle("حذف تاریخچه")
                    .setMessage("آیا از حذف تمام سوابق مطمئن هستید؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ ->

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

        val message = """
            آمار شمارش کیسه‌ها

            امروز: $daily کیسه

            این هفته: $weekly کیسه

            این ماه: $monthly کیسه

            مجموع کل سوابق: $all کیسه
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("آمار عملکرد")
            .setMessage(message)
            .setPositiveButton("بستن", null)
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
                    "برای ساخت گزارش، ابتدا باید سابقه شمارش داشته باشید."
                )
                .setPositiveButton("باشه", null)
                .show()

            return
        }

        try {

            pendingExcelData = createExcelFile(history)

            excelLauncher.launch(
                "گزارش_شمارش_کیسه_قند.xlsx"
            )

        } catch (e: Exception) {

            pendingExcelData = null

            Log.e(
                "SugarCounter",
                "Excel creation error",
                e
            )

            AlertDialog.Builder(this)
                .setTitle("خطای ساخت فایل")
                .setMessage("ساخت فایل Excel با خطا مواجه شد.")
                .setPositiveButton("باشه", null)
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
                .setTitle("ذخیره موفق")
                .setMessage("فایل Excel با موفقیت ذخیره شد.")
                .setPositiveButton("باشه", null)
                .show()

        } catch (e: Exception) {

            Log.e(
                "SugarCounter",
                "Excel save error",
                e
            )

            AlertDialog.Builder(this)
                .setMessage("خطا در ذخیره فایل Excel")
                .setPositiveButton("باشه", null)
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

            rows.append(
                """<row r="1"><c r="A1" t="inlineStr"><is><t>گزارش شمارش کیسه‌های قند</t></is></c></row>"""
            )

            rows.append(
                """<row r="2"><c r="A2" t="inlineStr"><is><t>ردیف</t></is></c><c r="B2" t="inlineStr"><is><t>تاریخ شمسی</t></is></c><c r="C2" t="inlineStr"><is><t>ساعت</t></is></c><c r="D2" t="inlineStr"><is><t>تعداد کیسه</t></is></c></row>"""
            )

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

            rows.append(
                """<row r="$totalRow"><c r="C$totalRow" t="inlineStr"><is><t>مجموع کل</t></is></c><c r="D$totalRow" t="n"><v>$total</v></c></row>"""
            )

            addEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
            )

            addEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )

            addEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="گزارش شمارش" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )

            addEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
            )

            addEntry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:$lastColumn$totalRow"/><sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews><sheetFormatPr defaultRowHeight="20"/><cols><col min="1" max="1" width="12" customWidth="1"/><col min="2" max="2" width="22" customWidth="1"/><col min="3" max="3" width="18" customWidth="1"/><col min="4" max="4" width="20" customWidth="1"/></cols><sheetData>$rows</sheetData></worksheet>"""
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            text = "درباره برنامه"
            textSize = 25f
            setTextColor(dark)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        content.addView(title)

        val subtitle = TextView(this).apply {
            text = "معرفی سامانه و عوامل توسعه"
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
                "دانشگاه آزاد اسلامی",
                "واحد همدان"
            ),
            LinearLayout.LayoutParams(0, dp(155), 1f).apply {
                marginEnd = dp(6)
            }
        )

        logos.addView(
            createAboutLogoCard(
                R.drawable.factory_logo,
                "شرکت قند هکمتان",
                "همدان"
            ),
            LinearLayout.LayoutParams(0, dp(155), 1f).apply {
                marginStart = dp(6)
            }
        )

        content.addView(logos)

        addAboutText(
            content,
            "سامانه هوشمند شمارش کیسه‌های قند",
            20f,
            true
        )

        val description = TextView(this).apply {
            text = "طراحی و توسعه سامانه هوشمند مبتنی بر بینایی ماشین"
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
            text = "هسته فناور کریستال‌بین"
            textSize = 19f
            setTextColor(green)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(5))
        }

        content.addView(teamTitle)

        val membersTitle = TextView(this).apply {
            text = "تیم توسعه پروژه"
            textSize = 13f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(13))
        }

        content.addView(membersTitle)

        val members = listOf(
            "محمدمهدی شیرمحمدی" to "استاد راهنما",
            "زهرا ده‌ده‌سی" to "عضو تیم توسعه",
            "علی قاسمی" to "عضو تیم توسعه",
            "سینا بالائی" to "عضو تیم توسعه"
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

        val footer = TextView(this).apply {
            text = "CRYSTAL VISION"
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
            .setPositiveButton("بستن", null)
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
    // تاریخ شمسی
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
