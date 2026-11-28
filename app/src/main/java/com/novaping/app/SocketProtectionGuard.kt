package com.novaping.app

/**
 * Enforces Android's protect(socket) precondition: the socket must be open
 * and not yet connected when protection is requested.
 */
internal object SocketProtectionGuard {
    fun evaluate(
        invalidPrecondition: Boolean,
        protectCall: () -> Boolean
    ): String {
        if (invalidPrecondition) return "invalid_socket"

        return try {
            if (protectCall()) "protected" else "rejected"
        } catch (_: SecurityException) {
            "security_exception"
        } catch (_: IllegalArgumentException) {
            "invalid_socket"
        } catch (_: Exception) {
            "exception"
        }
    }
}
