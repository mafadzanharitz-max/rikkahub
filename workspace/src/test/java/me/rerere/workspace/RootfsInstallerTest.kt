package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `extract handles alpine style rootfs`() {
        // Alpine minirootfs 以根目录条目 "./" 开头, 且 /bin/sh 是指向 /bin/busybox 的绝对路径软链
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./", '5', ByteArray(0))
            out.writeTarEntry("./etc/", '5', ByteArray(0))
            out.writeTarEntry("./usr/", '5', ByteArray(0))
            out.writeTarEntry("./var/", '5', ByteArray(0))
            out.writeTarEntry("./tmp/", '5', ByteArray(0))
            out.writeTarEntry("./bin/", '5', ByteArray(0))
            out.writeTarEntry("./bin/busybox", '0', armv7TestShellElf())
            out.writeTarEntry("./bin/sh", '2', ByteArray(0), linkName = "/bin/busybox")
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertTrue(File(target, "bin/busybox").readBytes().contentEquals(armv7TestShellElf()))
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString())
        assertTrue(WorkspaceManager.isUsableRootfs(target))
        assertEquals("/bin/sh", WorkspaceManager.rootfsShell(target))

        File(target, "bin/bash").writeText("bash")
        assertEquals("/bin/bash", WorkspaceManager.rootfsShell(target))
    }

    @Test
    fun cleanupPreservesRootfsBackupAndRemovesOtherTemporaryData() {
        val manager = WorkspaceManager(tmp.newFolder("workspace-base"))
        val root = "local"
        manager.ensureWorkspace(root)
        val temp = manager.tempDir(root)
        val backup = File(temp, "rootfs-backup").apply { mkdirs() }
        File(backup, "marker").writeText("preserve")
        val staging = File(temp, "rootfs-staging").apply { mkdirs() }
        File(staging, "partial").writeText("discard")
        val archive = File(temp, "rootfs.tar.gz").apply { writeText("partial") }
        val linux = manager.linuxDir(root)
        listOf("etc", "usr", "var", "tmp").forEach { File(linux, it).mkdirs() }
        File(linux, "usr/bin").mkdirs()
        File(linux, "usr/bin/dash").writeBytes(armv7TestShellElf())
        val rootfsTmp = File(linux, "tmp")
        val varTmp = File(linux, "var/tmp").apply { mkdirs() }
        File(rootfsTmp, "stale").writeText("discard")
        File(varTmp, "stale").writeText("discard")
        assertTrue(manager.hasRootfs(root))

        manager.cleanupAllTempDirs()

        assertEquals("preserve", File(backup, "marker").readText())
        assertFalse(staging.exists())
        assertFalse(archive.exists())
        assertTrue(rootfsTmp.isDirectory)
        assertTrue(varTmp.isDirectory)
        assertFalse(File(rootfsTmp, "stale").exists())
        assertFalse(File(varTmp, "stale").exists())
        assertTrue("Cleanup must not invalidate an otherwise healthy RootFS", manager.hasRootfs(root))
    }

    @Test
    fun recoverBackupRestoresInterruptedRootfsForWorkspace() {
        val manager = WorkspaceManager(tmp.newFolder("recovery-base"))
        val root = "local"
        manager.ensureWorkspace(root)
        val backup = File(manager.tempDir(root), "rootfs-backup").apply { mkdirs() }
        listOf("etc", "usr", "var", "tmp").forEach { File(backup, it).mkdirs() }
        File(backup, "bin").mkdirs()
        File(backup, "bin/sh").writeBytes(armv7TestShellElf())

        RootfsInstaller(manager).recoverBackup(root)

        assertTrue(manager.hasRootfs(root))
        assertFalse(backup.exists())
    }

    @Test
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

    @Test
    fun rejectsCleartextRootfsUrl() {
        try {
            createInstaller().requireHttpsUrl("http://example.invalid/rootfs.tar.gz")
            throw AssertionError("Expected cleartext URL to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun rejectsHttpsToHttpRedirect() {
        try {
            createInstaller().resolveHttpsRedirect(
                "https://example.invalid/rootfs.tar.gz",
                "http://mirror.invalid/rootfs.tar.gz",
            )
            throw AssertionError("Expected HTTPS downgrade to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test
    fun acceptsRelativeHttpsRedirect() {
        assertEquals(
            "https://example.invalid/mirror/rootfs.tar.gz",
            createInstaller().resolveHttpsRedirect(
                "https://example.invalid/releases/rootfs.tar.gz",
                "../mirror/rootfs.tar.gz",
            ),
        )
    }

    @Test
    fun backupRecoveryRejectsInvalidBackupAndPreservesBothDirectories() {
        val manager = WorkspaceManager(tmp.newFolder("invalid-backup-base"))
        val root = "invalid"
        manager.ensureWorkspace(root)
        val linux = manager.linuxDir(root)
        File(linux, "partial").writeText("active-incomplete")
        val backup = File(manager.tempDir(root), "rootfs-backup").apply { mkdirs() }
        File(backup, "partial").writeText("backup-incomplete")

        try {
            RootfsInstaller(manager).recoverBackup(root)
            throw AssertionError("Expected invalid backup recovery to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("backup failed validation"))
        }
        assertEquals("active-incomplete", File(linux, "partial").readText())
        assertEquals("backup-incomplete", File(backup, "partial").readText())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray, linkName: String = "") {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        linkName.toByteArray(Charsets.UTF_8).copyInto(header, 157)
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
