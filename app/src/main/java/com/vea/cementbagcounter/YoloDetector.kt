package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float
) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
}

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputSize = 416

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val NMS_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 60
    }

    init {
        val model = context.assets.open("best.onnx").use {
            it.readBytes()
        }

        session = env.createSession(model)
        inputName = session.inputNames.first()
    }

    /**
     * اجرای تشخیص روی تصویر
     */
    fun detect(source: Bitmap): List<Detection> {

        if (source.isRecycled) {
            return emptyList()
        }

        val resized = Bitmap.createScaledBitmap(
            source,
            inputSize,
            inputSize,
            true
        )

        val pixels = IntArray(inputSize * inputSize)

        resized.getPixels(
            pixels,
            0,
            inputSize,
            0,
            0,
            inputSize,
            inputSize
        )

        val input = FloatArray(
            3 * inputSize * inputSize
        )

        val plane = inputSize * inputSize

        for (i in pixels.indices) {

            val p = pixels[i]

            input[i] =
                ((p shr 16) and 255) / 255f

            input[plane + i] =
                ((p shr 8) and 255) / 255f

            input[2 * plane + i] =
                (p and 255) / 255f
        }

        if (resized !== source && !resized.isRecycled) {
            resized.recycle()
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(
                1,
                3,
                inputSize.toLong(),
                inputSize.toLong()
            )
        )

        tensor.use {

            val result = session.run(
                mapOf(inputName to tensor)
            )

            result.use {

                val output = result[0].value

                val data = flatten(output)

                return decode(
                    data,
                    source.width.toFloat(),
                    source.height.toFloat()
                )
            }
        }
    }

    /**
     * تبدیل خروجی مدل به آرایه Float
     */
    private fun flatten(value: Any): FloatArray {

        fun walk(
            v: Any?,
            out: MutableList<Float>
        ) {

            when (v) {

                is FloatArray -> {
                    v.forEach(out::add)
                }

                is Array<*> -> {
                    v.forEach {
                        if (it != null) {
                            walk(it, out)
                        }
                    }
                }
            }
        }

        val out = mutableListOf<Float>()

        walk(value, out)

        return out.toFloatArray()
    }

    /**
     * Decode خروجی YOLO
     */
    private fun decode(
        a: FloatArray,
        srcW: Float,
        srcH: Float
    ): List<Detection> {

        if (a.size < 5) {
            return emptyList()
        }

        val n = a.size / 5

        val channelFirst = n > 100

        val out = mutableListOf<Detection>()

        for (i in 0 until n) {

            fun at(c: Int): Float {

                return if (channelFirst) {
                    a[c * n + i]
                } else {
                    a[i * 5 + c]
                }
            }

            val score = at(4)

            if (
                score < CONFIDENCE_THRESHOLD ||
                score > 1.01f
            ) {
                continue
            }

            val cx =
                at(0) * srcW / inputSize

            val cy =
                at(1) * srcH / inputSize

            val w =
                at(2) * srcW / inputSize

            val h =
                at(3) * srcH / inputSize

            val left =
                max(0f, cx - w / 2f)

            val top =
                max(0f, cy - h / 2f)

            val right =
                min(srcW, cx + w / 2f)

            val bottom =
                min(srcH, cy + h / 2f)

            if (right <= left || bottom <= top) {
                continue
            }

            out.add(
                Detection(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    confidence = score
                )
            )
        }

        return nms(
            out,
            NMS_THRESHOLD
        ).take(MAX_DETECTIONS)
    }

    /**
     * حذف تشخیص‌های تکراری
     */
    private fun nms(
        items: List<Detection>,
        threshold: Float
    ): List<Detection> {

        val sorted = items
            .sortedByDescending { it.confidence }
            .toMutableList()

        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {

            val best = sorted.removeAt(0)

            kept.add(best)

            val iterator = sorted.iterator()

            while (iterator.hasNext()) {

                val candidate = iterator.next()

                if (iou(best, candidate) > threshold) {
                    iterator.remove()
                }
            }
        }

        return kept
    }

    /**
     * محاسبه IoU
     */
    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)

        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val interWidth =
            max(0f, x2 - x1)

        val interHeight =
            max(0f, y2 - y1)

        val inter =
            interWidth * interHeight

        val areaA =
            max(0f, a.right - a.left) *
                    max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
                    max(0f, b.bottom - b.top)

        val union =
            areaA + areaB - inter

        if (union <= 0f) {
            return 0f
        }

        return inter / union
    }

    /**
     * آزادسازی منابع
     */
    fun close() {
        session.close()
    }
}
