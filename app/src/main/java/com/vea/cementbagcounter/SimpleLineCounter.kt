package com.vea.cementbagcounter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TrackedDetection(
    val id: Int,
    val detection: Detection
)

data class TrackingUpdate(
    val newCounts: Int,
    val trackedDetections: List<TrackedDetection>
)

class SimpleLineCounter {

    // =========================================================
    // تنظیمات ردیابی
    // =========================================================

    private companion object {

        // چند فریم غیبت را تحمل کنیم
        const val MAX_MISSED_FRAMES = 12

        // تا چند فریم کادر پیش‌بینی‌شده نمایش داده شود
        const val MAX_DISPLAY_MISSED = 8

        // میزان نرم‌سازی حرکت
        const val POSITION_SMOOTHING = 0.65f

        // میزان تأثیر سرعت جدید
        const val VELOCITY_SMOOTHING = 0.55f

        // حداقل اطمینان برای ایجاد Track جدید
        const val MIN_TRACK_CONFIDENCE = 0.35f

        // ضریب گیت تطبیق
        const val GATE_FACTOR = 1.8f

        // حداقل فاصله مجاز
        const val MIN_GATE_DISTANCE = 100f

        // فاصله مجاز در محور عمودی
        const val VERTICAL_GATE_FACTOR = 1.5f

        // حداکثر سرعت تخمینی مجاز در هر فریم
        const val MAX_VELOCITY = 120f

        // فاصله امن اطراف خط شمارش
        const val CROSSING_MARGIN = 5f

        // حداقل تعداد فریم حضور برای معتبرشدن Track
        const val MIN_TRACK_HITS = 2

        // حداکثر فاصله زمانی بین دو تشخیص
        const val MAX_MATCH_MISSED = 12
    }

    // =========================================================
    // ساختار Track
    // =========================================================

    private data class Track(
        val id: Int,

        var detection: Detection,

        // موقعیت نرم‌شده
        var smoothCx: Float,
        var smoothCy: Float,

        // اندازه نرم‌شده کادر
        var smoothWidth: Float,
        var smoothHeight: Float,

        // سرعت
        var vx: Float = 0f,
        var vy: Float = 0f,

        // تعداد فریم‌های ازدست‌رفته
        var missed: Int = 0,

        // تعداد تشخیص‌های موفق
        var hits: Int = 1,

        // تعداد فریم‌های عمر Track
        var age: Int = 1,

        // وضعیت شمارش
        var armed: Boolean = false,
        var counted: Boolean = false,

        // آخرین مرکز واقعی
        var lastObservedCx: Float = detection.cx,
        var lastObservedCy: Float = detection.cy,

        // آخرین مرکز ثبت‌شده برای بررسی عبور
        var previousCrossingY: Float = detection.cy,

        // اعتماد Track
        var confidence: Float = detection.confidence
    )

    private data class Match(
        val trackId: Int,
        val detectionIndex: Int,
        val cost: Float
    )

    // =========================================================
    // متغیرهای سیستم
    // =========================================================

    private val tracks = mutableMapOf<Int, Track>()

    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    // =========================================================
    // تنظیم خطوط شمارش
    // =========================================================

    fun setLines(top: Float, bottom: Float) {

        topLine = min(top, bottom)
        bottomLine = max(top, bottom)

    }

    // =========================================================
    // ریست
    // =========================================================

    fun reset() {

        tracks.clear()
        nextId = 1

    }

    // =========================================================
    // آپدیت ساده
    // =========================================================

    fun update(detections: List<Detection>): Int {

        return updateWithTracks(detections).newCounts

    }

    // =========================================================
    // آپدیت اصلی Tracker
    // =========================================================

    fun updateWithTracks(
        detections: List<Detection>
    ): TrackingUpdate {

        var newCounts = 0

        // افزایش سن Trackها
        tracks.values.forEach {

            it.age++
            it.missed++

        }

        // =====================================================
        // مرحله 1: پیش‌بینی موقعیت
        // =====================================================

        val predictions = mutableMapOf<Int, Detection>()

        for (track in tracks.values) {

            predictions[track.id] = predict(track)

        }

        // =====================================================
        // مرحله 2: ساخت ماتریس تطبیق
        // =====================================================

        val candidates = mutableListOf<Match>()

        for (track in tracks.values) {

            if (track.missed > MAX_MATCH_MISSED) {
                continue
            }

            val predicted = predictions[track.id] ?: continue

            for (index in detections.indices) {

                val detection = detections[index]

                if (
                    detection.confidence <
                    MIN_TRACK_CONFIDENCE
                ) {
                    continue
                }

                val cost = calculateCost(
                    predicted,
                    detection,
                    track
                )

                if (cost >= 0f) {

                    candidates.add(
                        Match(
                            track.id,
                            index,
                            cost
                        )
                    )

                }

            }

        }

        // =====================================================
        // مرحله 3: تطبیق به ترتیب کمترین هزینه
        // =====================================================

        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()

        val visibleTracks = mutableListOf<TrackedDetection>()

        val sortedMatches =
            candidates.sortedBy { it.cost }

        for (match in sortedMatches) {

            if (match.trackId in usedTracks) {
                continue
            }

            if (match.detectionIndex in usedDetections) {
                continue
            }

            val track = tracks[match.trackId]
                ?: continue

            val detection =
                detections[match.detectionIndex]

            val predicted =
                predictions[track.id]
                    ?: continue

            usedTracks.add(track.id)
            usedDetections.add(match.detectionIndex)

            // =================================================
            // سرعت بر اساس پیش‌بینی و مشاهده واقعی
            // =================================================

            val measuredVx =
                detection.cx - track.lastObservedCx

            val measuredVy =
                detection.cy - track.lastObservedCy

            val frameGap =
                (track.missed + 1).coerceAtLeast(1)

            val correctedVx =
                measuredVx / frameGap.toFloat()

            val correctedVy =
                measuredVy / frameGap.toFloat()

            track.vx = smoothVelocity(
                track.vx,
                correctedVx
            )

            track.vy = smoothVelocity(
                track.vy,
                correctedVy
            )

            // =================================================
            // نرم‌سازی موقعیت
            // =================================================

            track.smoothCx = smoothPosition(
                predicted.cx,
                detection.cx
            )

            track.smoothCy = smoothPosition(
                predicted.cy,
                detection.cy
            )

            // =================================================
            // نرم‌سازی اندازه کادر
            // =================================================

            val newWidth =
                detection.right - detection.left

            val newHeight =
                detection.bottom - detection.top

            track.smoothWidth =
                track.smoothWidth * 0.35f +
                newWidth * 0.65f

            track.smoothHeight =
                track.smoothHeight * 0.35f +
                newHeight * 0.65f

            // =================================================
            // ثبت وضعیت Track
            // =================================================

            track.detection = detection

            track.lastObservedCx = detection.cx
            track.lastObservedCy = detection.cy

            track.missed = 0
            track.hits++

            track.confidence = detection.confidence

            // =================================================
            // آماده‌سازی برای شمارش
            // =================================================

            if (
                track.smoothCy > topLine &&
                track.smoothCy < bottomLine
            ) {

                track.armed = true

            }

            // =================================================
            // تشخیص عبور از خط شمارش
            // =================================================

            if (
                track.armed &&
                !track.counted &&
                crossedTopLine(
                    track.previousCrossingY,
                    track.smoothCy
                )
            ) {

                track.counted = true
                newCounts++

            }

            track.previousCrossingY =
                track.smoothCy

            // =================================================
            // خروجی کادر نرم‌شده
            // =================================================

            visibleTracks.add(
                TrackedDetection(
                    track.id,
                    createSmoothedDetection(track)
                )
            )

        }

        // =====================================================
        // مرحله 4: Trackهایی که تشخیص داده نشدند
        // =====================================================

        for (track in tracks.values) {

            if (track.id in usedTracks) {
                continue
            }

            if (track.missed <= MAX_DISPLAY_MISSED) {

                val predicted =
                    predictions[track.id]

                if (predicted != null) {

                    // ادامه ردیابی با پیش‌بینی
                    track.smoothCx = predicted.cx
                    track.smoothCy = predicted.cy

                    // کاهش تدریجی اعتماد
                    track.confidence *= 0.88f

                    // =================================================
                    // بررسی عبور احتمالی در فریم ازدست‌رفته
                    // =================================================

                    if (
                        track.armed &&
                        !track.counted &&
                        crossedTopLine(
                            track.previousCrossingY,
                            track.smoothCy
                        )
                    ) {

                        track.counted = true
                        newCounts++

                    }

                    track.previousCrossingY =
                        track.smoothCy

                    // =================================================
                    // نمایش کادر پیش‌بینی‌شده
                    // =================================================

                    visibleTracks.add(
                        TrackedDetection(
                            track.id,
                            createPredictedDetection(track)
                        )
                    )

                }

            }

        }

        // =====================================================
        // مرحله 5: ایجاد Track جدید
        // =====================================================

        for (index in detections.indices) {

            if (index in usedDetections) {
                continue
            }

            val detection = detections[index]

            if (
                detection.confidence <
                MIN_TRACK_CONFIDENCE
            ) {
                continue
            }

            val id = nextId++

            val width =
                detection.right - detection.left

            val height =
                detection.bottom - detection.top

            val track = Track(
                id = id,
                detection = detection,

                smoothCx = detection.cx,
                smoothCy = detection.cy,

                smoothWidth = width,
                smoothHeight = height,

                confidence = detection.confidence,

                armed =
                    detection.cy > topLine &&
                    detection.cy < bottomLine,

                previousCrossingY = detection.cy
            )

            tracks[id] = track

            visibleTracks.add(
                TrackedDetection(
                    id,
                    detection
                )
            )

        }

        // =====================================================
        // مرحله 6: حذف Trackهای قدیمی
        // =====================================================

        val expiredIds = tracks
            .filter {
                it.value.missed >
                MAX_MISSED_FRAMES
            }
            .keys

        for (id in expiredIds) {

            tracks.remove(id)

        }

        // =====================================================
        // خروجی
        // =====================================================

        return TrackingUpdate(
            newCounts,
            visibleTracks
        )

    }

    // =========================================================
    // پیش‌بینی موقعیت
    // =========================================================

    private fun predict(
        track: Track
    ): Detection {

        val predictedCx =
            track.smoothCx + track.vx

        val predictedCy =
            track.smoothCy + track.vy

        val halfWidth =
            track.smoothWidth / 2f

        val halfHeight =
            track.smoothHeight / 2f

        return Detection(
            predictedCx - halfWidth,
            predictedCy - halfHeight,
            predictedCx + halfWidth,
            predictedCy + halfHeight,
            track.confidence
        )

    }

    // =========================================================
    // محاسبه هزینه تطبیق
    // =========================================================

    private fun calculateCost(
        predicted: Detection,
        detection: Detection,
        track: Track
    ): Float {

        val dx =
            abs(predicted.cx - detection.cx)

        val dy =
            abs(predicted.cy - detection.cy)

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
            max(
                predictedSize,
                detectionSize
            ) * GATE_FACTOR
        )

        // =====================================================
        // محدودیت فاصله
        // =====================================================

        if (dx > gate) {
            return -1f
        }

        if (
            dy >
            gate * VERTICAL_GATE_FACTOR
        ) {
            return -1f
        }

        val distance = sqrt(
            dx * dx + dy * dy
        )

        // =====================================================
        // هم‌پوشانی کادرها
        // =====================================================

        val overlap = iou(
            predicted,
            detection
        )

        // اگر فاصله زیاد و هم‌پوشانی کم باشد
        // تطبیق رد می‌شود.

        if (
            overlap < 0.01f &&
            distance > gate * 0.70f
        ) {

            return -1f

        }

        // =====================================================
        // اختلاف اندازه
        // =====================================================

        val sizeDifference =
            abs(predictedSize - detectionSize) /
            max(predictedSize, detectionSize)

        // =====================================================
        // هزینه نهایی
        // =====================================================

        val distanceCost =
            distance / gate

        val overlapCost =
            1f - overlap

        val sizeCost =
            sizeDifference.coerceIn(0f, 1f)

        return
            distanceCost * 0.50f +
            overlapCost * 0.35f +
            sizeCost * 0.15f

    }

    // =========================================================
    // نرم‌سازی موقعیت
    // =========================================================

    private fun smoothPosition(
        predicted: Float,
        observed: Float
    ): Float {

        return predicted *
            (1f - POSITION_SMOOTHING) +
            observed *
            POSITION_SMOOTHING

    }

    // =========================================================
    // نرم‌سازی سرعت
    // =========================================================

    private fun smoothVelocity(
        oldVelocity: Float,
        newVelocity: Float
    ): Float {

        val clampedVelocity =
            newVelocity.coerceIn(
                -MAX_VELOCITY,
                MAX_VELOCITY
            )

        return oldVelocity *
            (1f - VELOCITY_SMOOTHING) +
            clampedVelocity *
            VELOCITY_SMOOTHING

    }

    // =========================================================
    // بررسی عبور از خط
    // =========================================================

    private fun crossedTopLine(
        previousY: Float,
        currentY: Float
    ): Boolean {

        val upperBoundary =
            topLine - CROSSING_MARGIN

        val lowerBoundary =
            topLine + CROSSING_MARGIN

        return
            previousY > lowerBoundary &&
            currentY < upperBoundary

    }

    // =========================================================
    // ساخت Detection نرم‌شده
    // =========================================================

    private fun createSmoothedDetection(
        track: Track
    ): Detection {

        val halfWidth =
            track.smoothWidth / 2f

        val halfHeight =
            track.smoothHeight / 2f

        return Detection(
            track.smoothCx - halfWidth,
            track.smoothCy - halfHeight,
            track.smoothCx + halfWidth,
            track.smoothCy + halfHeight,
            track.confidence
        )

    }

    // =========================================================
    // ساخت Detection پیش‌بینی‌شده
    // =========================================================

    private fun createPredictedDetection(
        track: Track
    ): Detection {

        val halfWidth =
            track.smoothWidth / 2f

        val halfHeight =
            track.smoothHeight / 2f

        return Detection(
            track.smoothCx - halfWidth,
            track.smoothCy - halfHeight,
            track.smoothCx + halfWidth,
            track.smoothCy + halfHeight,
            track.confidence
        )

    }

    // =========================================================
    // محاسبه IoU
    // =========================================================

    private fun iou(
        a: Detection,
        b: Detection
    ): Float {

        val x1 =
            max(a.left, b.left)

        val y1 =
            max(a.top, b.top)

        val x2 =
            min(a.right, b.right)

        val y2 =
            min(a.bottom, b.bottom)

        val interWidth =
            max(0f, x2 - x1)

        val interHeight =
            max(0f, y2 - y1)

        val intersection =
            interWidth * interHeight

        val areaA =
            max(
                0f,
                a.right - a.left
            ) *
            max(
                0f,
                a.bottom - a.top
            )

        val areaB =
            max(
                0f,
                b.right - b.left
            ) *
            max(
                0f,
                b.bottom - b.top
            )

        val union =
            areaA + areaB - intersection

        if (union <= 0f) {
            return 0f
        }

        return intersection / union

    }

}
