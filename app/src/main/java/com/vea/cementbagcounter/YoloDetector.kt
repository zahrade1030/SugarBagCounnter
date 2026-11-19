package com.vea.cementbagcounter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
    val width get() = right - left
    val height get() = bottom - top
}

class YoloDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    private val inputSize = 416

    companion object {
        private const val TAG = "YoloDetector"
        private const val CONFIDENCE_THRESHOLD = 0.30f
        private const val NMS_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 60
        private const val EXPECTED_CLASSES = 1
    }

    init {
        val modelBytes = context.assets.open("best.onnx").use {
            it.readBytes()
        }

        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(
                OrtSession.SessionOptions.OptLevel.ALL_OPT
            )
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }

        session = try {
            env.createSession(modelBytes, options)
        } finally {
            options.close()
        }

        inputName = session.inputNames.firstOrNull()
            ?: throw IllegalStateException("مدل ورودی معتبر ندارد")

        Log.i(TAG, "Model loaded successfully")
        Log.i(TAG, "Input: $inputName")
        Log.i(TAG, "Outputs: ${session.outputNames}")
    }

    fun detect(source: Bitmap): List<Detection> {

        if (
            source.isRecycled ||
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

            val plane = inputSize * inputSize
            val input = FloatArray(3 * plane)

            for (i in pixels.indices) {
                val pixel = pixels[i]

                input[i] = ((pixel shr 16) and 255) / 255f
                input[plane + i] = ((pixel shr 8) and 255) / 255f
                input[2 * plane + i] = (pixel and 255) / 255f
            }

            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(
                    1,
                    3,
                    inputSize.toLong(),
                    inputSize.toLong()
                )
            ).use { tensor ->

                session.run(
                    mapOf(inputName to tensor)
                ).use { result ->

                    if (result.size() == 0) {
                        return emptyList()
                    }

                    val outputValue = result[0].value
                    val shape = result[0].info.shape

                    Log.d(TAG, "Output shape: ${shape.contentToString()}")

                    val output = flatten(outputValue)

                    return decode(
                        output,
                        shape,
                        source.width.toFloat(),
                        source.height.toFloat()
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
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
                    output.addAll(v.toList())
                }

                is DoubleArray -> {
                    output.addAll(v.map { it.toFloat() })
                }

                is Array<*> -> {
                    for (item in v) {
                        if (item != null) walk(item)
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
        shape: LongArray,
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

        if (shape.size < 2) {
            Log.w(TAG, "Unsupported output shape")
            return emptyList()
        }

        val dimensions = shape.map { it.toInt() }

        val dim1 = dimensions[dimensions.size - 2]
        val dim2 = dimensions[dimensions.size - 1]

        val channelFirst = dim1 <= 100 && dim2 > dim1

        val channels = if (channelFirst) dim1 else dim2
        val candidates = if (channelFirst) dim2 else dim1

        if (
            channels < 5 ||
            candidates <= 0 ||
            data.size < channels * candidates
        ) {
            Log.w(
                TAG,
                "Unsupported dimensions: ${shape.contentToString()}"
            )
            return emptyList()
        }

        val hasObjectness = channels >= 6 &&
            channels != 5 + EXPECTED_CLASSES &&
            channels != 4 + EXPECTED_CLASSES

        val classCount = when {
            channels == 5 -> 1
            channels == 4 + EXPECTED_CLASSES -> EXPECTED_CLASSES
            channels >= 5 + EXPECTED_CLASSES -> channels - 5
            else -> 1
        }

        val out = mutableListOf<Detection>()

        for (i in 0 until candidates) {

            fun at(c: Int): Float {
                return if (channelFirst) {
                    data[c * candidates + i]
                } else {
                    data[i * channels + c]
                }
            }

            val cx = at(0)
            val cy = at(1)
            val w = at(2)
            val h = at(3)

            if (
                !cx.isFinite() ||
                !cy.isFinite() ||
                !w.isFinite() ||
                !h.isFinite() ||
                w <= 0f ||
                h <= 0f
            ) {
                continue
            }

            var confidence = 0f

            if (channels == 5) {
                confidence = at(4)
            } else if (hasObjectness) {
                val objectness = at(4)

                var bestClass = 0f

                for (c in 0 until classCount) {
                    val score = at(5 + c)

                    if (score.isFinite() && score > bestClass) {
                        bestClass = score
                    }
                }

                confidence = objectness * bestClass
            } else {
                var bestClass = 0f

                for (c in 0 until classCount) {
                    val score = at(4 + c)

                    if (score.isFinite() && score > bestClass) {
                        bestClass = score
                    }
                }

                confidence = bestClass
            }

            if (
                !confidence.isFinite() ||
                confidence < CONFIDENCE_THRESHOLD ||
                confidence > 1.01f
            ) {
                continue
            }

            val scaledCx = cx * srcW / inputSize
            val scaledCy = cy * srcH / inputSize
            val scaledW = w * srcW / inputSize
            val scaledH = h * srcH / inputSize

            val left = max(0f, scaledCx - scaledW / 2f)
            val top = max(0f, scaledCy - scaledH / 2f)
            val right = min(srcW, scaledCx + scaledW / 2f)
            val bottom = min(srcH, scaledCy + scaledH / 2f)

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
                    confidence = confidence
                )
            )
        }

        return nms(out, NMS_THRESHOLD)
            .take(MAX_DETECTIONS)
    }

    private fun nms(
        items: List<Detection>,
        threshold: Float
    ): List<Detection> {

        if (items.isEmpty()) return emptyList()

        val sorted = items
            .sortedByDescending { it.confidence }
            .toMutableList()

        val kept = mutableListOf<Detection>()

        while (
            sorted.isNotEmpty() &&
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

        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        try {
            session.close()
            Log.i(TAG, "Session closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session", e)
        }
    }
}
