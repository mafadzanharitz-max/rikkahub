package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.supportsQuota
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGPTProviderTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun siwcBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        sessionId: String? = "session-1",
    ) = api.buildRequestBody(SIWC_CAPABILITIES, messages, params.copy(sessionId = sessionId), stream = true)

    @Test
    fun `system prompt uses public Responses instructions field`() {
        val body = siwcBody(
            messages = listOf(UIMessage.system("Be brief."), UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )
        assertEquals("Be brief.", body["instructions"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `public inference streams without storage and drops unsupported parameters`() {
        val body = siwcBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-5.5"),
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 1024,
            ),
        )
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertFalse("max_output_tokens" in body)
        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
        assertFalse("prompt_cache_key" in body)
        assertFalse("include" in body)
    }

    @Test
    fun `SIWC profile omits prompt cache and encrypted reasoning payloads`() {
        val body = siwcBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-5.5", abilities = listOf(ModelAbility.REASONING)),
            ),
            sessionId = "conversation-42",
        )
        assertNull(body["prompt_cache_key"])
        assertNull(body["include"])
    }

    @Test
    fun `parses only explicitly visible public SIWC models`() {
        val models = parseSiwcModels(
            """
            {
              "models": [
                {"slug":"gpt-5.5","display_name":"GPT-5.5","visibility":"list",
                 "supported_reasoning_levels":[{"effort":"low"}]},
                {"slug":"gpt-5.5-mini","display_name":"GPT-5.5 mini","visibility":"list"},
                {"slug":"internal-model","display_name":"Internal","visibility":"hide"}
              ]
            }
            """.trimIndent()
        )
        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), models.map { it.modelId })
        assertEquals("GPT-5.5", models.first().displayName)
        assertEquals(listOf(ModelAbility.TOOL), models.first().abilities)
    }

    @Test
    fun `model parser does not fall back to hidden models`() {
        assertTrue(
            parseSiwcModels("""{"models":[{"slug":"hidden","visibility":"hide"}]}""").isEmpty()
        )
    }

    @Test
    fun `ChatGPT registration survives local sign out and serialization`() {
        val credential = OAuthCredential(
            accessToken = "access",
            refreshToken = "refresh",
            oauthClientId = "oaiapp_issued",
            issuer = "https://auth.openai.com",
            subject = "verified-subject",
            email = "user@example.com",
        )
        val connected = ProviderSetting.ChatGPT().withCredential(credential) as ProviderSetting.ChatGPT
        val signedOut = connected.withCredential(null) as ProviderSetting.ChatGPT
        assertNull(signedOut.credential)
        assertEquals("oaiapp_issued", signedOut.registration?.clientId)
        assertEquals("verified-subject", signedOut.registration?.subject)

        val encoded = json.encodeToString<List<ProviderSetting>>(listOf(signedOut))
        val restored = json.decodeFromString<List<ProviderSetting>>(encoded).single() as ProviderSetting.ChatGPT
        assertEquals(signedOut.registration, restored.registration)
    }

    @Test
    fun `ChatGPT credential and registration are masked and quota is unavailable`() {
        val credential = OAuthCredential(accessToken = "access-token-secret", refreshToken = "refresh-token-secret")
        assertFalse(credential.toString().contains("access-token-secret"))
        assertFalse(credential.toString().contains("refresh-token-secret"))
        assertFalse(ProviderSetting.ChatGPT(credential = credential).supportsQuota)
    }

    @Test
    fun `built in search support by provider type`() {
        assertTrue(ProviderSetting.ChatGPT().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Google().supportsBuiltInSearch)
        assertTrue(ProviderSetting.Claude().supportsBuiltInSearch)
        assertTrue(ProviderSetting.OpenAI(useResponseApi = true).supportsBuiltInSearch)
        assertFalse(ProviderSetting.OpenAI(useResponseApi = false).supportsBuiltInSearch)
    }
}
