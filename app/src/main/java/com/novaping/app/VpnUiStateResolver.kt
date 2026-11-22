package com.novaping.app

/**
 * Reconciles the last persisted NOVA PING state with Android's current VPN
 * transport state. A persisted "starting" state is stale after process death
 * unless Android still reports an active VPN transport.
 */
internal object VpnUiStateResolver {
    fun resolve(savedState: String?, vpnActive: Boolean): String = when {
        savedState == "replaced" && vpnActive -> "replaced"
        savedState == "replaced" && !vpnActive -> "stopped"
        savedState == "stopped" && vpnActive -> "other_vpn"
        savedState == null && vpnActive -> "other_vpn"
        savedState == "starting" && !vpnActive -> "stopped"
        savedState == "ready" && !vpnActive -> "stopped"
        savedState == "ready" -> "ready"
        savedState == "starting" -> "starting"
        savedState == null -> "stopped"
        else -> savedState
    }
}
