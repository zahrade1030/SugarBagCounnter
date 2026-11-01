package com.vea.cementbagcounter

import kotlin.math.abs

class SimpleLineCounter {

    private data class Track(
        var x: Float,
        var y: Float,
        var armed: Boolean = false,
        var counted: Boolean = false,
        var missed: Int = 0
    )

    private val tracks = mutableMapOf<Int, Track>()
    private var nextId = 1

    private var topLine = 150f
    private var bottomLine = 411f

    fun setLines(top: Float, bottom: Float) {
        topLine = minOf(top, bottom)
        bottomLine = maxOf(top, bottom)
    }

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<Detection>): Int {

        tracks.values.forEach { it.missed++ }

        val matchedIds = mutableSetOf<Int>()
        var newCounts = 0

        for (d in detections.sortedByDescending { it.confidence }) {

            val candidate = tracks.entries
                .filter {
                    it.key !in matchedIds &&
                    it.value.missed <= 2 &&
                    abs(it.value.x - d.cx) < 120f &&
                    abs(it.value.y - d.cy) < 160f
                }
                .minByOrNull {
                    abs(it.value.x - d.cx) +
                    abs(it.value.y - d.cy)
                }

            val track = if (candidate != null) {
                matchedIds.add(candidate.key)
                candidate.value.also {
                    it.missed = 0
                }
            } else {
                val id = nextId++
                matchedIds.add(id)

                Track(d.cx, d.cy).also {
                    tracks[id] = it
                }
            }

            val oldY = track.y

            track.x = d.cx
            track.y = d.cy

            // کیسه وارد محدوده بین دو خط شده است
            if (d.cy > topLine && d.cy < bottomLine) {
                track.armed = true
            }

            // شمارش فقط هنگام عبور از خط بالایی به سمت بالا
            if (
                track.armed &&
                !track.counted &&
                oldY > topLine &&
                d.cy <= topLine
            ) {
                track.counted = true
                newCounts++
            }
        }

        tracks.entries.removeAll {
            it.value.missed > 20
        }

        return newCounts
    }
}