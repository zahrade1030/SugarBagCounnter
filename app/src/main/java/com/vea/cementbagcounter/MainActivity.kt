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

        addButton(firstRow, "تنظیم خطوط") {
            setupMode = true
            overlay.isSetup = true
            statusText.text = "خطوط را با لمس جابه‌جا کنید"
        }

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

        addButton(firstRow, "توقف") {
            running = false

            val saved = saveCurrentSession()

            statusText.text =
                if (saved) "شمارش متوقف و نتیجه ثبت شد"
                else "شمارش متوقف شد"
        }

        addButton(secondRow, "ریست") {

            saveCurrentSession()

            totalCount = 0
            tracker.reset()
            countText.text = "تعداد: 0"
            statusText.text = "شمارش از ابتدا آماده است"
        }

        addButton(secondRow, "سوابق شمارش") {
            showHistory()
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

        setContentView(base)
    }

    private fun saveCurrentSession(): Boolean {

        if (sessionSaved) return false

        sessionSaved = true

        if (totalCount <= 0) return false

        val oldRecords = try {
            JSONArray(
                historyPrefs.getString("records", "[]") ?: "[]"
            )
        } catch (e: Exception) {
            JSONArray()
        }

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

    private fun showHistory() {

        val records = try {
            JSONArray(
                historyPrefs.getString("records", "[]") ?: "[]"
            )
        } catch (e: Exception) {
            JSONArray()
        }

        if (records.length() == 0) {
            AlertDialog.Builder(this)
                .setTitle("سوابق شمارش")
                .setMessage("هنوز هیچ سابقه‌ای ثبت نشده است.")
                .setPositiveButton("بستن", null)
                .show()
            return
        }

        val formatter = SimpleDateFormat(
            "yyyy/MM/dd   HH:mm",
            Locale("fa", "IR")
        )

        val historyText = StringBuilder()

        for (i in 0 until records.length()) {
            val item = records.optJSONObject(i) ?: continue

            val count = item.optInt("count", 0)
            val time = item.optLong("time", 0L)

            historyText.append("تعداد کیسه‌ها: $count\n")
            historyText.append(
                "تاریخ و ساعت: ${formatter.format(Date(time))}\n"
            )
            historyText.append("------------------------\n")
        }

        AlertDialog.Builder(this)
            .setTitle("سوابق شمارش")
            .setMessage(historyText.toString())
            .setPositiveButton("بستن", null)
            .show()
    }

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



