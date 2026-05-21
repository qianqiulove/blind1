package com.blind.v1

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class BlindOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pointsNorm = mutableListOf<Pair<Float, Float>>()
    private val path = Path()
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 140)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 0, 255, 120)
        style = Paint.Style.FILL
    }

    var overlayEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    fun updateContourNormalized(points: List<Pair<Float, Float>>) {
        pointsNorm.clear()
        pointsNorm.addAll(points)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || pointsNorm.size < 3) return

        path.reset()
        val w = width.toFloat()
        val h = height.toFloat()

        val first = pointsNorm.first()
        path.moveTo(first.first * w, first.second * h)
        for (i in 1 until pointsNorm.size) {
            val p = pointsNorm[i]
            path.lineTo(p.first * w, p.second * h)
        }
        path.close()
        canvas.drawPath(path, paintFill)
        canvas.drawPath(path, paintStroke)
    }
}

