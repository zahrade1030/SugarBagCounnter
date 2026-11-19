package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.DashPathEffect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetectionOverlay(context: Context) : View(context) {

    var isSetup = true
        set(value) {
            field = value
            invalidate()
        }

    var lineTop = 150f
        set(value) {
            field = value.coerceIn(0f, imageH)
            invalidate()
        }

    var lineBottom = 411f
        set(value) {
            field = value.coerceIn(0f, imageH)
            invalidate()
        }

    var roiLeft = 80f
        set(value) {
            field = value.coerceIn(0f, imageW)
            invalidate()
        }

    var roiRight = 560f
        set(value) {
            field = value.coerceIn(0f, imageW)
            invalidate()
        }

    private var imageW = 640f
    private var imageH = 640f

    private var detections: List<Detection> = emptyList()
    private var trackedDetections: List<TrackedDetection> = emptyList()

    private var showingTracked = false
    private var dragging = 0

    private val density = resources.displayMetrics.density
    private val touchThreshold = 40f * density
    private val minimumRoiWidth = 40f

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val predictedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 165, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = DashPathEffect(
            floatArrayOf(10f * density, 6f * density),
            0f
        )
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
    }

    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD151515.toInt()
        style = Paint.Style.FILL
    }

    private val lineLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val lineLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val roiLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val roiLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val setupMessageBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
    }

    fun setDetections(
        list: List<Detection>,
        w: Int,
        h: Int
    ) {
        detections = list
        imageW = w.toFloat().coerceAtLeast(1f)
        imageH = h.toFloat().coerceAtLeast(1f)

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
        imageW = w.toFloat().coerceAtLeast(1f)
        imageH = h.toFloat().coerceAtLeast(1f)

        showingTracked = true

        invalidate()
    }

    fun clearDetections() {
        detections = emptyList()
        trackedDetections = emptyList()
        showingTracked = false
        invalidate()
    }

    fun setImageSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        imageW = w.toFloat()
        imageH = h.toFloat()

        lineTop = lineTop.coerceIn(0f, imageH)
        lineBottom = lineBottom.coerceIn(0f, imageH)

        roiLeft = roiLeft.coerceIn(0f, imageW)
        roiRight = roiRight.coerceIn(0f, imageW)

        invalidate()
    }

    private fun imageScale(): Float {
        if (
            imageW <= 0f ||
            imageH <= 0f ||
            width <= 0 ||
            height <= 0
        ) {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val scale = imageScale()
        val dx = offsetX(scale)
        val dy = offsetY(scale)

        if (showingTracked) {
            trackedDetections.forEach { tracked ->
                drawDetection(
                    canvas,
                    tracked.detection,
                    scale,
                    dx,
                    dy,
                    tracked.id,
                    tracked.isPredicted
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
                    null,
                    false
                )
            }
        }

        drawGuides(canvas, scale, dx, dy)
        drawStatusMessage(canvas)
    }

    private fun drawGuides(
        canvas: Canvas,
        scale: Float,
        dx: Float,
        dy: Float
    ) {
        val topY = lineTop * scale + dy
        val bottomY = lineBottom * scale + dy

        val leftX = roiLeft * scale + dx
        val rightX = roiRight * scale + dx

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

        canvas.drawLine(
            leftX,
            0f,
            leftX,
            height.toFloat(),
            roiPaint
        )

        canvas.drawLine(
            rightX,
            0f,
            rightX,
            height.toFloat(),
            roiPaint
        )

        drawLineLabel(canvas, "خط شمارش", topY)
        drawLineLabel(canvas, "خط دوم", bottomY)

        drawRoiLabel(
            canvas,
            "شروع محدوده",
            leftX,
            true
        )

        drawRoiLabel(
            canvas,
            "پایان محدوده",
            rightX,
            false
        )
    }

    private fun drawStatusMessage(canvas: Canvas) {
        val message = if (isSetup) {
            "تنظیم خطوط و محدوده شمارش"
        } else {
            "شمارش فعال است"
        }

        val padding = 12f * density
        val messageWidth = textPaint.measureText(message)

        val left = 8f * density
        val top = 8f * density
        val heightMessage = 34f * density

        canvas.drawRoundRect(
            left,
            top,
            left + messageWidth + padding * 2f,
            top + heightMessage,
            8f * density,
            8f * density,
            setupMessageBackground
        )

        canvas.drawText(
            message,
            left + padding,
            top + heightMessage / 2f + 5f * density,
            textPaint
        )
    }

    private fun drawLineLabel(
        canvas: Canvas,
        label: String,
        y: Float
    ) {
        val padding = 8f * density
        val textWidth = lineLabelPaint.measureText(label)
        val labelHeight = 26f * density
        val left = 8f * density

        val top = (y - labelHeight / 2f).coerceIn(
            0f,
            (height - labelHeight).coerceAtLeast(0f)
        )

        canvas.drawRoundRect(
            left,
            top,
            left + textWidth + padding * 2,
            top + labelHeight,
            6f * density,
            6f * density,
            lineLabelBackgroundPaint
        )

        canvas.drawText(
            label,
            left + padding,
            top + labelHeight / 2f + 4.5f * density,
            lineLabelPaint
        )
    }

    private fun drawRoiLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        isLeft: Boolean
    ) {
        val padding = 8f * density
        val textWidth = roiLabelPaint.measureText(label)
        val labelHeight = 26f * density
        val labelWidth = textWidth + padding * 2f

        val desiredLeft = if (isLeft) {
            x + 8f * density
        } else {
            x - labelWidth - 8f * density
        }

        val left = desiredLeft.coerceIn(
            0f,
            (width - labelWidth).coerceAtLeast(0f)
        )

        val top = 48f * density

        canvas.drawRoundRect(
            left,
            top,
            left + labelWidth,
            top + labelHeight,
            6f * density,
            6f * density,
            roiLabelBackgroundPaint
        )

        canvas.drawText(
            label,
            left + padding,
            top + labelHeight / 2f + 4.5f * density,
            roiLabelPaint
        )
    }

    private fun drawDetection(
        canvas: Canvas,
        detection: Detection,
        scale: Float,
        dx: Float,
        dy: Float,
        trackId: Int?,
        isPredicted: Boolean
    ) {
        val left = detection.left * scale + dx
        val top = detection.top * scale + dy
        val right = detection.right * scale + dx
        val bottom = detection.bottom * scale + dy

        val currentPaint = if (isPredicted) {
            predictedBoxPaint
        } else {
            boxPaint
        }

        canvas.drawRect(
            left,
            top,
            right,
            bottom,
            currentPaint
        )

        val label = when {
            trackId != null && isPredicted ->
                "ID $trackId | پیش‌بینی"

            trackId != null ->
                "ID $trackId | ${(detection.confidence * 100).toInt()}%"

            else ->
                "${(detection.confidence * 100).toInt()}%"
        }

        val labelWidth =
            textPaint.measureText(label) + 16f * density

        val labelHeight = 28f * density

        val labelTop =
            (top - labelHeight - 4f * density).coerceAtLeast(0f)

        val safeLeft = left.coerceIn(
            0f,
            (width - labelWidth).coerceAtLeast(0f)
        )

        canvas.drawRoundRect(
            safeLeft,
            labelTop,
            safeLeft + labelWidth,
            labelTop + labelHeight,
            5f * density,
            5f * density,
            labelBackgroundPaint
        )

        canvas.drawText(
            label,
            safeLeft + 8f * density,
            labelTop + 19f * density,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSetup) return false

        val scale = imageScale()

        if (scale <= 0f) return false

        val dx = offsetX(scale)
        val dy = offsetY(scale)

        val imageX = (event.x - dx) / scale
        val imageY = (event.y - dy) / scale

        val topScreenY = lineTop * scale + dy
        val bottomScreenY = lineBottom * scale + dy

        val leftScreenX = roiLeft * scale + dx
        val rightScreenX = roiRight * scale + dx

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                val distances = listOf(
                    abs(event.y - topScreenY),
                    abs(event.y - bottomScreenY),
                    abs(event.x - leftScreenX),
                    abs(event.x - rightScreenX)
                )

                val closest = distances.indices.minByOrNull {
                    distances[it]
                } ?: return false

                if (distances[closest] > touchThreshold) {
                    dragging = 0
                    return false
                }

                dragging = closest + 1

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false

                when (dragging) {
                    1 -> {
                        lineTop = imageY.coerceIn(
                            0f,
                            imageH
                        )
                    }

                    2 -> {
                        lineBottom = imageY.coerceIn(
                            0f,
                            imageH
                        )
                    }

                    3 -> {
                        roiLeft = imageX.coerceIn(
                            0f,
                            (roiRight - minimumRoiWidth).coerceAtLeast(0f)
                        )
                    }

                    4 -> {
                        roiRight = imageX.coerceIn(
                            (roiLeft + minimumRoiWidth).coerceAtMost(imageW),
                            imageW
                        )
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging != 0
                dragging = 0

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return wasDragging
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging != 0
                dragging = 0

                parent?.requestDisallowInterceptTouchEvent(false)

                return wasDragging
            }
        }

        return dragging != 0
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
