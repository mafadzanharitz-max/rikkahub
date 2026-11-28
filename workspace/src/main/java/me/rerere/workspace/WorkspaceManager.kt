package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
) {
    private val fileSystem = WorkspaceFileSystem(config)
    private val rootfsLocks = ConcurrentHashMap<String, Any>()
    private val rootfsGates = ConcurrentHashMap<String, RootfsGate>()

    private class RootfsGate {
        val monitor = java.lang.Object()
        var activeLeases = 0
        var maintenance = false
    }

    /** Serialize installer activation, recovery, startup cleanup and workspace deletion per root. */
    fun <T> withRootfsLock(root: String, block: () -> T): T {
        val key = workspaceDir(root).absolutePath
        val lock = rootfsLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock, block)
    }

    init {
        baseDir.mkdirs()
    }

    /**
     * 工作区的目录约定。
     *
     * [extraMounts] 是单个工作区自己的挂载 (如用户配置的外部存储目录), 叠加在全局挂载表之后;
     * 下面凡是带这个参数的方法, 调用方需传入同一份列表, 否则 PRoot 内外看到的路径会对不上。
     */
    fun layout(extraMounts: List<WorkspaceBindMount> = emptyList()): WorkspaceLayout {
        val (writable, approval) = mountsOf(extraMounts).partition { it.writable }
        return WorkspaceLayout(
            workspaceDir = ROOTFS_WORKSPACE_DIR,
            writableRoots = listOf(ROOTFS_WORKSPACE_DIR, "/tmp") + writable.map { it.target.trimEnd('/') },
            approvalRoots = approval.map { it.target.trimEnd('/') },
        )
    }

    fun backend(
        root: String,
        shellCompatibilityMode: Boolean = false,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceBackend = LocalWorkspaceBackend(this, root, shellCompatibilityMode, extraMounts)

    private fun mountsOf(extraMounts: List<WorkspaceBindMount>): List<WorkspaceBindMount> =
        if (extraMounts.isEmpty()) bindMounts else bindMounts + extraMounts

    /**
     * PRoot 会跳过不存在的源目录, 这时 Rootfs 里对应路径落回 Rootfs 自身:
     * 写入能「成功」, 文件却不在挂载源里, 文件工具也读不到。工作区挂载失效时直接报错。
     */
    private fun requireMountsAvailable(extraMounts: List<WorkspaceBindMount>) {
        val unavailable = extraMounts.filterNot { it.source.isDirectory }
        if (unavailable.isNotEmpty()) {
            throw WorkspaceMountUnavailableException(unavailable.map { it.target })
        }
    }

    /**
     * Close admission for new RootFS users. The caller can stop active sessions,
     * then call awaitIdle() before changing the workspace.
     */
    fun requestRootfsMaintenance(root: String): RootfsMaintenance {
        val key = workspaceDir(root).absolutePath
        val gate = rootfsGates.computeIfAbsent(key) { RootfsGate() }
        synchronized(gate.monitor) {
            while (gate.maintenance) gate.monitor.wait()
            gate.maintenance = true
        }
        return RootfsMaintenance(
            root = root,
            awaitIdleAction = {
                synchronized(gate.monitor) {
                    while (gate.activeLeases > 0) gate.monitor.wait()
                }
            },
            releaseAction = {
                synchronized(gate.monitor) {
                    gate.maintenance = false
                    gate.monitor.notifyAll()
                }
            },
        )
    }

    /** Hold a lease for the complete lifetime of a process using this RootFS. */
    fun acquireRootfsLease(root: String): RootfsLease {
        val key = workspaceDir(root).absolutePath
        val gate = rootfsGates.computeIfAbsent(key) { RootfsGate() }
        synchronized(gate.monitor) {
            while (gate.maintenance) gate.monitor.wait()
            gate.activeLeases++
        }
        return RootfsLease {
            synchronized(gate.monitor) {
                check(gate.activeLeases > 0) { "RootFS lease underflow for $root" }
                gate.activeLeases--
                gate.monitor.notifyAll()
            }
        }
    }

    fun <T> withRootfsMaintenance(
        root: String,
        maintenance: RootfsMaintenance? = null,
        block: () -> T,
    ): T {
        if (maintenance != null) {
            require(maintenance.root == root) { "RootFS maintenance token belongs to another workspace" }
            maintenance.awaitIdle()
            return withRootfsLock(root, block)
        }
        val token = requestRootfsMaintenance(root)
        return try {
            token.awaitIdle()
            withRootfsLock(root, block)
        } finally {
            token.close()
        }
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = rootfsValidationError(root) == null

    fun rootfsValidationError(root: String): String? = rootfsValidationError(linuxDir(root))

    fun rootfsShell(root: String): String = rootfsShell(linuxDir(root))

    fun deleteWorkspace(root: String, maintenance: RootfsMaintenance? = null): Boolean =
        withRootfsMaintenance(root, maintenance) {
            workspaceDir(root).deleteRecursively()
        }

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(
        root: String,
        path: String,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
        mountsOf(extraMounts).sortedByDescending { it.target.trimEnd('/').length }.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String, extraMounts: List<WorkspaceBindMount> = emptyList()): Long =
        resolveRootfsFile(root, path, extraMounts).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(
        root: String,
        path: String,
        outputStream: OutputStream,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ) {
        val file = resolveRootfsFile(root, path, extraMounts)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Rootfs 内绝对路径对应的宿主机文件, 不检查是否存在 */
    fun resolveRootfsFile(root: String, path: String, extraMounts: List<WorkspaceBindMount> = emptyList()): File {
        val location = resolveRootfsPath(root, path, extraMounts)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        shellCompatibilityMode: Boolean = false,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceCommandResult {
        val lease = acquireRootfsLease(root)
        return try {
            shellRunner.execute(
                shellContext(root, command, cwd, timeoutMillis, stdin, shellCompatibilityMode, extraMounts)
            )
        } finally {
            lease.close()
        }
    }

    fun startBackgroundCommand(
        root: String,
        command: String,
        cwd: String,
        id: String,
        shellCompatibilityMode: Boolean = false,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceBackgroundCommand {
        val lease = acquireRootfsLease(root)
        return try {
            LeasedWorkspaceBackgroundCommand(
                shellRunner.startBackground(
                    shellContext(root, command, cwd, 0, null, shellCompatibilityMode, extraMounts),
                    id,
                ),
                lease,
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private fun shellContext(
        root: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        shellCompatibilityMode: Boolean,
        extraMounts: List<WorkspaceBindMount>,
    ): WorkspaceShellContext {
        require(command.isNotBlank()) { "Command is required" }
        requireMountsAvailable(extraMounts)
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return WorkspaceShellContext(
            root = root,
            command = command,
            cwd = cwd,
            filesDir = filesDir(root),
            linuxDir = linuxDir(root),
            tempDir = tempDir(root),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            bindMounts = mountsOf(extraMounts),
            shellCompatibilityMode = shellCompatibilityMode,
        )
    }

    /** 交互式终端启动前的准备: 补齐目录, 并按当前网络刷新 Rootfs 的 DNS 等配置 */
    fun prepareTerminal(root: String, nameservers: List<String>) = withRootfsLock(root) {
        ensureWorkspace(root)
        bindMounts.forEach { it.source.mkdirs() }
        RootfsPatcher().patch(linuxDir(root), RootfsPatchOptions(nameservers = nameservers))
    }

    fun terminalLaunch(
        root: String,
        shellCompatibilityMode: Boolean,
        extraMounts: List<WorkspaceBindMount> = emptyList(),
    ): WorkspaceTerminalLaunch? = withRootfsLock(root) {
        requireMountsAvailable(extraMounts)
        shellRunner.terminalLaunch(
            WorkspaceTerminalContext(
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                bindMounts = mountsOf(extraMounts),
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX) && root != "." && root != "..") {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            withRootfsMaintenance(root) {
                // Keep the transactional backup intact until RootfsInstaller recovery has inspected it.
                val temp = tempDir(root)
                temp.listFiles()?.filterNot { it.name == "rootfs-backup" }?.forEach { it.deleteRecursively() }
                // Clear temporary contents without removing required RootFS directories.
                // Rootfs validation requires /tmp to exist; deleting the directory itself
                // makes a healthy installation appear broken on the next application launch.
                listOf("tmp", "var/tmp").forEach { relativePath ->
                    File(linuxDir(root), relativePath).listFiles()?.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** Rootfs 内执行命令时的 HOME */
        const val ROOTFS_HOME_DIR = "/root"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

        /** Return null only when the active RootFS passes every structural and ARMHF check. */
        fun rootfsValidationError(linuxDir: File): String? {
            if (!linuxDir.isDirectory) return "RootFS directory is missing or is not a directory: ${linuxDir.absolutePath}"
            return try {
                Armv7Rootfs.validate(linuxDir)
                null
            } catch (error: Exception) {
                error.message?.takeIf { it.isNotBlank() }
                    ?: "RootFS validation failed (${error.javaClass.simpleName})"
            }
        }

        fun isUsableRootfs(linuxDir: File): Boolean = rootfsValidationError(linuxDir) == null

        /** Rootfs 内使用的 shell: 优先 bash, 没有时 (如 Alpine minirootfs) 回退到 /bin/sh */
        fun rootfsShell(linuxDir: File): String =
            if (linuxDir.hasRootfsEntry("bin/bash")) "/bin/bash" else "/bin/sh"

        // Alpine 等发行版的 /bin/sh 是指向 /bin/busybox 的绝对路径软链, 在宿主机上解析不到目标,
        // 只有 proot 内才能正确解析, 所以软链本身存在即视为存在
        private fun File.hasRootfsEntry(path: String): Boolean {
            val entry = File(this, path)
            return entry.isFile || Files.isSymbolicLink(entry.toPath())
        }
    }
}

/** 工作区挂载的源目录不存在或无权访问; 消息是英文, 会原样交给 AI, 界面按 [targets] 自行本地化 */
class WorkspaceMountUnavailableException(
    val targets: List<String>,
) : IllegalArgumentException(
    "Mounted directories are unavailable (missing, or storage permission not granted): " +
        targets.joinToString() + ". Fix or remove them in the workspace settings."
)

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)


/** Thread-independent lease for a running RootFS process. */
class RootfsLease internal constructor(private val releaseAction: () -> Unit) : AutoCloseable {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/** Admission barrier used to stop new processes before workspace maintenance. */
class RootfsMaintenance internal constructor(
    val root: String,
    private val awaitIdleAction: () -> Unit,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    fun awaitIdle() {
        check(!closed.get()) { "RootFS maintenance token is already closed" }
        awaitIdleAction()
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}
