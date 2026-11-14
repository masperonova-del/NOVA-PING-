package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnGameSelectionTest {
    @Test fun acceptsValidAndroidPackageNames() {
        assertEquals("com.activision.callofduty.shooter",
            VpnGameSelection.validatePackageName("com.activision.callofduty.shooter"))
        assertEquals("org.example_game.app",
            VpnGameSelection.validatePackageName(" org.example_game.app "))
    }

    @Test fun rejectsBlankMalformedAndSingleSegmentNames() {
        listOf("", "   ", "com..game", ".com.game", "com.game.", "com game", "game")
            .forEach { candidate ->
                assertThrows(IllegalArgumentException::class.java) {
                    VpnGameSelection.validatePackageName(candidate)
                }
            }
    }
}
