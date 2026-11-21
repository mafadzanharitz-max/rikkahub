#!/usr/bin/env python3
"""Apply transactional RootFS activation after the pinned ARMv7 overlay."""
import argparse
from pathlib import Path

INSTALLER = Path("workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt")
TESTS = Path("workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt")

OLD_INSTALL = """        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            download(url, archive, onProgress)
            extractTar(archive, stagingDir, format, onProgress)
            Armv7Rootfs.validate(stagingDir)
            linuxDir.deleteRecursively()
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
"""

NEW_INSTALL = """        val stagingDir = File(tempDir, "rootfs-staging")
        val backupDir = File(tempDir, "rootfs-backup")
        val linuxDir = manager.linuxDir(root)

        try {
            recoverRootfsBackup(linuxDir, backupDir)
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            download(url, archive, onProgress)
            extractTar(archive, stagingDir, format, onProgress)
            Armv7Rootfs.validate(stagingDir)
            // Patch the candidate completely before touching the active RootFS.
            patcher.patch(stagingDir)
            activateRootfs(stagingDir, linuxDir, backupDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
"""

HELPERS = """    /**
     * Recover the previous RootFS if the process was interrupted after moving it
     * to the backup directory but before activating the staged replacement.
     */
    internal fun recoverRootfsBackup(linuxDir: File, backupDir: File) {
        if (!backupDir.exists()) return
        if (!WorkspaceManager.isUsableRootfs(linuxDir)) {
            if (linuxDir.exists()) {
                check(linuxDir.deleteRecursively()) {
                    "Unable to clear incomplete RootFS activation before recovery"
                }
            }
            check(backupDir.renameTo(linuxDir)) {
                "Unable to restore previous RootFS from backup: ${backupDir.absolutePath}"
            }
        } else {
            // A usable active RootFS means activation completed; this is stale backup data.
            check(backupDir.deleteRecursively()) {
                "Unable to remove stale RootFS backup: ${backupDir.absolutePath}"
            }
        }
    }

    /**
     * Same-filesystem directory renames keep activation atomic. If activation fails,
     * restore the previous directory; retain the backup if restoration itself fails.
     */
    internal fun activateRootfs(stagingDir: File, linuxDir: File, backupDir: File) {
        check(!backupDir.exists()) { "RootFS backup must be recovered before activation" }
        val existingEntries = if (linuxDir.exists()) {
            linuxDir.list() ?: error("Unable to inspect existing RootFS directory")
        } else {
            emptyArray()
        }
        val hadPreviousRootfs = existingEntries.isNotEmpty()
        if (hadPreviousRootfs) {
            check(linuxDir.renameTo(backupDir)) {
                "Unable to preserve existing RootFS before activation"
            }
        } else if (linuxDir.exists()) {
            check(linuxDir.deleteRecursively()) {
                "Unable to clear empty RootFS directory before activation"
            }
        }

        try {
            check(stagingDir.renameTo(linuxDir)) {
                "Failed to move staged RootFS into workspace"
            }
        } catch (failure: Throwable) {
            if (linuxDir.exists() && !linuxDir.deleteRecursively()) {
                failure.addSuppressed(IOException("Unable to remove incomplete activated RootFS"))
            }
            if (hadPreviousRootfs && backupDir.exists() && !backupDir.renameTo(linuxDir)) {
                failure.addSuppressed(
                    IOException("Previous RootFS remains preserved at ${backupDir.absolutePath}")
                )
            }
            throw failure
        }

        // Cleanup failure is non-fatal: the new RootFS is active and recovery will
        // recognize it as usable and remove this stale backup on the next install.
        if (backupDir.exists()) backupDir.deleteRecursively()
    }

"""

def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count == 0 and new in source:
        return source
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one patch target, found {count}")
    return source.replace(old, new, 1)

def apply(root: Path) -> None:
    installer_path = root / INSTALLER
    installer = replace_once(installer_path.read_text(), OLD_INSTALL, NEW_INSTALL, str(INSTALLER))
    if "internal fun recoverRootfsBackup(" not in installer:
        marker = "    private fun download(\n"
        if installer.count(marker) != 1:
            raise SystemExit("RootfsInstaller helper insertion point changed")
        installer = installer.replace(marker, HELPERS + marker, 1)
    installer_path.write_text(installer)

    tests_path = root / TESTS
    tests = tests_path.read_text()
    test_methods = """    @Test
    fun activationReplacesOldRootfsAndRemovesBackup() {
        val linux = tmp.newFolder("linux")
        File(linux, "old-rootfs").writeText("old")
        val staging = tmp.newFolder("staging")
        File(staging, "new-rootfs").writeText("new")
        val backup = File(tmp.root, "backup")

        createInstaller().activateRootfs(staging, linux, backup)

        assertEquals("new", File(linux, "new-rootfs").readText())
        assertFalse(File(linux, "old-rootfs").exists())
        assertFalse(backup.exists())
    }

    @Test
    fun activationFailureRestoresPreviousRootfs() {
        val linux = tmp.newFolder("linux")
        File(linux, "old-rootfs").writeText("old")
        val missingStaging = File(tmp.root, "missing-staging")
        val backup = File(tmp.root, "backup")

        try {
            createInstaller().activateRootfs(missingStaging, linux, backup)
            throw AssertionError("Expected activation to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("old", File(linux, "old-rootfs").readText())
            assertFalse(backup.exists())
        }
    }

    @Test
    fun backupRecoveryRestoresRootfsAfterInterruptedActivation() {
        val linux = tmp.newFolder("linux")
        val backup = tmp.newFolder("backup")
        File(backup, "old-rootfs").writeText("old")

        createInstaller().recoverRootfsBackup(linux, backup)

        assertEquals("old", File(linux, "old-rootfs").readText())
        assertFalse(backup.exists())
    }

"""
    if "activationFailureRestoresPreviousRootfs" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1:
            raise SystemExit("RootfsInstallerTest insertion point changed")
        tests = tests.replace(marker, test_methods + marker, 1)
    tests_path.write_text(tests)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path.cwd())
    apply(parser.parse_args().root.resolve())
    print("Applied transactional RootFS activation and recovery hardening.")
