package com.nikonsync.app.update

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程 OTA 在线升级管理器
 * 支持国内 CDN 极速镜像检查、静默检测、更新说明弹窗、断点流式下载与唤起系统安装器
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // 国内极速镜像源（优先）与 GitHub 官方源（备选）
    private const val UPDATE_URL_FAST = "https://fastly.jsdelivr.net/gh/mtk112456/NikonSync@main/version.json"
    private const val UPDATE_URL_RAW = "https://raw.githubusercontent.com/mtk112456/NikonSync/main/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val releaseDate: String,
        val title: String,
        val changelog: String,
        val downloadUrl: String,
        val mirrorDownloadUrl: String
    )

    fun checkUpdate(context: Context, silent: Boolean = true, onCheckComplete: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersionCode = getAppVersionCode(context)
                val updateInfo = fetchRemoteVersionInfo()

                withContext(Dispatchers.Main) {
                    onCheckComplete?.invoke()
                    if (updateInfo != null && updateInfo.versionCode > currentVersionCode) {
                        showUpdateDialog(context, updateInfo)
                    } else {
                        if (!silent) {
                            Toast.makeText(context, "当前已是最新版本 (v${getAppVersionName(context)})", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查远程更新异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onCheckComplete?.invoke()
                    if (!silent) {
                        Toast.makeText(context, "检查更新失败，请确认网络连接", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun fetchRemoteVersionInfo(): UpdateInfo? {
        // 先尝试国内 CDN 极速源，失败则切回官方源
        val urls = listOf(UPDATE_URL_FAST, UPDATE_URL_RAW)
        for (urlStr in urls) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    return UpdateInfo(
                        versionCode = json.optInt("versionCode", 1),
                        versionName = json.optString("versionName", "1.0.0"),
                        releaseDate = json.optString("releaseDate", ""),
                        title = json.optString("title", "发现新版本"),
                        changelog = json.optString("changelog", "性能优化与功能完善"),
                        downloadUrl = json.optString("downloadUrl", ""),
                        mirrorDownloadUrl = json.optString("mirrorDownloadUrl", "")
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "请求更新源 $urlStr 失败: ${e.message}")
            }
        }
        return null
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo) {
        val message = buildString {
            append("最新版本: v${info.versionName} (${info.releaseDate})\n\n")
            append("【更新内容】\n")
            append(info.changelog)
        }

        AlertDialog.Builder(context)
            .setTitle(info.title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("立即更新") { _, _ ->
                val targetUrl = info.mirrorDownloadUrl.ifEmpty { info.downloadUrl }
                downloadAndInstallApk(context, targetUrl, info.versionName)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstallApk(context: Context, downloadUrl: String, versionName: String) {
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在下载新版本 v$versionName")
            setMessage("准备开始下载...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val destinationFile = File(context.externalCacheDir, "NikonSync_v${versionName}.apk")
            var downloadSuccess = false

            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.connect()

                val totalLength = conn.contentLength
                var downloadedBytes = 0

                conn.inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalLength > 0) {
                                val percent = (downloadedBytes * 100L / totalLength).toInt()
                                withContext(Dispatchers.Main) {
                                    progressDialog.progress = percent
                                    val mbDownloaded = downloadedBytes / (1024.0 * 1024.0)
                                    val mbTotal = totalLength / (1024.0 * 1024.0)
                                    progressDialog.setMessage(String.format("已下载: %.1f MB / %.1f MB", mbDownloaded, mbTotal))
                                }
                            }
                        }
                        output.flush()
                    }
                }
                downloadSuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "下载安装包异常: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (downloadSuccess && destinationFile.exists()) {
                    triggerInstall(context, destinationFile)
                } else {
                    Toast.makeText(context, "下载失败，请稍后重试或前往 GitHub 手动下载", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun triggerInstall(context: Context, apkFile: File) {
        try {
            // Android 8.0+ 权限检查
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "请在系统设置中允许 NikonSync 安装未知应用", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            // 使用 FileProvider 生成 content URI
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "调起系统安装器失败: ${e.message}", e)
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAppVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    private fun getAppVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
