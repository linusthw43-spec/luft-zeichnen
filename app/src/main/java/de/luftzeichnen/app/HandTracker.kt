package de.luftzeichnen.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable

/** Index-Konstanten der 21 MediaPipe-Handpunkte (jeweils x,y im FloatArray). */
object Hand {
    const val POINTS = 21
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_TIP = 8
    const val MIDDLE_TIP = 12
    const val RING_TIP = 16
    const val PINKY_TIP = 20

    fun x(hand: FloatArray, point: Int) = hand[point * 2]
    fun y(hand: FloatArray, point: Int) = hand[point * 2 + 1]
}

/** Erkennt bis zu zwei Hände im Bild (läuft auf der CPU, das ist auf allen Geräten stabil). */
class HandTracker(context: Context) : Closeable {

    private val landmarker: HandLandmarker

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(Delegate.CPU)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    /** Liefert pro Hand 42 Werte (x0,y0,x1,y1,…) in Pixeln des Bitmaps. */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<FloatArray> {
        val image = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(image, timestampMs)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.landmarks().map { lms ->
            val out = FloatArray(Hand.POINTS * 2)
            for (i in 0 until minOf(Hand.POINTS, lms.size)) {
                out[i * 2] = lms[i].x() * w
                out[i * 2 + 1] = lms[i].y() * h
            }
            out
        }
    }

    override fun close() {
        landmarker.close()
    }
}

/** Glättet das Zittern der Handpunkte und überbrückt kurze Aussetzer. */
class HandSmoother {
    private var hands: List<FloatArray> = emptyList()
    private var lastFull = 0L

    fun reset() {
        hands = emptyList()
    }

    fun update(detected: List<FloatArray>, now: Long): List<FloatArray> {
        val sorted = detected.sortedBy { Hand.x(it, Hand.WRIST) }
        if (sorted.size < hands.size && now - lastFull < HOLD_MS) return hands
        lastFull = now
        hands = if (sorted.size == hands.size) {
            sorted.mapIndexed { i, det ->
                val old = hands[i]
                FloatArray(det.size) { k -> old[k] + (det[k] - old[k]) * ALPHA }
            }
        } else {
            sorted.map { it.copyOf() }
        }
        return hands
    }

    private companion object {
        const val HOLD_MS = 250L
        const val ALPHA = 0.6f
    }
}

/**
 * Das Band zwischen den Händen: A = Zeigefinger links, B = Zeigefinger rechts,
 * C = Daumen rechts, D = Daumen links (je x,y).
 */
object Band {
    fun compute(hands: List<FloatArray>): FloatArray? {
        if (hands.size < 2) return null
        val l = hands[0]
        val r = hands[1]
        return floatArrayOf(
            Hand.x(l, Hand.INDEX_TIP), Hand.y(l, Hand.INDEX_TIP),
            Hand.x(r, Hand.INDEX_TIP), Hand.y(r, Hand.INDEX_TIP),
            Hand.x(r, Hand.THUMB_TIP), Hand.y(r, Hand.THUMB_TIP),
            Hand.x(l, Hand.THUMB_TIP), Hand.y(l, Hand.THUMB_TIP),
        )
    }
}
