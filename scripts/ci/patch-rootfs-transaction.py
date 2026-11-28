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

NETWORK_HELPERS = """    /**
     * RootFS archives are executable filesystem images. Never permit cleartext
     * transport, including when the user supplies a custom download URL.
     */
    internal fun requireHttpsUrl(value: String): URL {
        val parsed = URL(value)
        require(parsed.protocol.equals("https", ignoreCase = true) && parsed.host.isNotBlank()) {
            "RootFS downloads and redirects must use HTTPS"
        }
        return parsed
    }

    internal fun resolveHttpsRedirect(currentUrl: String, location: String): String {
        require(location.isNotBlank()) { "RootFS redirect omitted its Location header" }
        val current = requireHttpsUrl(currentUrl)
        val redirected = URL(current, location)
        requireHttpsUrl(redirected.toExternalForm())
        return redirected.toExternalForm()
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
    marker = "    private fun download(\n"
    if "internal fun requireHttpsUrl(" not in installer:
        if installer.count(marker) != 1:
            raise SystemExit("RootfsInstaller network helper insertion point changed")
        installer = installer.replace(marker, NETWORK_HELPERS + marker, 1)
    if "internal fun recoverRootfsBackup(" not in installer:
        if installer.count(marker) != 1:
            raise SystemExit("RootfsInstaller transaction helper insertion point changed")
        installer = installer.replace(marker, HELPERS + marker, 1)
    download_method = "    private fun download(\n        url: String,\n        target: File,\n        onProgress: (RootfsInstallProgress) -> Unit,\n    ) {\n        var currentUrl = requireHttpsUrl(url).toExternalForm()\n        var redirectCount = 0\n        while (true) {\n            val requestUrl = requireHttpsUrl(currentUrl)\n            val connection = requestUrl.openConnection() as HttpURLConnection\n            connection.connectTimeout = CONNECT_TIMEOUT_MS\n            connection.readTimeout = READ_TIMEOUT_MS\n            connection.instanceFollowRedirects = false\n            try {\n                val code = connection.responseCode\n                if (code == 301 ||\n                    code == 302 ||\n                    code == 303 ||\n                    code == 307 ||\n                    code == 308\n                ) {\n                    require(redirectCount < 5) {\n                        \"RootFS download exceeded the redirect limit\"\n                    }\n                    val location = connection.getHeaderField(\"Location\")\n                    currentUrl = resolveHttpsRedirect(currentUrl, location.orEmpty())\n                    redirectCount++\n                    continue\n                }\n                require(code in 200..299) { \"Rootfs download failed: HTTP $code\" }\n                val totalBytes = connection.contentLengthLong.takeIf { it > 0 }\n                target.parentFile?.mkdirs()\n                connection.inputStream.use { input ->\n                    target.outputStream().use { output ->\n                        val buffer = ByteArray(BUFFER_SIZE)\n                        var bytesRead = 0L\n                        var lastReportBytes = 0L\n                        while (true) {\n                            checkInterrupted()\n                            val read = input.read(buffer)\n                            if (read < 0) break\n                            output.write(buffer, 0, read)\n                            bytesRead += read\n                            if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {\n                                lastReportBytes = bytesRead\n                                onProgress(\n                                    RootfsInstallProgress(\n                                        stage = RootfsInstallStage.DOWNLOADING,\n                                        bytesRead = bytesRead,\n                                        totalBytes = totalBytes,\n                                    )\n                                )\n                            }\n                        }\n                        if (bytesRead == 0L) {\n                            onProgress(\n                                RootfsInstallProgress(\n                                    stage = RootfsInstallStage.DOWNLOADING,\n                                    bytesRead = 0,\n                                    totalBytes = totalBytes,\n                                )\n                            )\n                        }\n                    }\n                }\n                return\n            } finally {\n                connection.disconnect()\n            }\n        }\n    }\n\n"
    old_download_start = installer.find("    private fun download(\n")
    old_download_end = installer.find("    internal fun extractTar(\n", old_download_start)
    if old_download_start < 0 or old_download_end < 0:
        raise SystemExit("RootfsInstaller download method insertion point changed")
    current_download = installer[old_download_start:old_download_end]
    if "instanceFollowRedirects = true" in current_download:
        installer = installer[:old_download_start] + download_method + installer[old_download_end:]
    elif "instanceFollowRedirects = false" not in current_download:
        raise SystemExit("RootfsInstaller download method has an unknown implementation")
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
        // Reproduce the exact crash window: old active RootFS has been renamed
        // to backup, but staged RootFS has not yet been moved into the active path.
        val linux = tmp.newFolder("linux")
        listOf("etc", "usr", "var", "tmp").forEach { File(linux, it).mkdirs() }
        File(linux, "usr/bin").mkdirs()
        File(linux, "usr/bin/dash").writeBytes(armv7TestShellElf())
        File(linux, "etc/os-release").writeText("original-rootfs")
        val backup = File(tmp.root, "backup")
        assertTrue("Simulated pre-crash rename must succeed", linux.renameTo(backup))
        assertFalse(linux.exists())

        // A new installer instance represents a fresh process after restart.
        createInstaller().recoverRootfsBackup(linux, backup)

        assertTrue(File(linux, "usr/bin/dash").readBytes().contentEquals(armv7TestShellElf()))
        assertEquals("original-rootfs", File(linux, "etc/os-release").readText())
        assertTrue(WorkspaceManager.isUsableRootfs(linux))
        assertFalse(backup.exists())
    }

    @Test
    fun backupRecoveryRemovesStaleBackupWhenActiveRootfsIsUsable() {
        val linux = tmp.newFolder("linux")
        listOf("etc", "usr", "var", "tmp").forEach { File(linux, it).mkdirs() }
        File(linux, "usr/bin").mkdirs()
        File(linux, "usr/bin/dash").writeBytes(armv7TestShellElf())
        val backup = tmp.newFolder("backup")
        File(backup, "old-rootfs").writeText("old")

        createInstaller().recoverRootfsBackup(linux, backup)

        assertTrue(File(linux, "usr/bin/dash").readBytes().contentEquals(armv7TestShellElf()))
        assertFalse(backup.exists())
    }

"""
    if "activationFailureRestoresPreviousRootfs" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1:
            raise SystemExit("RootfsInstallerTest insertion point changed")
        tests = tests.replace(marker, test_methods + marker, 1)
    network_tests = "    @Test\n    fun rejectsCleartextRootfsUrl() {\n        try {\n            createInstaller().requireHttpsUrl(\"http://example.invalid/rootfs.tar.gz\")\n            throw AssertionError(\"Expected cleartext URL to be rejected\")\n        } catch (expected: IllegalArgumentException) {\n            assertTrue(expected.message.orEmpty().contains(\"HTTPS\"))\n        }\n    }\n\n    @Test\n    fun rejectsHttpsToHttpRedirect() {\n        try {\n            createInstaller().resolveHttpsRedirect(\n                \"https://example.invalid/rootfs.tar.gz\",\n                \"http://mirror.invalid/rootfs.tar.gz\",\n            )\n            throw AssertionError(\"Expected HTTPS downgrade to be rejected\")\n        } catch (expected: IllegalArgumentException) {\n            assertTrue(expected.message.orEmpty().contains(\"HTTPS\"))\n        }\n    }\n\n    @Test\n    fun acceptsRelativeHttpsRedirect() {\n        assertEquals(\n            \"https://example.invalid/mirror/rootfs.tar.gz\",\n            createInstaller().resolveHttpsRedirect(\n                \"https://example.invalid/releases/rootfs.tar.gz\",\n                \"../mirror/rootfs.tar.gz\",\n            ),\n        )\n    }\n\n"
    if "rejectsCleartextRootfsUrl" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1:
            raise SystemExit("RootfsInstallerTest network test insertion point changed")
        tests = tests.replace(marker, network_tests + marker, 1)
    tests_path.write_text(tests)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path.cwd())
    apply(parser.parse_args().root.resolve())
    print("Applied transactional RootFS activation and recovery hardening.")
