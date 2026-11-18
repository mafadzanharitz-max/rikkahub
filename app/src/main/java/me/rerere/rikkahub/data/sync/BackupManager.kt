package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.model.SshWorkspaceSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SecretCipher
import java.io.File
import java.io.FileOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backup transports carry account credentials. Require TLS for remote endpoints while
 * retaining loopback HTTP for local development/self-hosted services on the same device.
 */
internal fun requireSecureBackupEndpoint(rawEndpoint: String) {
    require(rawEndpoint.isNotBlank()) { "Backup server address is required" }
    val normalized = if ("://" in rawEndpoint) rawEndpoint else "https://$rawEndpoint"
    val uri = java.net.URI(normalized)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
    require(!host.isNullOrBlank()) { "Invalid backup server address" }
    val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    require(scheme == "https" || (scheme == "http" && isLoopback)) {
        "Remote backup servers must use HTTPS. HTTP is allowed only for localhost."
    }
}

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean, password: String): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".rkhb", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.awaitLoaded()
            ZipOutputStream(encryptedOutputStream(archive, password)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    rewriteSshCredentials(snapshot, forPortableBackup = true)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean, password: String? = null) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    val decryptedArchive = File.createTempFile("restore_plain_", ".zip", context.cacheDir)
                    val archiveToRead = try {
                        if (isEncryptedArchive(archive)) {
                            require(!password.isNullOrBlank()) { "Backup password is required" }
                            decryptArchive(archive, decryptedArchive, password)
                            decryptedArchive
                        } else {
                            archive // Legacy ZIP backups remain importable.
                        }
                    } catch (e: Throwable) {
                        decryptedArchive.delete()
                        throw e
                    }
                    try {
                    ZipFile(archiveToRead).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        rewriteSshCredentials(stagedDatabase, forPortableBackup = false)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                        val settings = json.decodeFromString<Settings>(migrated)
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeSettings(settings, json.launchCountOf(migrated)))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                    } finally {
                        decryptedArchive.delete()
                    }
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"
        private val BACKUP_MAGIC = byteArrayOf(0x52, 0x4B, 0x48, 0x42, 0x4B, 0x50, 0x31, 0x00)
        private const val BACKUP_VERSION = 1
        private const val BACKUP_KDF_ITERATIONS = 210_000
        private const val BACKUP_SALT_BYTES = 16
        private const val BACKUP_IV_BYTES = 12
        private const val BACKUP_TAG_BITS = 128

        /**
         * SSH credentials use a device-bound Android Keystore key in the live database.
         * Convert them to plaintext only inside the temporary database snapshot, which is
         * immediately streamed into the password-encrypted archive; re-encrypt on restore.
         */
        private fun rewriteSshCredentials(databaseFile: File, forPortableBackup: Boolean) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                val hasTable = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='workspaces'",
                    null,
                ).use { it.moveToFirst() }
                if (!hasTable) return

                val hasColumn = db.rawQuery("PRAGMA table_info(workspaces)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "ssh_config") found = true
                    }
                    found
                }
                if (!hasColumn) return

                val rows = mutableListOf<Pair<String, String>>()
                db.rawQuery("SELECT id, ssh_config FROM workspaces WHERE ssh_config IS NOT NULL", null).use { cursor ->
                    while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getString(1)
                }
                if (rows.isEmpty()) return

                db.beginTransaction()
                try {
                    rows.forEach { (id, rawConfig) ->
                        val settings = JsonInstant.decodeFromString<SshWorkspaceSettings>(rawConfig)
                        fun transform(secret: String?): String? = secret?.let { value ->
                            if (forPortableBackup) {
                                if (value.startsWith("v1:")) SecretCipher.decrypt(value) else value
                            } else {
                                if (value.startsWith("v1:")) value else SecretCipher.encrypt(value)
                            }
                        }
                        val portable = settings.copy(
                            encryptedPassword = transform(settings.encryptedPassword),
                            encryptedPrivateKey = transform(settings.encryptedPrivateKey),
                            encryptedPassphrase = transform(settings.encryptedPassphrase),
                            jumpHost = settings.jumpHost?.copy(
                                encryptedPassword = transform(settings.jumpHost.encryptedPassword),
                                encryptedPrivateKey = transform(settings.jumpHost.encryptedPrivateKey),
                                encryptedPassphrase = transform(settings.jumpHost.encryptedPassphrase),
                            ),
                        )
                        val values = ContentValues().apply {
                            put("ssh_config", JsonInstant.encodeToString(portable))
                        }
                        check(db.update("workspaces", values, "id = ?", arrayOf(id)) == 1) {
                            "Could not prepare SSH credentials for portable backup"
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
        }

        private fun isEncryptedArchive(file: File): Boolean {
            if (file.length() < BACKUP_MAGIC.size) return false
            return runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val header = ByteArray(BACKUP_MAGIC.size)
                    input.readFully(header)
                    header.contentEquals(BACKUP_MAGIC)
                }
            }.getOrDefault(false)
        }

        private fun backupKey(password: String, salt: ByteArray): javax.crypto.SecretKey {
            require(password.length >= 10) { "Backup password must contain at least 10 characters" }
            val spec = PBEKeySpec(password.toCharArray(), salt, BACKUP_KDF_ITERATIONS, 256)
            return try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                SecretKeySpec(bytes, "AES")
            } finally {
                spec.clearPassword()
            }
        }

        private fun encryptedOutputStream(target: File, password: String): CipherOutputStream {
            val salt = ByteArray(BACKUP_SALT_BYTES).also(SecureRandom()::nextBytes)
            val iv = ByteArray(BACKUP_IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, backupKey(password, salt), GCMParameterSpec(BACKUP_TAG_BITS, iv))
                updateAAD(BACKUP_MAGIC + byteArrayOf(BACKUP_VERSION.toByte()))
            }
            val output = DataOutputStream(target.outputStream().buffered())
            output.write(BACKUP_MAGIC)
            output.writeByte(BACKUP_VERSION)
            output.write(salt)
            output.write(iv)
            return CipherOutputStream(output, cipher)
        }

        private fun decryptArchive(source: File, target: File, password: String) {
            DataInputStream(source.inputStream().buffered()).use { input ->
                val magic = ByteArray(BACKUP_MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(BACKUP_MAGIC)) { "Unsupported encrypted backup format" }
                val version = input.readUnsignedByte()
                require(version == BACKUP_VERSION) { "Unsupported encrypted backup version: $version" }
                val salt = ByteArray(BACKUP_SALT_BYTES).also(input::readFully)
                val iv = ByteArray(BACKUP_IV_BYTES).also(input::readFully)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, backupKey(password, salt), GCMParameterSpec(BACKUP_TAG_BITS, iv))
                    updateAAD(BACKUP_MAGIC + byteArrayOf(version.toByte()))
                }
                CipherInputStream(input, cipher).use { decrypted ->
                    target.outputStream().buffered().use { decrypted.copyTo(it) }
                }
            }
        }

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeFromString<Settings>(settingsJson),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}
