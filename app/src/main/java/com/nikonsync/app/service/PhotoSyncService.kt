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
import com.nikonsync.app.ptp.NikonCameraProfile
import com.nikonsync.app.ptp.NikonPtpClient
import com.nikonsync.app.ptp.PtpObjectInfo
import com.nikonsync.app.ui.MainActivity
import com.nikonsync.app.util.NetworkUtils
import kotlinx.coroutines.*
import java.util.Locale

/**
 * 尼康相机后台长连接与即拍即传服务
 * 完全对齐原版 ZRelay 双状态机制：
 * 1. 首次配对阶段：与相机向导握手，提取认证码并提交确认，随后断开等待用户按 OK 键保存
 * 2. 日常传输阶段：复用已保存的专属 GUID 秒连，监听快门并即拍即传
 * 3. 网络感知与断线恢复
 */
class PhotoSyncService : Service() {
    companion object {
        const val TAG = "PhotoSyncService"
        const val CHANNEL_ID = "nikon_sync_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "com.nikonsync.action.START"
        const val ACTION_STOP_SYNC = "com.nikonsync.action.STOP"
        const val ACTION_PAIR_CAMERA = "com.nikonsync.action.PAIR"
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
    @Volatile var isPairing = false
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
    @Volatile var onPairingSuccess: ((cameraName: String, code: String) -> Unit)? = null
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
            ACTION_PAIR_CAMERA -> {
                val rawIp = intent.getStringExtra(EXTRA_CAMERA_IP) ?: "192.168.0.1"
                val ip = NetworkUtils.normalizeIp(rawIp)
                currentIp = ip
                isManualDisconnected = false
                startForeground(NOTIFICATION_ID, buildNotification("正在执行相机首次配对..."))
                startPairing(ip)
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

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isCameraConnected || isConnecting || isManualDisconnected || isPairing) return
                if (NetworkUtils.isWifiConnected(this@PhotoSyncService)) {
                    val targetIp = NetworkUtils.getCameraIp(this@PhotoSyncService)
                    currentIp = targetIp
                    autoRetryCount = 0
                    onStatusChanged?.invoke("检测到 Wi-Fi 已连接，正在尝试接入相机...")
                    startSync(targetIp, manual = false)
                }
            }

            override fun onLost(network: Network) {
                if (isCameraConnected) {
                    onStatusChanged?.invoke("Wi-Fi 已断开，等待重新连接...")
                    ptpClient?.disconnect()
                    isCameraConnected = false
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
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

    /**
     * 主动发起首次配对向导（重置旧配置并生成全新 GUID）
     */
    fun startPairing(ip: String) {
        val normalized = NetworkUtils.normalizeIp(ip)
        currentIp = normalized
        isManualDisconnected = false
        autoRetryCount = 0
        reconnectJob?.cancel()
        NikonCameraProfile.clearProfile(this)
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

                    if (!connected && isAutoReconnectEnabled && !isManualDisconnected && !isPairing) {
                        scheduleReconnect()
                    }
                }

                client.onPhotoCaptured = { handle ->
                    serviceScope.launch {
                        handleNewCapturedObject(client, handle)
                    }
                }

                // 核心对齐：检查本地是否已有配对成功的凭据
                if (!NikonCameraProfile.hasPairedProfile(this@PhotoSyncService)) {
                    // 【阶段 1：首次配对向导】
                    isPairing = true
                    onStatusChanged?.invoke("检测到尚未配对相机，正在启动首次配对向导...")
                    val guid = NikonCameraProfile.generateNewPairingGuid()
                    val friendlyName = NikonCameraProfile.generateDefaultFriendlyName()

                    val result = client.pairCamera(guid, friendlyName) { code ->
                        onStatusChanged?.invoke("已从相机提取配对码 [$code]，已提交配对确认！")
                    }

                    isPairing = false
                    isConnecting = false

                    when (result) {
                        is NikonPtpClient.PairingResult.Success -> {
                            NikonCameraProfile.savePairedProfile(
                                this@PhotoSyncService,
                                guid,
                                friendlyName,
                                result.cameraName,
                                ip
                            )
                            val okMsg = "🎉 配对已确认！请在相机屏幕上按下 OK / J 键确认保存配置，然后点击【连接相机】。"
                            onStatusChanged?.invoke(okMsg)
                            updateNotification("配对完成：请在相机上按 OK 键")
                            onPairingSuccess?.invoke(result.cameraName, result.pairingCode)
                        }
                        is NikonPtpClient.PairingResult.Error -> {
                            val failMsg = "配对失败: ${result.message}"
                            onStatusChanged?.invoke(failMsg)
                            updateNotification(failMsg)
                        }
                    }
                } else {
                    // 【阶段 2：日常传输会话】
                    val guid = NikonCameraProfile.getPairedGuid(this@PhotoSyncService)!!
                    val friendlyName = NikonCameraProfile.getPairedFriendlyName(this@PhotoSyncService)
                    val success = client.connect(guid, friendlyName)
                    if (!success) {
                        isConnecting = false
                        isCameraConnected = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动通信异常: ${e.message}", e)
                isConnecting = false
                isCameraConnected = false
                isPairing = false
                if (isAutoReconnectEnabled && !isManualDisconnected) {
                    scheduleReconnect()
                }
            }
        }
    }

    /**
     * 智能自动重试调度器
     */
    private fun scheduleReconnect() {
        if (!isAutoReconnectEnabled || isManualDisconnected || isCameraConnected || isConnecting || isPairing) return
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
            if (!isActive || isManualDisconnected || isCameraConnected || isConnecting || isPairing) return@launch

            if (NetworkUtils.isWifiConnected(this@PhotoSyncService)) {
                onStatusChanged?.invoke("正在自动重新连接相机 (第 $autoRetryCount 次尝试)...")
                startSyncInternal(currentIp)
            }
        }
    }

    fun stopSync(manual: Boolean = false) {
        if (manual) {
            isManualDisconnected = true
        }
        reconnectJob?.cancel()
        isConnecting = false
        isPairing = false
        ptpClient?.disconnect()
        isCameraConnected = false
        onStatusChanged?.invoke(if (manual) "已手动断开相机连接" else "已与相机断开连接")
        updateNotification(if (manual) "已手动断开" else "未连接")
    }

    fun triggerShutter() {
        serviceScope.launch {
            val success = ptpClient?.triggerShutter() ?: false
            if (success) {
                onStatusChanged?.invoke("远程快门指令发送成功！")
            } else {
                onStatusChanged?.invoke("远程快门指令发送失败（相机可能未就绪）")
            }
        }
    }

    private suspend fun handleNewCapturedObject(client: NikonPtpClient, handle: Int) {
        try {
            onStatusChanged?.invoke("正在读取对象信息 (Handle=$handle)...")
            val info = client.getObjectInfo(handle) ?: return

            val shouldDownload = when (formatMode) {
                FORMAT_RAW_ONLY -> info.isRaw
                FORMAT_JPG_ONLY -> info.isJpeg
                else -> true
            }

            if (!shouldDownload) {
                onStatusChanged?.invoke("跳过文件: ${info.filename} (格式过滤)")
                return
            }

            val sizeMB = String.format(Locale.US, "%.1f", info.compressedSize / 1048576.0)
            onStatusChanged?.invoke("正在接收 ${info.filename} ($sizeMB MB)...")
            updateNotification("正在接收 ${info.filename} ($sizeMB MB)...")

            val fileData = client.downloadObject(handle)
            if (fileData != null) {
                val uri = saveToMediaStore(info.filename, fileData, info.isRaw)
                if (uri != null) {
                    lastPhotoUri = uri.toString()
                    onNewPhotoSaved?.invoke(uri.toString())
                    onStatusChanged?.invoke("已存入系统相册: ${info.filename}")
                    updateNotification("已同步: ${info.filename}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载保存照片异常: ${e.message}", e)
            onStatusChanged?.invoke("同步照片失败: ${e.message}")
        }
    }

    private fun saveToMediaStore(filename: String, data: ByteArray, isRaw: Boolean): android.net.Uri? {
        val resolver = contentResolver
        val mimeType = when {
            filename.endsWith(".jpg", ignoreCase = true) || filename.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            filename.endsWith(".nef", ignoreCase = true) -> "image/x-nikon-nef"
            filename.endsWith(".nrw", ignoreCase = true) -> "image/x-nikon-nrw"
            else -> "image/jpeg"
        }

        val relativePath = "${Environment.DIRECTORY_DCIM}/NikonSync"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(data)
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "写入 MediaStore 失败: ${e.message}", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "尼康照片即拍即传服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持与尼康相机的长连接并在后台接收新照片"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NikonSync 即拍即传")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(it)
        }
        ptpClient?.disconnect()
    }
}
