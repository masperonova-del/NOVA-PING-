package com.novaping.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Animated network-state indicator.
 * It reflects Android validation; it does not measure latency.
 */
class NetworkStatusIndicator(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stateColor = 0xFF64D296.toInt()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Indicateur animé de l'état du réseau"
        isFocusable = false
    }

    fun setState(color: Int) {
        if (stateColor == color) return
        stateColor = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1500L
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
            }
        }
        animator?.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = 7f * density
        val pulse = 0.5f + 0.5f * kotlin.math.sin((phase * Math.PI * 2.0).toFloat())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = stateColor
        paint.alpha = (45 + pulse * 65).toInt()
        canvas.drawCircle(cx, cy, base + (5f + pulse * 5f) * density, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 35
        canvas.drawCircle(cx, cy, base + 5f * density, paint)
        paint.alpha = 255
        canvas.drawCircle(cx, cy, base, paint)
    }
}
