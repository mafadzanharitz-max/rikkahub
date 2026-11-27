package me.rerere.rikkahub.data.ai.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SettingsSecretCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderAccountManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appScope = AppScope()

    // 刷新请求到达令牌端点后卡住，直到测试放行
    private val requestArrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/.well-known/openid-configuration")) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(
                        """{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/revoke"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            } else if (request.url.encodedPath == "/revoke") {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                requestArrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(
                        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"chatgpt.tokens.use.direct"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
        }
        .build()

    @Before
    fun useJvmTestCipher() {
        SettingsSecretCodec.encrypt = { "test:" + it }
        SettingsSecretCodec.decrypt = { it.removePrefix("test:") }
    }

    @After
    fun tearDown() = runBlocking {
        appScope.coroutineContext.job.cancelAndJoin()
        storeScope.coroutineContext.job.cancelAndJoin()
        SettingsSecretCodec.encrypt = me.rerere.rikkahub.utils.SecretCipher::encrypt
        SettingsSecretCodec.decrypt = me.rerere.rikkahub.utils.SecretCipher::decrypt
    }

    private val provider = ProviderSetting.ChatGPT(
        credential = OAuthCredential(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            expiresAt = 1L, // 早已过期，一取凭据就会刷新
            accountId = "account",
            oauthClientId = "oaiapp_issued",
            grantedScopes = listOf("chatgpt.tokens.use.direct"),
        ),
    )

    private suspend fun createStore(): SettingsStore {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "settings.preferences_pb") },
            storeScope,
        )
        store.awaitLoaded()
        store.update { it.copy(providers = it.providers + provider) }
        return store
    }

    private fun SettingsStore.credential(): OAuthCredential? =
        (settingsFlow.value.providers.find { it.id == provider.id } as ProviderSetting.ChatGPT).credential

    @Test
    fun `refresh that already started is saved even if the caller is cancelled`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)

        val caller = launch(Dispatchers.Default) { manager.getCredential(provider, rejectedAccessToken = null) }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })
        // 服务端已经收到刷新请求（旧 refresh token 作废），这时调用方被取消，比如停止生成
        caller.cancelAndJoin()
        release.countDown()

        val saved = withTimeout(10_000) {
            store.settingsFlow
                .map { settings ->
                    (settings.providers.find { it.id == provider.id } as ProviderSetting.ChatGPT).credential
                }
                .first { it?.refreshToken == "new-refresh" }
        }
        assertEquals("new-access", saved?.accessToken)
        assertEquals("account", saved?.accountId)
    }

    @Test
    fun `refresh result is dropped if the user signs out meanwhile`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)

        val caller = async(Dispatchers.Default) {
            runCatching { manager.getCredential(provider, rejectedAccessToken = null) }
        }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })
        // UNDISTPATCHED ensures logout invalidates the session before it suspends on refreshLock.
        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.logout(provider.id) }
        release.countDown()
        logout.await()

        assertTrue(caller.await().isFailure)
        assertNull(store.credential())
    }
}
