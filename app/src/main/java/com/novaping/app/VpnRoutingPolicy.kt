package com.novaping.app

/**
 * Mirrors Android's per-app VPN allowlist semantics.
 *
 * With a non-empty allowlist, only listed packages use the VPN. Sockets created
 * by this app do not need VpnService.protect() when this app is not on that list.
 */
internal object VpnRoutingPolicy {
    fun isPackageRoutedThroughVpn(packageName: String, allowedPackages: Set<String>): Boolean =
        allowedPackages.isEmpty() || packageName in allowedPackages

    fun requiresSocketProtection(packageName: String, allowedPackages: Set<String>): Boolean =
        isPackageRoutedThroughVpn(packageName, allowedPackages)
}
