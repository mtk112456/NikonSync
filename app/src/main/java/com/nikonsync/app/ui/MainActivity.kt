package com.nikonsync.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
    private lateinit var photoSection: View
    private lateinit var tvLogConsole: TextView

    private var syncService: PhotoSyncService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PhotoSyncService.LocalBinder
            syncService = binder.getService()

            syncService?.onStatusChanged = { msg ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    appendLog(msg)
                    updateConnectionUI()
                }
            }

            syncService?.onNewPhotoSaved = { uriString ->
                runOnUiThread {
                    if (!isDestroyed) showPhoto(uriString)
                }
            }

            syncService?.lastPhotoUri?.let(::showPhoto)
            updateConnectionUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            updateConnectionUI()
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
        photoSection = findViewById(R.id.photoSection)
        tvLogConsole = findViewById(R.id.tvLogConsole)
        val preferences = getPreferences(Context.MODE_PRIVATE)
        etCameraIp.setText(preferences.getString("camera_ip", getString(R.string.default_ip)))

        btnConnect.setOnClickListener {
            val service = syncService ?: return@setOnClickListener
            if (service.isConnecting) return@setOnClickListener
            val isConnected = service.isCameraConnected
            if (isConnected) {
                val stopIntent = Intent(this, PhotoSyncService::class.java).apply {
                    action = PhotoSyncService.ACTION_STOP_SYNC
                }
                startService(stopIntent)
                btnConnect.isEnabled = false
                btnShutter.isEnabled = false
            } else {
                val ip = etCameraIp.text.toString().trim().ifEmpty { getString(R.string.default_ip) }
                etCameraIp.setText(ip)
                etCameraIp.clearFocus()
                preferences.edit().putString("camera_ip", ip).apply()
                val startIntent = Intent(this, PhotoSyncService::class.java).apply {
                    action = PhotoSyncService.ACTION_START_SYNC
                    putExtra(PhotoSyncService.EXTRA_CAMERA_IP, ip)
                }
                startForegroundService(startIntent)
                updateConnectionUI(connecting = true)
            }
        }

        btnShutter.setOnClickListener {
            appendLog("正在发送远程按快门请求...")
            syncService?.triggerShutter()
        }
    }

    private fun updateConnectionUI(connecting: Boolean = syncService?.isConnecting == true) {
        val connected = syncService?.isCameraConnected == true
        val (label, hint, color) = when {
            connecting -> Triple(R.string.connecting, R.string.connecting_hint, R.color.sync_gold_ink)
            connected -> Triple(R.string.connected, R.string.connected_hint, R.color.sync_green)
            else -> Triple(R.string.idle, R.string.wifi_hint, R.color.sync_muted)
        }
        tvStatusBadge.setText(label)
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, color))
        tvStatusText.setText(hint)
        btnConnect.setText(if (connected) R.string.disconnect else R.string.connect)
        btnConnect.isEnabled = syncService != null && !connecting
        btnShutter.isEnabled = connected && !connecting
        etCameraIp.isEnabled = !connected && !connecting
    }

    private fun showPhoto(uriString: String) {
        ivLastPhoto.setImageURI(Uri.parse(uriString))
        photoSection.visibility = View.VISIBLE
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

        val ungranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    private fun bindSyncService() {
        val intent = Intent(this, PhotoSyncService::class.java)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            syncService?.onStatusChanged = null
            syncService?.onNewPhotoSaved = null
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
