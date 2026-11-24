package me.rerere.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDavResourceHrefTest {
    private val base = "https://dav.example.com/dav/user"

    @Test
    fun acceptsRelativeHrefWithinCollection() {
        assertEquals(
            "https://dav.example.com/dav/user/backup.zip",
            validateWebDavResourceHref(base, "backup.zip"),
        )
    }

    @Test
    fun preservesLiteralPercentEncodedResourceNames() {
        assertEquals(
            "https://dav.example.com/dav/user/backup%25final.zip",
            validateWebDavResourceHref(base, "backup%25final.zip"),
        )
    }

    @Test
    fun acceptsAbsoluteSameOriginHrefWithinCollection() {
        assertEquals(
            "https://dav.example.com/dav/user/backup.zip",
            validateWebDavResourceHref(base, "https://dav.example.com/dav/user/backup.zip"),
        )
    }

    @Test
    fun rejectsExternalHostSchemeAndPort() {
        listOf(
            "https://evil.example/backup.zip",
            "http://dav.example.com/dav/user/backup.zip",
            "https://dav.example.com:8443/dav/user/backup.zip",
        ).forEach { href ->
            assertThrows(IllegalArgumentException::class.java) {
                validateWebDavResourceHref(base, href)
            }
        }
    }

    @Test
    fun rejectsSiblingPathThatOnlySharesPrefix() {
        assertThrows(IllegalArgumentException::class.java) {
            validateWebDavResourceHref(base, "/dav/user2/backup.zip")
        }
    }

    @Test
    fun rejectsLiteralAndEncodedTraversal() {
        listOf(
            "../escape.zip",
            "%2e%2e/escape.zip",
            "%2E%2E%2Fescape.zip",
            "%252e%252e/escape.zip",
        ).forEach { href ->
            assertThrows(IllegalArgumentException::class.java) {
                validateWebDavResourceHref(base, href)
            }
        }
    }

    @Test
    fun rejectsQueryFragmentAndUserInfo() {
        listOf(
            "backup.zip?download=1",
            "backup.zip#fragment",
            "https://user@dav.example.com/dav/user/backup.zip",
        ).forEach { href ->
            assertThrows(IllegalArgumentException::class.java) {
                validateWebDavResourceHref(base, href)
            }
        }
    }
}
