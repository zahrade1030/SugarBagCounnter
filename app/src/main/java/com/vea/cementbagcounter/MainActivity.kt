package com.vea.cementbagcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var detector: YoloDetector

    private val executor = Executors.newSingleThreadExecutor()

    private var running = true
    private var setupMode = true
    private var totalCount = 0
    private var sessionSaved = false

    private val tracker = SimpleLineCounter()

    private val historyPrefs by lazy {
        getSharedPreferences("sugar_count_history", MODE_PRIVATE)
    }

    private var pendingCsv = ""

    // انتخاب محل ذخیره فایل Excel/CSV
    private val exportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->

            if (uri == null) return@registerForActivityResult

            try {

                contentResolver.openOutputStream(uri)?.use { output ->

                    output.write(
                        byteArrayOf(
                            0xEF.toByte(),
                            0xBB.toByte(),
                            0xBF.toByte()
                        )
                    )

                    output.write(pendingCsv.toByteArray(Charsets.UTF_8))
                }

                AlertDialog.Builder(this)
                    .setTitle("خروجی موفق")
                    .setMessage("فایل سوابق با موفقیت ذخیره شد.")
                    .setPositiveButton("باشه", null)
                    .show()

            } catch (e: Exception) {

                AlertDialog.Builder(this)
                    .setTitle("خطا")
                    .setMessage("ذخیره فایل انجام نشد: ${e.localizedMessage}")
                    .setPositiveButton("باشه", null)
                    .show()
            }
        }

    private val permission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                startCamera()
            } else {
                statusText.text = "مجوز دوربین داده نشد"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        detector = YoloDetector(this)

        buildUi()

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

    // ساخت رابط کاربری
    private fun buildUi() {

        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 28, 24))
        }

        val header = TextView(this).apply {
            text = "شمارشگر کیسه شکر"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 14, 8, 14)
        }

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DetectionOverlay(this)

        val cameraFrame = FrameLayout(this)

        cameraFrame.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        cameraFrame.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        countText = TextView(this).apply {
            text = "تعداد: 0"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 4)
        }

        statusText = TextView(this).apply {
            text = "خطوط شمارش را تنظیم کنید"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 10)
        }

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val thirdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun addButton(
            row: LinearLayout,
            label: String,
            action: () -> Unit
        ) {

            val button = Button(this).apply {
                text = label
                setOnClickListener { action() }
            }

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        // دکمه تنظیم خطوط
        addButton(firstRow, "تنظیم خطوط") {

            setupMode = true
            overlay.isSetup = true

            statusText.text = "خطوط را با لمس جابه‌جا کنید"
        }

        // دکمه شروع شمارش
        addButton(firstRow, "شروع شمارش") {

            saveCurrentSession()

            totalCount = 0
            countText.text = "تعداد: 0"

            sessionSaved = false

            tracker.reset()

            tracker.setLines(
                overlay.lineTop,
                overlay.lineBottom
            )

            setupMode = false
            overlay.isSetup = false
            running = true

            statusText.text = "شمارش فعال است"
        }

        // دکمه توقف
        addButton(firstRow, "توقف") {

            running = false

            val saved = saveCurrentSession()

            statusText.text =
                if (saved) {
                    "شمارش متوقف و نتیجه ثبت شد"
                } else {
                    "شمارش متوقف شد"
                }
        }

        // دکمه ریست
        addButton(secondRow, "ریست") {

            saveCurrentSession()

            totalCount = 0

            tracker.reset()

            countText.text = "تعداد: 0"

            statusText.text = "شمارش از ابتدا آماده است"
        }

        // دکمه سوابق
        addButton(secondRow, "سوابق شمارش") {
            showHistory()
        }

        // دکمه گزارش آماری
        addButton(thirdRow, "گزارش آماری") {
            showStatisticsMenu()
        }

        // دکمه خروجی Excel
        addButton(thirdRow, "خروجی Excel") {
            exportExcel()
        }

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
        base.addView(firstRow)
        base.addView(secondRow)
        base.addView(thirdRow)

        setContentView(base)
    }

    // دریافت سوابق ذخیره شده
    private fun getRecords(): JSONArray {

        return try {

            JSONArray(
                historyPrefs.getString("records", "[]") ?: "[]"
            )

        } catch (e: Exception) {

            JSONArray()
        }
    }

    // ذخیره نتیجه شمارش
    private fun saveCurrentSession(): Boolean {

        if (sessionSaved) return false

        sessionSaved = true

        if (totalCount <= 0) return false

        val oldRecords = getRecords()

        val newRecord = JSONObject().apply {
            put("count", totalCount)
            put("time", System.currentTimeMillis())
        }

        val updatedRecords = JSONArray()

        updatedRecords.put(newRecord)

        for (i in 0 until minOf(oldRecords.length(), 499)) {

            val item = oldRecords.optJSONObject(i)

            if (item != null) {
                updatedRecords.put(item)
            }
        }

        historyPrefs.edit()
            .putString("records", updatedRecords.toString())
            .apply()

        return true
    }

    // تبدیل اعداد انگلیسی به فارسی
    private fun String.toPersianDigits(): String {

        val persianDigits = "۰۱۲۳۴۵۶۷۸۹"

        return map { char ->

            if (char in '0'..'9') {
                persianDigits[char - '0']
            } else {
                char
            }

        }.joinToString("")
    }

    // تبدیل تاریخ میلادی به شمسی
    private fun toPersianDate(time: Long): String {

        val date = java.util.Calendar.getInstance().apply {
            timeInMillis = time
        }

        val gy = date.get(java.util.Calendar.YEAR)
        val gm = date.get(java.util.Calendar.MONTH) + 1
        val gd = date.get(java.util.Calendar.DAY_OF_MONTH)

        val gDays = intArrayOf(
            0, 31, 59, 90, 120, 151,
            181, 212, 243, 273, 304, 334
        )

        val gy2 = if (gm > 2) gy + 1 else gy

        var days = 355666 +
                365 * gy +
                (gy2 + 3) / 4 -
                (gy2 + 99) / 100 +
                (gy2 + 399) / 400 +
                gd + gDays[gm - 1]

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

        return String.format(
            Locale.US,
            "%04d/%02d/%02d",
            jy,
            jm,
            jd
        ).toPersianDigits()
    }

    // تبدیل تاریخ و ساعت به نمایش فارسی
    private fun formatPersianDateTime(time: Long): String {

        val timeText = SimpleDateFormat(
            "HH:mm",
            Locale.US
        ).format(Date(time))

        return "${toPersianDate(time)}   ${timeText.toPersianDigits()}"
    }

    // نمایش سوابق شمارش
    private fun showHistory() {

        val records = getRecords()

        if (records.length() == 0) {

            AlertDialog.Builder(this)
                .setTitle("سوابق شمارش")
                .setMessage("هنوز هیچ سابقه‌ای ثبت نشده است.")
                .setPositiveButton("بستن", null)
                .show()

            return
        }

        val historyText = StringBuilder()

        for (i in 0 until records.length()) {

            val item = records.optJSONObject(i) ?: continue

            val count = item.optInt("count", 0)
            val time = item.optLong("time", 0L)

            historyText.append(
                "تعداد کیسه‌ها: ${count.toString().toPersianDigits()}\n"
            )

            historyText.append(
                "تاریخ و ساعت: ${formatPersianDateTime(time)}\n"
            )

            historyText.append("------------------------\n")
        }

        AlertDialog.Builder(this)
            .setTitle("سوابق شمارش")
            .setMessage(historyText.toString())
            .setPositiveButton("بستن", null)
            .show()
    }

    // منوی گزارش آماری
    private fun showStatisticsMenu() {

        val options = arrayOf(
            "گزارش روزانه",
            "گزارش هفتگی",
            "گزارش ماهانه"
        )

        AlertDialog.Builder(this)
            .setTitle("گزارش آماری")
            .setItems(options) { _, which ->

                when (which) {

                    0 -> showStatistics("daily")

                    1 -> showStatistics("weekly")

                    2 -> showStatistics("monthly")
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    // محاسبه گزارش روزانه، هفتگی و ماهانه
    private fun showStatistics(type: String) {

        val records = getRecords()

        if (records.length() == 0) {

            AlertDialog.Builder(this)
                .setTitle("گزارش آماری")
                .setMessage("هنوز سابقه‌ای برای گزارش وجود ندارد.")
                .setPositiveButton("بستن", null)
                .show()

            return
        }

        val groups = linkedMapOf<String, Pair<Int, Int>>()

        for (i in 0 until records.length()) {

            val item = records.optJSONObject(i) ?: continue

            val count = item.optInt("count", 0)
            val time = item.optLong("time", 0L)

            if (time <= 0 || count <= 0) continue

            val date = toPersianDate(time)

            val parts = date.split("/")

            val year = parts[0]
            val month = parts[1]
            val day = parts[2]

            val monthNumber = month.toInt()
            val dayNumber = day.toInt()

            val key = when (type) {

                "daily" -> date

                "monthly" -> "$year/$month"

                else -> {

                    val dayOfYear = if (monthNumber <= 6) {

                        (monthNumber - 1) * 31 + dayNumber

                    } else {

                        186 + (monthNumber - 7) * 30 + dayNumber
                    }

                    val week = (dayOfYear - 1) / 7 + 1

                    "$year-هفته-$week"
                }
            }

            val previous = groups[key]

            groups[key] = Pair(
                (previous?.first ?: 0) + count,
                (previous?.second ?: 0) + 1
            )
        }

        val report = StringBuilder()

        var totalBags = 0
        var totalSessions = 0

        for ((key, value) in groups) {

            totalBags += value.first
            totalSessions += value.second

            report.append("📅 $key\n")

            report.append(
                "تعداد کیسه‌ها: ${value.first.toString().toPersianDigits()}\n"
            )

            report.append(
                "تعداد دفعات شمارش: ${value.second.toString().toPersianDigits()}\n"
            )

            report.append("------------------------\n")
        }

        report.append(
            "\nمجموع کل کیسه‌ها: ${totalBags.toString().toPersianDigits()}"
        )

        report.append(
            "\nمجموع دفعات شمارش: ${totalSessions.toString().toPersianDigits()}"
        )

        val title = when (type) {

            "daily" -> "گزارش روزانه"

            "weekly" -> "گزارش هفتگی"

            else -> "گزارش ماهانه"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(report.toString())
            .setPositiveButton("بستن", null)
            .show()
    }

    // خروجی گرفتن از سوابق برای Excel
    private fun exportExcel() {

        val records = getRecords()

        if (records.length() == 0) {

            AlertDialog.Builder(this)
                .setTitle("خروجی Excel")
                .setMessage("هنوز هیچ سابقه‌ای برای خروجی گرفتن وجود ندارد.")
                .setPositiveButton("باشه", null)
                .show()

            return
        }

        val csv = StringBuilder()

        csv.append("ردیف;تعداد کیسه‌ها;تاریخ شمسی;ساعت\n")

        for (i in 0 until records.length()) {

            val item = records.optJSONObject(i) ?: continue

            val count = item.optInt("count", 0)
            val time = item.optLong("time", 0L)

            val date = toPersianDate(time)

            val timeText = SimpleDateFormat(
                "HH:mm",
                Locale.US
            ).format(Date(time)).toPersianDigits()

            csv.append(
                "${(i + 1).toString().toPersianDigits()};"
            )

            csv.append(
                "${count.toString().toPersianDigits()};"
            )

            csv.append("$date;")
            csv.append("$timeText\n")
        }

        pendingCsv = csv.toString()

        exportLauncher.launch("sugar_count_history.csv")
    }

    // اجرای دوربین و تشخیص کیسه‌ها
    private fun startCamera() {

        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({

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

                if (!running) {

                    image.close()

                    return@setAnalyzer
                }

                try {

                    val bitmap = ImageUtil.toBitmap(image)

                    val detections = detector.detect(bitmap)

                    runOnUiThread {

                        overlay.setDetections(
                            detections,
                            bitmap.width,
                            bitmap.height
                        )

                        if (!setupMode) {

                            totalCount += tracker.update(detections)

                            countText.text = "تعداد: $totalCount"
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        "SugarCounter",
                        "Detection error",
                        e
                    )

                    runOnUiThread {
                        statusText.text = "خطا در تشخیص تصویر"
                    }

                } finally {

                    image.close()
                }
            }

            try {

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysis
                )

            } catch (e: Exception) {

                statusText.text =
                    "خطای دوربین: ${e.localizedMessage}"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {

        super.onDestroy()

        executor.shutdown()

        detector.close()
    }
}
