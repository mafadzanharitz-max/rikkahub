package me.rerere.rikkahub.data.ai.account

import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginAttemptTrackerTest {
    @Test
    fun `new login supersedes previous login for same provider`() {
        val tracker = AccountLoginAttemptTracker()
        val providerId = Uuid.random()

        val first = tracker.begin(providerId)
        val second = tracker.begin(providerId)

        assertFalse(tracker.isCurrent(providerId, first))
        assertTrue(tracker.isCurrent(providerId, second))
    }

    @Test
    fun `cancellation invalidates pending login result`() {
        val tracker = AccountLoginAttemptTracker()
        val providerId = Uuid.random()
        val attempt = tracker.begin(providerId)

        tracker.invalidate(providerId)

        assertFalse(tracker.isCurrent(providerId, attempt))
    }

    @Test
    fun `login attempts are isolated per provider`() {
        val tracker = AccountLoginAttemptTracker()
        val firstProvider = Uuid.random()
        val secondProvider = Uuid.random()
        val first = tracker.begin(firstProvider)
        val second = tracker.begin(secondProvider)

        tracker.invalidate(firstProvider)

        assertFalse(tracker.isCurrent(firstProvider, first))
        assertTrue(tracker.isCurrent(secondProvider, second))
    }
    @Test
    fun `new login supersedes a pending logout mutation`() {
        val tracker = AccountLoginAttemptTracker()
        val providerId = Uuid.random()

        val logoutAttempt = tracker.invalidate(providerId)
        val loginAttempt = tracker.begin(providerId)

        assertFalse(tracker.isCurrent(providerId, logoutAttempt))
        assertTrue(tracker.isCurrent(providerId, loginAttempt))
    }

}
