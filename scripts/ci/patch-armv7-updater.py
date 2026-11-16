#!/usr/bin/env python3
"""Patch the pinned ARMv7 overlay so an empty fork release list means no update."""

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

test = root / "app/src/test/java/me/rerere/rikkahub/utils/Armv7UpdatesTest.kt"
replace_once(
    test,
    """    @Test fun selectsOnlyCompatibleFormalRelease() {""",
    """    @Test fun emptyReleaseListMeansNoUpdateRatherThanNetworkFailure() {
        val info = Armv7Updates.decode("[]")
        assertEquals("0.0.0", info.version)
        assertEquals(0, info.downloads.size)
    }

    @Test fun selectsOnlyCompatibleFormalRelease() {""",
)
print("Patched ARMv7 updater: an empty fork release list is a clean no-update state.")
