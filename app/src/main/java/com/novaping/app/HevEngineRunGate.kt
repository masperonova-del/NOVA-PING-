package com.novaping.app

import java.util.concurrent.atomic.AtomicBoolean

/**
 * HEV's native tunnel state is process-global, so only one engine run may
 * own the JNI lifecycle at a time, including while a previous run is stopping.
 */
internal class HevEngineRunGate {
    private val reserved = AtomicBoolean(false)

    fun tryReserve(): Boolean = reserved.compareAndSet(false, true)

    fun isReserved(): Boolean = reserved.get()

    fun release() {
        reserved.set(false)
    }
}
