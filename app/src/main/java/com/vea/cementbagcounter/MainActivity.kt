package com.vea.cementbagcounter

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    // --------------------------------------------------
    // رابط اصلی برنامه
    // --------------------------------------------------

    private fun buildUi() {

        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 28, 24))
        }

        val header = TextView(this).apply {
            text = "شمارشگر هوشمند کیسه‌های قند"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
            typeface = Typeface.DEFAULT_BOLD
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
            textSize = 30f
            setTextColor(Color.WHITE)
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
            val button = Button(this).apply {
                text = label
                textSize = 12f
                setOnClickListener { action() }
            }

            parent.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(48),
                    1f
                ).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            )
        }

        addActionButton(firstRow, "تنظیم خطوط") {
            setupMode = true
            overlay.isSetup = true
            statusText.text = "خطوط را با لمس جابه‌جا کنید"
        }

        addActionButton(firstRow, "شروع شمارش") {
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

        addActionButton(firstRow, "توقف") {
            stopCounting()
        }

        addActionButton(secondRow, "ریست") {
            totalCount = 0
            tracker.reset()
            countText.text = "تعداد: 0"
            statusText.text = "شمارش از ابتدا آماده است"
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

        val aboutButton = Button(this).apply {
            text = "درباره برنامه"
            setOnClickListener { showAbout() }
        }

        controls.addView(firstRow)
        controls.addView(secondRow)
        controls.addView(aboutButton)

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
    // دوربین و تشخیص
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
                            val newCount = tracker.update(detections)

                            if (newCount > 0) {
                                totalCount += newCount
                                countText.text = "تعداد: $totalCount"
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("SugarCounter", "Detection error", e)

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

    private fun stopCounting() {

        if (totalCount > 0) {
            saveCurrentSession()
        }

        running = false
        statusText.text = "شمارش متوقف شد"
    }

    // --------------------------------------------------
    // ذخیره تاریخچه
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
        } catch (e: Exception) {
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
    // آمار روزانه، هفتگی و ماهانه
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
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
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

            if (timestamp >= dayStart && timestamp <= now) {
                daily += count
            }

            if (timestamp >= weekStart && timestamp <= now) {
                weekly += count
            }

            if (timestamp >= monthStart && timestamp <= now) {
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
    // خروجی Excel
    // --------------------------------------------------

    private fun exportExcel() {

        val history = getHistory()

        if (history.length() == 0) {
            AlertDialog.Builder(this)
                .setMessage("برای ساخت گزارش، ابتدا باید سابقه شمارش داشته باشید.")
                .setPositiveButton("باشه", null)
                .show()
            return
        }

        pendingExcelData = createExcelFile(history)

        excelLauncher.launch(
            "گزارش_شمارش_کیسه_قند.xlsx"
        )
    }

    private fun saveExcelFile(uri: Uri) {

        val data = pendingExcelData ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(data)
                output.flush()
            }

            AlertDialog.Builder(this)
                .setMessage("فایل Excel با موفقیت ذخیره شد.")
                .setPositiveButton("باشه", null)
                .show()

        } catch (e: Exception) {
            Log.e("SugarCounter", "Excel save error", e)

            AlertDialog.Builder(this)
                .setMessage("خطا در ذخیره فایل: ${e.localizedMessage}")
                .setPositiveButton("باشه", null)
                .show()
        } finally {
            pendingExcelData = null
        }
    }

    private fun createExcelFile(history: JSONArray): ByteArray {

        val output = java.io.ByteArrayOutputStream()
        val zip = ZipOutputStream(output)

        fun addEntry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val total = run {
            var sum = 0
            for (i in 0 until history.length()) {
                sum += history.getJSONObject(i).optInt("count")
            }
            sum
        }

        val rows = StringBuilder()

        rows.append(
            """<row r="1"><c r="A1" t="inlineStr" s="1"><is><t>گزارش شمارش کیسه‌های قند</t></is></c></row>"""
        )

        rows.append(
            """<row r="2"><c r="A2" t="inlineStr" s="2"><is><t>ردیف</t></is></c><c r="B2" t="inlineStr" s="2"><is><t>تاریخ شمسی</t></is></c><c r="C2" t="inlineStr" s="2"><is><t>ساعت</t></is></c><c r="D2" t="inlineStr" s="2"><is><t>تعداد کیسه</t></is></c></row>"""
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

        val totalRow = history.length() + 3

        rows.append(
            """<row r="$totalRow"><c r="C$totalRow" t="inlineStr" s="2"><is><t>مجموع کل</t></is></c><c r="D$totalRow" t="n" s="2"><v>$total</v></c></row>"""
        )

        addEntry(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
        )

        addEntry(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        )

        addEntry(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="گزارش شمارش" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        )

        addEntry(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        )

        addEntry(
            "xl/styles.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="14"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDDEBE2"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="center"/></xf><xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0"/></cellXfs></styleSheet>"""
        )

        addEntry(
            "xl/worksheets/sheet1.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><cols><col min="1" max="1" width="12" customWidth="1"/><col min="2" max="2" width="20" customWidth="1"/><col min="3" max="3" width="15" customWidth="1"/><col min="4" max="4" width="18" customWidth="1"/></cols><sheetData>$rows</sheetData></worksheet>"""
        )

        zip.finish()
        zip.close()

        return output.toByteArray()
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // --------------------------------------------------
    // صفحه درباره برنامه
    // --------------------------------------------------

    private fun showAbout() {

        val white = Color.WHITE
        val dark = Color.rgb(35, 48, 42)
        val green = Color.rgb(35, 105, 76)
        val muted = Color.rgb(112, 125, 117)
        val border = Color.rgb(229, 235, 230)

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
            setTextColor(Color.rgb(112, 125, 117))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(13))
            typeface = Typeface.DEFAULT
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

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                ColorDrawable(white)
            )

            dialog.window?.setLayout(
                resources.displayMetrics.widthPixels - dp(32),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(green)
        }

        dialog.show()

        dialog.window?.setBackgroundDrawable(
            ColorDrawable(white)
        )

        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - dp(32),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
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
                    Color.WHITE,
                    16,
                    Color.rgb(229, 235, 230)
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
            setTextColor(Color.rgb(40, 55, 46))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        card.addView(mainText)

        val secondaryText = TextView(this).apply {
            text = subheading
            textSize = 11f
            setTextColor(Color.rgb(100, 115, 105))
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
                    Color.rgb(248, 250, 248),
                    12,
                    Color.rgb(235, 239, 236)
                )
            )
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val indicator = View(this).apply {
            setBackground(
                aboutBackground(
                    Color.rgb(49, 132, 91),
                    50,
                    Color.rgb(49, 132, 91)
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
            setTextColor(Color.rgb(38, 50, 43))
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
            setTextColor(Color.rgb(49, 119, 83))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackground(
                aboutBackground(
                    Color.rgb(231, 242, 234),
                    8,
                    Color.rgb(231, 242, 234)
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
                if (bold) {
                    Color.rgb(35, 105, 76)
                } else {
                    Color.rgb(45, 55, 48)
                }
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
    // تاریخ شمسی و ساعت
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

        var gy = gyInput
        val gm = gmInput
        val gd = gdInput

        var jy: Int
        val gy2 = if (gm > 2) gy + 1 else gy

        var days = 355666 +
            (365 * gy) +
            ((gy2 + 3) / 4) -
            ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) +
            gd +
            gDays[gm - 1]

        jy = -1595 + 33 * (days / 12053)
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
    // ابزارهای عمومی
    // --------------------------------------------------

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
    }
}
