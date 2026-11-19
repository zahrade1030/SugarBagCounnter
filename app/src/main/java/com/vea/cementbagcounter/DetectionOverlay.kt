package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class DetectionOverlay(context: Context) : View(context) {

    var isSetup = true

    var lineTop = 150f
    var lineBottom = 411f

    private var imageW = 640f
    private var imageH = 640f

    private var detections: List<Detection> = emptyList()
    private var trackedDetections: List<TrackedDetection> = emptyList()

    private var showingTracked = false
    private var dragging = 0

    // --------------------------------------------------
    // کادر تشخیص
    // --------------------------------------------------

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // --------------------------------------------------
    // خطوط شمارش
    // --------------------------------------------------

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
    }

    // --------------------------------------------------
    // نوشته‌ها
    // --------------------------------------------------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // --------------------------------------------------
    // پس‌زمینه نوشته شناسه
    // --------------------------------------------------

    private val labelBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            style = Paint.Style.FILL
        }

    // --------------------------------------------------
    // دریافت تشخیص‌ها
    // --------------------------------------------------

    fun setDetections(
        list: List<Detection>,
        w: Int,
        h: Int
    ) {
        detections = list

        imageW = w.toFloat()
        imageH = h.toFloat()

        trackedDetections = emptyList()
        showingTracked = false

        invalidate()
    }

    // --------------------------------------------------
    // دریافت تشخیص‌های ردیابی‌شده
    // --------------------------------------------------

    fun setTrackedDetections(
        list: List<TrackedDetection>,
        w: Int,
        h: Int
    ) {
        trackedDetections = list

        imageW = w.toFloat()
        imageH = h.toFloat()

        showingTracked = true

        invalidate()
    }

    // --------------------------------------------------
    // محاسبه اندازه تصویر دوربین
    // --------------------------------------------------

    private fun imageScale(): Float {

        if (imageW <= 0f || imageH <= 0f) {
            return 1f
        }

        return max(
            width / imageW,
            height / imageH
        )
    }

    private fun offsetX(scale: Float): Float {

        return (width - imageW * scale) / 2f
    }

    private fun offsetY(scale: Float): Float {

        return (height - imageH * scale) / 2f
    }

    // --------------------------------------------------
    // رسم خطوط و کادرهای تشخیص
    // --------------------------------------------------

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val scale = imageScale()

        val dx = offsetX(scale)
        val dy = offsetY(scale)

        // کادرهای تشخیص و ردیابی

        if (showingTracked) {

            trackedDetections.forEach { tracked ->

                drawDetection(
                    canvas,
                    tracked.detection,
                    scale,
                    dx,
                    dy,
                    tracked.id
                )
            }

        } else {

            detections.forEach { detection ->

                drawDetection(
                    canvas,
                    detection,
                    scale,
                    dx,
                    dy,
                    null
                )
            }
        }

        // خطوط شمارش

        val topY = lineTop * scale + dy

        val bottomY = lineBottom * scale + dy

        canvas.drawLine(
            0f,
            topY,
            width.toFloat(),
            topY,
            linePaint
        )

        canvas.drawLine(
            0f,
            bottomY,
            width.toFloat(),
            bottomY,
            linePaint
        )

        // پیام وضعیت

        canvas.drawText(
            if (isSetup) {
                "برای جابه‌جایی خطوط لمس کن"
            } else {
                "شمارش فعال است"
            },
            18f,
            38f,
            textPaint
        )
    }

    // --------------------------------------------------
    // رسم کادر تشخیص و شناسه کیسه
    // --------------------------------------------------

    private fun drawDetection(
        canvas: Canvas,
        detection: Detection,
        scale: Float,
        dx: Float,
        dy: Float,
        trackId: Int?
    ) {

        val left = detection.left * scale + dx

        val top = detection.top * scale + dy

        val right = detection.right * scale + dx

        val bottom = detection.bottom * scale + dy

        // رسم کادر

        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            boxPaint
        )

        // متن شناسه و اطمینان تشخیص

        val label = if (trackId != null) {

            "ID $trackId | ${(detection.confidence * 100).toInt()}%"

        } else {

            "${(detection.confidence * 100).toInt()}%"
        }

        val labelWidth =
            textPaint.measureText(label) + 16f

        val labelTop =
            (top - 42f).coerceAtLeast(0f)

        // پس‌زمینه نوشته

        canvas.drawRoundRect(
            left,
            labelTop,
            left + labelWidth,
            labelTop + 36f,
            6f,
            6f,
            labelBackgroundPaint
        )

        // نوشته

        canvas.drawText(
            label,
            left + 8f,
            labelTop + 27f,
            textPaint
        )
    }

    // --------------------------------------------------
    // جابه‌جایی خطوط شمارش با لمس
    // --------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (!isSetup) {
            return false
        }

        val scale = imageScale()

        val y = (
            event.y - offsetY(scale)
        ) / scale

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                dragging = if (
                    abs(y - lineTop) < abs(y - lineBottom)
                ) {
                    1
                } else {
                    2
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                val newY = y.coerceIn(
                    20f,
                    imageH - 20f
                )

                if (dragging == 1) {

                    lineTop = newY

                } else if (dragging == 2) {

                    lineBottom = newY
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                dragging = 0

                return true
            }
        }

        return true
    }
}
