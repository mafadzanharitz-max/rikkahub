package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertThrows
import org.junit.Test

class McpOAuthIssuerValidationTest {
    private val issuer = "https://auth.example.com"

    @Test
    fun matchingResponseIssuerIsAccepted() {
        validateMcpOAuthResponseIssuer(issuer, issuer, issuerParameterSupported = true)
    }

    @Test
    fun missingIssuerIsAcceptedWhenNotAdvertised() {
        validateMcpOAuthResponseIssuer(issuer, null, issuerParameterSupported = false)
    }

    @Test
    fun mismatchedResponseIssuerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMcpOAuthResponseIssuer(
                issuer,
                "https://attacker.example.net",
                issuerParameterSupported = false,
            )
        }
    }

    @Test
    fun missingIssuerIsRejectedWhenAdvertised() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMcpOAuthResponseIssuer(issuer, null, issuerParameterSupported = true)
        }
    }

    @Test
    fun blankResponseIssuerIsRejectedWhenPresent() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMcpOAuthResponseIssuer(issuer, "", issuerParameterSupported = false)
        }
    }
}
