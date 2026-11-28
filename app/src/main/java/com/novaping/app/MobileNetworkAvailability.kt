package com.novaping.app

/**
 * A selected cellular upstream is usable only while Android reports both
 * cellular transport and Internet capability. Validation is deliberately
 * not required because it can fluctuate independently during recovery.
 */
internal object MobileNetworkAvailability {
    fun isUsable(
        hasCellularTransport: Boolean,
        hasInternetCapability: Boolean
    ): Boolean = hasCellularTransport && hasInternetCapability
}
