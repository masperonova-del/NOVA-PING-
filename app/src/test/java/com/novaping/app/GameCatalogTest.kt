package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCatalogTest {
    @Test fun exposesKnownGameProfilesWithoutInventingServerTargets() {
        assertEquals(4, GameCatalog.profiles.size)
        assertTrue(GameCatalog.profiles.all { it.serverEvidence == ServerEvidence.NOT_VERIFIED })
        assertTrue(GameCatalog.findByDisplayName("Call of Duty: Mobile").note.contains("pas le ping"))
    }
}
