package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.common.http.stringOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val OPENAI_API_BASE = "https://api.openai.com/v1"

/** Public API capability profile: no Codex-only fields, tools, or backend endpoints. */
internal val SIWC_CAPABILITIES = ResponseProviderCapabilities(
    supportsReasoningSummary = false,
    supportEncryptedContent = false,
    supportsSamplingParams = false,
    supportsCustomBody = false,
    supportsPromptCacheKey = false,
)

class ChatGPTProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials)
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> {
        val request = Request.Builder()
            .url("$OPENAI_API_BASE/models")
            .apply { configureAuth(providerSetting) }
            .get()
            .build()
        return parseSiwcModels(client.fetchBody(request))
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.ChatGPT): ProviderQuota =
        ProviderQuota.UsageLimits(windows = emptyList(), limitReached = false)

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = responseAPI.streamText(
        endpoint = ResponsesEndpoint(
            url = "$OPENAI_API_BASE/responses",
            capabilities = SIWC_CAPABILITIES,
            configureRequest = { configureAuth(providerSetting) },
        ),
        messages = messages,
        params = params,
    )

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var result = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id)
        )
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) finish = chunk
            result = handler.handle(result, chunk)
        }
        val message = result.last()
        return TextGenerationResult(
            id = finish?.responseId.orEmpty(),
            model = finish?.model ?: params.model.modelId,
            message = message,
            finishReason = finish?.finishReason,
            usage = message.usage,
        )
    }

    private suspend fun Request.Builder.configureAuth(setting: ProviderSetting.ChatGPT) =
        configureAccountAuth(credentials, setting, emptyList()) { }
}

/** Public SIWC model catalogue; only explicitly listable models are exposed. */
internal fun parseSiwcModels(body: String): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty()
    return models.mapNotNull { element ->
        val model = element.jsonObject
        if (model.stringOrNull("visibility") != "list") return@mapNotNull null
        val slug = model.stringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Model(
            modelId = slug,
            displayName = model.stringOrNull("display_name") ?: slug,
            abilities = listOf(ModelAbility.TOOL),
        )
    }
}
