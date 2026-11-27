package me.rerere.rikkahub.data.ai.account

import okhttp3.OkHttpClient

/**
 * Revocation requests contain refresh tokens in their bodies. Do not let OkHttp forward them
 * to a redirect target, including same-scheme 307/308 responses.
 */
internal fun OkHttpClient.forOAuthTokenRevocation(): OkHttpClient = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
