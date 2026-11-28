package com.novaping.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketProtectionGuardTest {
    @Test
    fun doesNotCallProtectWhenSocketPreconditionIsInvalid() {
        var protectCalled = false

        val result = SocketProtectionGuard.evaluate(invalidPrecondition = true) {
            protectCalled = true
            true
        }

        assertEquals("invalid_socket", result)
        assertFalse(protectCalled)
    }

    @Test
    fun returnsProtectedWhenAndroidAcceptsSocket() {
        var protectCalled = false

        val result = SocketProtectionGuard.evaluate(invalidPrecondition = false) {
            protectCalled = true
            true
        }

        assertEquals("protected", result)
        assertTrue(protectCalled)
    }

    @Test
    fun preservesAndroidProtectionRejection() {
        val result = SocketProtectionGuard.evaluate(invalidPrecondition = false) {
            false
        }

        assertEquals("rejected", result)
    }

    @Test
    fun classifiesProtectionExceptionsWithoutFailingOpen() {
        val security = SocketProtectionGuard.evaluate(invalidPrecondition = false) {
            throw SecurityException("denied")
        }
        val invalid = SocketProtectionGuard.evaluate(invalidPrecondition = false) {
            throw IllegalArgumentException("bad socket")
        }
        val generic = SocketProtectionGuard.evaluate(invalidPrecondition = false) {
            throw IllegalStateException("unexpected")
        }

        assertEquals("security_exception", security)
        assertEquals("invalid_socket", invalid)
        assertEquals("exception", generic)
    }
}
