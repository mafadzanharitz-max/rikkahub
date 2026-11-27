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
            out.writeTarEntry("./bin/", '5', ByteArray(0))
            out.writeTarEntry("./bin/busybox", '0', "busybox".toByteArray())
            out.writeTarEntry("./bin/sh", '2', ByteArray(0), linkName = "/bin/busybox")
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("busybox", File(target, "bin/busybox").readText())
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
        val rootfsTmp = File(manager.linuxDir(root), "tmp").apply { mkdirs() }
        File(rootfsTmp, "stale").writeText("discard")

        manager.cleanupAllTempDirs()

        assertEquals("preserve", File(backup, "marker").readText())
        assertFalse(staging.exists())
        assertFalse(archive.exists())
        assertFalse(rootfsTmp.exists())
    }

    @Test
    fun recoverBackupRestoresInterruptedRootfsForWorkspace() {
        val manager = WorkspaceManager(tmp.newFolder("recovery-base"))
        val root = "local"
        manager.ensureWorkspace(root)
        val backup = File(manager.tempDir(root), "rootfs-backup").apply { mkdirs() }
        File(backup, "bin").mkdirs()
        File(backup, "bin/sh").writeText("shell")

        RootfsInstaller(manager).recoverBackup(root)

        assertTrue(manager.hasRootfs(root))
        assertFalse(backup.exists())
    }

    @Test
    fun recoverBackupRestoresValidBackupOverIncompleteActiveRootfs() {
        val manager = WorkspaceManager(tmp.newFolder("incomplete-active-base"))
        val root = "local"
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "bin").mkdirs()
        File(manager.linuxDir(root), "partial-install").writeText("incomplete")
        val backup = File(manager.tempDir(root), "rootfs-backup").apply { mkdirs() }
        File(backup, "bin").mkdirs()
        File(backup, "bin/sh").writeText("previous-shell")

        RootfsInstaller(manager).recoverBackup(root)

        assertTrue(manager.hasRootfs(root))
        assertEquals("previous-shell", File(manager.linuxDir(root), "bin/sh").readText())
        assertFalse(backup.exists())
        assertFalse(File(manager.linuxDir(root), "partial-install").exists())
    }

    @Test
    fun recoverBackupKeepsValidActiveRootfsAndRemovesStaleBackup() {
        val manager = WorkspaceManager(tmp.newFolder("valid-active-base"))
        val root = "local"
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "bin").mkdirs()
        File(manager.linuxDir(root), "bin/sh").writeText("new-shell")
        val backup = File(manager.tempDir(root), "rootfs-backup").apply { mkdirs() }
        File(backup, "bin").mkdirs()
        File(backup, "bin/sh").writeText("previous-shell")

        RootfsInstaller(manager).recoverBackup(root)

        assertTrue(manager.hasRootfs(root))
        assertEquals("new-shell", File(manager.linuxDir(root), "bin/sh").readText())
        assertFalse(backup.exists())
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
