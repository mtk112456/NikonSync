package com.nikonsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nikonsync.app.ptp.NikonPtpClient
import com.nikonsync.app.ui.MainActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoSyncService : Service() {
    companion object {
        const val TAG = "PhotoSyncService"
        const val CHANNEL_ID = "nikon_sync_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SYNC = "com.nikonsync.action.START"
        const val ACTION_STOP_SYNC = "com.nikonsync.action.STOP"
        const val EXTRA_CAMERA_IP = "extra_camera_ip"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ptpClient: NikonPtpClient? = null

    @Volatile var isCameraConnected = false
        private set
    @Volatile var isConnecting = false
        private set
    @Volatile var lastPhotoUri: String? = null
        private set

    @Volatile var onStatusChanged: ((message: String) -> Unit)? = null
    @Volatile var onNewPhotoSaved: ((uriString: String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): PhotoSyncService = this@PhotoSyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYNC -> {
                val ip = intent.getStringExtra(EXTRA_CAMERA_IP) ?: "192.168.0.1"
                startForeground(NOTIFICATION_ID, buildNotification("正在连接尼康相机 ($ip)..."))
                startSync(ip)
            }
            ACTION_STOP_SYNC -> {
                stopSync()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSync(ip: String) {
        if (isConnecting || isCameraConnected) return
        isConnecting = true
        onStatusChanged?.invoke("正在连接相机 $ip…")
        serviceScope.launch {
            ptpClient?.disconnect()
            val client = NikonPtpClient(host = ip)
            ptpClient = client

            client.onConnectionStateChanged = { connected, msg ->
                isCameraConnected = connected
                isConnecting = false
                onStatusChanged?.invoke(msg)
                updateNotification(msg)
            }

            client.onPhotoCaptured = { handle ->
                onStatusChanged?.invoke("检测到新快门拍摄！正在拉取照片...")
                serviceScope.launch {
                    val photoBytes = client.downloadObject(handle)
                    if (photoBytes != null && photoBytes.isNotEmpty()) {
                        savePhotoToGallery(photoBytes)
                    }
                }
            }

            client.connect()
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

    fun stopSync() {
        ptpClient?.disconnect()
        ptpClient = null
        isCameraConnected = false
        isConnecting = false
        onStatusChanged?.invoke("已与相机断开连接")
    }

    private fun savePhotoToGallery(bytes: ByteArray) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "Nikon_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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

                Log.i(TAG, "Photo saved successfully to gallery: $uri ($filename)")
                lastPhotoUri = uri.toString()
                onStatusChanged?.invoke("照片已实时保存到手机相册: $filename")
                onNewPhotoSaved?.invoke(uri.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save photo error: ${e.message}", e)
            onStatusChanged?.invoke("保存照片失败: ${e.message}")
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
        stopSync()
        serviceScope.cancel()
    }
}
