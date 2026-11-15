package com.novaping.app

/**
 * Strict validation for package names passed from the game picker to the VPN
 * adapter. This validates syntax only; Android PackageManager verifies install
 * state at the point of use.
 */
internal object VpnGameSelection {
    private val packageNamePattern =
        Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    internal fun validatePackageName(value: String): String {
        val candidate = value.trim()
        require(candidate.isNotEmpty() && packageNamePattern.matches(candidate)) {
            "Invalid Android application package name"
        }
        return candidate
    }
}
