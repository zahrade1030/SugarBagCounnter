
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

    private data class Track(
        val id: Int,
        var detection: Detection,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var missed: Int = 0,
        var armed: Boolean = false,
        var counted: Boolean = false
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

        tracks.values.forEach { it.missed++ }

        val candidates = mutableListOf<Match>()

        for (track in tracks.values) {
            val predictedX = track.detection.cx + track.vx
            val predictedY = track.detection.cy + track.vy

            for (index in detections.indices) {
                val detection = detections[index]

                val dx = abs(predictedX - detection.cx)
                val dy = abs(predictedY - detection.cy)

                val oldSize = max(
                    track.detection.right - track.detection.left,
                    track.detection.bottom - track.detection.top
                )

                val newSize = max(
                    detection.right - detection.left,
                    detection.bottom - detection.top
                )

                val gate = max(90f, max(oldSize, newSize) * 1.4f)

                if (dx > gate || dy > gate * 1.35f) {
                    continue
                }

                val distance = sqrt(dx * dx + dy * dy)
                val overlap = iou(track.detection, detection)

                if (overlap < 0.01f && distance > gate * 0.75f) {
                    continue
                }

                val cost = distance / gate + (1f - overlap) * 0.65f

                candidates.add(
                    Match(track.id, index, cost)
                )
            }
        }

        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()

        var newCounts = 0
        val visibleTracks = mutableListOf<TrackedDetection>()

        for (match in candidates.sortedBy { it.cost }) {

            if (
                match.trackId in usedTracks ||
                match.detectionIndex in usedDetections
            ) {
                continue
            }

            val track = tracks[match.trackId] ?: continue
            val detection = detections[match.detectionIndex]

            usedTracks.add(match.trackId)
            usedDetections.add(match.detectionIndex)

            val oldY = track.detection.cy

            val measuredVx = detection.cx - track.detection.cx
            val measuredVy = detection.cy - track.detection.cy

            track.vx = track.vx * 0.35f + measuredVx * 0.65f
            track.vy = track.vy * 0.35f + measuredVy * 0.65f

            track.detection = detection
            track.missed = 0

            if (detection.cy > topLine && detection.cy < bottomLine) {
                track.armed = true
            }

            if (
                track.armed &&
                !track.counted &&
                oldY > topLine &&
                detection.cy <= topLine
            ) {
                track.counted = true
                newCounts++
            }

            visibleTracks.add(
                TrackedDetection(track.id, detection)
            )
        }

        for (index in detections.indices) {
            if (index in usedDetections) continue

            val detection = detections[index]
            val id = nextId++

            val track = Track(
                id = id,
                detection = detection,
                armed = detection.cy > topLine &&
                        detection.cy < bottomLine
            )

            tracks[id] = track

            visibleTracks.add(
                TrackedDetection(id, detection)
            )
        }

        tracks.entries.removeAll {
            it.value.missed > 10
        }

        return TrackingUpdate(
            newCounts = newCounts,
            trackedDetections = visibleTracks
        )
    }

    private fun iou(a: Detection, b: Detection): Float {

        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val intersection = max(0f, x2 - x1) *
                max(0f, y2 - y1)

        val areaA = max(0f, a.right - a.left) *
                max(0f, a.bottom - a.top)

        val areaB = max(0f, b.right - b.left) *
                max(0f, b.bottom - b.top)

        return intersection /
                (areaA + areaB - intersection + 0.000001f)
    }
}
