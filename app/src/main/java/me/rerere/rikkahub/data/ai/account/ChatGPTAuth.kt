package me.rerere.rikkahub.data.ai.account

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** OpenAI Sign in with ChatGPT protocol. */
internal object ChatGPTAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZATION_ENDPOINT = "$ISSUER/api/accounts/authorize"
    const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
    const val JWKS_ENDPOINT = "$ISSUER/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val AGENT_NAME = "RikkaHub ARMv7"
    const val CALLBACK_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val CALLBACK_HOST = "127.0.0.1"
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    data class VerifiedIdentity(val subject: String, val email: String?, val idToken: String)

    private val hostIdLock = Any()

    /** Keep the installation identifier outside backup-eligible app data. */
    fun getOrCreateHostId(context: Context): String = synchronized(hostIdLock) {
        val file = File(context.noBackupFilesDir, "siwc-ext-agent-host-id")
        if (file.isFile) {
            val stored = file.readText().trim()
            if (stored.matches(Regex("urn:uuid:[0-9a-fA-F-]{36}"))) return stored
        }
        file.parentFile?.mkdirs()
        val value = "urn:uuid:${java.util.UUID.randomUUID()}"
        val temporary = File(file.parentFile, file.name + ".tmp")
        file.delete()
        temporary.writeText(value)
        check(temporary.renameTo(file)) { "Unable to persist the SIWC installation identity" }
        value
    }

    fun authorizationRequest(
        pkce: OAuthHttpClient.Pkce,
        state: String,
        nonce: String,
        redirectUri: String,
        clientId: String,
        hostId: String,
        previous: OAuthCredential?,
        registration: ChatGPTRegistration? = null,
    ) = OAuthHttpClient.AuthorizationRequest(
        authorizationEndpoint = AUTHORIZATION_ENDPOINT,
        clientId = clientId,
        redirectUri = redirectUri,
        pkce = pkce,
        state = state,
        nonce = nonce,
        scope = SCOPES,
        resources = listOf(RESOURCE),
        additionalParameters = buildMap {
            put("ext_agent_host_id", hostId)
            if (registration == null) put("agent_name_hint", AGENT_NAME)
            else {
                previous?.idTokenHint?.let { put("id_token_hint", it) }
                (registration.email ?: previous?.email)?.let { put("login_hint", it) }
            }
        },
    )

    fun refreshRequest(refreshToken: String, clientId: String) =
        OAuthHttpClient.RefreshTokenRequest(
            tokenEndpoint = TOKEN_ENDPOINT,
            clientId = clientId,
            refreshToken = refreshToken,
            resources = listOf(RESOURCE),
            encoding = OAuthHttpClient.TokenEncoding.FORM,
        )

    suspend fun verifyIdToken(
        http: OkHttpClient,
        idToken: String,
        expectedClientId: String,
        expectedNonce: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): VerifiedIdentity {
        val parts = idToken.split('.')
        require(parts.size == 3) { "Invalid SIWC identity token" }
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(String(decoder.decode(parts[0]))).jsonObject
        require(header["alg"]?.jsonPrimitive?.contentOrNull == "RS256") {
            "Unsupported SIWC identity-token algorithm"
        }
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull
            ?: error("SIWC identity token has no key ID")
        val jwksRequest = Request.Builder().url(JWKS_ENDPOINT).get().build()
        val keys = http.newCall(jwksRequest).await().use { response ->
            if (!response.isSuccessful) error("Unable to retrieve SIWC signing keys (HTTP ${response.code})")
            Json.parseToJsonElement(response.body.string()).jsonObject["keys"]?.jsonArray
                ?: error("Invalid SIWC signing-key document")
        }
        val jwk = keys.firstOrNull { it.jsonObject["kid"]?.jsonPrimitive?.contentOrNull == kid }
            ?.jsonObject ?: error("Unknown SIWC identity-token signing key")
        require(jwk["kty"]?.jsonPrimitive?.contentOrNull == "RSA") { "Invalid SIWC signing-key type" }
        val modulus = decoder.decode(jwk["n"]!!.jsonPrimitive.content)
        val exponent = decoder.decode(jwk["e"]!!.jsonPrimitive.content)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, modulus), BigInteger(1, exponent))
        )
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decoder.decode(parts[2]))) { "SIWC identity-token signature is invalid" }

        val claims = Json.parseToJsonElement(String(decoder.decode(parts[1]))).jsonObject
        require(claims["iss"]?.jsonPrimitive?.contentOrNull == ISSUER) {
            "SIWC identity-token issuer mismatch"
        }
        val audienceMatches = when (val aud = claims["aud"]) {
            is kotlinx.serialization.json.JsonPrimitive -> aud.contentOrNull == expectedClientId
            is JsonArray -> aud.any { it.jsonPrimitive.contentOrNull == expectedClientId }
            else -> false
        }
        require(audienceMatches) { "SIWC identity-token audience mismatch" }
        if (claims["aud"] is JsonArray && (claims["aud"] as JsonArray).size > 1) {
            require(claims["azp"]?.jsonPrimitive?.contentOrNull == expectedClientId) {
                "SIWC identity-token authorized party mismatch"
            }
        }
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0L) > nowSeconds) {
            "SIWC identity token has expired"
        }
        require(claims["nonce"]?.jsonPrimitive?.contentOrNull == expectedNonce) {
            "SIWC identity-token nonce mismatch"
        }
        val subject = claims["sub"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: error("SIWC identity token has no subject")
        val email = claims["email"]?.jsonPrimitive?.contentOrNull
        return VerifiedIdentity(subject, email, idToken)
    }

    fun toCredential(
        token: OAuthHttpClient.TokenResponse,
        identity: VerifiedIdentity,
        previous: OAuthCredential?,
        clientId: String,
        registration: ChatGPTRegistration? = null,
        now: Long = System.currentTimeMillis(),
    ): OAuthCredential {
        val scopes = token.scope?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.distinct().orEmpty()
        require(REQUIRED_SCOPE in scopes) { "ChatGPT did not grant $REQUIRED_SCOPE" }
        require(previous?.subject == null || previous.subject == identity.subject) {
            "The authorized ChatGPT account does not match the selected registration"
        }
        require(registration?.subject == null || registration.subject == identity.subject) {
            "The authorized ChatGPT account does not match the saved registration"
        }
        require(previous?.oauthClientId == null || previous.oauthClientId == clientId) {
            "The SIWC client registration does not match the selected account"
        }
        return OAuthCredential(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = token.expiresIn?.let { now + it * 1000L } ?: previous?.expiresAt ?: 0L,
            accountId = null,
            email = identity.email ?: previous?.email,
            plan = null,
            oauthClientId = clientId,
            issuer = ISSUER,
            subject = identity.subject,
            idTokenHint = token.idToken ?: identity.idToken,
            grantedScopes = scopes,
        )
    }

    fun refreshedCredential(
        token: OAuthHttpClient.TokenResponse,
        previous: OAuthCredential,
        now: Long = System.currentTimeMillis(),
    ): OAuthCredential {
        val scopes = token.scope?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.distinct()
            ?: previous.grantedScopes
        require(REQUIRED_SCOPE in scopes) { "ChatGPT refresh did not retain plan-use permission" }
        return previous.copy(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous.refreshToken,
            expiresAt = token.expiresIn?.let { now + it * 1000L } ?: previous.expiresAt,
            grantedScopes = scopes,
        )
    }

    suspend fun revokeRefreshToken(http: OkHttpClient, refreshToken: String, clientId: String): Boolean {
        val discoveryRequest = Request.Builder().url(DISCOVERY_ENDPOINT).get().build()
        val endpoint = http.newCall(discoveryRequest).await().use { response ->
            if (!response.isSuccessful) return false
            val document = Json.parseToJsonElement(response.body.string()).jsonObject
            require(document["issuer"]?.jsonPrimitive?.contentOrNull == ISSUER) {
                "Unexpected SIWC discovery issuer"
            }
            val value = document["revocation_endpoint"]?.jsonPrimitive?.contentOrNull ?: return false
            require(value.startsWith("$ISSUER/")) { "Unexpected SIWC revocation endpoint" }
            value
        }
        val body = FormBody.Builder()
            .add("token", refreshToken)
            .add("token_type_hint", "refresh_token")
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(endpoint).post(body).build()
        var attempt = 0
        while (attempt < 3) {
            val code = try {
                http.newCall(request).await().use { it.code }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                attempt++
                if (attempt >= 3) return false
                delay(250L * (1 shl attempt))
                continue
            }
            if (code == 200) return true
            if (code !in 500..599) return false
            attempt++
            if (attempt < 3) delay(250L shl attempt)
        }
        return false
    }
    fun planDisplayName(plan: String): String = when (plan.lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "pro" -> "Pro (More)"
        "prolite" -> "Pro"
        "promax" -> "Pro (Max)"
        "team" -> "Team"
        "self_serve_business_prolite" -> "Self Serve Business ProLite"
        "self_serve_business_usage_based" -> "Self Serve Business Usage Based"
        "business" -> "Business"
        "ent26", "enterprise", "hc" -> "Enterprise"
        "enterprise_cbp_automation" -> "Enterprise (Automation)"
        "enterprise_cbp_usage_based" -> "Enterprise CBP Usage Based"
        "edu", "education" -> "Edu"
        "edu_plus" -> "Edu Plus"
        "edu_pro" -> "Edu Pro"
        else -> plan.split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

}
