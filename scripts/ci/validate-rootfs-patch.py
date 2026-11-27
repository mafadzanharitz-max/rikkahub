#!/usr/bin/env python3
"""Validate generated RootFS Kotlin output and regression-test coverage."""
from pathlib import Path
import sys

INSTALLER = Path("workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt")
TESTS = Path("workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt")
SOURCE_MARKERS = (
    "private fun parsePax(text: String)",
    "private inner class LimitedInputStream(",
    "private fun createSymlink(root: File, target: File, linkName: String)",
    "private fun createHardLink(root: File, target: File, linkName: String)",
    "private fun File.safeResolve(path: String)",
    "root.resolve(linkName.trimStart('/')).canonicalFile.toPath()",
    "Files.isSymbolicLink(target.toPath())",
    "Files.createLink(link, existing)",
    "source.copyTo(target, overwrite = true)",
    "MAX_COMPRESSED_ARCHIVE_BYTES",
    "MAX_EXPANDED_ARCHIVE_BYTES",
    "MAX_ARCHIVE_ENTRIES",
)
TEST_MARKERS = (
    "extractReplacesExistingDanglingSymlinkEntry",
    "extractRejectsPathTraversal",
    "extractRejectsHardlinkEscape",
    "extractRejectsOversizedIndividualFile",
    "extractRejectsSymlinkEscape",
    "extractRejectsTraversalThroughEarlierSymlink",
    "extractRejectsOversizedMetadataEntry",
    "extractRejectsMalformedPaxRecordLength",
    "extractUsesUtf8ByteLengthsForMultiplePaxRecords",
    "createHardLinkFallsBackToCopyWhenAndroidDeniesLinkat",
)
FORBIDDEN_SOURCE_MARKERS = ("root.resolve(linkName.trimStart('/',",)

def main() -> int:
    missing = [str(path) for path in (INSTALLER, TESTS) if not path.is_file()]
    if missing:
        print("Missing generated RootFS files: " + ", ".join(missing), file=sys.stderr)
        return 1
    source = INSTALLER.read_text(encoding="utf-8")
    tests = TESTS.read_text(encoding="utf-8")
    absent_source = [item for item in SOURCE_MARKERS if item not in source]
    absent_tests = [item for item in TEST_MARKERS if item not in tests]
    forbidden = [item for item in FORBIDDEN_SOURCE_MARKERS if item in source]
    if absent_source or absent_tests or forbidden:
        for title, items in (
            ("Missing generated Kotlin source invariants", absent_source),
            ("Missing RootFS regression tests", absent_tests),
            ("Forbidden malformed Kotlin escape patterns", forbidden),
        ):
            if items:
                print(title + ":", *items, sep="\\n- ", file=sys.stderr)
        return 1
    print(f"Validated {len(SOURCE_MARKERS)} generated Kotlin source invariants.")
    print(f"Validated {len(TEST_MARKERS)} generated RootFS regression-test markers.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
