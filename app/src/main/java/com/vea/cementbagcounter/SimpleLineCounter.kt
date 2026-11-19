package com.vea.cementbagcounter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TrackedDetection(
    val id: Int,
    val detection: Detection,
    val isPredicted: Boolean = false
)

data class TrackingUpdate(
    val newCounts: Int,
    val trackedDetections: List<TrackedDetection>
)

class SimpleLineCounter {

    companion object {
        private const val MAX_MISSED_FRAMES = 12
        private const val MAX_DISPLAY_MISSED_FRAMES = 8

        private const val MIN_GATE_DISTANCE = 100f
        private const val GATE_FACTOR = 1.8f
        private const val VERTICAL_GATE_FACTOR = 1.5f

        private const val POSITION_SMOOTHING = 0.65f
        private const val VELOCITY_SMOOTHING = 0.55f
        private const val MAX_VELOCITY = 120f

        private const val CROSSING_MARGIN = 3f
        private const val MIN_TRACK_HITS = 2
        private const val MIN_CONFIDENCE = 0.20f
    }

    private data class Track(
        val id: Int,

        var detection: Detection,

        var smoothCx: Float,
        var smoothCy: Float,

        var smoothWidth: Float,
        var smoothHeight: Float,

        var vx: Float = 0f,
        var vy: Float = 0f,

        var missed: Int = 0,
        var hits: Int = 1,
        var age: Int = 1,

        var armed: Boolean = false,
        var counted: Boolean = false,

        var previousTop: Float = 0f,
        var previousBottom: Float = 0f,

        var confidence: Float = 0f
    )

    private data class Match(
        val trackId: Int,
        val detectionIndex: Int,
        val cost: Float
    )

    private val tracks = mutableMapOf<Int, Track>()

    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    fun setLines(top: Float, bottom: Float) {
        topLine = min(top, bottom)
        bottomLine = max(top, bottom)
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<Detection>): Int {
        return updateWithTracks(detections).newCounts
    }

    fun updateWithTracks(
        detections: List<Detection>
    ): TrackingUpdate {

        var newCounts = 0

        val visibleTracks = mutableListOf<TrackedDetection>()

        // افزایش تعداد فریم‌های ازدست‌رفته
        for (track in tracks.values) {
            track.missed++
            track.age++
        }

        // پیش‌بینی موقعیت Trackها
        val predictions = mutableMapOf<Int, Detection>()

        for (track in tracks.values) {
            predictions[track.id] = predictDetection(track)
        }

        // ایجاد کاندیدهای تطبیق
        val candidates = mutableListOf<Match>()

        for (track in tracks.values) {

            if (track.missed > MAX_MISSED_FRAMES) {
                continue
            }

            val predicted = predictions[track.id] ?: continue

            for (index in detections.indices) {

                val detection = detections[index]

                if (detection.confidence < MIN_CONFIDENCE) {
                    continue
                }

                val cost = calculateCost(
                    predicted,
                    detection
                )

                if (cost >= 0f) {
                    candidates.add(
                        Match(
                            trackId = track.id,
                            detectionIndex = index,
                            cost = cost
                        )
                    )
                }
            }
        }

        // تطبیق بر اساس کمترین هزینه
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()

        for (match in candidates.sortedBy { it.cost }) {

            if (match.trackId in usedTracks) {
                continue
            }

            if (match.detectionIndex in usedDetections) {
                continue
            }

            val track = tracks[match.trackId] ?: continue

            val detection = detections[match.detectionIndex]

            usedTracks.add(match.trackId)
            usedDetections.add(match.detectionIndex)

            // محاسبه سرعت
            val measuredVx = detection.cx - track.smoothCx
            val measuredVy = detection.cy - track.smoothCy

            val limitedVx = measuredVx.coerceIn(
                -MAX_VELOCITY,
                MAX_VELOCITY
            )

            val limitedVy = measuredVy.coerceIn(
                -MAX_VELOCITY,
                MAX_VELOCITY
            )

            track.vx =
                track.vx * (1f - VELOCITY_SMOOTHING) +
                        limitedVx * VELOCITY_SMOOTHING

            track.vy =
                track.vy * (1f - VELOCITY_SMOOTHING) +
                        limitedVy * VELOCITY_SMOOTHING

            // موقعیت قبلی کادر واقعی
            val oldTop = track.previousTop
            val oldBottom = track.previousBottom

            // هموارسازی مرکز
            track.smoothCx =
                track.smoothCx * (1f - POSITION_SMOOTHING) +
                        detection.cx * POSITION_SMOOTHING

            track.smoothCy =
                track.smoothCy * (1f - POSITION_SMOOTHING) +
                        detection.cy * POSITION_SMOOTHING

            // هموارسازی ابعاد
            val detectedWidth =
                detection.right - detection.left

            val detectedHeight =
                detection.bottom - detection.top

            track.smoothWidth =
                track.smoothWidth * (1f - POSITION_SMOOTHING) +
                        detectedWidth * POSITION_SMOOTHING

            track.smoothHeight =
                track.smoothHeight * (1f - POSITION_SMOOTHING) +
                        detectedHeight * POSITION_SMOOTHING

            track.detection = detection

            track.missed = 0
            track.hits++

            track.confidence = detection.confidence

            // بررسی ورود کادر به منطقه بین خطوط
            if (boxIntersectsCountingZone(detection)) {
                track.armed = true
            }

            // شمارش هنگام عبور لبه بالایی کادر از خط بالایی
            if (
                track.armed &&
                !track.counted &&
                track.hits >= MIN_TRACK_HITS &&
                crossedTopLine(
                    previousTop = oldTop,
                    currentTop = detection.top
                )
            ) {
                track.counted = true
                newCounts++
            }

            // ذخیره موقعیت واقعی برای فریم بعد
            track.previousTop = detection.top
            track.previousBottom = detection.bottom

            visibleTracks.add(
                TrackedDetection(
                    id = track.id,
                    detection = createSmoothedDetection(track),
                    isPredicted = false
                )
            )
        }

        // ایجاد Track جدید برای تشخیص‌های تطبیق‌نیافته
        for (index in detections.indices) {

            if (index in usedDetections) {
                continue
            }

            val detection = detections[index]

            if (detection.confidence < MIN_CONFIDENCE) {
                continue
            }

            val id = nextId++

            val width = detection.right - detection.left
            val height = detection.bottom - detection.top

            val track = Track(
                id = id,
                detection = detection,

                smoothCx = detection.cx,
                smoothCy = detection.cy,

                smoothWidth = width,
                smoothHeight = height,

                vx = 0f,
                vy = 0f,

                missed = 0,
                hits = 1,
                age = 1,

                armed = boxIntersectsCountingZone(detection),
                counted = false,

                previousTop = detection.top,
                previousBottom = detection.bottom,

                confidence = detection.confidence
            )

            tracks[id] = track

            visibleTracks.add(
                TrackedDetection(
                    id = id,
                    detection = createSmoothedDetection(track),
                    isPredicted = false
                )
            )
        }

        // نمایش موقت Trackهای گم‌شده
        for (track in tracks.values) {

            if (track.id in usedTracks) {
                continue
            }

            if (track.missed == 0) {
                continue
            }

            if (track.missed > MAX_DISPLAY_MISSED_FRAMES) {
                continue
            }

            visibleTracks.add(
                TrackedDetection(
                    id = track.id,
                    detection = predictDetection(track),
                    isPredicted = true
                )
            )
        }

        // حذف Trackهای منقضی‌شده
        val expiredIds = tracks.values
            .filter {
                it.missed > MAX_MISSED_FRAMES
            }
            .map {
                it.id
            }

        for (id in expiredIds) {
            tracks.remove(id)
        }

        return TrackingUpdate(
            newCounts = newCounts,
            trackedDetections = visibleTracks
        )
    }

    /**
     * بررسی برخورد کادر کیسه با محدوده شمارش
     */
    private fun boxIntersectsCountingZone(
        detection: Detection
    ): Boolean {

        return detection.bottom > topLine &&
                detection.top < bottomLine
    }

    /**
     * پیش‌بینی موقعیت Track در فریم ازدست‌رفته
     */
    private fun predictDetection(
        track: Track
    ): Detection {

        val predictedCx =
            track.smoothCx + track.vx * track.missed

        val predictedCy =
            track.smoothCy + track.vy * track.missed

        val width = track.smoothWidth
        val height = track.smoothHeight

        val confidence =
            (track.confidence *
                    Math.pow(
                        0.88,
                        track.missed.toDouble()
                    ).toFloat())
                .coerceAtLeast(0.05f)

        return Detection(
            left = predictedCx - width / 2f,
            top = predictedCy - height / 2f,
            right = predictedCx + width / 2f,
            bottom = predictedCy + height / 2f,
            confidence = confidence
        )
    }

    /**
     * ساخت کادر هموارشده برای نمایش
     */
    private fun createSmoothedDetection(
        track: Track
    ): Detection {

        val halfWidth = track.smoothWidth / 2f
        val halfHeight = track.smoothHeight / 2f

        return Detection(
            left = track.smoothCx - halfWidth,
            top = track.smoothCy - halfHeight,
            right = track.smoothCx + halfWidth,
            bottom = track.smoothCy + halfHeight,
            confidence = track.confidence
        )
    }

    /**
     * محاسبه هزینه تطبیق
     */
    private fun calculateCost(
        predicted: Detection,
        detection: Detection
    ): Float {

        val dx = abs(predicted.cx - detection.cx)
        val dy = abs(predicted.cy - detection.cy)

        val predictedSize = max(
            predicted.right - predicted.left,
            predicted.bottom - predicted.top
        )

        val detectionSize = max(
            detection.right - detection.left,
            detection.bottom - detection.top
        )

        val gate = max(
            MIN_GATE_DISTANCE,
            max(predictedSize, detectionSize) * GATE_FACTOR
        )

        if (dx > gate) {
            return -1f
        }

        if (dy > gate * VERTICAL_GATE_FACTOR) {
            return -1f
        }

        val distance = sqrt(dx * dx + dy * dy)

        val overlap = iou(predicted, detection)

        if (
            overlap < 0.01f &&
            distance > gate * 0.70f
        ) {
            return -1f
        }

        val sizeDifference =
            abs(predictedSize - detectionSize) /
                    max(predictedSize, detectionSize)
                        .coerceAtLeast(1f)

        val distanceCost = distance / gate

        val overlapCost = 1f - overlap

        val sizeCost = sizeDifference.coerceIn(
            0f,
            1f
        )

        return (
                distanceCost * 0.50f +
                        overlapCost * 0.35f +
                        sizeCost * 0.15f
                )
    }

    /**
     * تشخیص عبور لبه بالایی کادر از خط بالایی
     *
     * جهت شمارش:
     * از پایین تصویر به بالای تصویر
     */
    private fun crossedTopLine(
        previousTop: Float,
        currentTop: Float
    ): Boolean {

        val upperBoundary =
            topLine - CROSSING_MARGIN

        val lowerBoundary =
            topLine + CROSSING_MARGIN

        return previousTop > lowerBoundary &&
                currentTop <= upperBoundary
    }

    /**
     * محاسبه IoU
     */
    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val intersectionLeft =
            max(a.left, b.left)

        val intersectionTop =
            max(a.top, b.top)

        val intersectionRight =
            min(a.right, b.right)

        val intersectionBottom =
            min(a.bottom, b.bottom)

        val intersectionWidth =
            max(0f, intersectionRight - intersectionLeft)

        val intersectionHeight =
            max(0f, intersectionBottom - intersectionTop)

        val intersectionArea =
            intersectionWidth * intersectionHeight

        val areaA =
            max(0f, a.right - a.left) *
                    max(0f, a.bottom - a.top)

        val areaB =
            max(0f, b.right - b.left) *
                    max(0f, b.bottom - b.top)

        val unionArea =
            areaA + areaB - intersectionArea

        if (unionArea <= 0f) {
            return 0f
        }

        return (
                intersectionArea / unionArea
                ).coerceIn(0f, 1f)
    }
}
