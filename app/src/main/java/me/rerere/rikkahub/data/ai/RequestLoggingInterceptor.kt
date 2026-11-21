package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val requestHeaders = RequestLogRedactor.redactHeaders(request.headers)
        // Request bodies can contain API credentials, conversation content, and large Base64 media.
        // Keep a marker for diagnostics without serializing or retaining the payload.
        val requestBody = request.body?.let { RequestLogRedactor.REDACTED_BODY }

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = RequestLogRedactor.redactUrl(request.url),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = e.javaClass.simpleName,
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = RequestLogRedactor.redactHeaders(response.headers)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = RequestLogRedactor.redactUrl(request.url),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
            )
        )

        return response
    }
}

internal object RequestLogRedactor {
    const val REDACTED = "██"
    const val REDACTED_BODY = "[omitted: request body may contain sensitive content]"

    private val sensitiveNamePattern = Regex(
        "(authorization|api[-_]?key|access[-_]?token|auth[-_]?token|client[-_]?secret|secret|password|cookie|credential|signature|session[-_]?id|jwt|token|(?:^|[-_])auth(?:$|[-_])|(?:^|[-_])sig(?:$|[-_])|(?:^|[-_])key(?:$|[-_]))",
        RegexOption.IGNORE_CASE,
    )

    internal fun isSensitiveName(name: String): Boolean =
        sensitiveNamePattern.containsMatchIn(name)

    fun redactHeaders(headers: Headers): Map<String, String> =
        headers.names().associateWith { name ->
            if (isSensitiveName(name)) {
                REDACTED
            } else {
                headers.values(name).joinToString(", ")
            }
        }

    fun redactUrl(url: HttpUrl): String {
        val builder = url.newBuilder()
            .username("")
            .password("")
            .fragment(null)

        url.queryParameterNames
            .filter(::isSensitiveName)
            .forEach { name ->
                val valueCount = url.queryParameterValues(name).size
                builder.removeAllQueryParameters(name)
                repeat(valueCount) {
                    builder.addQueryParameter(name, REDACTED)
                }
            }

        return builder.build().toString()
    }
}
