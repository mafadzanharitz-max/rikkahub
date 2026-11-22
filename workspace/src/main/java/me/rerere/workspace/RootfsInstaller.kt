package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun install(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        val parsedUrl = URI(url)
        require(parsedUrl.scheme.equals("https", ignoreCase = true) &&
            !parsedUrl.host.isNullOrBlank() && parsedUrl.rawUserInfo == null
        ) { "Rootfs download URL must be a valid HTTPS URL" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(url)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val backupDir = File(tempDir, "rootfs-backup")
        val linuxDir = manager.linuxDir(root)
        var previousMoved = false
        var newInstalled = false

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            download(url, archive, onProgress)
            extractTar(archive, stagingDir, format, onProgress)
            if (backupDir.exists() && !linuxDir.exists()) {
                require(backupDir.renameTo(linuxDir)) {
                    "Failed to recover the previous rootfs backup"
                }
            }
            backupDir.deleteRecursively()
            if (linuxDir.exists()) {
                require(linuxDir.renameTo(backupDir)) {
                    "Failed to preserve the previous rootfs before replacement"
                }
                previousMoved = true
            }
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            newInstalled = true
            patcher.patch(linuxDir)
            backupDir.deleteRecursively()
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } catch (error: Throwable) {
            if (newInstalled) linuxDir.deleteRecursively()
            if (previousMoved && backupDir.exists() && !backupDir.renameTo(linuxDir)) {
                error.addSuppressed(IOException("Failed to restore previous rootfs from " + backupDir.absolutePath))
            }
            throw error
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = connectionFactory(URL(url))
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            require(connection.url.protocol.equals("https", ignoreCase = true)) {
                "Rootfs download redirects must remain on HTTPS"
            }
            require(code in 200..299) { "Rootfs download failed: HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            require(totalBytes == null || totalBytes <= MAX_DOWNLOAD_BYTES) {
                "Rootfs archive exceeds the maximum download size"
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytesRead += read
                        require(bytesRead <= MAX_DOWNLOAD_BYTES) {
                            "Rootfs archive exceeds the maximum download size"
                        }
                        output.write(buffer, 0, read)
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            var expandedBytes = 0L
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                require(header.size >= 0) { "Invalid negative tar entry size" }
                expandedBytes += header.size
                require(expandedBytes <= MAX_EXTRACTED_BYTES) {
                    "Rootfs archive exceeds the maximum extracted size"
                }
                entries++
                require(entries <= MAX_TAR_ENTRIES) {
                    "Rootfs archive contains too many entries"
                }
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            val rootFile = root.canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
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

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    // 根目录条目 (如 Alpine minirootfs 开头的 "./") 规范化后为空串, 由调用方跳过
    private fun normalizeTarPath(path: String): String {
        require(!path.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        val segments = path
            .replace('\\', '/')
            .trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return segments.joinToString("/")
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_TAR_ENTRIES = 200_000
    }
}
