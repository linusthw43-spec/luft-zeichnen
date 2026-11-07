package de.luftzeichnen.app

import android.graphics.Color
import kotlin.math.abs

enum class FilterMode(val label: String) {
    THERMAL("THERMAL"),
    RAINBOW("REGENBOGEN"),
    NEGATIVE("NEGATIV"),
    XRAY("RÖNTGEN"),
    NEON("NEON"),
    GLITCH("GLITCH");

    fun next(): FilterMode = entries[(ordinal + 1) % entries.size]
}

/** Pixel-Effekte, die auf den Bereich zwischen den Händen angewendet werden. */
object Filters {

    /** Wärmebild-Farbverlauf: dunkel = blau, mittel = cyan/magenta, hell = orange/gelb/weiß. */
    private val thermalLut: IntArray = buildLut(
        floatArrayOf(0f, 0.15f, 0.30f, 0.45f, 0.60f, 0.75f, 0.88f, 1f),
        intArrayOf(
            0x0A0050, 0x1E28FF, 0x00D8FF, 0xB040FF,
            0xFF20C8, 0xFF5A00, 0xFFE000, 0xFFFFFF,
        ),
    )

    private val rainbowLut = IntArray(256) { i ->
        Color.HSVToColor(floatArrayOf(i * 360f / 256f, 1f, 1f))
    }

    fun apply(mode: FilterMode, px: IntArray, w: Int, h: Int, timeMs: Long) {
        when (mode) {
            FilterMode.THERMAL -> lutFilter(px, thermalLut, 0)
            FilterMode.RAINBOW -> lutFilter(px, rainbowLut, ((timeMs / 6) % 256).toInt())
            FilterMode.NEGATIVE -> negative(px)
            FilterMode.XRAY -> xray(px)
            FilterMode.NEON -> neon(px, w, h, timeMs)
            FilterMode.GLITCH -> glitch(px, w, h, timeMs)
        }
    }

    private fun buildLut(stops: FloatArray, colors: IntArray): IntArray = IntArray(256) { i ->
        val t = i / 255f
        var s = 0
        while (s < stops.size - 2 && t > stops[s + 1]) s++
        val f = ((t - stops[s]) / (stops[s + 1] - stops[s])).coerceIn(0f, 1f)
        val a = colors[s]
        val b = colors[s + 1]
        val r = lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, f)
        val g = lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, f)
        val bl = lerp(a and 0xFF, b and 0xFF, f)
        (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt().coerceIn(0, 255)

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /** Automatischer Kontrast: liefert (untere Grenze, Spannweite) der Helligkeit. */
    private fun levels(px: IntArray): IntArray {
        val hist = IntArray(256)
        for (c in px) hist[lum(c)]++
        val cut = px.size / 50
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > lo && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        return intArrayOf(lo, maxOf(24, hi - lo))
    }

    private fun lutFilter(px: IntArray, lut: IntArray, shift: Int) {
        val lv = levels(px)
        val lo = lv[0]
        val range = lv[1]
        for (i in px.indices) {
            val v = ((lum(px[i]) - lo) * 255 / range).coerceIn(0, 255)
            px[i] = lut[(v + shift) and 0xFF]
        }
    }

    private fun negative(px: IntArray) {
        for (i in px.indices) px[i] = px[i] xor 0x00FFFFFF
    }

    private fun xray(px: IntArray) {
        val lv = levels(px)
        val lo = lv[0]
        val range = lv[1]
        for (i in px.indices) {
            val v = 255 - ((lum(px[i]) - lo) * 255 / range).coerceIn(0, 255)
            val r = v * 70 / 100
            val g = v * 90 / 100
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or v
        }
    }

    private fun neon(px: IntArray, w: Int, h: Int, timeMs: Long) {
        val l = IntArray(px.size) { lum(px[it]) }
        val shift = (timeMs / 8).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    px[i] = 0xFF000000.toInt()
                    continue
                }
                val gx = -l[i - w - 1] - 2 * l[i - 1] - l[i + w - 1] + l[i - w + 1] + 2 * l[i + 1] + l[i + w + 1]
                val gy = -l[i - w - 1] - 2 * l[i - w] - l[i - w + 1] + l[i + w - 1] + 2 * l[i + w] + l[i + w + 1]
                val mag = ((abs(gx) + abs(gy)) * 2).coerceAtMost(255)
                val c = rainbowLut[((x + y) / 3 + shift) and 0xFF]
                val r = ((c shr 16) and 0xFF) * mag / 255
                val g = ((c shr 8) and 0xFF) * mag / 255
                val b = (c and 0xFF) * mag / 255
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun glitch(px: IntArray, w: Int, h: Int, timeMs: Long) {
        val src = px.copyOf()
        val k = maxOf(3, w / 40)
        val seed = (timeMs / 90).toInt()
        val sliceH = maxOf(4, h / 14)
        for (y in 0 until h) {
            val slice = y / sliceH
            val hash = (slice * 73856093) xor (seed * 19349663)
            val rowShift = if ((hash ushr 3) % 4 == 0) ((hash ushr 7) % (k * 4)) - k * 2 else 0
            val row = y * w
            for (x in 0 until w) {
                val xr = (x + k + rowShift).coerceIn(0, w - 1)
                val xg = (x + rowShift).coerceIn(0, w - 1)
                val xb = (x - k + rowShift).coerceIn(0, w - 1)
                val r = (src[row + xr] shr 16) and 0xFF
                val g = (src[row + xg] shr 8) and 0xFF
                val b = src[row + xb] and 0xFF
                px[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
