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
    }

    fun setDetections(list: List<Detection>, w: Int, h: Int) {
        detections = list
        imageW = w.toFloat()
        imageH = h.toFloat()
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

        detections.forEach {
            canvas.drawRect(
                it.left * scale + dx,
                it.top * scale + dy,
                it.right * scale + dx,
                it.bottom * scale + dy,
                boxPaint
            )

            canvas.drawText(
                "${(it.confidence * 100).toInt()}%",
                it.left * scale + dx,
                (it.top * scale + dy - 8).coerceAtLeast(28f),
                textPaint
            )
        }

        val topY = lineTop * scale + dy
        val bottomY = lineBottom * scale + dy

        canvas.drawLine(
            0f, topY, width.toFloat(), topY, linePaint
        )

        canvas.drawLine(
            0f, bottomY, width.toFloat(), bottomY, linePaint
        )

        canvas.drawText(
            if (isSetup) "برای جابه‌جایی خطوط لمس کن"
            else "شمارش فعال است",
            18f,
            38f,
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