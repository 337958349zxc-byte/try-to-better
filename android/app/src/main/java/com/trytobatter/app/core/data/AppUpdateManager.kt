package com.trytobatter.app.core.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class AppRelease(val packageName: String, val versionCode: Long, val versionName: String, val size: Long, val sha256: String, val downloadUrl: String, val notes: String)
data class UpdateInfo(val phase: UpdatePhase = UpdatePhase.IDLE, val release: AppRelease? = null, val progress: Int = 0, val checkedAt: Long = 0, val message: String = "")

class AppUpdateManager(private val context: Context) {
    private val _state = MutableStateFlow(UpdateInfo())
    val state = _state.asStateFlow()
    private val client = LanClient(context)
    private var verifiedFile: File? = null
    @Suppress("DEPRECATION")
    fun installedCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).let { if(Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }

    suspend fun check(address: String, expectedServerId: String? = null) {
        check(_state.value.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) { "正在处理更新，请稍候" }
        _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, message = "正在检查电脑发布的版本")
        try {
            val release = withContext(Dispatchers.IO) {
                val health = client.health(address)
                require(expectedServerId == null || health.getString("serverId") == expectedServerId) { "电脑身份不一致，请重新配对" }
                val j = client.request(address, "/api/update").optJSONObject("update") ?: error("电脑尚未发布安装包")
                parseRelease(j, address, context.packageName)
            }
            val ready = verifiedFile?.isFile == true && _state.value.release == release
            if(!ready) verifiedFile = null
            _state.value = UpdateInfo(if(release.versionCode <= installedCode()) UpdatePhase.CURRENT else if(ready) UpdatePhase.READY else UpdatePhase.AVAILABLE,
                release, if(ready) 100 else 0, System.currentTimeMillis(), if(release.versionCode <= installedCode()) "已是当前发布的最新版本" else "发现新版本 ${release.versionName}")
        } catch(e: Exception) { _state.value = _state.value.copy(phase = UpdatePhase.ERROR, checkedAt = System.currentTimeMillis(), message = e.message ?: "检查更新失败"); throw e }
    }
    suspend fun download() {
        check(_state.value.phase !in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.CHECKING)) { "更新处理中" }
        val release = _state.value.release ?: error("请先检查更新")
        require(release.versionCode > installedCode()) { "没有可安装的更新" }
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, message = "下载并校验安装包")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "candidate.part")
        val complete = File(directory, "verified-update.apk")
        verifiedFile = null
        try {
            withContext(Dispatchers.IO) {
                val c = client.connection(release.downloadUrl).apply { readTimeout = 30_000 }
                try {
                    require(c.responseCode == 200) { "安装包下载失败，请稍后重试" }
                    val announced = c.contentLengthLong
                    require(announced < 0 || announced == release.size) { "安装包大小与发布信息不一致" }
                    var bytes = 0L
                    val hash = MessageDigest.getInstance("SHA-256")
                    c.inputStream.use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while(true) {
                            val n = input.read(buffer); if(n < 0) break
                            bytes += n; require(bytes <= release.size) { "安装包超过预期大小" }
                            output.write(buffer, 0, n); hash.update(buffer, 0, n)
                            val progress = (bytes * 100 / release.size).toInt()
                            if(progress != _state.value.progress) _state.value = _state.value.copy(progress = progress)
                        }
                    } }
                    require(bytes == release.size && hex(hash.digest()) == release.sha256) { "安装包校验失败，请重新下载" }
                    verifyArchive(context, partial, release)
                    if(complete.exists()) check(complete.delete()) { "无法替换旧更新文件" }
                    check(partial.renameTo(complete)) { "无法保存已校验安装包" }
                    verifiedFile = complete
                } finally { c.disconnect() }
            }
            _state.value = _state.value.copy(phase = UpdatePhase.READY, progress = 100, message = "安装包已校验，等待系统安装确认")
        } catch(e: Exception) {
            partial.delete(); verifiedFile = null
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = e.message ?: "下载未完成，请重试")
            throw e
        }
    }
    fun installIntent(): Intent {
        val file = verifiedFile ?: error("请先下载并校验安装包")
        check(file.isFile && _state.value.phase == UpdatePhase.READY) { "更新文件已失效，请重新下载" }
        return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun parseRelease(j: JSONObject, address: String, packageName: String): AppRelease {
            val url = URI(j.getString("downloadUrl")); val base = URI(LanAddress.normalize(address))
            require(url.host == base.host && url.scheme == base.scheme && url.port == 8080 && url.userInfo == null && url.rawQuery == null && url.rawFragment == null && url.path.matches(Regex("/[A-Za-z0-9._-]+\\.apk"))) { "更新下载地址不属于这台电脑的发布通道" }
            val release = AppRelease(j.getString("packageName"), j.getLong("versionCode"), j.getString("versionName"), j.getLong("size"), j.getString("sha256").lowercase(), url.toString(), j.optString("releaseNotes"))
            require(release.packageName == packageName && release.versionCode > 0 && release.versionName.isNotBlank() && release.size in 1..150_000_000 && release.sha256.matches(Regex("[0-9a-f]{64}"))) { "更新信息无效或应用不匹配" }
            return release
        }
        @Suppress("DEPRECATION")
        fun verifyArchive(context: Context, file: File, release: AppRelease) {
            val pm = context.packageManager
            val flags = if(Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取安装包")
            val installed = pm.getPackageInfo(context.packageName, flags)
            val version = if(Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            require(archive.packageName == context.packageName && version == release.versionCode && archive.versionName == release.versionName) { "安装包版本或应用标识不符" }
            val archiveSigners = if(Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
            val installedSigners = if(Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            fun fingerprints(signers: Array<android.content.pm.Signature>?) = signers?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
            val expected = fingerprints(installedSigners)
            require(expected.isNotEmpty() && fingerprints(archiveSigners) == expected) { "安装包签名与已安装应用不一致" }
        }
    }
}
