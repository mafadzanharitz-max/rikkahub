package me.rerere.rikkahub.data.ai.mcp

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthNetworkPolicyTest {
    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun acceptsPublicIpv4AndIpv6Addresses() {
        assertTrue(McpOAuthNetworkPolicy.isPubliclyRoutable(address("8.8.8.8")))
        assertTrue(McpOAuthNetworkPolicy.isPubliclyRoutable(address("2606:4700:4700::1111")))
    }

    @Test
    fun rejectsPrivateReservedAndSpecialIpv4Ranges() {
        listOf(
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.0.8",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
        ).forEach { assertFalse("$it must not be routable", McpOAuthNetworkPolicy.isPubliclyRoutable(address(it))) }
    }

    @Test
    fun rejectsNonGlobalIpv6AndTransitionRanges() {
        listOf(
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "2001:2::1",
            "2001::1",
            "2002::1",
        ).forEach { assertFalse("$it must not be routable", McpOAuthNetworkPolicy.isPubliclyRoutable(address(it))) }
    }

    @Test
    fun loopbackHostnameOnlyAcceptsLoopbackAnswers() {
        McpOAuthNetworkPolicy.validateResolvedAddresses("localhost", listOf(address("127.0.0.1"), address("::1")))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            McpOAuthNetworkPolicy.validateResolvedAddresses("localhost", listOf(address("8.8.8.8")))
        }
    }

    @Test
    fun mixedDnsAnswersFailClosed() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            McpOAuthNetworkPolicy.validateResolvedAddresses(
                "metadata.example.com",
                listOf(address("8.8.8.8"), address("10.0.0.1")),
            )
        }
    }
}
