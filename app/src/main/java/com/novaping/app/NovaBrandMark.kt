package com.novaping.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Animated NOVA PING identity mark.
 * Presentation only: no network calls, timers or diagnostic state are touched.
 */
internal class NovaBrandMark(context: Context) : View(context) {
    private val blue = Color.rgb(64, 160, 255)
    private val silver = Color.rgb(220, 231, 245)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        contentDescription = "Logo NOVA PING"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val scale = side / 100f
        val save = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        paint.color = blue
        paint.strokeWidth = 1.8f
        canvas.drawCircle(0f, 0f, 39f, paint)

        paint.strokeWidth = 3.2f
        paint.color = Color.argb(110, 64, 160, 255)
        canvas.drawArc(-34f, -34f, 34f, 34f, -90f, 250f, false, paint)
        paint.color = blue
        canvas.drawArc(-34f, -34f, 34f, 34f, phase * 360f, 72f, false, paint)

        val pulse = 1f + 0.08f * (0.5f + 0.5f * kotlin.math.sin((phase * 6.28318f).toDouble()))
        paint.color = Color.argb(35, 64, 160, 255)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, 0f, 26f * pulse, paint)

        paint.color = silver
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6.5f
        val n = Path().apply {
            moveTo(-19f, 20f)
            lineTo(-19f, -20f)
            lineTo(19f, 20f)
            lineTo(19f, -20f)
        }
        canvas.drawPath(n, paint)

        paint.style = Paint.Style.FILL
        paint.color = blue
        canvas.drawCircle(0f, -39f, 3.5f, paint)
        canvas.drawCircle(27.6f, 27.6f, 2.8f, paint)
        canvas.drawCircle(-27.6f, -27.6f, 2.8f, paint)
        canvas.restoreToCount(save)
    }
}
