package me.rerere.ai.provider

/**
 * An HTTP failure returned by a text-generation provider.
 *
 * The response body is deliberately not retained: provider error payloads can contain
 * user data or gateway-specific details and are not needed for safe routing decisions.
 */
class ProviderHttpException(
    val statusCode: Int,
    cause: Throwable? = null,
) : Exception("Provider request failed (HTTP $statusCode)", cause) {
    enum class Kind {
        AUTHENTICATION,
        RATE_LIMITED,
        INVALID_REQUEST,
        TRANSIENT_SERVER,
        OTHER,
    }

    val kind: Kind
        get() = when (statusCode) {
            401, 403 -> Kind.AUTHENTICATION
            402, 429 -> Kind.RATE_LIMITED
            400, 404, 405, 409, 413, 415, 422 -> Kind.INVALID_REQUEST
            408, 425 -> Kind.TRANSIENT_SERVER
            in 500..599 -> Kind.TRANSIENT_SERVER
            else -> Kind.OTHER
        }

    /** Cross-provider fallback is safe to consider only for transient HTTP failures. */
    val fallbackEligible: Boolean
        get() = kind == Kind.TRANSIENT_SERVER
}
