package com.example.sonyremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    var tint = Color.BLACK
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        const val POWER = 0; const val SKIP_BACK = 1; const val REW = 2
        const val PLAY = 3; const val FF = 4; const val SKIP_FWD = 5; const val INPUT = 6
        const val BT = 7; const val USB = 8; const val SYNC = 9
    }

    private fun poly(close: Boolean, vararg v: Float): Path {
        val q = Path()
        q.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) { q.lineTo(v[i], v[i + 1]); i += 2 }
        if (close) q.close()
        return q
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        p.color = tint
        p.strokeWidth = 2f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.style = if (kind == POWER || kind == INPUT || kind == BT || kind == USB || kind == SYNC) Paint.Style.STROKE else Paint.Style.FILL
        when (kind) {
            POWER -> {
                c.drawLine(12f, 3f, 12f, 12f, p)
                c.drawArc(RectF(4f, 5f, 20f, 21f), -50f, 280f, false, p)
            }
            INPUT -> {
                c.drawPath(poly(false, 4f, 7f, 17f, 7f), p)
                c.drawPath(poly(false, 13f, 3f, 17f, 7f, 13f, 11f), p)
                c.drawPath(poly(false, 20f, 17f, 7f, 17f), p)
                c.drawPath(poly(false, 11f, 13f, 7f, 17f, 11f, 21f), p)
            }
            BT -> {
                c.drawPath(poly(false, 7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f), p)
            }
            USB -> {
                c.drawPath(poly(true, 9f, 3f, 15f, 3f, 15f, 9f, 9f, 9f), p)
                c.drawPath(poly(true, 7f, 9f, 17f, 9f, 17f, 21f, 7f, 21f), p)
                c.drawLine(11f, 5.5f, 11f, 6.5f, p)
                c.drawLine(13f, 5.5f, 13f, 6.5f, p)
            }
            SYNC -> {
                c.drawArc(RectF(4f, 4f, 20f, 20f), 200f, 140f, false, p)
                c.drawPath(poly(false, 20f, 4f, 20f, 9.5f, 14.5f, 9.5f), p)
                c.drawArc(RectF(4f, 4f, 20f, 20f), 20f, 140f, false, p)
                c.drawPath(poly(false, 4f, 20f, 4f, 14.5f, 9.5f, 14.5f), p)
            }
            SKIP_BACK -> {
                c.drawPath(poly(true, 6f, 5f, 8f, 5f, 8f, 19f, 6f, 19f), p)
                c.drawPath(poly(true, 20f, 5f, 20f, 19f, 9f, 12f), p)
            }
            REW -> {
                c.drawPath(poly(true, 12f, 6f, 12f, 18f, 3f, 12f), p)
                c.drawPath(poly(true, 21f, 6f, 21f, 18f, 12f, 12f), p)
            }
            PLAY -> {
                c.drawPath(poly(true, 5f, 5f, 5f, 19f, 14f, 12f), p)
                c.drawPath(poly(true, 16f, 5f, 19f, 5f, 19f, 19f, 16f, 19f), p)
            }
            FF -> {
                c.drawPath(poly(true, 12f, 6f, 12f, 18f, 21f, 12f), p)
                c.drawPath(poly(true, 3f, 6f, 3f, 18f, 12f, 12f), p)
            }
            SKIP_FWD -> {
                c.drawPath(poly(true, 16f, 5f, 18f, 5f, 18f, 19f, 16f, 19f), p)
                c.drawPath(poly(true, 4f, 5f, 4f, 19f, 15f, 12f), p)
            }
        }
    }
}