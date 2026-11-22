package com.novaping.app

/**
 * Pure decision policy for Wi-Fi preference handling.
 *
 * This class never changes Android connectivity itself. Its decisions must be
 * translated into Android-approved user/system actions by the platform layer.
 */
internal enum class WifiLockMode {
    MANUAL,
    SMART
}

internal enum class WifiLockDecision {
    KEEP_CURRENT,
    REQUEST_PREFERRED_NETWORK,
    KEEP_TEMPORARY_NETWORK,
    REQUEST_TEMPORARY_ALTERNATIVE,
    WAIT_FOR_PREFERRED_NETWORK,
    WAIT_FOR_USABLE_NETWORK,
    CONFIGURE_PREFERRED_NETWORK
}

internal data class WifiLockSnapshot(
    val preferredSsid: String?,
    val currentSsid: String?,
    val preferredAvailable: Boolean,
    val preferredInternetValidated: Boolean,
    val currentInternetValidated: Boolean,
    val alternativeAvailable: Boolean
)

internal object WifiLockPolicy {
    fun decide(mode: WifiLockMode, snapshot: WifiLockSnapshot): WifiLockDecision {
        val preferred = snapshot.preferredSsid
            ?.takeIf { it.isNotBlank() }
            ?: return WifiLockDecision.CONFIGURE_PREFERRED_NETWORK

        val currentIsPreferred = snapshot.currentSsid == preferred

        return when (mode) {
            WifiLockMode.MANUAL -> when {
                currentIsPreferred && snapshot.preferredAvailable ->
                    WifiLockDecision.KEEP_CURRENT
                snapshot.preferredAvailable ->
                    WifiLockDecision.REQUEST_PREFERRED_NETWORK
                else ->
                    WifiLockDecision.WAIT_FOR_PREFERRED_NETWORK
            }

            WifiLockMode.SMART -> when {
                snapshot.preferredAvailable && snapshot.preferredInternetValidated -> {
                    if (currentIsPreferred && snapshot.currentInternetValidated) {
                        WifiLockDecision.KEEP_CURRENT
                    } else {
                        WifiLockDecision.REQUEST_PREFERRED_NETWORK
                    }
                }

                !currentIsPreferred && snapshot.currentInternetValidated ->
                    WifiLockDecision.KEEP_TEMPORARY_NETWORK

                snapshot.alternativeAvailable ->
                    WifiLockDecision.REQUEST_TEMPORARY_ALTERNATIVE

                else ->
                    WifiLockDecision.WAIT_FOR_USABLE_NETWORK
            }
        }
    }
}
