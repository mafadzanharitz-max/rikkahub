package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * HTTP diagnostics must never persist credentials or user/provider payloads.
 *
 * Request bodies are deliberately omitted: provider payloads can contain prompts,
 * attachments metadata, tool arguments, and occasionally credentials.
 */
class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val requestHeaders = RequestLogRedaction.redactedMap(request.headers)

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = RequestLogRedaction.redactedUrl(request.url),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = null,
                    error = "Request failed (${e.javaClass.simpleName})"
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = RequestLogRedaction.redactedUrl(request.url),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = null,
                responseCode = response.code,
                responseHeaders = RequestLogRedaction.redactedMap(response.headers),
                durationMs = durationMs,
                error = null
            )
        )

        return response
    }

}

/**
 * Shared, deterministic redaction helpers kept independently testable so credential
 * filtering cannot silently regress when request logging changes.
 */
internal object RequestLogRedaction {
    private const val REDACTED = "[REDACTED]"

    fun redactedMap(headers: Headers): Map<String, String> =
        headers.names().associateWith { name ->
            if (isSensitiveHeader(name)) REDACTED else headers.get(name) ?: ""
        }

    fun redactedUrl(url: okhttp3.HttpUrl): String {
        val builder = url.newBuilder()
        if (url.username.isNotEmpty()) builder.username(REDACTED)
        if (url.password.isNotEmpty()) builder.password(REDACTED)
        url.queryParameterNames
            .filter(::isSensitiveQueryParameter)
            .forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }

    private fun isSensitiveHeader(name: String): Boolean {
        val normalized = name.lowercase().replace('-', '_')
        return normalized in SENSITIVE_HEADERS ||
            SENSITIVE_HEADER_MARKERS.any(normalized::contains) ||
            normalized.endsWith("_key")
    }

    private fun isSensitiveQueryParameter(name: String): Boolean {
        val normalized = name.lowercase().replace('-', '_')
        return normalized in SENSITIVE_QUERY_PARAMETERS ||
            SENSITIVE_QUERY_MARKERS.any(normalized::contains) ||
            normalized.endsWith("_key")
    }

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy_authorization", "x_api_key", "api_key",
        "x_goog_api_key", "cookie", "set_cookie", "x_auth_token",
        "x_api_token", "x_access_token", "x_refresh_token", "x_csrf_token",
        "x_amz_security_token",
    )

    private val SENSITIVE_HEADER_MARKERS = listOf(
        "authorization", "auth", "session", "api_key", "apikey", "token",
        "secret", "password", "credential", "signature", "private_key",
        "access_key", "refresh_key", "cookie",
    )

    private val SENSITIVE_QUERY_PARAMETERS = setOf(
        "key", "api_key", "apikey", "token", "access_token", "refresh_token",
        "password", "secret", "signature", "auth", "auth_token",
        "client_secret", "clientsecret", "id_token", "jwt", "session",
        "session_token", "sig", "credential", "private_key", "secret_key",
        "access_key", "x_api_key",
    )

    private val SENSITIVE_QUERY_MARKERS = listOf(
        "session", "token", "secret", "password", "credential", "signature",
        "private_key", "access_key", "refresh_key", "auth",
    )
}
