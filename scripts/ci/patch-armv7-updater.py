#!/usr/bin/env python3
"""Patch the pinned ARMv7 overlay: empty feed handling and SHA-256-locked updates."""

from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text()
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one patch target in {path}, found {count}")
    path.write_text(source.replace(old, new, 1))


root = Path(".")
updater = root / "app/src/main/java/me/rerere/rikkahub/utils/Armv7Updates.kt"

replace_once(
    updater,
    """return releases.mapNotNull { it as? JsonObject }
            .filter { !it.flag("draft") && !it.flag("prerelease") }""",
    """val releaseItems = releases.mapNotNull { it as? JsonObject }
        if (releaseItems.isEmpty()) {
            return UpdateInfo(
                version = "0.0.0",
                publishedAt = "1970-01-01T00:00:00Z",
                changelog = "No ARMv7a release has been published yet.",
                downloads = emptyList(),
            )
        }
        return releaseItems
            .filter { !it.flag("draft") && !it.flag("prerelease") }""",
)

replace_once(
    updater,
    """if (files.none { it.text("name") == "SHA256SUMS" } ||
                    files.none { it.text("name") == "build-info.json" }
                ) return@firstNotNullOfOrNull null
                val url = apk.text("browser_download_url")""",
    """val checksumAsset = files.singleOrNull { it.text("name") == "SHA256SUMS" }
                    ?: return@firstNotNullOfOrNull null
                val buildInfo = files.singleOrNull { it.text("name") == "build-info.json" }
                    ?: return@firstNotNullOfOrNull null
                val checksumUrl = checksumAsset.text("browser_download_url")
                if (checksumUrl.isBlank() || buildInfo.text("name") != "build-info.json") {
                    return@firstNotNullOfOrNull null
                }
                val url = apk.text("browser_download_url")""",
)

replace_once(
    updater,
    """                            size = "\${apk["size"]!!.jsonPrimitive.longOrNull!! / 1024 / 1024} MB",
                        )""",
    """                            size = "\${apk["size"]!!.jsonPrimitive.longOrNull!! / 1024 / 1024} MB",
                            checksumUrl = checksumUrl,
                        )""",
)

replace_once(
    updater,
    """    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()""",
    """    fun expectedSha256(checksums: String, fileName: String): String {
        val matches = checksums.lineSequence().mapNotNull { line ->
            val match = Regex("^([0-9a-fA-F]{64})\\\\s+\\\\*?(.+?)\\\\s*$")
                .matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[1].lowercase().takeIf { match.groupValues[2] == fileName }
        }.toList()
        require(matches.size == 1) { "Missing or ambiguous SHA-256 entry for $fileName" }
        return matches.single()
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()""",
)

test = root / "app/src/test/java/me/rerere/rikkahub/utils/Armv7UpdatesTest.kt"
replace_once(
    test,
    """{"name":"SHA256SUMS"},{"name":"build-info.json"}$assets]}""",
    """{"name":"SHA256SUMS","browser_download_url":"https://github.com/jacob-ayang/rikkahub-armv7a/releases/download/$tag/SHA256SUMS"},{"name":"build-info.json"}$assets]}""",
)
replace_once(
    test,
    """    @Test fun selectsOnlyCompatibleFormalRelease() {""",
    """    @Test fun emptyReleaseListMeansNoUpdateRatherThanNetworkFailure() {
        val info = Armv7Updates.decode("[]")
        assertEquals("0.0.0", info.version)
        assertEquals(0, info.downloads.size)
    }

    @Test fun selectsNewestCompleteArmv7ReleaseAndItsExactAsset() {
        val info = Armv7Updates.decode("[\${release("3.2.0")},\${release("3.1.0")}]")
        assertEquals("3.2.0", info.version)
        assertEquals("2026-10-24T12:00:00Z", info.publishedAt)
        assertEquals(1, info.downloads.size)
        assertEquals("rikkahub-3.2.0-armv7a-123abcd.apk", info.downloads.single().name)
        assertEquals(
            "https://github.com/mafadzanharitz-max/rikkahub/releases/download/3.2.0/app.apk",
            info.downloads.single().url,
        )
        assertEquals(
            "https://github.com/mafadzanharitz-max/rikkahub/releases/download/3.2.0/SHA256SUMS",
            info.downloads.single().checksumUrl,
        )
    }

    @Test fun skipsIncompleteNewestReleaseAndUsesOlderCompleteRelease() {
        val incomplete = release("3.2.0").replace("SHA256SUMS", "missing-checksums.txt")
        val info = Armv7Updates.decode("[\${incomplete},\${release("3.1.0")}]")
        assertEquals("3.1.0", info.version)
    }

    @Test fun parsesOnlyTheExactSha256Filename() {
        val expected = "a".repeat(64)
        assertEquals(expected, Armv7Updates.expectedSha256("$expected  app.apk", "app.apk"))
    }

    @Test fun rejectsMissingAmbiguousAndMalformedChecksums() {
        val valid = "b".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            Armv7Updates.expectedSha256("$valid  other.apk", "app.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Armv7Updates.expectedSha256("$valid  app.apk\\n$valid *app.apk", "app.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Armv7Updates.expectedSha256("not-a-hash  app.apk", "app.apk")
        }
    }

    @Test fun selectsOnlyCompatibleFormalRelease() {""",
)
print("Patched ARMv7 updater: empty-feed handling and exact-file SHA-256 verification metadata.")
