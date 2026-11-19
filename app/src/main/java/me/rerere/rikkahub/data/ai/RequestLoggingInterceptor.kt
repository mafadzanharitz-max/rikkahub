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
        val requestHeaders = request.headers.redactedMap()

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.redacted().toString(),
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
                url = request.url.redacted().toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = null,
                responseCode = response.code,
                responseHeaders = response.headers.redactedMap(),
                durationMs = durationMs,
                error = null
            )
        )

        return response
    }

    private fun Headers.redactedMap(): Map<String, String> {
        return names().associateWith { name ->
            if (isSensitiveHeader(name)) {
                REDACTED
            } else {
                get(name) ?: ""
            }
        }
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

    private fun okhttp3.HttpUrl.redacted(): okhttp3.HttpUrl {
        val builder = newBuilder()
        if (username.isNotEmpty()) builder.username(REDACTED)
        if (password.isNotEmpty()) builder.password(REDACTED)
        queryParameterNames
            .filter(::isSensitiveQueryParameter)
            .forEach { builder.removeAllQueryParameters(it) }
        return builder.build()
    }

    private companion object {
        const val REDACTED = "[REDACTED]"

        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-goog-api-key",
            "cookie",
            "set-cookie",
            "x-auth-token",
            "x-api-token",
            "x-access-token",
            "x-refresh-token",
            "x-csrf-token",
            "x-amz-security-token",
        )

        // Custom provider headers are user-defined, so exact-name matching alone is not
        // sufficient (for example, "anthropic-api-key" or "x-vendor-session-token").
        val SENSITIVE_HEADER_MARKERS = listOf(
            "authorization",
            "api_key",
            "apikey",
            "token",
            "secret",
            "password",
            "credential",
            "signature",
            "private_key",
            "access_key",
            "refresh_key",
            "cookie",
        )

        val SENSITIVE_QUERY_PARAMETERS = setOf(
            "key",
            "api_key",
            "apikey",
            "token",
            "access_token",
            "refresh_token",
            "password",
            "secret",
            "signature",
            "auth",
            "auth_token",
            "client_secret",
            "clientsecret",
            "id_token",
            "jwt",
            "session",
            "session_token",
            "sig",
            "credential",
            "private_key",
            "secret_key",
            "access_key",
            "x_api_key",
        )

        val SENSITIVE_QUERY_MARKERS = listOf(
            "token",
            "secret",
            "password",
            "credential",
            "signature",
            "private_key",
            "access_key",
            "refresh_key",
            "auth",
        )
    }
}
