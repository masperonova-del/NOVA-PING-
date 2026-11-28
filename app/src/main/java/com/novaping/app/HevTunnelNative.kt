package com.novaping.app

/**
 * Direct JNI lifecycle boundary for the pinned HEV engine.
 *
 * [nativeStart] is blocking and MUST run on a dedicated background thread.
 * [nativeStop] may be called from another thread to request an orderly stop.
 * This object does not create a TUN interface or request Android VPN consent.
 */
internal object HevTunnelNative {
    private const val LIBRARY_NAME = "hev-socks5-tunnel"

    @Volatile
    private var loaded = false

    @Volatile
    private var loadAttempted = false

    // HEV keeps process-global native state. A service recreation must not
    // enter nativeStart while an earlier engine thread is still unwinding.
    private val engineRunGate = HevEngineRunGate()

    @Synchronized
    fun isAvailable(): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        loadAttempted = true
        loaded = try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
        return loaded
    }

    /** Blocks until HEV exits or [requestStop] is called from another thread. */
    fun runBlocking(config: String, tunFileDescriptor: Int): Int {
        require(config.isNotBlank()) { "HEV configuration must not be empty" }
        require(tunFileDescriptor >= 0) { "Invalid TUN file descriptor" }
        check(isAvailable()) { "HEV native library is unavailable" }
        check(engineRunGate.isReserved()) {
            "HEV engine run was not reserved by the VPN service"
        }
        return try {
            nativeStart(config, tunFileDescriptor)
        } finally {
            engineRunGate.release()
        }
    }

    /** Reserves the process-wide native engine before its worker thread starts. */
    fun reserveEngineRun(): Boolean = engineRunGate.tryReserve()

    /** Releases a reservation when thread startup fails or the worker exits. */
    fun releaseEngineRun() {
        engineRunGate.release()
    }

    /** True only after HEV has entered its native event loop. */
    fun isEngineReady(): Boolean = isAvailable() && nativeIsInitialized()

    /** Requests the native event loop to stop; safe to call during service shutdown. */
    fun requestStop() {
        if (isAvailable()) nativeStop()
    }

    private external fun nativeStart(config: String, tunFileDescriptor: Int): Int
    private external fun nativeStop()
    private external fun nativeIsInitialized(): Boolean
}

/** Builds the minimal HEV YAML configuration for NOVA PING's loopback bridge. */
internal object HevTunnelConfig {
    fun create(port: Int, credentials: Socks5BridgeCredentials): String {
        require(port in 1..65_535)
        require(credentials.username.matches(Regex("[a-f0-9]{48}")))
        require(credentials.password.matches(Regex("[a-f0-9]{48}")))

        return """
            tunnel:
              name: tun0
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'off'
            socks5:
              address: 127.0.0.1
              port: $port
              udp: 'udp'
              username: '${credentials.username}'
              password: '${credentials.password}'
            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
        """.trimIndent()
    }
}
