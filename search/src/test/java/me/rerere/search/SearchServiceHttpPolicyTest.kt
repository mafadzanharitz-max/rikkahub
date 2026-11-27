package me.rerere.search

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchServiceHttpPolicyTest {
    @Test
    fun `search service never follows redirects with configured credentials`() {
        val originalClient = SearchService.httpClient
        val originalRoulette = SearchService.keyRoulette
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        try {
            SearchService.init(client)

            assertFalse(SearchService.httpClient.followRedirects)
            assertFalse(SearchService.httpClient.followSslRedirects)
        } finally {
            SearchService.httpClient = originalClient
            SearchService.keyRoulette = originalRoulette
        }
    }
}
