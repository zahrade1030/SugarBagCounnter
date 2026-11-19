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

    // خطوط شمارش
    var lineTop = 150f
    var lineBottom = 411f

    // ابعاد تصویر اصلی
    private var imageW = 640f
    private var imageH = 640f

    // تشخیص‌های خام
    private var detections: List<Detection> = emptyList()

    // تشخیص‌های ردیابی‌شده
    private var trackedDetections: List<TrackedDetection> =
        emptyList()

    private var showingTracked = false

    // خط در حال جابه‌جایی
    private var dragging = 0

    private val touchThreshold =
        40f * resources.displayMetrics.density

    // کادر تشخیص واقعی
    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

    // کادر پیش‌بینی‌شده
    private val predictedBoxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 165, 0)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(12f, 8f),
                0f
            )
        }

    // خطوط شمارش
    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }

    // متن
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    // پس‌زمینه متن
    private val labelBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            style = Paint.Style.FILL
        }

    // متن خطوط
    private val lineLabelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    // پس‌زمینه برچسب خطوط
    private val lineLabelBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }

    /**
     * دریافت تشخیص‌های خام مدل
     */
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

    /**
     * دریافت تشخیص‌های Tracker
     */
    fun setTrackedDetections(
        list: List<TrackedDetection>,
        w: Int,
        h: Int
    ) {

        trackedDetections = list

        imageW = w.toFloat()
        imageH = h

        showingTracked = true

        invalidate()
    }

    /**
     * مقیاس تصویر در Preview
     */
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

    /**
     * رسم Overlay
     */
    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val scale = imageScale()

        val dx = offsetX(scale)
        val dy = offsetY(scale)

        // رسم کادرها
        if (showingTracked) {

            trackedDetections.forEach { tracked ->

                drawDetection(
                    canvas = canvas,
                    detection = tracked.detection,
                    scale = scale,
                    dx = dx,
                    dy = dy,
                    trackId = tracked.id,
                    isPredicted = tracked.isPredicted
                )
            }

        } else {

            detections.forEach { detection ->

                drawDetection(
                    canvas = canvas,
                    detection = detection,
                    scale = scale,
                    dx = dx,
                    dy = dy,
                    trackId = null,
                    isPredicted = false
                )
            }
        }

        // رسم خطوط شمارش
        val topY =
            lineTop * scale + dy

        val bottomY =
            lineBottom * scale + dy

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

        // برچسب خط بالایی
        drawLineLabel(
            canvas,
            "خط شمارش",
            topY
        )

        // برچسب خط پایینی
        drawLineLabel(
            canvas,
            "خط محدوده",
            bottomY
        )

        // پیام راهنما
        val message = if (isSetup) {
            "برای جابه‌جایی خطوط، روی خط لمس کنید"
        } else {
            "شمارش فعال است"
        }

        canvas.drawText(
            message,
            18f,
            38f,
            textPaint
        )
    }

    /**
     * رسم برچسب خطوط
     */
    private fun drawLineLabel(
        canvas: Canvas,
        label: String,
        y: Float
    ) {

        val padding = 12f

        val textWidth =
            lineLabelPaint.measureText(label)

        val labelHeight = 34f

        val left = 18f

        val top = (y - labelHeight / 2f)
            .coerceIn(
                0f,
                height.toFloat() - labelHeight
            )

        canvas.drawRoundRect(
            left,
            top,
            left + textWidth + padding * 2f,
            top + labelHeight,
            8f,
            8f,
            lineLabelBackgroundPaint
        )

        canvas.drawText(
            label,
            left + padding,
            top + labelHeight / 2f + 8f,
            lineLabelPaint
        )
    }

    /**
     * رسم کادر تشخیص
     */
    private fun drawDetection(
        canvas: Canvas,
        detection: Detection,
        scale: Float,
        dx: Float,
        dy: Float,
        trackId: Int?,
        isPredicted: Boolean
    ) {

        val left =
            detection.left * scale + dx

        val top =
            detection.top * scale + dy

        val right =
            detection.right * scale + dx

        val bottom =
            detection.bottom * scale + dy

        val currentPaint = if (isPredicted) {
            predictedBoxPaint
        } else {
            boxPaint
        }

        // رسم کادر
        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            currentPaint
        )

        // متن کادر
        val label = when {

            trackId != null && isPredicted ->
                "ID $trackId | PREDICT"

            trackId != null ->
                "ID $trackId | ${(detection.confidence * 100).toInt()}%"

            else ->
                "${(detection.confidence * 100).toInt()}%"
        }

        val labelWidth =
            textPaint.measureText(label) + 16f

        val labelHeight = 36f

        val labelTop =
            (top - 42f).coerceAtLeast(0f)

        // پس‌زمینه
        canvas.drawRoundRect(
            left,
            labelTop,
            left + labelWidth,
            labelTop + labelHeight,
            6f,
            6f,
            labelBackgroundPaint
        )

        // متن
        canvas.drawText(
            label,
            left + 8f,
            labelTop + 27f,
            textPaint
        )
    }

    /**
     * کنترل جابه‌جایی خطوط
     */
    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        if (!isSetup) {
            return false
        }

        val scale = imageScale()

        val dy = offsetY(scale)

        val imageY =
            (event.y - dy) / scale

        val topScreenY =
            lineTop * scale + dy

        val bottomScreenY =
            lineBottom * scale + dy

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {

                val distanceTop =
                    abs(event.y - topScreenY)

                val distanceBottom =
                    abs(event.y - bottomScreenY)

                if (
                    distanceTop <= touchThreshold ||
                    distanceBottom <= touchThreshold
                ) {

                    dragging = if (
                        distanceTop <= distanceBottom
                    ) {
                        1
                    } else {
                        2
                    }

                    return true
                }

                dragging = 0

                return false
            }

            MotionEvent.ACTION_MOVE -> {

                if (dragging == 0) {
                    return false
                }

                val newY = imageY.coerceIn(
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

            MotionEvent.ACTION_UP -> {

                val wasDragging =
                    dragging != 0

                dragging = 0

                return wasDragging
            }

            MotionEvent.ACTION_CANCEL -> {

                val wasDragging =
                    dragging != 0

                dragging = 0

                return wasDragging
            }
        }

        return dragging != 0
    }
}
