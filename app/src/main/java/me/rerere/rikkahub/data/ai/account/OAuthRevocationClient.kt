package me.rerere.rikkahub.data.ai.account

import me.rerere.oauth.withoutCredentialRedirects
import okhttp3.OkHttpClient

/**
 * Revocation requests contain refresh tokens in their bodies. Reuse the OAuth module's
 * no-redirect policy so tokens are never forwarded to a redirect target.
 */
internal fun OkHttpClient.forOAuthTokenRevocation(): OkHttpClient = withoutCredentialRedirects()
