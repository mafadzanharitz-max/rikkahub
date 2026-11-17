package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

private const val API_URL = "https://updates.rikka-ai.com/"
private const val RELEASE_ASSET_PREFIX =
    "https://github.com/mafadzanharitz-max/rikkahub/releases/download/"
private const val MAX_APK_BYTES = 300L * 1024L * 1024L

class UpdateChecker(
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val updateState: StateFlow<UiState<UpdateInfo>> = checkUpdate().stateIn(
        scope = appScope,
        started = SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    private fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(API_URL)
                            .get()
                            .addHeader(
                                "User-Agent",
                                "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    response.use {
                        if (it.isSuccessful) {
                            json.decodeFromString<UpdateInfo>(it.body.string())
                        } else {
                            throw Exception("Failed to fetch update info (HTTP ${it.code})")
                        }
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(context, "Allow installs from this app in Android settings.", Toast.LENGTH_LONG).show()
            }
            return
        }

        appScope.launch {
            var temporaryFile: File? = null
            try {
                val verifiedApk = withContext(Dispatchers.IO) {
                    require(download.name.matches(Regex("rikkahub-.+-armv7a-[0-9a-f]{7,40}\\.apk"))) {
                        "Unexpected APK filename"
                    }
                    require(download.url.startsWith(RELEASE_ASSET_PREFIX)) { "Untrusted APK download URL" }
                    require(download.checksumUrl.startsWith(RELEASE_ASSET_PREFIX)) {
                        "Untrusted checksum URL"
                    }

                    val checksumRequest = Request.Builder().url(download.checksumUrl).get().build()
                    val checksumResponse = client.newCall(checksumRequest).execute()
                    val checksumText = checksumResponse.use {
                        check(it.isSuccessful) { "Checksum download failed (HTTP ${it.code})" }
                        val body = it.body
                        check(body.contentLength() in 0..(1024L * 1024L)) { "Invalid checksum file size" }
                        body.string()
                    }
                    val expectedHash = Armv7Updates.expectedSha256(checksumText, download.name)

                    val partFile = File(context.cacheDir, "update-${UUID.randomUUID()}.apk.part")
                    temporaryFile = partFile
                    val apkRequest = Request.Builder().url(download.url).get().build()
                    val apkResponse = client.newCall(apkRequest).execute()
                    apkResponse.use { response ->
                        check(response.isSuccessful) { "APK download failed (HTTP ${response.code})" }
                        val body = response.body
                        check(body.contentLength() in 1..MAX_APK_BYTES) { "Invalid APK size" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var totalBytes = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partFile).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    totalBytes += count
                                    check(totalBytes <= MAX_APK_BYTES) { "APK exceeds size limit" }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                        check(totalBytes > 0L) { "Downloaded APK is empty" }
                        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        check(actualHash.equals(expectedHash, ignoreCase = true)) {
                            "SHA-256 verification failed; APK was not installed"
                        }
                    }

                    val verifiedFile = File(context.cacheDir, "verified-${UUID.randomUUID()}.apk")
                    check(partFile.renameTo(verifiedFile)) { "Could not finalize verified APK" }
                    temporaryFile = null
                    verifiedFile
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    verifiedApk,
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            } catch (error: Exception) {
                temporaryFile?.delete()
                Toast.makeText(
                    context,
                    "Update verification/download failed: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
    val checksumUrl: String,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
