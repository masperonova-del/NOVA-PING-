package com.novaping.app

/** User-selected Wi-Fi preference mode. Android retains final network-selection authority. */
enum class WifiLockMode {
    MANUAL,
    SMART;

    companion object {
        fun fromPreference(value: String?): WifiLockMode =
            if (value == "smart") SMART else MANUAL
    }
}

/** Snapshot of the selected network's current status; it does not force Android routing. */
data class WifiLockSnapshot(
    val preferredSsid: String?,
    val activeSsid: String?,
    val wifiAvailable: Boolean,
    val internetValidated: Boolean
) {
    fun preferredNetworkIsActive(): Boolean =
        !preferredSsid.isNullOrBlank() && preferredSsid == activeSsid

    fun preferredNetworkIsUsable(): Boolean =
        preferredNetworkIsActive() && wifiAvailable && internetValidated
}
