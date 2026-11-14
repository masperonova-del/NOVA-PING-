package com.novaping.app

import android.net.VpnService
import java.net.DatagramSocket
import java.net.Socket

/**
 * Android VPN platform adapter.
 *
 * This class deliberately does not establish a TUN interface. The forwarding
 * engine and non-blocking packet-observation tap must be integrated and tested
 * before any traffic is routed through NOVA PING.
 */
internal class NovaVpnService : VpnService() {

    /**
     * Creates a per-app VPN builder for one explicitly selected, installed game.
     * The caller must obtain system consent with VpnService.prepare() before
     * attempting to establish the returned builder.
     */
    internal fun createGameTunnelBuilder(gamePackageName: String): Builder {
        require(gamePackageName.isNotBlank()) { "Game package name is required" }
        require(gamePackageName != packageName) { "NOVA PING cannot select itself as the game app" }
        // Fail early if the selected package is not installed.
        packageManager.getApplicationInfo(gamePackageName, 0)

        return Builder()
            .setSession("NOVA PING — diagnostic réseau")
            .addAllowedApplication(gamePackageName)
    }

    /** Must be called before connecting an upstream TCP socket. */
    internal fun protectUpstreamSocket(socket: Socket): Boolean = protect(socket)

    /** Must be called before connecting or sending through an upstream UDP socket. */
    internal fun protectUpstreamDatagramSocket(socket: DatagramSocket): Boolean = protect(socket)
}
