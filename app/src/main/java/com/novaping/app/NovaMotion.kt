package com.novaping.app

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup

/**
 * Lightweight presentation-only motion.
 *
 * Animations are deliberately isolated from measurement code: they never
 * schedule probes, delay network work, or modify measured timestamps.
 */
internal object NovaMotion {
    fun animateEntrance(container: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return

        container.post {
            if (!container.isAttachedToWindow) return@post
            val children = (0 until container.childCount).map { container.getChildAt(it) }
            children.forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 12f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * 18L)
                    .setDuration(220L)
                    .start()
            }
        }
    }

    fun pulse(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.025f)
            .scaleY(1.025f)
            .setDuration(110L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .start()
            }
            .start()
    }
}
