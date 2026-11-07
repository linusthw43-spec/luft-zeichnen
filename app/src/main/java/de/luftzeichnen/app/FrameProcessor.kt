package de.luftzeichnen.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.ceil
import kotlin.math.floor

/** Ein fertig verarbeitetes Kamerabild inklusive Handpunkten und Effekt-Ausschnitt. */
class Frame(
    val bitmap: Bitmap,
    /** Hände (links → rechts), je 21 Punkte als x,y in Bitmap-Pixeln. */
    val hands: List<FloatArray>,
    /** Eckpunkte des Bands (A,B,C,D) in Bitmap-Pixeln oder null. */
    val band: FloatArray?,
    val effect: Bitmap?,
    val effectX: Int,
    val effectY: Int,
)

/** Läuft im Kamera-Thread: dreht das Bild, erkennt Hände und berechnet den Effekt. */
class FrameProcessor(
    private val context: Context,
    private val onFrame: (Frame) -> Unit,
) {
    @Volatile
    var filterMode: FilterMode = FilterMode.THERMAL

    var onError: ((String) -> Unit)? = null

    private var tracker: HandTracker? = null
    private var trackerFailed = false
    private val smoother = HandSmoother()
    private var lastTs = 0L

    fun process(image: ImageProxy, mirror: Boolean) {
        val bitmap = try {
            toUpright(image, mirror)
        } catch (e: Exception) {
            Log.w(TAG, "Bild konnte nicht gelesen werden", e)
            null
        } finally {
            image.close()
        }
        if (bitmap == null) return

        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts

        val detected = try {
            obtainTracker()?.detect(bitmap, ts) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Handerkennung fehlgeschlagen", e)
            emptyList()
        }
        val hands = smoother.update(detected, ts)
        val band = Band.compute(hands)

        var effect: Bitmap? = null
        var ex = 0
        var ey = 0
        if (band != null) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until 4) {
                minX = minOf(minX, band[i * 2]); maxX = maxOf(maxX, band[i * 2])
                minY = minOf(minY, band[i * 2 + 1]); maxY = maxOf(maxY, band[i * 2 + 1])
            }
            val l = floor(minX - 2).toInt().coerceIn(0, bitmap.width)
            val t = floor(minY - 2).toInt().coerceIn(0, bitmap.height)
            val r = ceil(maxX + 2).toInt().coerceIn(0, bitmap.width)
            val b = ceil(maxY + 2).toInt().coerceIn(0, bitmap.height)
            val w = r - l
            val h = b - t
            if (w >= 3 && h >= 3) {
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, l, t, w, h)
                Filters.apply(filterMode, px, w, h, ts)
                effect = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                ex = l
                ey = t
            }
        }
        onFrame(Frame(bitmap, hands, band, effect, ex, ey))
    }

    fun resetTracking() {
        smoother.reset()
    }

    private fun toUpright(image: ImageProxy, mirror: Boolean): Bitmap {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !mirror) return src
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun obtainTracker(): HandTracker? {
        tracker?.let { return it }
        if (trackerFailed) return null
        return try {
            HandTracker(context).also { tracker = it }
        } catch (e: Throwable) {
            trackerFailed = true
            Log.e(TAG, "HandTracker konnte nicht starten", e)
            onError?.invoke(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    fun close() {
        tracker?.close()
        tracker = null
    }

    private companion object {
        const val TAG = "FrameProcessor"
    }
}
