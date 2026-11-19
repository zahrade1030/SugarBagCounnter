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

        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(
                OrtSession.SessionOptions.OptLevel.ALL_OPT
            )
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }

        session = env.createSession(model, options)
        options.close()

        inputName = session.inputNames.first()
    }

    fun detect(source: Bitmap): List<Detection> {

        if (source.isRecycled ||
            source.width <= 0 ||
            source.height <= 0
        ) {
            return emptyList()
        }

        var resized: Bitmap? = null

        try {

            resized = Bitmap.createScaledBitmap(
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

                val pixel = pixels[i]

                input[i] =
                    ((pixel shr 16) and 255) / 255f

                input[plane + i] =
                    ((pixel shr 8) and 255) / 255f

                input[2 * plane + i] =
                    (pixel and 255) / 255f
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

                session.run(
                    mapOf(inputName to tensor)
                ).use { result ->

                    if (result.size() == 0) {
                        return emptyList()
                    }

                    val output = result[0].value

                    val data = flatten(output)

                    return decode(
                        data,
                        source.width.toFloat(),
                        source.height.toFloat()
                    )
                }
            }

        } catch (e: Exception) {

            android.util.Log.e(
                "YoloDetector",
                "Detection failed",
                e
            )

            return emptyList()

        } finally {

            if (
                resized != null &&
                resized !== source &&
                !resized.isRecycled
            ) {
                resized.recycle()
            }
        }
    }

    private fun flatten(value: Any): FloatArray {

        val output = ArrayList<Float>()

        fun walk(v: Any?) {

            when (v) {

                is FloatArray -> {
                    for (item in v) {
                        output.add(item)
                    }
                }

                is Array<*> -> {
                    for (item in v) {
                        if (item != null) {
                            walk(item)
                        }
                    }
                }

                is Number -> {
                    output.add(v.toFloat())
                }
            }
        }

        walk(value)

        return output.toFloatArray()
    }

    private fun decode(
        data: FloatArray,
        srcW: Float,
        srcH: Float
    ): List<Detection> {

        if (
            data.isEmpty() ||
            srcW <= 0f ||
            srcH <= 0f
        ) {
            return emptyList()
        }

        // پشتیبانی از خروجی‌های رایج YOLO
        val channels = 5

        if (data.size % channels != 0) {
            android.util.Log.w(
                "YoloDetector",
                "Unexpected output size: ${data.size}"
            )

            return emptyList()
        }

        val n = data.size / channels

        if (n <= 0) {
            return emptyList()
        }

        val channelFirst = n > 100

        val out = mutableListOf<Detection>()

        for (i in 0 until n) {

            fun at(c: Int): Float {
                return if (channelFirst) {
                    data[c * n + i]
                } else {
                    data[i * channels + c]
                }
            }

            val cx = at(0)
            val cy = at(1)
            val w = at(2)
            val h = at(3)
            val score = at(4)

            if (
                !cx.isFinite() ||
                !cy.isFinite() ||
                !w.isFinite() ||
                !h.isFinite() ||
                !score.isFinite()
            ) {
                continue
            }

            if (
                score < CONFIDENCE_THRESHOLD ||
                score > 1.01f ||
                w <= 0f ||
                h <= 0f
            ) {
                continue
            }

            val scaledCx = cx * srcW / inputSize
            val scaledCy = cy * srcH / inputSize
            val scaledW = w * srcW / inputSize
            val scaledH = h * srcH / inputSize

            val left = max(
                0f,
                scaledCx - scaledW / 2f
            )

            val top = max(
                0f,
                scaledCy - scaledH / 2f
            )

            val right = min(
                srcW,
                scaledCx + scaledW / 2f
            )

            val bottom = min(
                srcH,
                scaledCy + scaledH / 2f
            )

            if (
                right <= left ||
                bottom <= top
            ) {
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

    private fun nms(
        items: List<Detection>,
        threshold: Float
    ): List<Detection> {

        val sorted = items
            .sortedByDescending { it.confidence }
            .toMutableList()

        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty() &&
            kept.size < MAX_DETECTIONS
        ) {

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

    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)

        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val interWidth = max(0f, x2 - x1)
        val interHeight = max(0f, y2 - y1)

        val inter = interWidth * interHeight

        val areaA =
            max(0f, a.right - a.left) *
            max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
            max(0f, b.bottom - b.top)

        val union = areaA + areaB - inter

        if (union <= 0f) {
            return 0f
        }

        return inter / union
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            android.util.Log.e(
                "YoloDetector",
                "Error closing session",
                e
            )
        }
    }
}
