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
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProviderAccountManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appScope = AppScope()

    // 刷新请求到达令牌端点后卡住，直到测试放行
    private val requestArrived = CountDownLatch(1)
    private val refreshRequestCount = AtomicInteger()
    private val revokeRequestArrived = CountDownLatch(1)
    private val releaseRevoke = CountDownLatch(1)
    private val revokeRequestCount = AtomicInteger()
    private val lastRevokeBody = AtomicReference<String?>()
    @Volatile private var holdRevokeResponse = false
    @Volatile private var revokeResponseCode = 200
    private val release = CountDownLatch(1)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.endsWith("/.well-known/openid-configuration")) {
                val issuer = if (request.url.host == "auth.x.ai") "https://auth.x.ai" else "https://auth.openai.com"
                val revokePath = if (request.url.host == "auth.x.ai") "/oauth2/revoke" else "/revoke"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("")
                    .body(
                        """{"issuer":"$issuer","revocation_endpoint":"$issuer$revokePath"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            } else if (request.url.encodedPath == "/revoke" || request.url.encodedPath == "/oauth2/revoke") {
                revokeRequestCount.incrementAndGet()
                revokeRequestArrived.countDown()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                lastRevokeBody.set(buffer.readUtf8())
                if (holdRevokeResponse) releaseRevoke.await(10, TimeUnit.SECONDS)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(revokeResponseCode)
                    .message("")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                refreshRequestCount.incrementAndGet()
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

    private suspend fun createStore(accountProvider: ProviderSetting.Account = provider): SettingsStore {
        val store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(temp.root, "settings.preferences_pb") },
            storeScope,
        )
        store.awaitLoaded()
        store.update { it.copy(providers = it.providers + accountProvider) }
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
    fun `cached credential is not returned after sign out begins`() = runBlocking {
        val validProvider = provider.copy(
            credential = provider.credential!!.copy(
                expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            ),
        )
        val store = createStore(validProvider)
        val manager = ProviderAccountManager(store, appScope, client)

        // Force a refresh while the cached access token is still time-valid.
        val refresh = async(Dispatchers.Default) {
            runCatching {
                manager.getCredential(validProvider, rejectedAccessToken = "old-access")
            }
        }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })

        // Logout marks the session as signing out before it can acquire the held refresh lock.
        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.logout(provider.id) }

        // This call would otherwise take getCredential's lock-free cached-token fast path.
        assertTrue(
            runCatching {
                manager.getCredential(validProvider, rejectedAccessToken = null)
            }.isFailure
        )

        release.countDown()
        logout.await()
        assertTrue(refresh.await().isFailure)
        assertNull(store.credential())
    }

    @Test
    fun `logout completes after its caller is cancelled while waiting for refresh`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)

        val refresh = async(Dispatchers.Default) {
            runCatching { manager.getCredential(provider, rejectedAccessToken = null) }
        }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })

        // Logout has marked the session and is suspended behind the in-flight refresh.
        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.logout(provider.id) }
        logout.cancel()
        release.countDown()
        logout.join()

        assertTrue(logout.isCancelled)
        assertTrue(refresh.await().isFailure)
        assertNull(store.credential())
    }

    @Test
    fun `logout revokes remote session before clearing local credential`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)
        holdRevokeResponse = true

        val logout = async(Dispatchers.Default) { manager.logout(provider.id) }
        assertTrue(withContext(Dispatchers.IO) { revokeRequestArrived.await(10, TimeUnit.SECONDS) })

        assertEquals("old-refresh", store.credential()?.refreshToken)
        assertTrue(runCatching {
            manager.getCredential(provider, rejectedAccessToken = null)
        }.isFailure)

        releaseRevoke.countDown()
        assertTrue(logout.await())
        assertNull(store.credential())
    }

    @Test
    fun `logout clears local credential but reports failed remote revocation`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)
        revokeResponseCode = 503

        val revoked = manager.logout(provider.id)

        assertFalse(revoked)
        assertNull(store.credential())
        assertTrue(revokeRequestCount.get() >= 3)
    }

    @Test
    fun `grok logout revokes its refresh token before clearing it locally`() = runBlocking {
        val grokProvider = ProviderSetting.Grok(
            credential = OAuthCredential(
                accessToken = "grok-access",
                refreshToken = "grok-refresh",
                expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            )
        )
        val store = createStore(grokProvider)
        val manager = ProviderAccountManager(store, appScope, client)

        assertTrue(manager.logout(grokProvider.id))

        val saved = store.settingsFlow.value.providers.find { it.id == grokProvider.id } as ProviderSetting.Grok
        assertNull(saved.credential)
        assertEquals(1, revokeRequestCount.get())
        assertTrue(lastRevokeBody.get().orEmpty().contains("token=grok-refresh"))
        assertTrue(lastRevokeBody.get().orEmpty().contains("client_id=${GrokAuth.CLIENT_ID}"))
    }

    @Test
    fun `removed account provider cannot refresh using a stale setting`() = runBlocking {
        val store = createStore()
        store.update { settings ->
            settings.copy(providers = settings.providers.filterNot { it.id == provider.id })
        }
        val manager = ProviderAccountManager(store, appScope, client)

        val result = runCatching {
            manager.getCredential(provider, rejectedAccessToken = null)
        }

        assertTrue(result.isFailure)
        assertEquals(0, refreshRequestCount.get())
    }

    @Test
    fun `provider changed to api key cannot refresh a stale account setting`() = runBlocking {
        val store = createStore()
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == provider.id) ProviderSetting.OpenAI(id = provider.id) else it
                }
            )
        }
        val manager = ProviderAccountManager(store, appScope, client)

        val result = runCatching {
            manager.getCredential(provider, rejectedAccessToken = null)
        }

        assertTrue(result.isFailure)
        assertEquals(0, refreshRequestCount.get())
    }

    @Test
    fun `stale chatgpt setting cannot receive grok credentials for the same id`() = runBlocking {
        val grokCredential = OAuthCredential(
            accessToken = "grok-access",
            refreshToken = "grok-refresh",
            expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
        )
        val store = createStore(
            ProviderSetting.Grok(id = provider.id, credential = grokCredential)
        )
        val manager = ProviderAccountManager(store, appScope, client)

        val result = runCatching {
            manager.getCredential(provider, rejectedAccessToken = null)
        }

        assertTrue(result.isFailure)
        assertEquals(0, refreshRequestCount.get())
    }

    @Test
    fun `in flight chatgpt refresh cannot replace credentials after account type changes`() = runBlocking {
        val store = createStore()
        val manager = ProviderAccountManager(store, appScope, client)
        val refresh = async(Dispatchers.Default) {
            runCatching { manager.getCredential(provider, rejectedAccessToken = null) }
        }
        assertTrue(withContext(Dispatchers.IO) { requestArrived.await(10, TimeUnit.SECONDS) })

        val originalCredential = provider.credential!!
        store.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == provider.id) {
                        ProviderSetting.Grok(id = provider.id, credential = originalCredential)
                    } else it
                }
            )
        }
        release.countDown()

        assertTrue(refresh.await().isFailure)
        val current = store.settingsFlow.value.providers.find { it.id == provider.id }
        assertTrue(current is ProviderSetting.Grok)
        assertEquals(originalCredential, (current as ProviderSetting.Grok).credential)
        assertEquals("old-access", (current as ProviderSetting.Grok).credential?.accessToken)
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
