
package com.vea.cementbagcounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class CountRecord(
    val count: Int,
    val time: Long
)

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
    private var sessionSaved = true

    private val tracker = SimpleLineCounter()

    private val preferences by lazy {
        getSharedPreferences("sugar_count_history", MODE_PRIVATE)
    }

    private var pendingXlsx: ByteArray? = null

    private val excelLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->

            if (uri != null) {
                try {
                    val data = pendingXlsx

                    if (data != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(data)
                        }

                        showMessage("فایل Excel با موفقیت ذخیره شد")
                    } else {
                        showMessage("اطلاعات فایل آماده نیست")
                    }

                } catch (e: Exception) {
                    Log.e("SugarCounter", "Excel export error", e)
                    showMessage("خطا در ذخیره فایل Excel")
                }
            }

            pendingXlsx = null
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

    // --------------------------------------------------
    // رابط کاربری اصلی
    // --------------------------------------------------

    private fun buildUi() {

        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 28, 24))
        }

        val header = TextView(this).apply {
            text = "شمارشگر کیسه قند"
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
            text = "تعداد: ۰"
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

        // ردیف اول
        addButtonRow(
            base,
            "تنظیم خطوط",
            "شروع شمارش"
        ) { index ->

            if (index == 0) {
                setupMode = true
                overlay.isSetup = true
                statusText.text = "خطوط را با لمس جابه‌جا کنید"
            } else {
                startCounting()
            }
        }

        // ردیف دوم
        addButtonRow(
            base,
            "توقف",
            "ریست"
        ) { index ->

            if (index == 0) {
                stopCounting()
            } else {
                resetCounting()
            }
        }

        // ردیف سوم
        addButtonRow(
            base,
            "سوابق شمارش",
            "آمار شمارش"
        ) { index ->

            if (index == 0) {
                showHistory()
            } else {
                showStatisticsMenu()
            }
        }

        // ردیف چهارم
        addButtonRow(
            base,
            "خروجی Excel",
            "درباره برنامه"
        ) { index ->

            if (index == 0) {
                exportExcel()
            } else {
                showAbout()
            }
        }

        setContentView(base)
    }

    private fun addButtonRow(
        parent: LinearLayout,
        firstLabel: String,
        secondLabel: String,
        action: (Int) -> Unit
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val first = createButton(firstLabel)
        val second = createButton(secondLabel)

        first.setOnClickListener {
            action(0)
        }

        second.setOnClickListener {
            action(1)
        }

        row.addView(
            first,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(3)
            }
        )

        row.addView(
            second,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(3)
            }
        )

        parent.addView(row)
    }

    private fun createButton(label: String): Button {

        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(42, 100, 75))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // --------------------------------------------------
    // کنترل شمارش
    // --------------------------------------------------

    private fun startCounting() {

        saveCurrentSession()

        totalCount = 0
        sessionSaved = false

        tracker.reset()

        tracker.setLines(
            overlay.lineTop,
            overlay.lineBottom
        )

        setupMode = false
        overlay.isSetup = false
        running = true

        countText.text = "تعداد: ۰"
        statusText.text = "شمارش فعال است"
    }

    private fun stopCounting() {

        running = false

        saveCurrentSession()

        statusText.text = "شمارش متوقف شد"
    }

    private fun resetCounting() {

        saveCurrentSession()

        totalCount = 0
        sessionSaved = false

        tracker.reset()

        tracker.setLines(
            overlay.lineTop,
            overlay.lineBottom
        )

        countText.text = "تعداد: ۰"
        statusText.text = "شمارش از ابتدا آماده است"
    }

    // --------------------------------------------------
    // ذخیره سوابق
    // --------------------------------------------------

    private fun getRecords(): JSONArray {

        return try {
            JSONArray(
                preferences.getString("records", "[]")
            )
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveCurrentSession() {

        if (sessionSaved) return

        if (totalCount <= 0) {
            sessionSaved = true
            return
        }

        try {
            val records = getRecords()

            val newRecord = JSONObject().apply {
                put("count", totalCount)
                put("time", System.currentTimeMillis())
            }

            val updated = JSONArray()

            updated.put(newRecord)

            for (i in 0 until records.length()) {
                if (i < 499) {
                    updated.put(records.getJSONObject(i))
                }
            }

            preferences.edit()
                .putString("records", updated.toString())
                .apply()

            sessionSaved = true

        } catch (e: Exception) {
            Log.e("SugarCounter", "Save history error", e)
        }
    }

    private fun readRecords(): List<CountRecord> {

        val json = getRecords()
        val result = mutableListOf<CountRecord>()

        for (i in 0 until json.length()) {

            try {
                val item = json.getJSONObject(i)

                result.add(
                    CountRecord(
                        count = item.optInt("count", 0),
                        time = item.optLong("time", 0L)
                    )
                )

            } catch (e: Exception) {
                Log.e("SugarCounter", "Invalid history record", e)
            }
        }

        return result
    }

    // --------------------------------------------------
    // تاریخ شمسی
    // --------------------------------------------------

    private fun toPersianDigits(value: String): String {

        val english = "0123456789"
        val persian = "۰۱۲۳۴۵۶۷۸۹"

        return value.map { character ->

            val index = english.indexOf(character)

            if (index >= 0) {
                persian[index]
            } else {
                character
            }

        }.joinToString("")
    }

    private fun jalaliDate(time: Long): String {

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time

        val gy = calendar.get(Calendar.YEAR)
        val gm = calendar.get(Calendar.MONTH) + 1
        val gd = calendar.get(Calendar.DAY_OF_MONTH)

        val gDays = intArrayOf(
            0, 31, 59, 90, 120, 151,
            181, 212, 243, 273, 304, 334
        )

        val gy2 = if (gm > 2) gy + 1 else gy

        var days =
            355666 +
            365 * gy +
            (gy2 + 3) / 4 -
            (gy2 + 99) / 100 +
            (gy2 + 399) / 400 +
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

        return String.format(
            Locale.US,
            "%04d/%02d/%02d",
            jy,
            jm,
            jd
        )
    }

    private fun clockTime(time: Long): String {

        return SimpleDateFormat(
            "HH:mm",
            Locale.US
        ).format(Date(time))
    }

    private fun formatDateTime(time: Long): String {

        return toPersianDigits(
            "${jalaliDate(time)} - ${clockTime(time)}"
        )
    }

    // --------------------------------------------------
    // نمایش سوابق
    // --------------------------------------------------

    private fun showHistory() {

        val records = readRecords()

        if (records.isEmpty()) {
            showMessage("هنوز سابقه‌ای ثبت نشده است")
            return
        }

        val lines = records.mapIndexed { index, record ->

            "${toPersianDigits((index + 1).toString())}. " +
            "تعداد: ${toPersianDigits(record.count.toString())}  |  " +
            formatDateTime(record.time)

        }

        AlertDialog.Builder(this)
            .setTitle("سوابق شمارش")
            .setItems(lines.toTypedArray(), null)
            .setPositiveButton("بستن", null)
            .show()
    }

    // --------------------------------------------------
    // آمار روزانه، هفتگی و ماهانه
    // --------------------------------------------------

    private fun showStatisticsMenu() {

        val options = arrayOf(
            "آمار روزانه",
            "آمار هفتگی",
            "آمار ماهانه"
        )

        AlertDialog.Builder(this)
            .setTitle("نوع گزارش را انتخاب کنید")
            .setItems(options) { _, which ->

                when (which) {
                    0 -> showStatistics("daily")
                    1 -> showStatistics("weekly")
                    2 -> showStatistics("monthly")
                }
            }
            .show()
    }

    private fun showStatistics(type: String) {

        val records = readRecords()

        if (records.isEmpty()) {
            showMessage("هنوز سابقه‌ای برای گزارش وجود ندارد")
            return
        }

        val groups = linkedMapOf<String, Pair<Int, Int>>()

        for (record in records) {

            val date = jalaliDate(record.time)

            val key = when (type) {

                "daily" -> date

                "monthly" -> date.substring(0, 7)

                else -> {
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = record.time

                    val day = calendar.get(Calendar.DAY_OF_WEEK)

                    val daysFromSaturday =
                        if (day == Calendar.SATURDAY) 0 else day

                    calendar.add(
                        Calendar.DAY_OF_MONTH,
                        -daysFromSaturday
                    )

                    jalaliDate(calendar.timeInMillis)
                }
            }

            val old = groups[key] ?: Pair(0, 0)

            groups[key] = Pair(
                old.first + record.count,
                old.second + 1
            )
        }

        val title = when (type) {
            "daily" -> "گزارش روزانه"
            "weekly" -> "گزارش هفتگی"
            else -> "گزارش ماهانه"
        }

        val report = StringBuilder()

        groups.forEach { (key, value) ->

            report.append(
                "${toPersianDigits(key)}\n"
            )

            report.append(
                "مجموع کیسه‌ها: ${
                    toPersianDigits(value.first.toString())
                }\n"
            )

            report.append(
                "تعداد جلسات: ${
                    toPersianDigits(value.second.toString())
                }\n\n"
            )
        }

        val textView = TextView(this).apply {
            text = report.toString()
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val scroll = ScrollView(this).apply {
            addView(textView)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .show()
    }

    // --------------------------------------------------
    // صفحه درباره برنامه
    // --------------------------------------------------

    private fun showAbout() {

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val logos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val universityImage = ImageView(this).apply {
            setImageResource(R.drawable.university_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "لوگوی دانشگاه"
        }

        val factoryImage = ImageView(this).apply {
            setImageResource(R.drawable.factory_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "لوگوی کارخانه"
        }

        logos.addView(
            universityImage,
            LinearLayout.LayoutParams(
                0,
                dp(110),
                1f
            )
        )

        logos.addView(
            factoryImage,
            LinearLayout.LayoutParams(
                0,
                dp(110),
                1f
            )
        )

        content.addView(logos)

        addAboutText(
            content,
            "سامانه هوشمند شمارش کیسه‌های قند",
            20f,
            true
        )

        addAboutText(
            content,
            "دانشگاه آزاد اسلامی واحد همدان",
            16f,
            true
        )

        addAboutText(
            content,
            "هسته فناور کریستال‌بین",
            16f,
            true
        )

        addAboutText(
            content,
            "اعضای پروژه",
            17f,
            true
        )

        addAboutText(
            content,
            "محمدمهدی شیرمحمدی - استاد راهنما",
            15f,
            false
        )

        addAboutText(
            content,
            "زهرا ده‌ده‌سی",
            15f,
            false
        )

        addAboutText(
            content,
            "علی قاسمی",
            15f,
            false
        )

        addAboutText(
            content,
            "سینا بالائی",
            15f,
            false
        )

        val scroll = ScrollView(this).apply {
            addView(content)
        }

        AlertDialog.Builder(this)
            .setTitle("درباره برنامه")
            .setView(scroll)
            .setPositiveButton("بستن", null)
            .show()
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
            setTextColor(Color.rgb(35, 55, 45))
            gravity = Gravity.CENTER
            setPadding(4, dp(7), 4, dp(7))

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }

        parent.addView(text)
    }

    // --------------------------------------------------
    // ساخت فایل واقعی Excel
    // --------------------------------------------------

    private fun exportExcel() {

        val records = readRecords()

        if (records.isEmpty()) {
            showMessage("برای ساخت گزارش، ابتدا یک جلسه شمارش ثبت کنید")
            return
        }

        try {

            pendingXlsx = createXlsx(records)

            excelLauncher.launch(
                "sugar_count_history.xlsx"
            )

        } catch (e: Exception) {

            Log.e("SugarCounter", "Create Excel error", e)

            showMessage("خطا در آماده‌سازی فایل Excel")
        }
    }

    private fun createXlsx(records: List<CountRecord>): ByteArray {

        val output = ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->

            addZipFile(
                zip,
                "[Content_Types].xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
                """.trimIndent()
            )

            addZipFile(
                zip,
                "_rels/.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                    Target="xl/workbook.xml"/>
                </Relationships>
                """.trimIndent()
            )

            addZipFile(
                zip,
                "xl/workbook.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets>
                        <sheet name="گزارش شمارش" sheetId="1" r:id="rId1"/>
                    </sheets>
                </workbook>
                """.trimIndent()
            )

            addZipFile(
                zip,
                "xl/_rels/workbook.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                    Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                    Target="styles.xml"/>
                </Relationships>
                """.trimIndent()
            )

            addZipFile(
                zip,
                "xl/styles.xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">

                    <fonts count="3">
                        <font>
                            <sz val="11"/>
                            <name val="Arial"/>
                        </font>
                        <font>
                            <b/>
                            <sz val="16"/>
                            <color rgb="FFFFFFFF"/>
                            <name val="Arial"/>
                        </font>
                        <font>
                            <b/>
                            <sz val="11"/>
                            <name val="Arial"/>
                        </font>
                    </fonts>

                    <fills count="4">
                        <fill><patternFill patternType="none"/></fill>
                        <fill><patternFill patternType="gray125"/></fill>
                        <fill>
                            <patternFill patternType="solid">
                                <fgColor rgb="FF285F49"/>
                                <bgColor indexed="64"/>
                            </patternFill>
                        </fill>
                        <fill>
                            <patternFill patternType="solid">
                                <fgColor rgb="FFE5F0E9"/>
                                <bgColor indexed="64"/>
                            </patternFill>
                        </fill>
                    </fills>

                    <borders count="2">
                        <border>
                            <left/>
                            <right/>
                            <top/>
                            <bottom/>
                            <diagonal/>
                        </border>
                        <border>
                            <left style="thin"><color rgb="FF9BAA9F"/></left>
                            <right style="thin"><color rgb="FF9BAA9F"/></right>
                            <top style="thin"><color rgb="FF9BAA9F"/></top>
                            <bottom style="thin"><color rgb="FF9BAA9F"/></bottom>
                            <diagonal/>
                        </border>
                    </borders>

                    <cellStyleXfs count="1">
                        <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
                    </cellStyleXfs>

                    <cellXfs count="7">
                        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>

                        <xf numFmtId="0" fontId="1" fillId="2" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="center" vertical="center"/>
                        </xf>

                        <xf numFmtId="0" fontId="2" fillId="3" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="center" vertical="center"/>
                        </xf>

                        <xf numFmtId="0" fontId="2" fillId="2" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="center" vertical="center" wrapText="1"/>
                        </xf>

                        <xf numFmtId="0" fontId="0" fillId="0" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="center" vertical="center"/>
                        </xf>

                        <xf numFmtId="0" fontId="2" fillId="3" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="right" vertical="center"/>
                        </xf>

                        <xf numFmtId="0" fontId="2" fillId="3" borderId="1"
                        xfId="0" applyAlignment="1">
                            <alignment horizontal="center" vertical="center"/>
                        </xf>
                    </cellXfs>

                    <cellStyles count="1">
                        <cellStyle name="Normal" xfId="0" builtinId="0"/>
                    </cellStyles>

                </styleSheet>
                """.trimIndent()
            )

            val sheet = StringBuilder()

            val lastDataRow = 4 + records.size
            val totalRow = lastDataRow + 1

            sheet.append(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            )

            sheet.append(
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">"""
            )

            sheet.append(
                """
                <sheetViews>
                    <sheetView showGridLines="0" rightToLeft="1" workbookViewId="0"/>
                </sheetViews>
                """.trimIndent()
            )

            sheet.append(
                """
                <cols>
                    <col min="1" max="1" width="12" customWidth="1"/>
                    <col min="2" max="2" width="20" customWidth="1"/>
                    <col min="3" max="3" width="15" customWidth="1"/>
                    <col min="4" max="4" width="20" customWidth="1"/>
                </cols>
                """.trimIndent()
            )

            sheet.append("<sheetData>")

            sheet.append(
                """<row r="1" ht="32" customHeight="1">"""
            )

            sheet.append(
                excelCell(
                    "A1",
                    "سامانه هوشمند شمارش کیسه‌های قند",
                    1
                )
            )

            sheet.append("</row>")

            sheet.append(
                """<row r="2" ht="25" customHeight="1">"""
            )

            sheet.append(
                excelCell(
                    "A2",
                    "دانشگاه آزاد اسلامی واحد همدان - هسته فناور کریستال‌بین",
                    2
                )
            )

            sheet.append("</row>")

            sheet.append("""<row r="3"/>""")

            sheet.append("""<row r="4" ht="28" customHeight="1">""")

            sheet.append(excelCell("A4", "ردیف", 3))
            sheet.append(excelCell("B4", "تاریخ شمسی", 3))
            sheet.append(excelCell("C4", "ساعت", 3))
            sheet.append(excelCell("D4", "تعداد کیسه", 3))

            sheet.append("</row>")

            records.forEachIndexed { index, record ->

                val row = index + 5

                sheet.append(
                    """<row r="$row" ht="24" customHeight="1">"""
                )

                sheet.append(
                    excelCell(
                        "A$row",
                        toPersianDigits((index + 1).toString()),
                        4
                    )
                )

                sheet.append(
                    excelCell(
                        "B$row",
                        toPersianDigits(jalaliDate(record.time)),
                        4
                    )
                )

                sheet.append(
                    excelCell(
                        "C$row",
                        toPersianDigits(clockTime(record.time)),
                        4
                    )
                )

                sheet.append(
                    excelCell(
                        "D$row",
                        toPersianDigits(record.count.toString()),
                        4
                    )
                )

                sheet.append("</row>")
            }

            val totalCount = records.sumOf { it.count }

            sheet.append(
                """<row r="$totalRow" ht="28" customHeight="1">"""
            )

            sheet.append(
                excelCell(
                    "A$totalRow",
                    "مجموع کل کیسه‌ها",
                    5
                )
            )

            sheet.append(
                excelCell(
                    "D$totalRow",
                    toPersianDigits(totalCount.toString()),
                    6
                )
            )

            sheet.append("</row>")

            sheet.append("</sheetData>")

            sheet.append(
                """<autoFilter ref="A4:D$lastDataRow"/>"""
            )

            sheet.append(
                """
                <mergeCells count="3">
                    <mergeCell ref="A1:D1"/>
                    <mergeCell ref="A2:D2"/>
                    <mergeCell ref="A$totalRow:C$totalRow"/>
                </mergeCells>
                """.trimIndent()
            )

            sheet.append(
                """
                <pageMargins left="0.25" right="0.25" top="0.5"
                bottom="0.5" header="0.2" footer="0.2"/>
                """.trimIndent()
            )

            sheet.append("</worksheet>")

            addZipFile(
                zip,
                "xl/worksheets/sheet1.xml",
                sheet.toString()
            )
        }

        return output.toByteArray()
    }

    private fun excelCell(
        address: String,
        value: String,
        style: Int
    ): String {

        val safe = xmlEscape(value)

        return """
            <c r="$address" s="$style" t="inlineStr">
                <is><t xml:space="preserve">$safe</t></is>
            </c>
        """.trimIndent()
    }

    private fun xmlEscape(value: String): String {

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun addZipFile(
        zip: ZipOutputStream,
        name: String,
        content: String
    ) {

        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // --------------------------------------------------
    // دوربین و تشخیص کیسه‌ها
    // --------------------------------------------------

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

                            countText.text =
                                "تعداد: ${toPersianDigits(totalCount.toString())}"
                        }
                    }

                    bitmap.recycle()

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

    // --------------------------------------------------
    // ابزارهای عمومی
    // --------------------------------------------------

    private fun showMessage(message: String) {

        android.widget.Toast.makeText(
            this,
            message,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {

        saveCurrentSession()

        super.onDestroy()

        executor.shutdown()

        detector.close()
    }
}
