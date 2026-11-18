
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

    private val boxPaint = Paint(3).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val linePaint = Paint(3).apply {
        color = Color.YELLOW
        strokeWidth = 5f
    }

    private val textPaint = Paint(3).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val labelBackgroundPaint = Paint(3).apply {
        color = Color.rgb(20, 20, 20)
        style = Paint.Style.FILL
    }

    fun setDetections(list: List<Detection>, w: Int, h: Int) {
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
        return max(width / imageW, height / imageH)
    }

    private fun offsetX(scale: Float): Float {
        return (width - imageW * scale) / 2f
    }

    private fun offsetY(scale: Float): Float {
        return (height - imageH * scale) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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

        canvas.drawText(
            if (isSetup) "برای جابه‌جایی خطوط لمس کن"
            else "شمارش فعال است",
            18f,
            38f,
            textPaint
        )
    }

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

        val labelWidth = textPaint.measureText(label) + 16f
        val labelTop = (top - 42f).coerceAtLeast(0f)

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSetup) return false

        val scale = imageScale()
        val y = (event.y - offsetY(scale)) / scale

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (
                    abs(y - lineTop) < abs(y - lineBottom)
                ) 1 else 2

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val newY = y.coerceIn(20f, imageH - 20f)

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
