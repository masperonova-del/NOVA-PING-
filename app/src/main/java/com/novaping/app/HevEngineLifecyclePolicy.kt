package com.novaping.app

/**
 * Pure lifecycle decisions for the native HEV engine.
 * A clean native return is only expected after NOVA PING requested a stop.
 */
internal object HevEngineExitPolicy {
    fun unexpectedExitMessage(exitCode: Int, stopRequested: Boolean): String? {
        if (stopRequested) return null
        return if (exitCode == 0) {
            "Le moteur HEV s'est arrêté de manière inattendue."
        } else {
            "Le moteur HEV s'est arrêté avec le code $exitCode."
        }
    }
}

internal object HevReadinessPolicy {
    fun hasTimedOut(
        ready: Boolean,
        engineAlive: Boolean,
        stopRequested: Boolean,
        elapsedMs: Long,
        timeoutMs: Long
    ): Boolean {
        if (ready || !engineAlive || stopRequested || timeoutMs <= 0L) return false
        return elapsedMs >= timeoutMs
    }
}
