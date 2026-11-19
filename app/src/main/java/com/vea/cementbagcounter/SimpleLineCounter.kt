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
        var lastDetection: Detection? = null,
        var previousY: Float = 0f,
        var confidence: Float = 0f
    )

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    private var roiLeft = 0f
    private var roiRight = Float.MAX_VALUE

    private val maxMissedFrames = 15
    private val maxAssociationDistance = 140f
    private val maxVelocity = 100f
    private val predictionLimit = 5

    private val minimumHitsToCount = 2

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

    fun updateWithTracks(
        detections: List<Detection>
    ): TrackingUpdate {

        tracks.values.forEach {
            it.missed++
            it.age++
        }

        val matchedIds = mutableSetOf<Int>()
        val output = mutableListOf<TrackedDetection>()
        var newCounts = 0

        val validDetections = detections
            .filter {
                detectionInsideRoi(it) &&
                    it.confidence.isFinite() &&
                    it.confidence > 0f
            }
            .sortedByDescending { it.confidence }

        for (detection in validDetections) {

            val candidate = tracks.entries
                .filter { (id, track) ->
                    id !in matchedIds &&
                        track.missed <= 3 &&
                        isAssociationPossible(track, detection)
                }
                .minByOrNull {
                    associationCost(it.value, detection)
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

                val velocityWeight =
                    if (track.missed == 0) 0.55f else 0.35f

                track.velocityX = (
                    track.velocityX * (1f - velocityWeight) +
                        dx * velocityWeight
                    ).coerceIn(-maxVelocity, maxVelocity)

                track.velocityY = (
                    track.velocityY * (1f - velocityWeight) +
                        dy * velocityWeight
                    ).coerceIn(-maxVelocity, maxVelocity)

                track.previousY = previousY

                track.x = detection.cx
                track.y = detection.cy

                track.left = detection.left
                track.top = detection.top
                track.right = detection.right
                track.bottom = detection.bottom

                track.missed = 0
                track.hits++
                track.lastDetection = detection
                track.confidence = detection.confidence

                // Track must enter the counting zone before being armed.
                if (
                    track.y > topLine &&
                    track.y < bottomLine
                ) {
                    track.armed = true
                }

                // Count one crossing per tracked object.
                if (
                    track.armed &&
                    !track.counted &&
                    track.hits >= minimumHitsToCount &&
                    previousY > topLine &&
                    track.y <= topLine
                ) {
                    track.counted = true
                    newCounts++
                }

            } else {

                trackId = nextId++

                track = Track(
                    x = detection.cx,
                    y = detection.cy,
                    left = detection.left,
                    top = detection.top,
                    right = detection.right,
                    bottom = detection.bottom,
                    lastDetection = detection,
                    previousY = detection.cy,
                    confidence = detection.confidence
                )

                if (
                    detection.cy > topLine &&
                    detection.cy < bottomLine
                ) {
                    track.armed = true
                }

                tracks[trackId] = track
                matchedIds.add(trackId)
            }

            output.add(
                TrackedDetection(
                    id = trackId,
                    detection = detection,
                    isPredicted = false
                )
            )
        }

        // Predict positions for temporarily missing objects.
        tracks.forEach { (id, track) ->

            if (
                id !in matchedIds &&
                track.missed in 1..predictionLimit &&
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

        // Remove expired tracks.
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

    private fun isAssociationPossible(
        track: Track,
        detection: Detection
    ): Boolean {

        val distance = associationDistance(track, detection)

        if (distance > maxAssociationDistance) {
            return false
        }

        val oldWidth = max(1f, track.right - track.left)
        val oldHeight = max(1f, track.bottom - track.top)

        val newWidth = max(1f, detection.width)
        val newHeight = max(1f, detection.height)

        val widthRatio = newWidth / oldWidth
        val heightRatio = newHeight / oldHeight

        if (
            widthRatio !in 0.35f..2.8f ||
            heightRatio !in 0.35f..2.8f
        ) {
            return false
        }

        return true
    }

    private fun associationDistance(
        track: Track,
        detection: Detection
    ): Float {

        val predictionFrames = min(track.missed + 1, 3)

        val predictedX =
            track.x + track.velocityX * predictionFrames

        val predictedY =
            track.y + track.velocityY * predictionFrames

        val dx = predictedX - detection.cx
        val dy = predictedY - detection.cy

        return sqrt(dx * dx + dy * dy)
    }

    private fun associationCost(
        track: Track,
        detection: Detection
    ): Float {

        val distance = associationDistance(track, detection)

        val oldWidth = max(1f, track.right - track.left)
        val oldHeight = max(1f, track.bottom - track.top)

        val newWidth = max(1f, detection.width)
        val newHeight = max(1f, detection.height)

        val sizePenalty =
            abs(oldWidth - newWidth) +
                abs(oldHeight - newHeight)

        val confidencePenalty =
            (1f - detection.confidence) * 20f

        return distance + sizePenalty * 0.15f + confidencePenalty
    }

    private fun predictedDetection(
        track: Track
    ): Detection {

        val predictionFrames = min(track.missed, predictionLimit)

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
