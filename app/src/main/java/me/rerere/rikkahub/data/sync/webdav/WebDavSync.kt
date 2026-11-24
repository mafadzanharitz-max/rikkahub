package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.requireSecureBackupEndpoint
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        val normalizedUrl = if ("://" in config.url) config.url else "https://${config.url}"
        requireSecureBackupEndpoint(normalizedUrl)
        return WebDavClient(config.copy(url = normalizedUrl), httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig, password: String) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, password)
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(
                path = file.name,
                file = file,
                contentType = "application/octet-stream"
            ).getOrThrow()
            Log.i(TAG, "Backup uploaded (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && (it.displayName.endsWith(".zip") || it.displayName.endsWith(".rkhb")) }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem, password: String) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "Downloading remote backup")
            client.downloadToFile(item.href, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, password)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.href).getOrThrow()
        Log.i(TAG, "Remote backup deleted")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig, password: String) {
        restoreFromBackupFile(file, config, password)
    }

    suspend fun prepareBackupFile(config: WebDavConfig, password: String): File = backupManager.createBackup(
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        password = password,
    )

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig, password: String) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        password = password,
    )

}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
