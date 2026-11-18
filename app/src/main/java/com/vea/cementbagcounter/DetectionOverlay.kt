
package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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


    // تنظیمات تصویر راهنما
    var guideOverlayEnabled = true
    set(value) {
        field = value
        invalidate()
    }
    
    var guideOverlayAlpha = 48
    set(value) {
        field = value.coerceIn(0, 255)
        invalidate()
    }


    // کادر تشخیص
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // خطوط شمارش
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 5f
    }

    // نوشته‌ها
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // پس‌زمینه نوشته شناسه
    private val labelBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            style = Paint.Style.FILL
        }

    // رنگ خطوط راهنما
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // رنگ محدوده راهنما
    private val guideFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    // متن راهنما
    private val guideTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

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

    private fun imageScale(): Float {
        if (imageW <= 0f || imageH <= 0f) return 1f

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // مرحله اول: تصویر راهنما
        if (guideOverlayEnabled) {
            drawReferenceGuide(canvas)
        }

        val scale = imageScale()
        val dx = offsetX(scale)
        val dy = offsetY(scale)

        // مرحله دوم: کادرهای تشخیص و ردیابی
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

        // مرحله سوم: خطوط شمارش
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

    // تصویر راهنمای تنظیم دوربین
    private fun drawReferenceGuide(canvas: Canvas) {

        if (width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        val alpha = guideOverlayAlpha

        if (alpha <= 0) return

        val gold = Color.rgb(229, 189, 105)

        guidePaint.color = gold
        guidePaint.alpha = alpha

        guideFillPaint.color = gold
        guideFillPaint.alpha = (alpha * 0.12f).toInt()

        guideTextPaint.alpha =
            (alpha * 2.2f).toInt().coerceAtMost(180)

        // محدوده کلی مسیر نوار نقاله
        val conveyor = Path().apply {
            moveTo(w * 0.08f, h * 0.24f)
            lineTo(w * 0.92f, h * 0.24f)
            lineTo(w * 0.99f, h * 0.94f)
            lineTo(w * 0.01f, h * 0.94f)
            close()
        }

        canvas.drawPath(conveyor, guideFillPaint)
        canvas.drawPath(conveyor, guidePaint)

        // خطوط کناری نوار نقاله
        canvas.drawLine(
            w * 0.08f, h * 0.24f,
            w * 0.01f, h * 0.94f,
            guidePaint
        )

        canvas.drawLine(
            w * 0.92f, h * 0.24f,
            w * 0.99f, h * 0.94f,
            guidePaint
        )

        // خط مرکزی برای تنظیم زاویه دوربین
        canvas.drawLine(
            w * 0.5f, h * 0.20f,
            w * 0.5f, h * 0.94f,
            guidePaint
        )

        // محدوده پیشنهادی قرارگیری کیسه
        val bagRect = RectF(
            w * 0.27f,
            h * 0.39f,
            w * 0.73f,
            h * 0.69f
        )

        canvas.drawRoundRect(
            bagRect,
            18f,
            18f,
            guideFillPaint
        )

        canvas.drawRoundRect(
            bagRect,
            18f,
            18f,
            guidePaint
        )

        // شکل شماتیک کیسه
        val bag = Path().apply {
            moveTo(w * 0.35f, h * 0.44f)
            lineTo(w * 0.65f, h * 0.44f)
            lineTo(w * 0.68f, h * 0.63f)
            lineTo(w * 0.32f, h * 0.63f)
            close()
        }

        canvas.drawPath(bag, guidePaint)

        // خطوط گوشه‌ای محدوده
        val cornerLength = w * 0.055f

        drawCorner(
            canvas,
            w * 0.20f,
            h * 0.35f,
            cornerLength,
            guidePaint
        )

        drawCorner(
            canvas,
            w * 0.80f,
            h * 0.35f,
            cornerLength,
            guidePaint,
            true
        )

        drawCorner(
            canvas,
            w * 0.20f,
            h * 0.74f,
            cornerLength,
            guidePaint,
            false,
            true
        )

        drawCorner(
            canvas,
            w * 0.80f,
            h * 0.74f,
            cornerLength,
            guidePaint,
            true,
            true
        )

        // نوشته‌های راهنما
        canvas.drawText(
            "محدوده تنظیم دوربین",
            w * 0.5f,
            h * 0.17f,
            guideTextPaint
        )

        canvas.drawText(
            "محدوده قرارگیری کیسه",
            w * 0.5f,
            h * 0.73f,
            guideTextPaint
        )
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        length: Float,
        paint: Paint,
        right: Boolean = false,
        bottom: Boolean = false
    ) {

        val directionX = if (right) -1f else 1f
        val directionY = if (bottom) -1f else 1f

        canvas.drawLine(
            x,
            y,
            x + length * directionX,
            y,
            paint
        )

        canvas.drawLine(
            x,
            y,
            x,
            y + length * directionY,
            paint
        )
    }

    // نمایش کادر تشخیص و شناسه کیسه
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

        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            boxPaint
        )

        val label = if (trackId != null) {
            "ID $trackId | ${(detection.confidence * 100).toInt()}%"
        } else {
            "${(detection.confidence * 100).toInt()}%"
        }

        val labelWidth =
            textPaint.measureText(label) + 16f

        val labelTop =
            (top - 42f).coerceAtLeast(0f)

        canvas.drawRoundRect(
            left,
            labelTop,
            left + labelWidth,
            labelTop + 36f,
            6f,
            6f,
            labelBackgroundPaint
        )

        canvas.drawText(
            label,
            left + 8f,
            labelTop + 27f,
            textPaint
        )
    }

    // جابه‌جایی خطوط شمارش با لمس
    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (!isSetup) return false

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
