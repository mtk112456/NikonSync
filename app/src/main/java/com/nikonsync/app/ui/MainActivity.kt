package com.nikonsync.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nikonsync.app.R
import com.nikonsync.app.service.PhotoSyncService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var etCameraIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnShutter: Button
    private lateinit var ivLastPhoto: ImageView
    private lateinit var tvLogConsole: TextView

    private var syncService: PhotoSyncService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PhotoSyncService.LocalBinder
            syncService = binder.getService()
            isBound = true

            syncService?.onStatusChanged = { msg ->
                runOnUiThread {
                    appendLog(msg)
                    updateConnectionUI(syncService?.isCameraConnected == true)
                }
            }

            syncService?.onNewPhotoSaved = { uriString ->
                runOnUiThread {
                    ivLastPhoto.visibility = View.VISIBLE
                    ivLastPhoto.setImageURI(Uri.parse(uriString))
                }
            }

            updateConnectionUI(syncService?.isCameraConnected == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            isBound = false
            updateConnectionUI(false)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            appendLog("核心权限已就绪")
        } else {
            appendLog("部分权限未授予，可能影响后台保存照片或通知")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        bindSyncService()
    }

    private fun initViews() {
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvStatusText = findViewById(R.id.tvStatusText)
        etCameraIp = findViewById(R.id.etCameraIp)
        btnConnect = findViewById(R.id.btnConnect)
        btnShutter = findViewById(R.id.btnShutter)
        ivLastPhoto = findViewById(R.id.ivLastPhoto)
        tvLogConsole = findViewById(R.id.tvLogConsole)

        btnConnect.setOnClickListener {
            val isConnected = syncService?.isCameraConnected == true
            if (isConnected) {
                val stopIntent = Intent(this, PhotoSyncService::class.java).apply {
                    action = PhotoSyncService.ACTION_STOP_SYNC
                }
                startService(stopIntent)
                updateConnectionUI(false)
            } else {
                val ip = etCameraIp.text.toString().trim().ifEmpty { "192.168.0.1" }
                val startIntent = Intent(this, PhotoSyncService::class.java).apply {
                    action = PhotoSyncService.ACTION_START_SYNC
                    putExtra(PhotoSyncService.EXTRA_CAMERA_IP, ip)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            }
        }

        btnShutter.setOnClickListener {
            appendLog("正在发送远程按快门请求...")
            syncService?.triggerShutter()
        }
    }

    private fun updateConnectionUI(connected: Boolean) {
        if (connected) {
            tvStatusBadge.text = "● 已连接"
            tvStatusBadge.setTextColor(Color.parseColor("#4CAF50"))
            tvStatusText.text = "已与相机建立 PTP 会话，拍摄将自动同步"
            btnConnect.text = "断开相机连接"
            btnConnect.setBackgroundColor(Color.parseColor("#E53935"))
            btnShutter.isEnabled = true
        } else {
            tvStatusBadge.text = "○ 未连接"
            tvStatusBadge.setTextColor(Color.parseColor("#888888"))
            tvStatusText.text = "请确保手机已连接尼康相机 Wi-Fi (如 Z30_xxx)"
            btnConnect.text = "开始连接"
            btnConnect.setBackgroundColor(Color.parseColor("#1976D2"))
            btnShutter.isEnabled = false
        }
    }

    private fun appendLog(msg: String) {
        val current = tvLogConsole.text.toString()
        val line = "[${android.text.format.DateFormat.format("HH:mm:ss", java.util.Date())}] $msg\n"
        tvLogConsole.text = line + current.take(2000)
    }

    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val ungranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun bindSyncService() {
        val intent = Intent(this, PhotoSyncService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
