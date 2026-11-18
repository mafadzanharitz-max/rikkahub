package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertThrows
import org.junit.Test

class McpEndpointSecurityTest {
    @Test
    fun remoteHttpsEndpointsAreAccepted() {
        requireSecureMcpEndpoint("https://mcp.example.com/sse")
        requireSecureMcpEndpoint("https://mcp.example.com:8443/mcp")
    }

    @Test
    fun loopbackHttpEndpointsAreAcceptedForLocalDevelopment() {
        requireSecureMcpEndpoint("http://localhost:8080/sse")
        requireSecureMcpEndpoint("http://127.0.0.1:8080/mcp")
        requireSecureMcpEndpoint("http://[::1]:8080/mcp")
    }

    @Test
    fun remoteHttpEndpointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSecureMcpEndpoint("http://mcp.example.com/sse")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSecureMcpEndpoint("http://192.168.1.20:8080/mcp")
        }
    }

    @Test
    fun credentialsInEndpointUrlAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireSecureMcpEndpoint("https://user:secret@mcp.example.com/sse")
        }
    }
}
