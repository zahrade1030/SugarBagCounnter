package com.vea.cementbagcounter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        var x: Float,
        var y: Float,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var missed: Int = 0,
        var hits: Int = 1,
        var age: Int = 1,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var lastDetection: Detection? = null
    )

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    private var roiLeft = 0f
    private var roiRight = Float.MAX_VALUE

    private val maxMissedFrames = 12
    private val maxAssociationDistance = 140f

    // جلوگیری از پرش ناگهانی Track بین دو کیسه
    private val maxVelocity = 100f

    fun setLines(top: Float, bottom: Float) {
        topLine = min(top, bottom)
        bottomLine = max(top, bottom)
    }

    fun setRoi(left: Float, right: Float) {
        roiLeft = min(left, right)
        roiRight = max(left, right)
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<Detection>): Int {
        return updateWithTracks(detections).newCounts
    }

    fun updateWithTracks(detections: List<Detection>): TrackingUpdate {

        tracks.values.forEach {
            it.missed++
            it.age++
        }

        val matchedIds = mutableSetOf<Int>()
        val output = mutableListOf<TrackedDetection>()
        var newCounts = 0

        val validDetections = detections
            .filter { detectionInsideRoi(it) }
            .sortedByDescending { it.confidence }

        for (detection in validDetections) {

            val candidate = tracks.entries
                .filter { (id, track) ->
                    id !in matchedIds &&
                    track.missed <= 2 &&
                    associationDistance(track, detection) <
                    maxAssociationDistance
                }
                .minByOrNull {
                    associationDistance(it.value, detection)
                }

            val track: Track
            val trackId: Int

            if (candidate != null) {

                trackId = candidate.key
                track = candidate.value
                matchedIds.add(trackId)

                val previousY = track.y

                val dx = detection.cx - track.x
                val dy = detection.cy - track.y

                track.velocityX =
                    (track.velocityX * 0.5f + dx * 0.5f)
                        .coerceIn(-maxVelocity, maxVelocity)

                track.velocityY =
                    (track.velocityY * 0.5f + dy * 0.5f)
                        .coerceIn(-maxVelocity, maxVelocity)

                track.x = detection.cx
                track.y = detection.cy

                track.left = detection.left
                track.top = detection.top
                track.right = detection.right
                track.bottom = detection.bottom

                track.missed = 0
                track.hits++
                track.lastDetection = detection

                // فعال شدن Track پس از ورود به محدوده شمارش
                if (
                    track.y > topLine &&
                    track.y < bottomLine
                ) {
                    track.armed = true
                }

                // شمارش عبور واقعی از خط بالایی
                if (
                    track.armed &&
                    !track.counted &&
                    track.hits >= 2 &&
                    previousY > topLine &&
                    track.y <= topLine
                ) {
                    track.counted = true
                    newCounts++
                }

            } else {

                trackId = nextId++
                matchedIds.add(trackId)

                track = Track(
                    x = detection.cx,
                    y = detection.cy,
                    left = detection.left,
                    top = detection.top,
                    right = detection.right,
                    bottom = detection.bottom,
                    lastDetection = detection
                )

                if (
                    detection.cy > topLine &&
                    detection.cy < bottomLine
                ) {
                    track.armed = true
                }

                tracks[trackId] = track
            }

            output.add(
                TrackedDetection(
                    id = trackId,
                    detection = detection,
                    isPredicted = false
                )
            )
        }

        // پیش‌بینی موقعیت کیسه در فریم‌های ازدست‌رفته
        tracks.forEach { (id, track) ->

            if (
                id !in matchedIds &&
                track.missed in 1..5 &&
                track.lastDetection != null
            ) {
                val predicted = predictedDetection(track)

                if (detectionInsideRoi(predicted)) {
                    output.add(
                        TrackedDetection(
                            id = id,
                            detection = predicted,
                            isPredicted = true
                        )
                    )
                }
            }
        }

        // حذف Trackهای منقضی شده
        tracks.entries.removeAll {
            it.value.missed > maxMissedFrames
        }

        return TrackingUpdate(
            newCounts = newCounts,
            trackedDetections = output
        )
    }

    private fun detectionInsideRoi(
        detection: Detection
    ): Boolean {
        return detection.cx >= roiLeft &&
               detection.cx <= roiRight
    }

    private fun associationDistance(
        track: Track,
        detection: Detection
    ): Float {

        val predictionFrames = min(track.missed + 1, 2)

        val predictedX =
            track.x + track.velocityX * predictionFrames

        val predictedY =
            track.y + track.velocityY * predictionFrames

        return abs(predictedX - detection.cx) +
               abs(predictedY - detection.cy)
    }

    private fun predictedDetection(
        track: Track
    ): Detection {

        val predictionFrames = min(track.missed, 3)

        val predictedX =
            track.x + track.velocityX * predictionFrames

        val predictedY =
            track.y + track.velocityY * predictionFrames

        val shiftX = predictedX - track.x
        val shiftY = predictedY - track.y

        val original = track.lastDetection!!

        return Detection(
            left = original.left + shiftX,
            top = original.top + shiftY,
            right = original.right + shiftX,
            bottom = original.bottom + shiftY,
            confidence = original.confidence
        )
    }
}
