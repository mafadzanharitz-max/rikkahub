#!/usr/bin/env python3
"""Apply bounded, path-safe RootFS extraction after the pinned ARMv7 overlay."""
from pathlib import Path

INSTALLER = Path("workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt")
TESTS = Path("workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt")

def once(s, old, new, label):
    n = s.count(old)
    if n == 0 and new in s:
        return s
    if n != 1:
        raise SystemExit(f"{label}: expected one target, found {n}")
    return s.replace(old, new, 1)

def apply(root):
    p = root / INSTALLER
    s = p.read_text()
    s = once(s, '            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }\n',
'''            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            require(totalBytes == null || totalBytes <= MAX_COMPRESSED_ARCHIVE_BYTES) {
                "RootFS archive exceeds the compressed size limit"
            }
''', "download content length")
    s = once(s, '                            output.write(buffer, 0, read)\n                            bytesRead += read\n',
'''                            require(bytesRead <= MAX_COMPRESSED_ARCHIVE_BYTES - read) {
                                "RootFS archive exceeds the compressed size limit"
                            }
                            require((target.parentFile?.usableSpace ?: Long.MAX_VALUE) >=
                                read + MIN_FREE_SPACE_BYTES) {
                                "Insufficient storage space to download RootFS archive"
                            }
                            output.write(buffer, 0, read)
                            bytesRead += read
''', "download byte accounting")
    s = once(s, '        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->\n            var entries = 0\n',
'''        require(archive.isFile && archive.length() <= MAX_COMPRESSED_ARCHIVE_BYTES) {
            "RootFS archive exceeds the compressed size limit"
        }
        require(targetDir.isDirectory || targetDir.mkdirs()) {
            "Unable to create RootFS staging directory"
        }
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { rawInput ->
            val input = LimitedInputStream(rawInput, MAX_EXPANDED_ARCHIVE_BYTES)
            var entries = 0
            var extractedBytes = 0L
''', "extract stream initialization")
    s = once(s, '                val rawHeader = input.readTarHeader() ?: break\n                val header = rawHeader.copy(\n',
'''                val rawHeader = input.readTarHeader() ?: break
                entries++
                require(entries <= MAX_ARCHIVE_ENTRIES) {
                    "RootFS archive contains too many entries"
                }
                require(rawHeader.size >= 0L && rawHeader.size <= MAX_EXPANDED_ARCHIVE_BYTES) {
                    "RootFS archive entry has an invalid size"
                }
                val header = rawHeader.copy(
''', "entry count and size validation")
    for typ, label in [("LONG_NAME", "long name"), ("LONG_LINK", "long link"), ("PAX", "PAX")]:
        needle = f'                if (header.type == TarEntryType.{typ}) {{\n'
        insert = needle + '''                    require(header.size <= MAX_METADATA_ENTRY_BYTES) {
                        "RootFS archive metadata entry is too large"
                    }
'''
        s = once(s, needle, insert, label + " metadata bound")
    s = once(s, '                val target = targetDir.safeResolve(header.name)\n                target.parentFile?.mkdirs()\n',
'''                if (header.type == TarEntryType.FILE) {
                    require(header.size <= MAX_INDIVIDUAL_FILE_BYTES) {
                        "RootFS archive contains an oversized file"
                    }
                    require(extractedBytes <= MAX_EXTRACTED_FILE_BYTES - header.size) {
                        "RootFS extracted files exceed the size limit"
                    }
                    val available = targetDir.usableSpace
                    require(available >= header.size + MIN_FREE_SPACE_BYTES) {
                        "Insufficient storage space to extract RootFS"
                    }
                    extractedBytes += header.size
                }
                val target = targetDir.safeResolve(header.name)
                target.parentFile?.let { parent ->
                    require(parent.mkdirs() || parent.isDirectory) {
                        "Unable to create RootFS entry parent directory"
                    }
                }
''', "target resolution and extraction budget")
    s = once(s, '                entries++\n                onProgress(\n', '                onProgress(\n', "remove duplicate entry counter")

    a = s.find("    private fun createSymlink(")
    b = s.find("    private fun createHardLink(", a)
    if a < 0 or b < 0: raise SystemExit("Symlink helper boundaries changed")
    s = s[:a] + r'''    private fun createSymlink(root: File, target: File, linkName: String) {
        require(linkName.isNotBlank()) { "RootFS symlink target is empty" }
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (target.parentFile ?: root).canonicalFile.toPath()
        val resolved = if (File(linkName).isAbsolute) {
            root.resolve(linkName.trimStart('/', '\\')).canonicalFile.toPath()
        } else {
            File(target.parentFile ?: root, linkName).canonicalFile.toPath()
        }
        require(resolved == rootPath || resolved.startsWith(rootPath)) {
            "Symlink escapes rootfs: " + target.name
        }
        val relativeTarget = parentPath.relativize(resolved).toFile()
        require(!Files.isSymbolicLink(target.toPath())) {
            "Refusing to replace an existing symlink"
        }
        if (target.exists()) {
            require(target.delete()) { "Unable to replace existing RootFS symlink entry" }
        }
        Files.createSymbolicLink(target.toPath(), relativeTarget.toPath())
    }

''' + s[b:]
    a = s.find("    private fun createHardLink(")
    b = s.find("    private fun InputStream.readTarHeader(", a)
    if a < 0 or b < 0: raise SystemExit("Hardlink helper boundaries changed")
    s = s[:a] + '''    private fun createHardLink(root: File, target: File, linkName: String) {
        require(linkName.isNotBlank()) { "RootFS hardlink target is empty" }
        val source = root.safeResolve(linkName)
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
            "RootFS hardlink source must be an existing regular file"
        }
        require(!Files.isSymbolicLink(target.toPath())) {
            "Refusing to replace an existing symlink with a hardlink"
        }
        if (target.exists()) {
            require(target.delete()) { "Unable to replace existing RootFS hardlink entry" }
        }
        // Do not fall back to copying: that would duplicate unaccounted storage.
        Files.createLink(target.toPath(), source.toPath())
    }

''' + s[b:]

    # Do not traverse a symlink created by an earlier archive entry.
    a = s.find("    private fun File.safeResolve(path: String): File {")
    b = s.find("    private fun File.applyMode(", a)
    if a < 0 or b < 0: raise SystemExit("safeResolve helper boundaries changed")
    s = s[:a] + '''    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        var current = root
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) {
                "RootFS archive entry traverses a symlink: $path"
            }
            if (index < parts.lastIndex) {
                require(!current.exists() || current.isDirectory) {
                    "RootFS archive path has a non-directory parent: $path"
                }
            }
        }
        val canonical = current.canonicalFile
        require(canonical.path == root.path ||
            canonical.path.startsWith(root.path + File.separator)) {
            "RootFS entry escapes target directory: $path"
        }
        return current
    }

''' + s[b:]

    s = once(s, '                    TarEntryType.FILE -> {\\n                        target.outputStream().use { output ->',
'''                    TarEntryType.FILE -> {
                        require(!Files.isSymbolicLink(target.toPath())) {
                            "Refusing to write a RootFS file through a symlink"
                        }
                        if (target.exists()) {
                            require(target.isFile) { "RootFS file entry conflicts with a directory" }
                        }
                        target.outputStream().use { output ->''',
        "regular file output safety")

    marker = "    private fun InputStream.readTarHeader(): TarHeader? {"
    limited = '''    private inner class LimitedInputStream(
        private val delegate: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var consumed = 0L
        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) account(count.toLong())
            return count
        }
        override fun skip(count: Long): Long {
            if (count <= 0L) return 0L
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = count
            while (remaining > 0L) {
                checkInterrupted()
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
            return count - remaining
        }
        private fun account(count: Long) {
            require(consumed <= limit - count) {
                "RootFS archive expands beyond the extraction size limit"
            }
            consumed += count
        }
        override fun close() = delegate.close()
    }

'''
    if "private inner class LimitedInputStream(" not in s:
        if s.count(marker) != 1: raise SystemExit("LimitedInputStream insertion point changed")
        s = s.replace(marker, limited + marker, 1)
    constants = '''        private const val MAX_COMPRESSED_ARCHIVE_BYTES = 512L * 1024 * 1024
        private const val MAX_EXPANDED_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_EXTRACTED_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_INDIVIDUAL_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 100_000
        private const val MAX_METADATA_ENTRY_BYTES = 1024L * 1024
        private const val MIN_FREE_SPACE_BYTES = 32L * 1024 * 1024
'''
    if "private const val MAX_COMPRESSED_ARCHIVE_BYTES" not in s:
        s = once(s, "        private const val TAR_BLOCK_SIZE = 512\n",
                 constants + "        private const val TAR_BLOCK_SIZE = 512\n",
                 "archive limit constants")
    p.write_text(s)

    tp = root / TESTS
    tests = tp.read_text()
    tests = once(tests,
        '        assertEquals("/bin/busybox", Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString())',
        '        assertEquals("busybox", Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString())',
        "RootFS absolute symlink expectation")
    methods = '''    @Test
    fun extractRejectsPathTraversal() {
        val archive = tmp.newFile("traversal.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("../outside.txt", '0', "escape".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected path traversal rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(File(tmp.root, "outside.txt").exists())
        }
    }

    @Test
    fun extractRejectsSymlinkEscape() {
        val archive = tmp.newFile("symlink-escape.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("escape", '2', ByteArray(0), linkName = "../../outside")
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe-symlink")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected symlink escape rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(File(tmp.root, "outside").exists())
        }
    }

'''
    if "extractRejectsPathTraversal" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1: raise SystemExit("Archive test insertion point changed")
        tests = tests.replace(marker, methods + marker, 1)
    tp.write_text(tests)

if __name__ == "__main__":
    apply(Path.cwd().resolve())
    print("Applied bounded RootFS archive extraction hardening.")
