package me.rerere.rikkahub.data.ai.mcp

import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthAttemptTrackerTest {
    @Test
    fun `new attempt supersedes the previous attempt for the same server`() {
        val tracker = McpOAuthAttemptTracker()
        val serverId = Uuid.random()

        val first = tracker.begin(serverId)
        val second = tracker.begin(serverId)

        assertFalse(tracker.isCurrent(serverId, first))
        assertTrue(tracker.isCurrent(serverId, second))
    }

    @Test
    fun `invalidating an attempt makes it stale`() {
        val tracker = McpOAuthAttemptTracker()
        val serverId = Uuid.random()
        val attempt = tracker.begin(serverId)

        tracker.invalidate(serverId)

        assertFalse(tracker.isCurrent(serverId, attempt))
    }

    @Test
    fun `attempt generations are isolated by server id`() {
        val tracker = McpOAuthAttemptTracker()
        val firstServer = Uuid.random()
        val secondServer = Uuid.random()

        val firstAttempt = tracker.begin(firstServer)
        val secondAttempt = tracker.begin(secondServer)
        tracker.invalidate(firstServer)

        assertFalse(tracker.isCurrent(firstServer, firstAttempt))
        assertTrue(tracker.isCurrent(secondServer, secondAttempt))
    }
}
