package me.rerere.rikkahub.data.sync

import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCredentialPrivacyTest {
    @Test
    fun `portable backup retains ChatGPT provider but excludes its OAuth credentials`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.ChatGPT(
                    name = "My ChatGPT",
                    registration = me.rerere.ai.provider.ChatGPTRegistration(
                        clientId = "oaiapp-registration-secret",
                        issuer = "https://auth.openai.com",
                        subject = "siwc-subject-secret",
                        email = "user@example.com",
                    ),
                    credential = OAuthCredential(
                        accessToken = "siwc-access-secret",
                        refreshToken = "siwc-refresh-secret",
                        expiresAt = 123456789L,
                        accountId = "workspace-id",
                        email = "user@example.com",
                        plan = "plus",
                    ),
                ),
            ),
        )

        val backupSettings = settings.withoutChatGPTCredentialsForPortableTransfer()
        val chatGpt = backupSettings.providers.single() as ProviderSetting.ChatGPT
        val serialized = JsonInstant.encodeToString(backupSettings)

        assertEquals("My ChatGPT", chatGpt.name)
        assertNull(chatGpt.credential)
        assertNull(chatGpt.registration)
        assertNull(chatGpt.registration)
        assertFalse(serialized.contains("oaiapp-registration-secret"))
        assertFalse(serialized.contains("siwc-subject-secret"))
        assertFalse(serialized.contains("siwc-access-secret"))
        assertFalse(serialized.contains("siwc-refresh-secret"))
        assertFalse(serialized.contains("workspace-id"))
        assertFalse(serialized.contains("user@example.com"))
        assertTrue(serialized.contains("\"chatgpt\""))
    }

    @Test
    fun `backup sanitization does not modify live settings`() {
        val credential = OAuthCredential(accessToken = "live-access", refreshToken = "live-refresh")
        val settings = Settings(providers = listOf(ProviderSetting.ChatGPT(credential = credential)))
        val backupSettings = settings.withoutChatGPTCredentialsForPortableTransfer()

        assertEquals(credential, (settings.providers.single() as ProviderSetting.ChatGPT).credential)
        assertNull((backupSettings.providers.single() as ProviderSetting.ChatGPT).credential)
    }
}
