package com.novaping.app

import android.content.Context
import com.wireguard.android.backend.GoBackend

/**
 * Small integration boundary around the maintained WireGuard Android backend.
 *
 * Creating the backend loads its native engine. This does not start a VPN,
 * request VPN consent, or route any application traffic.
 */
internal object WireGuardEngine {
    fun detectVersion(context: Context): String {
        return GoBackend(context.applicationContext).getVersion()
    }
}
