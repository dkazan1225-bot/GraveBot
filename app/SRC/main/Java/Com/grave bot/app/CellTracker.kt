package com.gravebot.app

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental temporal tracker for the five marked pumpkins.
 *
 * It detects the five grey grave cells, waits until the grave sprites have
 * changed to pumpkins, then follows local image patches frame-to-frame.
 * Patch matching includes the sprite and nearby background. It is not a
 * semantic identity model: identical sprites, heavy blur, or large jumps can
 * make a match ambiguous. In those cases the cell is left unknown.
 */
class CellTracker(
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float
) {
    data class Result(
        val states: IntArray,
        val pumpkinCount: Int,
        val confident: Boolean,
        val phase: String = "Ищу надгробия",
        val frameRateHint: Int = 30
    )

    private data class Track(
        var x: Int,
        var y: Int,
        var patch: IntArray,
        var score: Int = 0,
        var lost: Int = 0
    )

    private var previousMask = IntArray(25)
    private var stableFrames = IntArray(25)
    private var lockedTargets: IntArray? = null
    private val tracks = ArrayList<Track>()
    private var framesSeen = 0
    private var trackingStarted = false
    private var lastPumpkinCount = 0
    private var lastPhase = "Ищу надгробия"

    private val patchRadius = 12
    private val patchStep = 6
    private val patchSide = (patchRadius * 2 / patchStep) + 1
    private val maxMatchScore = 62
    private val maxLostFrames = 5

    @Synchronized
    fun analyze(bitmap: Bitmap): Result {
        framesSeen++
        val w = bitmap.width
        val h = bitmap.height
        val x0 = (left.coerceIn(0f, .94f) * w).toInt()
        val y0 = (top.coerceIn(0f, .94f) * h).toInt()
        val bw = (width.coerceIn(.05f, 1f-left) * w).toInt()
        val bh = (height.coerceIn(.05f, 1f-top) * h).toInt()
        if (bw < 100 || bh < 100) return Result(IntArray(25) { -1 }, 0, false, "Проверь область поля")

        val cellW = bw / 5
        val cellH = bh / 5
        val mask = IntArray(25)
        var pumpkinCount = 0
        for (row in 0 until 5) for (col in 0 until 5) {
            val i = row * 5 + col
            val cx = x0 + col * cellW + cellW / 2
            val cy = y0 + row * cellH + cellH / 2
            val stats = classifyCell(bitmap, cx, cy, cellW, cellH)
            if (stats.first >= 0.065f) mask[i] = 1
            if (stats.second >= 0.045f) pumpkinCount++
        }
        lastPumpkinCount = pumpkinCount

        for (i in 0 until 25) {
            stableFrames[i] = if (mask[i] == 1 && previousMask[i] == 1) stableFrames[i] + 1
                              else if (mask[i] == 1) 1 else 0
        }
        previousMask = mask

        if (lockedTargets == null) {
            val candidates = stableFrames.indices.filter { stableFrames[it] >= 2 }
            if (candidates.size == 5) lockedTargets = candidates.toIntArray()
        }

        val targets = lockedTargets
        if (targets == null) {
            val n = mask.count { it == 1 }
            lastPhase = if (n > 0) "Надгробия: $n/5 · фиксирую" else "Ищу надгробия…"
            return Result(IntArray(25) { -1 }, pumpkinCount, false, lastPhase)
        }

        if (!trackingStarted) {
            // The graves must have transformed into pumpkins before patch templates are taken.
            if (pumpkinCount >= 20) {
                for (cell in targets) {
                    val row = cell / 5
                    val col = cell % 5
                    val cx = x0 + col * cellW + cellW / 2
                    val cy = y0 + row * cellH + cellH / 2
                    tracks.add(Track(cx, cy, extractPatch(bitmap, cx, cy)))
                }
                trackingStarted = tracks.size == 5
                lastPhase = if (trackingStarted) "Пять объектов захвачены · отслеживаю" else "Не удалось создать шаблоны"
            } else {
                lastPhase = "Надгробия запомнены · жду превращения в тыквы ($pumpkinCount/20)"
            }
            val states = IntArray(25) { -1 }
            targets.forEach { states[it] = 1 }
            return Result(states, pumpkinCount, true, lastPhase)
        }

        // Track each patch in a local window around its last position.
        // Search radius is approximately one cell; a larger jump is marked unknown.
        val occupied = ArrayList<Pair<Int,Int>>()
        for (track in tracks) {
            val radiusX = max(30, cellW * 3 / 4)
            val radiusY = max(30, cellH * 3 / 4)
            var bestX = track.x
            var bestY = track.y
            var bestScore = Int.MAX_VALUE
            var y = max(y0 + patchRadius, track.y - radiusY)
            val yEnd = min(y0 + bh - patchRadius, track.y + radiusY)
            while (y <= yEnd) {
                var x = max(x0 + patchRadius, track.x - radiusX)
                val xEnd = min(x0 + bw - patchRadius, track.x + radiusX)
                while (x <= xEnd) {
                    var overlaps = false
                    for (p in occupied) if (abs(p.first-x) < patchRadius && abs(p.second-y) < patchRadius) { overlaps = true; break }
                    if (!overlaps) {
                        val score = comparePatch(track.patch, bitmap, x, y)
                        if (score < bestScore) { bestScore = score; bestX = x; bestY = y }
                    }
                    x += 6
                }
                y += 6
            }
            track.score = bestScore
            if (bestScore <= maxMatchScore) {
                track.x = bestX
                track.y = bestY
                track.lost = 0
                // Slowly adapt to lighting/animation while retaining the original texture.
                val fresh = extractPatch(bitmap, bestX, bestY)
                for (i in track.patch.indices) track.patch[i] = (track.patch[i] * 0.82f + fresh[i] * 0.18f).toInt()
                occupied.add(bestX to bestY)
            } else {
                track.lost++
            }
        }

        val states = IntArray(25) { -1 }
        var good = 0
        for (track in tracks) {
            if (track.lost <= maxLostFrames && track.score <= maxMatchScore) {
                val col = ((track.x - x0) / cellW).coerceIn(0,4)
                val row = ((track.y - y0) / cellH).coerceIn(0,4)
                states[row*5+col] = 1
                good++
            }
        }
        lastPhase = when {
            good == 5 -> "Отслеживаю 5/5 · патч-сопоставление"
            else -> "Найдено $good/5 · неоднозначное движение"
        }
        return Result(states, pumpkinCount, good == 5, lastPhase)
    }

    private fun classifyCell(bitmap: Bitmap, cx: Int, cy: Int, cw: Int, ch: Int): Pair<Float,Float> {
        var samples = 0
        var stone = 0
        var orange = 0
        val sx = cx - (cw * .28f).toInt()
        val ex = cx + (cw * .28f).toInt()
        val sy = cy - (ch * .30f).toInt()
        val ey = cy + (ch * .30f).toInt()
        val stepX = max(2, (ex-sx)/24)
        val stepY = max(2, (ey-sy)/24)
        var y = sy
        while (y < ey) {
            var x = sx
            while (x < ex) {
                val c = bitmap.getPixel(x.coerceIn(0,bitmap.width-1), y.coerceIn(0,bitmap.height-1))
                val r = (c ushr 16) and 255
                val g = (c ushr 8) and 255
                val b = c and 255
                val hi = max(r,max(g,b)); val lo = min(r,min(g,b))
                val sat = if (hi == 0) 0 else (hi-lo)*255/hi
                if (hi in 75..205 && sat < 62 && abs(r-g) < 42) stone++
                if (r > 135 && g in 45..190 && b < 120 && r > g+20) orange++
                samples++
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0f to 0f else stone.toFloat()/samples to orange.toFloat()/samples
    }

    private fun extractPatch(bitmap: Bitmap, cx: Int, cy: Int): IntArray {
        val out = IntArray(patchSide * patchSide * 3)
        var k = 0
        for (dy in -patchRadius..patchRadius step patchStep) {
            for (dx in -patchRadius..patchRadius step patchStep) {
                val c = bitmap.getPixel((cx+dx).coerceIn(0,bitmap.width-1),(cy+dy).coerceIn(0,bitmap.height-1))
                out[k++] = (c ushr 16) and 255
                out[k++] = (c ushr 8) and 255
                out[k++] = c and 255
            }
        }
        return out
    }

    private fun comparePatch(template: IntArray, bitmap: Bitmap, cx: Int, cy: Int): Int {
        var k = 0
        var sum = 0
        var count = 0
        for (dy in -patchRadius..patchRadius step patchStep) {
            for (dx in -patchRadius..patchRadius step patchStep) {
                val c = bitmap.getPixel(cx+dx,cy+dy)
                sum += abs(template[k++] - ((c ushr 16) and 255))
                sum += abs(template[k++] - ((c ushr 8) and 255))
                sum += abs(template[k++] - (c and 255))
                count += 3
            }
        }
        return sum / count
    }

    companion object {
        fun fromImage(image: Image): Bitmap? {
            if (image.format != PixelFormat.RGBA_8888) return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            return cropped
        }
    }
}
