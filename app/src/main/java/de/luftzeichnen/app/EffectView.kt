package de.luftzeichnen.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Zeichnet das Kamerabild, das Effekt-Band zwischen den Händen
 * sowie die Tracking-Boxen mit Koordinaten.
 * [render] wird auch für Foto und Videoaufnahme benutzt.
 */
class EffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var frame: Frame? = null
        private set

    /** Verlauf der Zeigefinger-Positionen (Bitmap-Pixel) für die "Geister"-Boxen. */
    private val history = ArrayDeque<FloatArray>()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val topPath = Path()
    private val bottomPath = Path()
    private val outlinePath = Path()
    private val tmpRect = RectF()
    private var dashUnit = -1f

    fun submit(f: Frame) {
        frame = f
        if (f.hands.isEmpty()) {
            history.clear()
        } else {
            val pts = FloatArray(f.hands.size * 2)
            f.hands.forEachIndexed { i, h ->
                pts[i * 2] = Hand.x(h, Hand.INDEX_TIP)
                pts[i * 2 + 1] = Hand.y(h, Hand.INDEX_TIP)
            }
            history.addFirst(pts)
            while (history.size > HISTORY) history.removeLast()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        render(canvas, width, height)
    }

    /** Zeichnet alles in ein Ziel mit Größe [w] x [h] (Bild wird wie "center crop" skaliert). */
    fun render(c: Canvas, w: Int, h: Int) {
        c.drawColor(Color.BLACK)
        val f = frame ?: return
        if (w <= 0 || h <= 0) return
        val bw = f.bitmap.width
        val bh = f.bitmap.height
        val s = max(w / bw.toFloat(), h / bh.toFloat())
        val dx = (w - bw * s) / 2f
        val dy = (h - bh * s) / 2f
        val u = w / 400f

        fun sx(x: Float) = dx + x * s
        fun sy(y: Float) = dy + y * s

        // 1) Kamerabild
        drawBitmapScaled(c, f.bitmap, dx, dy, s)

        // 2) Band zwischen den Händen
        val band = f.band
        if (band != null) {
            val p = FloatArray(8) { i -> if (i % 2 == 0) sx(band[i]) else sy(band[i]) }
            val ax = p[0]; val ay = p[1]
            val bx = p[2]; val by = p[3]
            val cx = p[4]; val cy = p[5]
            val qx = p[6]; val qy = p[7]
            val m1x = ax + (qx - ax) * SPLIT; val m1y = ay + (qy - ay) * SPLIT
            val m2x = bx + (cx - bx) * SPLIT; val m2y = by + (cy - by) * SPLIT

            quad(topPath, ax, ay, bx, by, m2x, m2y, m1x, m1y)
            quad(bottomPath, m1x, m1y, m2x, m2y, cx, cy, qx, qy)
            quad(outlinePath, ax, ay, bx, by, cx, cy, qx, qy)

            f.effect?.let { eff ->
                c.save()
                c.clipPath(topPath)
                drawBitmapScaled(c, eff, sx(f.effectX.toFloat()), sy(f.effectY.toFloat()), s)
                c.restore()
            }

            // Schraffierter Streifen
            c.save()
            c.clipPath(bottomPath)
            c.drawColor(0xB0000000.toInt())
            bottomPath.computeBounds(tmpRect, true)
            linePaint.strokeWidth = 1.1f * u
            linePaint.alpha = 235
            val step = 4.5f * u
            val span = tmpRect.height()
            var k = tmpRect.left - span
            while (k < tmpRect.right) {
                c.drawLine(k, tmpRect.bottom, k + span, tmpRect.top, linePaint)
                k += step
            }
            c.restore()

            linePaint.strokeWidth = 0.9f * u
            linePaint.alpha = 220
            c.drawPath(outlinePath, linePaint)
            c.drawLine(m1x, m1y, m2x, m2y, linePaint)
        }

        // 3) Tracking-Overlay
        textPaint.textSize = 11f * u
        textPaint.setShadowLayer(1.5f * u, 0f, 0f, 0x99000000.toInt())
        if (dashUnit != u) {
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(5f * u, 4f * u), 0f)
            dashUnit = u
        }
        dashPaint.strokeWidth = 0.8f * u

        val hands = f.hands
        if (hands.isEmpty()) return

        val refX = sx(Hand.x(hands[0], Hand.INDEX_TIP)).toInt()
        val refY = sy(Hand.y(hands[0], Hand.INDEX_TIP)).toInt()
        val refLabel = "x:$refX y:$refY"

        // Geister-Boxen an älteren Positionen
        for ((n, idx) in GHOSTS.withIndex()) {
            val pts = history.elementAtOrNull(idx) ?: continue
            for (i in 0 until pts.size / 2) {
                val gx = sx(pts[i * 2])
                val gy = sy(pts[i * 2 + 1])
                val half = (14f + n * 6f) * u
                linePaint.alpha = 150 - n * 30
                linePaint.strokeWidth = 0.8f * u
                c.drawRect(gx - half, gy - half, gx + half, gy + half, linePaint)
                textPaint.alpha = 210 - n * 40
                c.drawText(refLabel, gx - half + 3f * u, gy - half + textPaint.textSize + 2f * u, textPaint)
            }
        }
        textPaint.alpha = 255

        // Verbindungslinien zwischen den Händen
        if (hands.size >= 2) {
            val l = hands[0]
            val r = hands[1]
            dashPaint.alpha = 170
            c.drawLine(
                sx(Hand.x(l, Hand.INDEX_TIP)), sy(Hand.y(l, Hand.INDEX_TIP)),
                sx(Hand.x(r, Hand.THUMB_TIP)), sy(Hand.y(r, Hand.THUMB_TIP)), dashPaint,
            )
            c.drawLine(
                sx(Hand.x(l, Hand.THUMB_TIP)), sy(Hand.y(l, Hand.THUMB_TIP)),
                sx(Hand.x(r, Hand.INDEX_TIP)), sy(Hand.y(r, Hand.INDEX_TIP)), dashPaint,
            )
            c.drawLine(
                sx(Hand.x(l, Hand.WRIST)), sy(Hand.y(l, Hand.WRIST)),
                sx(Hand.x(r, Hand.WRIST)), sy(Hand.y(r, Hand.WRIST)), dashPaint,
            )
        }

        for (hand in hands) {
            // Große Box um die ganze Hand
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until Hand.POINTS) {
                val x = sx(Hand.x(hand, i))
                val y = sy(Hand.y(hand, i))
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            val pad = 10f * u
            val l = minX - pad
            val t = minY - pad
            val r = maxX + pad
            val b = maxY + pad
            linePaint.alpha = 130
            linePaint.strokeWidth = 0.8f * u
            c.drawRect(l, t, r, b, linePaint)
            linePaint.alpha = 255
            linePaint.strokeWidth = 1.6f * u
            brackets(c, l, t, r, b, 9f * u)
            val ix = sx(Hand.x(hand, Hand.INDEX_TIP)).toInt()
            val iy = sy(Hand.y(hand, Hand.INDEX_TIP)).toInt()
            c.drawText("x:$ix y:$iy", l + 4f * u, t + textPaint.textSize + 3f * u, textPaint)

            // Kleine Boxen an Fingerspitzen
            for (tip in TIPS) {
                val x = sx(Hand.x(hand, tip))
                val y = sy(Hand.y(hand, tip))
                val big = tip == Hand.INDEX_TIP || tip == Hand.THUMB_TIP
                val half = (if (big) 9f else 5f) * u
                linePaint.strokeWidth = 1.1f * u
                linePaint.alpha = if (big) 255 else 170
                brackets(c, x - half, y - half, x + half, y + half, half * 0.6f)
                dotPaint.alpha = 230
                c.drawCircle(x, y, 1.4f * u, dotPaint)
                if (big) {
                    c.drawText("x:${x.toInt()} y:${y.toInt()}", x + half + 3f * u, y - half, textPaint)
                }
            }
        }
        linePaint.alpha = 255
    }

    private fun drawBitmapScaled(c: Canvas, bmp: Bitmap, x: Float, y: Float, s: Float) {
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        c.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        c.restore()
    }

    private fun quad(
        path: Path,
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
    ) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.lineTo(x4, y4)
        path.close()
    }

    private fun brackets(c: Canvas, l: Float, t: Float, r: Float, b: Float, len: Float) {
        c.drawLine(l, t, l + len, t, linePaint); c.drawLine(l, t, l, t + len, linePaint)
        c.drawLine(r, t, r - len, t, linePaint); c.drawLine(r, t, r, t + len, linePaint)
        c.drawLine(l, b, l + len, b, linePaint); c.drawLine(l, b, l, b - len, linePaint)
        c.drawLine(r, b, r - len, b, linePaint); c.drawLine(r, b, r, b - len, linePaint)
    }

    private companion object {
        /** Anteil des bunten Effekts am Band (Rest = Schraffur). */
        const val SPLIT = 0.62f
        const val HISTORY = 30
        val GHOSTS = intArrayOf(8, 18, 28)
        val TIPS = intArrayOf(Hand.THUMB_TIP, Hand.INDEX_TIP, Hand.MIDDLE_TIP, Hand.RING_TIP, Hand.PINKY_TIP)
    }
}
