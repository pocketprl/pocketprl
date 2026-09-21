package dev.pocketprl.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptPolicyTest {
    @Test
    fun freeAttemptsHaveNoLockout() {
        for (n in 0..AttemptPolicy.FREE_ATTEMPTS) assertEquals(0L, AttemptPolicy.lockoutMs(n))
    }

    @Test
    fun lockoutDoublesThenCaps() {
        assertEquals(5_000L, AttemptPolicy.lockoutMs(4))
        assertEquals(10_000L, AttemptPolicy.lockoutMs(5))
        assertEquals(20_000L, AttemptPolicy.lockoutMs(6))
        // A hostile number of guesses cannot wrap the shift into a negative lockout.
        assertEquals(AttemptPolicy.MAX_LOCKOUT_MS, AttemptPolicy.lockoutMs(1000))
        assertTrue(AttemptPolicy.lockoutMs(1000) > 0)
    }

    @Test
    fun wipeFiresOnTheTenthFailureOnlyWhenEnabled() {
        assertFalse(AttemptPolicy.isWipeThreshold(8, 10))
        assertTrue(AttemptPolicy.isWipeThreshold(9, 10))
        assertFalse(AttemptPolicy.isWipeThreshold(9, null))
    }

    @Test
    fun remainingCountsDownAndWarnsOnTheLastThree() {
        assertEquals(3, AttemptPolicy.remainingBeforeWipe(7, 10))
        assertEquals(2, AttemptPolicy.remainingBeforeWipe(8, 10))
        assertEquals(1, AttemptPolicy.remainingBeforeWipe(9, 10))
        assertNull(AttemptPolicy.remainingBeforeWipe(7, null))
        assertTrue(AttemptPolicy.shouldWarn(AttemptPolicy.WARN_WHEN_REMAINING))
        assertFalse(AttemptPolicy.shouldWarn(AttemptPolicy.WARN_WHEN_REMAINING + 1))
        assertFalse(AttemptPolicy.shouldWarn(null))
    }
}
