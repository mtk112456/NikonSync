package com.nikonsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nikonsync.app.ptp.NikonPtpClient
import com.nikonsync.app.ptp.PtpObjectInfo
import com.nikonsync.app.ui.MainActivity
import com.nikonsync.app.util.NetworkUtils
import kotlinx.coroutines.*
import java.util.Locale

/**
 * 尼康相机后台长连接与即拍即传服务
 * 支持：
 * 1. 自动感知相机 Wi-Fi 热点连接并自动发起 PTP 握手 (零人工干预)
 * 2. 相机睡眠唤醒或 Wi-Fi 抖动后的智能自动重连 (带重试退避)
 * 3. 快门 ObjectAdded 监听与即拍即传 (格式过滤策略)
 */
class PhotoSyncService : Service() {
    companion object {
        const val TAG = "PhotoSyncService"
        const val CHANNEL_ID = "nikon_sync_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "com.nikonsync.action.START"
        const val ACTION_STOP_SYNC = "com.nikonsync.action.STOP"
        const val EXTRA_CAMERA_IP = "extra_camera_ip"
        const val EXTRA_FORMAT_MODE = "extra_format_mode"

        const val FORMAT_ALL = 0       // 同步全部 (JPG + RAW)
        const val FORMAT_JPG_ONLY = 1  // 仅同步 JPG
        const val FORMAT_RAW_ONLY = 2  // 仅同步 RAW
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var ptpClient: NikonPtpClient? = null
        private set

    @Volatile var isCameraConnected = false
        private set
    @Volatile var isConnecting = false
        private set
    @Volatile var lastPhotoUri: String? = null
        private set
    @Volatile var formatMode: Int = FORMAT_JPG_ONLY

    // 自动重连与自动连接控制
    @Volatile var isAutoReconnectEnabled = true
    @Volatile var isManualDisconnected = false
    @Volatile var currentIp: String = "192.168.0.1"

    @Volatile private var autoRetryCount = 0
    private val MAX_AUTO_RETRIES = 2

    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile var onStatusChanged: ((message: String) -> Unit)? = null
    @Volatile var onNewPhotoSaved: ((uriString: String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PhotoSyncService = this@PhotoSyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val rawIp = intent.getStringExtra(EXTRA_CAMERA_IP) ?: "192.168.0.1"
                val ip = NetworkUtils.normalizeIp(rawIp)
                formatMode = intent.getIntExtra(EXTRA_FORMAT_MODE, FORMAT_JPG_ONLY)
                currentIp = ip
                isManualDisconnected = false
                startForeground(NOTIFICATION_ID, buildNotification("正在连接尼康相机 ($ip)..."))
                startSync(ip, manual = false)
            }
            ACTION_STOP_SYNC -> {
                stopSync(manual = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * 注册 Wi-Fi 状态动态监听，当手机连上相机 Wi-Fi 时自动连接
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                autoRetryCount = 0
                if (isAutoReconnectEnabled && !isCameraConnected && !isConnecting) {
                    val detectedIp = NetworkUtils.getCameraIp(this@PhotoSyncService, currentIp)
                    currentIp = detectedIp
                    Log.i(TAG, "Wi-Fi 已连接，探测到相机 IP: $detectedIp，准备自动连接")
                    serviceScope.launch {
                        delay(1200) // 等待 Wi-Fi IP 分配及相机端口响应就绪
                        if (isAutoReconnectEnabled && !isCameraConnected && !isConnecting && !isManualDisconnected) {
                            onStatusChanged?.invoke("检测到已连上相机 Wi-Fi，正在自动连接 ($detectedIp)...")
                            startSyncInternal(detectedIp)
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi 网络断开")
                autoRetryCount = 0
                reconnectJob?.cancel()
                if (isCameraConnected) {
                    onStatusChanged?.invoke("Wi-Fi 已断开，等待重新连接相机热点...")
                    stopSyncInternal(manual = false)
                }
            }
        }

        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调异常: ${e.message}")
        }
    }

    fun startSync(ip: String, manual: Boolean = false) {
        val normalized = NetworkUtils.normalizeIp(ip)
        if (manual) {
            isManualDisconnected = false
            autoRetryCount = 0
        }
        currentIp = normalized
        reconnectJob?.cancel()
        startSyncInternal(normalized)
    }

    private fun startSyncInternal(ip: String) {
        if (isConnecting || isCameraConnected) return
        isConnecting = true
        updateNotification("正在连接相机 ($ip)...")

        serviceScope.launch {
            try {
                ptpClient?.disconnect()
                val client = NikonPtpClient(host = ip)
                ptpClient = client

                client.onConnectionStateChanged = { connected, msg ->
                    isCameraConnected = connected
                    isConnecting = false
                    if (connected) {
                        autoRetryCount = 0
                    }
                    onStatusChanged?.invoke(msg)
                    updateNotification(msg)

                    if (!connected && isAutoReconnectEnabled && !isManualDisconnected) {
                        scheduleReconnect()
                    }
                }

                client.onPhotoCaptured = { handle ->
                    serviceScope.launch {
                        handleNewCapturedObject(client, handle)
                    }
                }

                client.connect()
            } catch (e: Exception) {
                Log.e(TAG, "启动通信异常: ${e.message}", e)
                isConnecting = false
                isCameraConnected = false
                if (isAutoReconnectEnabled && !isManualDisconnected) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * 智能自动重试调度器（有最大次数限制，避免无限刷屏）
     */
    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled || isManualDisconnected || isCameraConnected || isConnecting) return
        if (autoRetryCount >= MAX_AUTO_RETRIES) {
            onStatusChanged?.invoke("未连接到相机，等待连上相机 Wi-Fi 热点...")
            updateNotification("未连接到相机，等待连上 Wi-Fi")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            autoRetryCount++
            val waitSec = if (autoRetryCount == 1) 3 else 5
            delay(waitSec * 1000L)
            if (!isActive || isManualDisconnected || isCameraConnected || isConnecting) return@launch

            if (NetworkUtils.isWifiConnected(this@PhotoSyncService)) {
                val ip = NetworkUtils.getCameraIp(this@PhotoSyncService, currentIp)
                currentIp = ip
                startSyncInternal(ip)
            } else {
                onStatusChanged?.invoke("等待连上相机 Wi-Fi 热点...")
            }
        }
    }

    private suspend fun handleNewCapturedObject(client: NikonPtpClient, handle: Int) {
        onStatusChanged?.invoke("检测到快门拍照，正在获取对象元数据...")
        val info = client.getObjectInfo(handle)
        val filename = info?.filename ?: "DSC_${handle}.JPG"
        val isRaw = info?.isRaw == true || filename.endsWith(".nef", true) || filename.endsWith(".nrw", true)
        val isJpg = info?.isJpeg == true || filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true)

        // 格式过滤校验
        if (formatMode == FORMAT_JPG_ONLY && isRaw) {
            onStatusChanged?.invoke("根据格式策略过滤: 跳过 RAW 文件 ($filename)")
            return
        }
        if (formatMode == FORMAT_RAW_ONLY && isJpg) {
            onStatusChanged?.invoke("根据格式策略过滤: 跳过 JPG 文件 ($filename)")
            return
        }

        onStatusChanged?.invoke("正在拉取照片: $filename (${formatFileSize(info?.compressedSize ?: 0)})...")
        val photoBytes = client.downloadObject(handle)
        if (photoBytes != null && photoBytes.isNotEmpty()) {
            savePhotoToGallery(photoBytes, filename, isRaw)
        } else {
            onStatusChanged?.invoke("拉取照片失败: $filename")
        }
    }

    fun triggerShutter() {
        serviceScope.launch {
            val ok = ptpClient?.triggerShutter() ?: false
            if (ok) {
                onStatusChanged?.invoke("远程快门指令已触发")
            } else {
                onStatusChanged?.invoke("远程快门指令发送失败，请检查相机状态")
            }
        }
    }

    fun stopSync(manual: Boolean = false) {
        if (manual) {
            isManualDisconnected = true
        }
        reconnectJob?.cancel()
        stopSyncInternal(manual)
    }

    private fun stopSyncInternal(manual: Boolean) {
        ptpClient?.disconnect()
        ptpClient = null
        isCameraConnected = false
        isConnecting = false
        val msg = if (manual) "已手动断开相机连接" else "已与相机断开连接"
        onStatusChanged?.invoke(msg)
        updateNotification(msg)
    }

    fun savePhotoToGallery(bytes: ByteArray, preferredName: String, isRaw: Boolean) {
        try {
            val mime = if (isRaw) "image/x-nikon-nef" else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, preferredName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NikonSync")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }

                Log.i(TAG, "照片已安全保存到系统相册: $uri ($preferredName)")
                lastPhotoUri = uri.toString()
                onStatusChanged?.invoke("已存入系统相册 Pictures/NikonSync: $preferredName")
                onNewPhotoSaved?.invoke(uri.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存相册异常: ${e.message}", e)
            onStatusChanged?.invoke("保存照片失败: ${e.message}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "计算中"
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NikonSync 后台实时传输服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持与尼康相机的长连接并实时传输照片"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NikonSync 相机伴侣")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        stopSync(manual = true)
        serviceScope.cancel()
    }
}
