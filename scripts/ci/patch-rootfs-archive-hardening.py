#!/usr/bin/env python3
"""Apply bounded, path-safe RootFS extraction after the pinned ARMv7 overlay."""
from pathlib import Path

INSTALLER = Path("workspace/src/main/java/me/rerere/workspace/RootfsInstaller.kt")
TESTS = Path("workspace/src/test/java/me/rerere/workspace/RootfsInstallerTest.kt")

def once(s, old, new, label):
    old_count = s.count(old)
    new_count = s.count(new)
    if new_count and old_count == 0:
        return s
    if new_count and old_count == 1 and len(old) <= len(new):
        old_index = s.find(old)
        new_index = s.find(new)
        if new_index <= old_index < new_index + len(new):
            return s
        raise SystemExit(f"{label}: patched and unpatched targets both present")
    if old_count != 1:
        raise SystemExit(f"{label}: expected one target, found {old_count}")
    return s.replace(old, new, 1)

def apply(root):
    p = root / INSTALLER
    s = p.read_text()
    old_pax = r'''    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }
'''
    new_pax = r'''    private fun parsePax(text: String): Map<String, String> {
        // PAX record lengths are UTF-8 byte counts, not Kotlin character counts.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < bytes.size) {
            val space = bytes.indexOf(' '.code.toByte(), index)
            require(space > index) { "Malformed RootFS PAX record length" }
            val lengthText = String(bytes, index, space - index, Charsets.US_ASCII)
            require(lengthText.isNotEmpty() && lengthText.all { it in '0'..'9' }) {
                "Malformed RootFS PAX record length"
            }
            val length = lengthText.toIntOrNull()
                ?: throw IllegalArgumentException("RootFS PAX record length is out of range")
            require(length > 0 && length <= bytes.size - index) {
                "RootFS PAX record exceeds its metadata entry"
            }
            val end = index + length
            val recordEnd = end - 1
            require(recordEnd > space && bytes[recordEnd] == '\n'.code.toByte()) {
                "Malformed RootFS PAX record terminator"
            }
            val recordStart = space + 1
            val equals = bytes.indexOf('='.code.toByte(), recordStart)
            require(equals > recordStart && equals < recordEnd) {
                "Malformed RootFS PAX key/value record"
            }
            val key = String(bytes, recordStart, equals - recordStart, Charsets.UTF_8)
            val value = String(bytes, equals + 1, recordEnd - equals - 1, Charsets.UTF_8)
            result[key] = value
            index = end
        }
        return result
    }
'''
    s = once(s, old_pax, new_pax, "PAX record parser")
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
            root.resolve(linkName.trimStart('/')).canonicalFile.toPath()
        } else {
            File(target.parentFile ?: root, linkName).canonicalFile.toPath()
        }
        require(resolved == rootPath || resolved.startsWith(rootPath)) {
            "Symlink escapes rootfs: " + target.name
        }
        val relativeTarget = parentPath.relativize(resolved).toFile()
        // Replace the final link itself, including dangling links; never follow it.
        if (Files.isSymbolicLink(target.toPath())) {
            Files.delete(target.toPath())
        } else if (target.exists()) {
            require(target.isFile && target.delete()) {
                "Unable to replace existing RootFS symlink entry"
            }
        }
        Files.createSymbolicLink(target.toPath(), relativeTarget.toPath())
    }

''' + s[b:]
    a = s.find("    private fun createHardLink(")
    b = s.find("    private fun InputStream.readTarHeader(", a)
    if a < 0 or b < 0: raise SystemExit("Hardlink helper boundaries changed")
    s = s[:a] + '''    internal fun createHardLink(
        root: File,
        target: File,
        linkName: String,
        createLink: (java.nio.file.Path, java.nio.file.Path) -> java.nio.file.Path = { link, existing ->
            Files.createLink(link, existing)
        },
    ) {
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
        try {
            createLink(target.toPath(), source.toPath())
        } catch (failure: Throwable) {
            if (failure !is IOException &&
                failure !is UnsupportedOperationException &&
                failure !is SecurityException
            ) {
                throw failure
            }
            // Some Android app-data SELinux policies reject linkat(). Materialize
            // the archive hardlink as an independent file when the host forbids it.
            if (target.exists() || Files.isSymbolicLink(target.toPath())) throw failure
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
            target.setLastModified(source.lastModified())
        }
    }

''' + s[b:]

    # Do not traverse a symlink created by an earlier archive entry.
    a = s.find("    private fun File.safeResolve(path: String): File {")
    b = s.find("    private fun File.applyMode(", a)
    if a < 0 or b < 0: raise SystemExit("safeResolve helper boundaries changed")
    s = s[:a] + '''    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val rootPath = root.toPath()
        var current = root
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            current = File(current, part)
            // Parent symlinks redirect subsequent archive writes and are forbidden.
            // The final component may be a dangling link; entry-specific logic handles it.
            if (index < parts.lastIndex) {
                require(!Files.isSymbolicLink(current.toPath())) {
                    "RootFS archive entry traverses a symlink: $path"
                }
                require(!current.exists() || current.isDirectory) {
                    "RootFS archive path has a non-directory parent: $path"
                }
            }
        }
        require(current.toPath().normalize().startsWith(rootPath)) {
            "RootFS entry escapes target directory: $path"
        }
        return current
    }

''' + s[b:]

    s = once(s, '                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)\n',
'''                    TarEntryType.HARDLINK -> {
                        val source = targetDir.safeResolve(header.linkName)
                        require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
                            "RootFS hardlink source must be an existing regular file"
                        }
                        val linkBytes = source.length()
                        require(linkBytes <= MAX_INDIVIDUAL_FILE_BYTES) {
                            "RootFS hardlink exceeds the individual file size limit"
                        }
                        require(extractedBytes <= MAX_EXTRACTED_FILE_BYTES - linkBytes) {
                            "RootFS hardlinks exceed the extracted size limit"
                        }
                        require(targetDir.usableSpace >= linkBytes + MIN_FREE_SPACE_BYTES) {
                            "Insufficient storage space to materialize RootFS hardlink"
                        }
                        extractedBytes += linkBytes
                        createHardLink(targetDir, target, header.linkName)
                    }
''', "hardlink extraction budget")

    s = once(s, '                    TarEntryType.FILE -> {\n                        target.outputStream().use { output ->',
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
    fun extractRejectsHardlinkEscape() {
        val archive = tmp.newFile("hardlink-escape.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("escape", '1', ByteArray(0), linkName = "../outside")
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe-hardlink")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected hardlink escape rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(File(tmp.root, "outside").exists())
        }
    }

    @Test
    fun extractRejectsOversizedIndividualFile() {
        val archive = tmp.newFile("oversized.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            val header = ByteArray(TAR_BLOCK)
            "oversized.bin".toByteArray().copyInto(header, 0)
            (512L * 1024 * 1024 + 1).toString(8).padStart(11, '0')
                .toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            out.write(header)
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("oversized")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected oversized file rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(File(target, "oversized.bin").exists())
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

    @Test
    fun extractRejectsTraversalThroughEarlierSymlink() {
        val archive = tmp.newFile("symlink-parent.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("real/", '5', ByteArray(0))
            out.writeTarEntry("alias", '2', ByteArray(0), linkName = "real")
            out.writeTarEntry("alias/escape.txt", '0', "escape".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe-symlink-parent")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected symlink parent traversal rejection")
        } catch (expected: IllegalArgumentException) {
            assertFalse(File(target, "real/escape.txt").exists())
        }
    }

    @Test
    fun extractRejectsOversizedMetadataEntry() {
        val archive = tmp.newFile("oversized-metadata.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            val header = ByteArray(TAR_BLOCK)
            "metadata".toByteArray().copyInto(header, 0)
            (1024L * 1024 + 1).toString(8).padStart(11, '0')
                .toByteArray().copyInto(header, 124)
            header[156] = 'L'.code.toByte()
            out.write(header)
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe-metadata")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected oversized metadata rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("metadata"))
        }
    }

    @Test
    fun extractRejectsMalformedPaxRecordLength() {
        val archive = tmp.newFile("malformed-pax.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("PaxHeaders/bad", 'x', "0 path=bad\\n".toByteArray())
            out.writeTarEntry("fallback", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("safe-pax")
        try {
            createInstaller().extractTar(archive, target) {}
            throw AssertionError("Expected malformed PAX record rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("PAX"))
            assertFalse(File(target, "fallback").exists())
        }
    }

    @Test
    fun extractUsesUtf8ByteLengthsForMultiplePaxRecords() {
        val archive = tmp.newFile("unicode-pax.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("目录/", '5', ByteArray(0))
            out.writeTarEntry("目录/target", '0', "target".toByteArray())
            val pax = paxRecord("path", "目录/link") + paxRecord("linkpath", "target")
            out.writeTarEntry("PaxHeaders/link", 'x', pax)
            out.writeTarEntry("fallback", '2', ByteArray(0), linkName = "wrong")
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("unicode-pax")
        createInstaller().extractTar(archive, target) {}

        assertTrue(Files.isSymbolicLink(File(target, "目录/link").toPath()))
        assertEquals("target", Files.readSymbolicLink(File(target, "目录/link").toPath()).toString())
    }

    private fun paxRecord(key: String, value: String): ByteArray {
        val body = "$key=$value\\n"
        var length = body.toByteArray(Charsets.UTF_8).size + 2
        while (true) {
            val record = "$length $body".toByteArray(Charsets.UTF_8)
            if (record.size == length) return record
            length = record.size
        }
    }

'''
    replacement_test = '''    @Test
    fun extractReplacesExistingDanglingSymlinkEntry() {
        val archive = tmp.newFile("replace-symlink.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("usr/", '5', ByteArray(0))
            out.writeTarEntry("usr/bin/", '5', ByteArray(0))
            out.writeTarEntry("usr/bin/perl", '0', "perl".toByteArray())
            out.writeTarEntry("usr/bin/perl5.38.2", '2', ByteArray(0), linkName = "missing-perl")
            out.writeTarEntry("usr/bin/perl5.38.2", '2', ByteArray(0), linkName = "/usr/bin/perl")
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        val target = tmp.newFolder("replace-symlink")
        createInstaller().extractTar(archive, target) {}

        val link = File(target, "usr/bin/perl5.38.2")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("perl", Files.readSymbolicLink(link.toPath()).toString())
        assertEquals("perl", link.readText())
    }

'''
    if "extractReplacesExistingDanglingSymlinkEntry" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1: raise SystemExit("Archive test insertion point changed")
        tests = tests.replace(marker, replacement_test + marker, 1)

    if "extractRejectsPathTraversal" not in tests:
        marker = "    private fun createInstaller()"
        if tests.count(marker) != 1: raise SystemExit("Archive test insertion point changed")
        tests = tests.replace(marker, methods + marker, 1)
    tp.write_text(tests)

if __name__ == "__main__":
    apply(Path.cwd().resolve())
    print("Applied bounded RootFS archive extraction hardening.")
