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
    "internal fun createHardLink(",
    "private fun File.safeResolve(path: String)",
    "root.resolve(linkName.trimStart('/')).canonicalFile.toPath()",
    "Files.isSymbolicLink(target.toPath())",
    "Files.createLink(link, existing)",
    "source.copyTo(target, overwrite = true)",
    "MAX_COMPRESSED_ARCHIVE_BYTES",
    "MAX_EXPANDED_ARCHIVE_BYTES",
    "MAX_ARCHIVE_ENTRIES",
    "verifySha256(archive, OFFICIAL_ROOTFS_SHA256)",
    "OFFICIAL_ROOTFS_SHA256",
)
TEST_MARKERS = (
    "verifySha256AcceptsMatchingDigest",
    "verifySha256RejectsMismatch",
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
    app_repository = Path("app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt")
    detail_vm = Path("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailVM.kt")
    detail_page = Path("app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt")
    workspace_model = Path("workspace/src/main/java/me/rerere/workspace/Workspace.kt")
    workspace_manager = Path("workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt")
    rootfs_patcher = Path("workspace/src/main/java/me/rerere/workspace/RootfsPatcher.kt")
    armv7_rootfs = Path("workspace/src/main/java/me/rerere/workspace/Armv7Rootfs.kt")
    required_app_files = (app_repository, detail_vm, detail_page, workspace_model, workspace_manager, rootfs_patcher, armv7_rootfs)
    missing = [str(path) for path in (INSTALLER, TESTS, *required_app_files) if not path.is_file()]
    if missing:
        print("Missing generated RootFS files: " + ", ".join(missing), file=sys.stderr)
        return 1
    source = INSTALLER.read_text(encoding="utf-8")
    tests = TESTS.read_text(encoding="utf-8")
    repository = app_repository.read_text(encoding="utf-8")
    view_model = detail_vm.read_text(encoding="utf-8")
    page = detail_page.read_text(encoding="utf-8")
    model = workspace_model.read_text(encoding="utf-8")
    manager = workspace_manager.read_text(encoding="utf-8")
    patcher = rootfs_patcher.read_text(encoding="utf-8")
    armv7 = armv7_rootfs.read_text(encoding="utf-8")
    absent_source = [item for item in SOURCE_MARKERS if item not in source]
    absent_tests = [item for item in TEST_MARKERS if item not in tests]
    forbidden = [item for item in FORBIDDEN_SOURCE_MARKERS if item in source]
    lifecycle_markers = {
        "persist interrupted status after cancellation": "WorkspaceShellStatus.INTERRUPTED.name" in repository,
        "recover interrupted operation without promoting it to Ready": "if (hasRootfs) WorkspaceShellStatus.INTERRUPTED.name" in repository,
        "cancel action reaches the active install job": "fun cancelRootfsInstall()" in view_model and "onCancelInstall = vm::cancelRootfsInstall" in page,
        "interrupted state is represented in the workspace model": "    INTERRUPTED," in model,
        "interrupted status has a visible label": "WorkspaceShellStatus.INTERRUPTED.name -> stringResource(R.string.workspace_detail_shell_interrupted)" in page,
    }
    additional_markers = {
        "RootFS health uses ARMHF ELF validation": "Armv7Rootfs.validate(linuxDir)" in manager,
        "ARMHF RootFS requires essential directories": 'listOf("etc", "usr", "var", "tmp")' in armv7,
        "temporary directories receive sticky mode 01777": "chmod(dir, 0x3ff)" in patcher,
        "default Ubuntu RootFS uses 24.04.5 ARMHF": "ubuntu-base-24.04.5-base-armhf.tar.gz" in page,
    }
    missing_additional = [name for name, present in additional_markers.items() if not present]
    missing_lifecycle = [name for name, present in lifecycle_markers.items() if not present]
    if absent_source or absent_tests or forbidden or missing_lifecycle or missing_additional:
        for title, items in (
            ("Missing generated Kotlin source invariants", absent_source),
            ("Missing RootFS regression tests", absent_tests),
            ("Forbidden malformed Kotlin escape patterns", forbidden),
            ("Missing RootFS lifecycle safeguards", missing_lifecycle),
            ("Missing RootFS hardening safeguards", missing_additional),
        ):
            if items:
                print(title + ":", *items, sep="\\n- ", file=sys.stderr)
        return 1
    print(f"Validated {len(SOURCE_MARKERS)} generated Kotlin source invariants.")
    print(f"Validated {len(TEST_MARKERS)} generated RootFS regression-test markers.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
