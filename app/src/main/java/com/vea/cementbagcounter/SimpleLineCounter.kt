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

    private data class Track(
        val id: Int,
        var detection: Detection,
        var cx: Float,
        var cy: Float,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var width: Float = 0f,
        var height: Float = 0f,
        var missed: Int = 0,
        var hits: Int = 1,
        var age: Int = 1,
        var armed: Boolean = false,
        var counted: Boolean = false
    )

    private val tracks = mutableMapOf<Int, Track>()

    private var nextId = 1

    // خط شمارش
    var topLine = 150f
        private set

    // خط محدوده
    var bottomLine = 411f
        private set

    companion object {

        private const val MAX_MISSED_FRAMES = 12

        private const val MAX_DISPLAY_MISSED_FRAMES = 8

        private const val MIN_GATE_DISTANCE = 100f

        private const val GATE_FACTOR = 1.8f

        private const val POSITION_SMOOTHING = 0.65f

        private const val VELOCITY_SMOOTHING = 0.55f

        private const val MAX_VELOCITY = 120f

        private const val MIN_TRACK_HITS = 2

        private const val MIN_CONFIDENCE = 0.20f

        private const val MIN_IOU_FOR_MATCH = 0.05f

        private const val COUNT_LINE_TOLERANCE = 1f
    }

    /**
     * تنظیم خطوط
     */
    fun setLines(top: Float, bottom: Float) {
        topLine = min(top, bottom)
        bottomLine = max(top, bottom)
    }

    /**
     * پاک کردن Tracking و شروع شمارش جدید
     */
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    /**
     * پردازش فریم جدید
     */
    fun updateWithTracks(
        detections: List<Detection>
    ): TrackingUpdate {

        var newCounts = 0

        // حذف تشخیص‌های ضعیف
        val validDetections = detections.filter {
            it.confidence >= MIN_CONFIDENCE &&
                    it.right > it.left &&
                    it.bottom > it.top
        }

        // به‌روزرسانی سن Trackها
        tracks.values.forEach {
            it.age++
            it.missed++
        }

        // پیش‌بینی موقعیت Trackها
        val predictions = tracks.values.map { track ->
            val predictedX = track.cx + track.vx
            val predictedY = track.cy + track.vy

            Pair(track, Pair(predictedX, predictedY))
        }

        // ایجاد کاندیدهای اتصال
        data class MatchCandidate(
            val trackId: Int,
            val detectionIndex: Int,
            val score: Float
        )

        val candidates = mutableListOf<MatchCandidate>()

        for ((track, prediction) in predictions) {

            val predictedX = prediction.first
            val predictedY = prediction.second

            for (index in validDetections.indices) {

                val detection = validDetections[index]

                val distance = distance(
                    predictedX,
                    predictedY,
                    detection.cx,
                    detection.cy
                )

                val predictedWidth = max(track.width, 1f)
                val predictedHeight = max(track.height, 1f)

                val detectionWidth =
                    max(detection.right - detection.left, 1f)

                val detectionHeight =
                    max(detection.bottom - detection.top, 1f)

                val averageSize =
                    (predictedWidth + predictedHeight +
                            detectionWidth + detectionHeight) / 4f

                val gateDistance = max(
                    MIN_GATE_DISTANCE,
                    averageSize * GATE_FACTOR
                )

                val iou = calculateIoU(
                    track.detection,
                    detection
                )

                // تشخیص‌های خیلی دور حذف می‌شوند
                if (distance > gateDistance && iou < MIN_IOU_FOR_MATCH) {
                    continue
                }

                // امتیاز کمتر یعنی تطبیق بهتر
                val normalizedDistance =
                    distance / gateDistance

                val score =
                    normalizedDistance - (iou * 0.5f)

                candidates.add(
                    MatchCandidate(
                        track.id,
                        index,
                        score
                    )
                )
            }
        }

        // بهترین تطبیق‌ها اول انتخاب می‌شوند
        candidates.sortBy { it.score }

        val matchedTrackIds = mutableSetOf<Int>()
        val matchedDetectionIndices = mutableSetOf<Int>()

        for (candidate in candidates) {

            if (candidate.trackId in matchedTrackIds) continue

            if (candidate.detectionIndex in matchedDetectionIndices) continue

            val track = tracks[candidate.trackId] ?: continue

            val detection =
                validDetections[candidate.detectionIndex]

            matchedTrackIds.add(candidate.trackId)

            matchedDetectionIndices.add(candidate.detectionIndex)

            updateTrack(track, detection)

            // آماده شدن Track برای شمارش
            if (isInsideCountingZone(track.detection)) {
                track.armed = true
            }

            // شمارش لحظه برخورد با خط
            if (
                track.armed &&
                !track.counted &&
                track.hits >= MIN_TRACK_HITS &&
                touchesCountingLine(track.detection)
            ) {
                track.counted = true
                newCounts++
            }
        }

        // ایجاد Track جدید برای تشخیص‌های بدون تطبیق
        for (index in validDetections.indices) {

            if (index in matchedDetectionIndices) continue

            val detection = validDetections[index]

            val track = createTrack(detection)

            tracks[track.id] = track

            // اگر Track جدید معتبر باشد و با خط تماس داشته باشد
            if (
                track.armed &&
                track.hits >= MIN_TRACK_HITS &&
                touchesCountingLine(track.detection)
            ) {
                track.counted = true
                newCounts++
            }
        }

        // حذف Trackهای قدیمی
        val expiredIds = tracks.values
            .filter { it.missed > MAX_MISSED_FRAMES }
            .map { it.id }

        expiredIds.forEach {
            tracks.remove(it)
        }

        // آماده‌سازی خروجی برای نمایش
        val visibleTracks = tracks.values
            .filter {
                it.missed <= MAX_DISPLAY_MISSED_FRAMES
            }
            .map { track ->

                val displayDetection = if (track.missed > 0) {
                    predictedDetection(track)
                } else {
                    track.detection
                }

                TrackedDetection(
                    id = track.id,
                    detection = displayDetection,
                    isPredicted = track.missed > 0
                )
            }

        return TrackingUpdate(
            newCounts = newCounts,
            trackedDetections = visibleTracks
        )
    }

    /**
     * ایجاد Track جدید
     */
    private fun createTrack(
        detection: Detection
    ): Track {

        val width = detection.right - detection.left
        val height = detection.bottom - detection.top

        val track = Track(
            id = nextId++,
            detection = detection,
            cx = detection.cx,
            cy = detection.cy,
            width = width,
            height = height
        )

        track.armed = isInsideCountingZone(detection)

        return track
    }

    /**
     * به‌روزرسانی Track
     */
    private fun updateTrack(
        track: Track,
        detection: Detection
    ) {

        val oldCx = track.cx
        val oldCy = track.cy

        val newCx = detection.cx
        val newCy = detection.cy

        val measuredVx = newCx - oldCx
        val measuredVy = newCy - oldCy

        track.vx = (
                track.vx * VELOCITY_SMOOTHING +
                        measuredVx * (1f - VELOCITY_SMOOTHING)
                ).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        track.vy = (
                track.vy * VELOCITY_SMOOTHING +
                        measuredVy * (1f - VELOCITY_SMOOTHING)
                ).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

        track.cx =
            oldCx * POSITION_SMOOTHING +
                    newCx * (1f - POSITION_SMOOTHING)

        track.cy =
            oldCy * POSITION_SMOOTHING +
                    newCy * (1f - POSITION_SMOOTHING)

        val newWidth = detection.right - detection.left
        val newHeight = detection.bottom - detection.top

        track.width =
            track.width * POSITION_SMOOTHING +
                    newWidth * (1f - POSITION_SMOOTHING)

        track.height =
            track.height * POSITION_SMOOTHING +
                    newHeight * (1f - POSITION_SMOOTHING)

        track.detection = detection

        track.missed = 0
        track.hits++

        if (isInsideCountingZone(detection)) {
            track.armed = true
        }
    }

    /**
     * آیا باکس در محدوده بین خطوط قرار دارد؟
     */
    private fun isInsideCountingZone(
        detection: Detection
    ): Boolean {

        return detection.bottom >= topLine &&
                detection.top <= bottomLine
    }

    /**
     * بررسی تماس با خط شمارش
     *
     * شمارش با برخورد باکس با خط انجام می‌شود،
     * نه بعد از عبور کامل آن.
     */
    private fun touchesCountingLine(
        detection: Detection
    ): Boolean {

        return detection.top <= topLine + COUNT_LINE_TOLERANCE &&
                detection.bottom >= topLine - COUNT_LINE_TOLERANCE
    }

    /**
     * پیش‌بینی موقعیت Track گم‌شده
     */
    private fun predictedDetection(
        track: Track
    ): Detection {

        val predictedCx = track.cx + track.vx * track.missed
        val predictedCy = track.cy + track.vy * track.missed

        val halfWidth = track.width / 2f
        val halfHeight = track.height / 2f

        return Detection(
            left = predictedCx - halfWidth,
            top = predictedCy - halfHeight,
            right = predictedCx + halfWidth,
            bottom = predictedCy + halfHeight,
            confidence = track.detection.confidence
        )
    }

    /**
     * فاصله دو نقطه
     */
    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {

        val dx = x2 - x1
        val dy = y2 - y1

        return sqrt(dx * dx + dy * dy)
    }

    /**
     * محاسبه IoU بین دو باکس
     */
    private fun calculateIoU(
        a: Detection,
        b: Detection
    ): Float {

        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)

        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

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

        val unionArea = areaA + areaB - intersectionArea

        if (unionArea <= 0f) return 0f

        return intersectionArea / unionArea
    }
}
