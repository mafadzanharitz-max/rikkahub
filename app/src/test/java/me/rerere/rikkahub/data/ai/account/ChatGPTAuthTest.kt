package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.OAuthHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGPTAuthTest {
    @Test
    fun `OAuth token revocation client refuses redirects`() {
        val base = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val revocationClient = base.forOAuthTokenRevocation()

        assertFalse(revocationClient.followRedirects)
        assertFalse(revocationClient.followSslRedirects)
    }

    @Test
    fun `new registration uses dynamic client and SIWC authorization contract`() {
        val oauth = OAuthHttpClient(okhttp3.OkHttpClient())
        val request = ChatGPTAuth.authorizationRequest(
            pkce = OAuthHttpClient.Pkce("verifier", "challenge"),
            state = "state",
            nonce = "nonce",
            redirectUri = "http://127.0.0.1:1455/auth/callback",
            clientId = ChatGPTAuth.DYNAMIC_CLIENT_ID,
            hostId = "urn:uuid:123e4567-e89b-42d3-a456-426614174000",
            previous = null,
        )
        val url = oauth.buildAuthorizationUrl(request)
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertTrue(url.contains("client_id=dynamic_agent_client"))
        assertTrue(url.contains("agent_name_hint=RikkaHub%20ARMv7"))
        assertTrue(url.contains("ext_agent_host_id=urn%3Auuid%3A"))
        assertTrue(url.contains("nonce=nonce"))
        assertTrue(url.contains("chatgpt.tokens.use.direct"))
        assertFalse(url.contains("codex_cli"))
    }

    @Test
    fun `returning registration reuses issued client and sends identity hints`() {
        val oauth = OAuthHttpClient(okhttp3.OkHttpClient())
        val previous = OAuthCredential(
            accessToken = "access",
            oauthClientId = "oaiapp_issued",
            email = "person@example.com",
            idTokenHint = "signed-id-token",
        )
        val url = oauth.buildAuthorizationUrl(
            ChatGPTAuth.authorizationRequest(
                pkce = OAuthHttpClient.Pkce("v", "c"),
                state = "s",
                nonce = "n",
                redirectUri = "http://127.0.0.1:1455/auth/callback",
                clientId = previous.oauthClientId!!,
                hostId = "urn:uuid:123e4567-e89b-42d3-a456-426614174000",
                previous = previous,
                registration = ChatGPTRegistration(
                    clientId = "oaiapp_issued",
                    issuer = ChatGPTAuth.ISSUER,
                    subject = "subject",
                    email = "person@example.com",
                ),
            )
        )
        assertTrue(url.contains("client_id=oaiapp_issued"))
        assertTrue(url.contains("id_token_hint=signed-id-token"))
        assertTrue(url.contains("login_hint=person%40example.com"))
        assertFalse(url.contains("agent_name_hint"))
    }

    @Test
    fun `refresh request uses form encoding issued client and resource without scope`() {
        val request = ChatGPTAuth.refreshRequest("refresh", "oaiapp_issued")
        assertEquals(OAuthHttpClient.TokenEncoding.FORM, request.encoding)
        assertEquals("oaiapp_issued", request.clientId)
        assertEquals(ChatGPTAuth.TOKEN_ENDPOINT, request.tokenEndpoint)
        assertEquals(listOf(ChatGPTAuth.RESOURCE), request.resources)
        assertEquals(null, request.scope)
    }

    @Test
    fun `credential creation requires granted plan use scope and binds identity`() {
        val token = OAuthHttpClient.TokenResponse(
            accessToken = "access",
            refreshToken = "refresh",
            idToken = "id-token",
            expiresIn = 3600,
            scope = "openid profile offline_access resource.invoke chatgpt.tokens.use.direct",
        )
        val identity = ChatGPTAuth.VerifiedIdentity("subject-1", "person@example.com", "id-token")
        val credential = ChatGPTAuth.toCredential(token, identity, null, "oaiapp_issued", now = 1000L)
        assertEquals("oaiapp_issued", credential.oauthClientId)
        assertEquals("subject-1", credential.subject)
        assertEquals("https://auth.openai.com", credential.issuer)
        assertEquals(3_601_000L, credential.expiresAt)
        assertTrue("chatgpt.tokens.use.direct" in credential.grantedScopes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `credential creation rejects missing plan use scope`() {
        ChatGPTAuth.toCredential(
            OAuthHttpClient.TokenResponse(accessToken = "access", scope = "openid profile"),
            ChatGPTAuth.VerifiedIdentity("subject", null, "id"),
            null,
            "oaiapp_issued",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `credential creation rejects account subject switch`() {
        ChatGPTAuth.toCredential(
            OAuthHttpClient.TokenResponse(
                accessToken = "access",
                scope = "chatgpt.tokens.use.direct",
            ),
            ChatGPTAuth.VerifiedIdentity("new-subject", null, "id"),
            OAuthCredential(
                accessToken = "old",
                oauthClientId = "oaiapp_issued",
                subject = "old-subject",
            ),
            "oaiapp_issued",
        )
    }
    @Test
    fun `stale UI edits preserve signed out registration metadata`() {
        val registration = ChatGPTRegistration(
            clientId = "oaiapp_issued",
            issuer = ChatGPTAuth.ISSUER,
            subject = "verified-subject",
            email = "person@example.com",
        )
        val stored = ProviderSetting.ChatGPT(registration = registration)
        val edited = stored.copy(name = "Renamed", registration = null)
        val merged = edited.preserveCredential(stored) as ProviderSetting.ChatGPT

        assertEquals("Renamed", merged.name)
        assertEquals(registration, merged.registration)
    }

}
