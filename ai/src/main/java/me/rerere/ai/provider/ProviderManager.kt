package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.util.ImageEncodeCache
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    accountCredentials: AccountCredentialProvider,
) {
    // 存储已注册的Provider实例
    // Provider requests carry API keys and user-defined headers; never forward them on redirects.
    private val credentialClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        ImageEncodeCache.init(context.cacheDir)

        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(credentialClient, context))
        registerProvider("google", GoogleProvider(credentialClient, context))
        registerProvider("claude", ClaudeProvider(credentialClient, context))
        registerProvider("chatgpt", ChatGPTProvider(credentialClient, accountCredentials))
        registerProvider("grok", GrokProvider(credentialClient, accountCredentials))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
        } as Provider<T>
    }
}
