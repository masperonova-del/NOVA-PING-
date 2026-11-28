package com.novaping.app

/**
 * A selected cellular network is mandatory while Mobile Data Lock is active.
 * Any loss of that network's required capabilities must stop the tunnel.
 */
internal object PreferredNetworkMonitorPolicy {
    fun shouldStop(
        isSelectedNetwork: Boolean,
        mobileLockEnabled: Boolean,
        tunnelEstablished: Boolean
    ): Boolean = isSelectedNetwork && mobileLockEnabled && tunnelEstablished
}
