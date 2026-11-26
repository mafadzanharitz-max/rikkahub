package me.rerere.rikkahub.data.ai.mcp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Dns
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "McpOAuthDiscovery"

/**
 * DNS boundary for OAuth metadata discovery. Every address returned for a hostname is
 * checked immediately before OkHttp connects; mixed public/private DNS answers fail closed.
 */
internal object McpOAuthNetworkPolicy {
    fun validateResolvedAddresses(hostname: String, addresses: List<InetAddress>) {
        require(addresses.isNotEmpty()) { "DNS returned no addresses" }
        val isLoopbackName = hostname.equals("localhost", ignoreCase = true) ||
            hostname == "127.0.0.1" || hostname == "::1"
        require(addresses.all { address ->
            if (isLoopbackName) address.isLoopbackAddress
            else isPubliclyRoutable(address)
        }) { "OAuth discovery hostname resolved to a non-public address" }
    }

    fun isPubliclyRoutable(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false // Shared address space (100.64/10)
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 0 && c == 0 -> false
                a == 192 && b == 0 && c == 2 -> false
                a == 198 && b in 18..19 -> false
                a == 198 && b == 51 && c == 100 -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }

        if (address is Inet6Address) {
            // IPv4-mapped IPv6 must obey the IPv4 policy too.
            if (bytes.take(10).all { it == 0 } && bytes[10] == 255 && bytes[11] == 255) {
                return isPubliclyRoutable(InetAddress.getByAddress(bytes.takeLast(4).map { it.toByte() }.toByteArray()))
            }
            // Only global-unicast 2000::/3 is eligible. Reject transition, documentation,
            // benchmarking and other special-purpose ranges within that allocation.
            if ((bytes[0] and 0xe0) != 0x20) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 &&
                (bytes[2] == 0x00 && bytes[3] == 0x00 || // Teredo
                 bytes[2] == 0x00 && bytes[3] == 0x02 || // Benchmarking
                 bytes[2] == 0x0d && bytes[3] == 0xb8) // Documentation
            ) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x02) return false // 6to4
            return true
        }

        return false
    }

    val publicDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            validateResolvedAddresses(hostname, addresses)
            return addresses
        }
    }

    fun validateUrlHost(value: String) {
        val url = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid OAuth endpoint URL")
        val addresses = Dns.SYSTEM.lookup(url.host)
        validateResolvedAddresses(url.host, addresses)
    }

    fun newSafeClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        // OAuth endpoints must not redirect to an unvalidated host or downgrade transport.
        .followRedirects(false)
        .followSslRedirects(false)
        // Proxy-side DNS would bypass this process's validated DNS result.
        .proxy(Proxy.NO_PROXY)
        .dns(publicDns)
        .build()
}

/** MCP 授权规范特有的资源服务器与授权服务器元数据发现。 */
internal class McpOAuthDiscoveryClient(
    private val httpClient: OkHttpClient,
) {
    private val discoveryHttpClient = McpOAuthNetworkPolicy.newSafeClient(httpClient)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class ProtectedResourceMetadata(
        val resource: String? = null,
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    data class AuthorizationServerMetadata(
        val issuer: String? = null,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
        @SerialName("token_endpoint") val tokenEndpoint: String? = null,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
        @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
        @SerialName("client_id_metadata_document_supported") val clientIdMetadataDocumentSupported: Boolean = false,
        @SerialName("authorization_response_iss_parameter_supported")
        val authorizationResponseIssParameterSupported: Boolean = false,
    )

    /**
     * 优先根据 MCP Server 401 响应中的 resource_metadata 定位，退回 RFC 9728
     * well-known 路径。
     */
    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata =
        withContext(Dispatchers.IO) {
            val expectedResource = canonicalResource(serverUrl)
            val candidates = buildList {
                probeResourceMetadataUrl(serverUrl)?.let { add(it) }
                addAll(wellKnownProtectedResourceUrls(serverUrl))
            }.distinct()
            for (url in candidates) {
                val metadata = try {
                    getJson<ProtectedResourceMetadata>(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                // RFC 9728 binds metadata to the exact protected resource identifier.
                if (metadata.resource != expectedResource || metadata.authorizationServers.isEmpty()) {
                    Log.w(TAG, "Rejected protected resource metadata with mismatched resource or empty authorization_servers")
                    continue
                }
                if (metadata.authorizationServers.any { !isSecureMetadataUrl(it) }) {
                    Log.w(TAG, "Rejected protected resource metadata containing an insecure authorization server")
                    continue
                }
                Log.i(TAG, "Accepted protected resource metadata for the requested resource")
                return@withContext metadata
            }
            error("无法发现与请求资源匹配的受保护资源元数据")
        }

    /** 依次尝试 RFC 8414 与 OIDC Discovery 的 well-known 路径。 */
    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata =
        withContext(Dispatchers.IO) {
            require(isSecureMetadataUrl(issuer)) { "Authorization server issuer must use HTTPS" }
            for (url in wellKnownAuthorizationServerUrls(issuer)) {
                val metadata = try {
                    getJson<AuthorizationServerMetadata>(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                // RFC 8414 requires the issuer in metadata to identify the requested issuer.
                if (metadata.issuer != issuer ||
                    metadata.authorizationEndpoint == null ||
                    metadata.tokenEndpoint == null ||
                    !isSecureMetadataUrl(metadata.authorizationEndpoint) ||
                    !isSecureMetadataUrl(metadata.tokenEndpoint) ||
                    (metadata.registrationEndpoint != null && !isSecureMetadataUrl(metadata.registrationEndpoint))
                ) {
                    Log.w(TAG, "Rejected authorization server metadata with issuer or endpoint mismatch")
                    continue
                }
                Log.i(TAG, "Accepted authorization server metadata for the requested issuer")
                return@withContext metadata
            }
            error("无法发现授权服务器元数据 (authorization server metadata): $issuer")
        }

    private suspend fun probeResourceMetadataUrl(serverUrl: String): String? {
        val request = Request.Builder()
            .url(serverUrl)
            .header("Accept", "application/json, text/event-stream")
            .get()
            .build()
        return try {
            executeRaw(request).use { response ->
                if (response.code != 401) return null
                val header = response.header("WWW-Authenticate") ?: return null
                val metadataUrl = RESOURCE_METADATA_REGEX.find(header)?.groupValues?.getOrNull(1)
                    ?: return null
                metadataUrl.takeIf(::isSecureMetadataUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun wellKnownProtectedResourceUrls(serverUrl: String): List<String> {
        val url = serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-protected-resource$path")
            }
            add("$origin/.well-known/oauth-protected-resource")
        }.distinct()
    }

    private fun wellKnownAuthorizationServerUrls(issuer: String): List<String> {
        val url = issuer.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
        val path = url.encodedPath.trimEnd('/')
        return buildList {
            if (path.isNotEmpty() && path != "/") {
                add("$origin/.well-known/oauth-authorization-server$path")
                add("$origin/.well-known/openid-configuration$path")
                add("$origin$path/.well-known/openid-configuration")
            }
            add("$origin/.well-known/oauth-authorization-server")
            add("$origin/.well-known/openid-configuration")
        }.distinct()
    }

    private fun isSecureMetadataUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        val loopbackHttp = url.scheme == "http" &&
            (url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1")
        return (url.scheme == "https" || loopbackHttp) &&
            url.host.isNotBlank() &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null
    }

    private fun portSuffix(url: HttpUrl): String {
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        return if (url.port == defaultPort) "" else ":${url.port}"
    }

    private suspend inline fun <reified T> getJson(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val text = execute(request)
        return json.decodeFromString(text)
    }

    private suspend fun execute(request: Request): String {
        executeRaw(request).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
            }
            return body
        }
    }

    private suspend fun executeRaw(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = discoveryHttpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }

    companion object {
        private val RESOURCE_METADATA_REGEX = Regex("resource_metadata=\"([^\"]+)\"")

        /** 服务器 origin，用于未提供受保护资源元数据时作为授权服务器 issuer。 */
        fun serverOrigin(serverUrl: String): String? {
            val url = serverUrl.toHttpUrlOrNull() ?: return null
            val defaultPort = HttpUrl.defaultPort(url.scheme)
            val port = if (url.port == defaultPort) "" else ":${url.port}"
            return "${url.scheme}://${url.host}$port"
        }

        /** RFC 8707 与 MCP 规范使用的 canonical resource URI。 */
        fun canonicalResource(serverUrl: String): String {
            val url = serverUrl.toHttpUrlOrNull() ?: return serverUrl
            val canonical = url.newBuilder().fragment(null).build().toString()
            // 根路径不带尾部斜杠（如 https://mcp.mongodb.com），与资源服务器声明的 resource 保持一致
            return if (url.encodedPath == "/" && url.query == null) canonical.removeSuffix("/") else canonical
        }
    }
}
