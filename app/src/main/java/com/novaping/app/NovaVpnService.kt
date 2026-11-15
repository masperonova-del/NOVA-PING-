package com.novaping.app

import android.content.Context
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
     * Creates a per-app VPN builder using the game package selected and saved
     * after the Android consent flow. This method is preparation only:
     * callers must still implement the complete forwarding lifecycle before
     * invoking Builder.establish().
     */
    internal fun createGameTunnelBuilderFromSelection(): Builder {
        check(VpnService.prepare(this) == null) {
            "Android VPN consent has not been granted"
        }
        val selectedPackage = readSelectedGamePackage(this)
            ?: throw IllegalStateException("No valid game package has been selected")
        return createGameTunnelBuilder(selectedPackage)
    }

    /**
     * Creates a per-app VPN builder for one explicitly selected, installed game.
     * This method does not establish the TUN interface.
     */
    internal fun createGameTunnelBuilder(gamePackageName: String): Builder {
        val validPackage = VpnGameSelection.validatePackageName(gamePackageName)
        require(validPackage != packageName) { "NOVA PING cannot select itself as the game app" }
        packageManager.getApplicationInfo(validPackage, 0)

        return Builder()
            .setSession("NOVA PING — diagnostic réseau")
            .addAllowedApplication(validPackage)
    }

    /** Must be called before connecting an upstream TCP socket. */
    internal fun protectUpstreamSocket(socket: Socket): Boolean = protect(socket)

    /** Must be called before connecting or sending through an upstream UDP socket. */
    internal fun protectUpstreamDatagramSocket(socket: DatagramSocket): Boolean = protect(socket)

    companion object {
        private const val VPN_PREFERENCES = "nova_ping_vpn"
        private const val KEY_SELECTED_GAME_PACKAGE = "selected_game_package"

        /**
         * Persists the explicit game selection only after Android consent.
         * The package is validated again when the service prepares its builder.
         */
        internal fun saveSelectedGamePackage(context: Context, packageName: String) {
            val validPackage = VpnGameSelection.validatePackageName(packageName)
            require(validPackage != context.packageName) {
                "NOVA PING cannot select itself as the game app"
            }
            context.packageManager.getApplicationInfo(validPackage, 0)
            context.getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_GAME_PACKAGE, validPackage)
                .apply()
        }

        internal fun readSelectedGamePackage(context: Context): String? {
            val saved = context.getSharedPreferences(VPN_PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_GAME_PACKAGE, null)
                ?: return null
            return try {
                val validPackage = VpnGameSelection.validatePackageName(saved)
                if (validPackage == context.packageName) return null
                context.packageManager.getApplicationInfo(validPackage, 0)
                validPackage
            } catch (_: Exception) {
                null
            }
        }
    }
}
