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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nikonsync.app.R
import com.nikonsync.app.service.BluetoothGpsRelayService
import com.nikonsync.app.service.PhotoSyncService
import com.nikonsync.app.update.AppUpdateManager
import com.nikonsync.app.util.NetworkUtils

class MainActivity : AppCompatActivity() {

    private lateinit var btnOpenGuide: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var etCameraIp: EditText
    private lateinit var rgFormatMode: RadioGroup
    private lateinit var rbFormatJpg: RadioButton
    private lateinit var rbFormatAll: RadioButton
    private lateinit var rbFormatRaw: RadioButton
    private lateinit var switchAutoConnect: SwitchCompat
    private lateinit var btnConnect: Button
    private lateinit var btnShutter: Button
    private lateinit var btnOpenGallery: MaterialButton
    private lateinit var btnOpenPictureControl: MaterialButton
    private lateinit var tvGpsStatus: TextView
    private lateinit var switchGpsRelay: SwitchCompat
    private lateinit var ivLastPhoto: ImageView
    private lateinit var photoSection: View
    private lateinit var tvLogConsole: TextView

    private var syncService: PhotoSyncService? = null
    private var gpsService: BluetoothGpsRelayService? = null

    private val syncConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PhotoSyncService.LocalBinder
            syncService = binder.getService()

            syncService?.onStatusChanged = { msg ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    tvStatusText.text = msg
                    // 仅核心业务事件才打入底部控制台，防止重试过程反复刷屏
                    if (msg.contains("相机连接成功") ||
                        msg.contains("已与相机断开") ||
                        msg.contains("已手动断开") ||
                        msg.contains("已存入系统相册") ||
                        msg.contains("远程快门") ||
                        msg.contains("格式策略") ||
                        msg.contains("连上相机 Wi-Fi") ||
                        msg.contains("未连接到相机")
                    ) {
                        appendLog(msg)
                    }
                    updateConnectionUI()
                }
            }

            syncService?.onNewPhotoSaved = { uriString ->
                runOnUiThread {
                    if (!isDestroyed) showPhoto(uriString)
                }
            }

            syncService?.lastPhotoUri?.let(::showPhoto)
            if (::switchAutoConnect.isInitialized) {
                syncService?.isAutoReconnectEnabled = switchAutoConnect.isChecked
            }
            updateConnectionUI()

            // 服务就绪后，若已开启自动连接且当前连上了 Wi-Fi，立即自动发起连接
            if (::switchAutoConnect.isInitialized && switchAutoConnect.isChecked) {
                if (syncService?.isCameraConnected == false && syncService?.isConnecting == false && syncService?.isManualDisconnected == false) {
                    if (NetworkUtils.isWifiConnected(this@MainActivity)) {
                        val ip = etCameraIp.text.toString().trim().ifEmpty { NetworkUtils.getCameraIp(this@MainActivity) }
                        etCameraIp.setText(ip)
                        appendLog("检测到 Wi-Fi 已就绪，启动自动连接相机 ($ip)...")
                        syncService?.startSync(ip, manual = false)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            syncService = null
            updateConnectionUI()
        }
    }

    private val gpsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothGpsRelayService.LocalBinder
            gpsService = binder.getService()

            gpsService?.onGpsStatusChanged = { msg, connected ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    tvGpsStatus.text = msg
                    switchGpsRelay.isChecked = gpsService?.isGpsRunning == true
                    if (connected) {
                        tvGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sync_green))
                    } else {
                        tvGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sync_muted))
                    }
                }
            }
            switchGpsRelay.isChecked = gpsService?.isGpsRunning == true
            tvGpsStatus.text = gpsService?.statusMessage ?: "未启动"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gpsService = null
            switchGpsRelay.isChecked = false
            tvGpsStatus.text = "GPS 服务已停止"
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            appendLog("核心权限 (网络/定位/蓝牙/相册) 已就绪")
        } else {
            appendLog("部分权限未授予，可能影响蓝牙定位注入或后台保存照片")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        bindSyncService()
        bindGpsService()

        // 启动时静默检查远程更新
        AppUpdateManager.checkUpdate(this, silent = true)
    }

    private fun initViews() {
        btnOpenGuide = findViewById(R.id.btnOpenGuide)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvStatusText = findViewById(R.id.tvStatusText)
        etCameraIp = findViewById(R.id.etCameraIp)
        rgFormatMode = findViewById(R.id.rgFormatMode)
        rbFormatJpg = findViewById(R.id.rbFormatJpg)
        rbFormatAll = findViewById(R.id.rbFormatAll)
        rbFormatRaw = findViewById(R.id.rbFormatRaw)
        switchAutoConnect = findViewById(R.id.switchAutoConnect)
        btnConnect = findViewById(R.id.btnConnect)
        btnShutter = findViewById(R.id.btnShutter)
        btnOpenGallery = findViewById(R.id.btnOpenGallery)
        btnOpenPictureControl = findViewById(R.id.btnOpenPictureControl)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        switchGpsRelay = findViewById(R.id.switchGpsRelay)
        ivLastPhoto = findViewById(R.id.ivLastPhoto)
        photoSection = findViewById(R.id.photoSection)
        tvLogConsole = findViewById(R.id.tvLogConsole)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        etCameraIp.setText(prefs.getString("camera_ip", getString(R.string.default_ip)))

        val savedFormat = prefs.getInt("format_mode", PhotoSyncService.FORMAT_JPG_ONLY)
        when (savedFormat) {
            PhotoSyncService.FORMAT_ALL -> rbFormatAll.isChecked = true
            PhotoSyncService.FORMAT_RAW_ONLY -> rbFormatRaw.isChecked = true
            else -> rbFormatJpg.isChecked = true
        }

        val autoConnectSaved = prefs.getBoolean("auto_connect", true)
        switchAutoConnect.isChecked = autoConnectSaved
        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_connect", isChecked).apply()
            syncService?.isAutoReconnectEnabled = isChecked
            appendLog(if (isChecked) "已开启：连上相机 Wi-Fi 时自动连接" else "已关闭自动连接")
        }

        rgFormatMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbFormatAll -> PhotoSyncService.FORMAT_ALL
                R.id.rbFormatRaw -> PhotoSyncService.FORMAT_RAW_ONLY
                else -> PhotoSyncService.FORMAT_JPG_ONLY
            }
            prefs.edit().putInt("format_mode", mode).apply()
            syncService?.formatMode = mode
            val modeName = when (mode) {
                PhotoSyncService.FORMAT_ALL -> "全部 (JPG + RAW)"
                PhotoSyncService.FORMAT_RAW_ONLY -> "仅 RAW (NEF)"
                else -> "仅 JPG"
            }
            appendLog("已切换即拍即传格式策略: $modeName")
        }

        // 打开配对与操作指南
        btnOpenGuide.setOnClickListener {
            val intent = Intent(this, GuideActivity::class.java)
            startActivity(intent)
        }

        // 手动检查远程 OTA 更新
        btnCheckUpdate.setOnClickListener {
            appendLog("正在检查最新版本...")
            AppUpdateManager.checkUpdate(this, silent = false)
        }

        // 打开相机卡内画廊
        btnOpenGallery.setOnClickListener {
            val ip = etCameraIp.text.toString().trim().ifEmpty { getString(R.string.default_ip) }
            val intent = Intent(this, GalleryActivity::class.java).apply {
                putExtra(GalleryActivity.EXTRA_CAMERA_IP, ip)
            }
            startActivity(intent)
        }

        // 打开 Picture Control 调色
        btnOpenPictureControl.setOnClickListener {
            val intent = Intent(this, PictureControlActivity::class.java)
            startActivity(intent)
        }

        // 蓝牙 GPS 开关
        switchGpsRelay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val intent = Intent(this, BluetoothGpsRelayService::class.java).apply {
                    action = BluetoothGpsRelayService.ACTION_START_GPS
                }
                startForegroundService(intent)
                appendLog("已启动蓝牙 GPS 实时中继服务")
            } else {
                val intent = Intent(this, BluetoothGpsRelayService::class.java).apply {
                    action = BluetoothGpsRelayService.ACTION_STOP_GPS
                }
                startService(intent)
                appendLog("已停止蓝牙 GPS 中继服务")
            }
        }

        btnConnect.setOnClickListener {
            val service = syncService ?: return@setOnClickListener
            if (service.isConnecting) return@setOnClickListener
            val isConnected = service.isCameraConnected
            if (isConnected) {
                service.stopSync(manual = true)
                appendLog("已手动断开相机连接（自动重连已暂停）")
                updateConnectionUI()
            } else {
                val ip = etCameraIp.text.toString().trim().ifEmpty { NetworkUtils.getCameraIp(this) }
                etCameraIp.setText(ip)
                etCameraIp.clearFocus()
                prefs.edit().putString("camera_ip", ip).apply()

                val mode = when (rgFormatMode.checkedRadioButtonId) {
                    R.id.rbFormatAll -> PhotoSyncService.FORMAT_ALL
                    R.id.rbFormatRaw -> PhotoSyncService.FORMAT_RAW_ONLY
                    else -> PhotoSyncService.FORMAT_JPG_ONLY
                }
                service.formatMode = mode

                val startIntent = Intent(this, PhotoSyncService::class.java).apply {
                    action = PhotoSyncService.ACTION_START_SYNC
                    putExtra(PhotoSyncService.EXTRA_CAMERA_IP, ip)
                    putExtra(PhotoSyncService.EXTRA_FORMAT_MODE, mode)
                }
                startForegroundService(startIntent)
                service.startSync(ip, manual = true)
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
        if (tvStatusText.text.isNullOrEmpty()) {
            tvStatusText.setText(hint)
        }
        btnConnect.setText(if (connected) R.string.disconnect else R.string.connect)
        btnConnect.isEnabled = syncService != null && !connecting
        btnShutter.isEnabled = connected && !connecting
        etCameraIp.isEnabled = !connected && !connecting
    }

    private fun showPhoto(uriString: String) {
        ivLastPhoto.setImageURI(Uri.parse(uriString))
        photoSection.visibility = View.VISIBLE
    }

    private var lastLoggedMessage: String? = null

    private fun appendLog(msg: String) {
        if (msg == lastLoggedMessage) return
        lastLoggedMessage = msg
        val line = "[${android.text.format.DateFormat.format("HH:mm:ss", java.util.Date())}] $msg\n"
        tvLogConsole.append(line)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun bindSyncService() {
        val intent = Intent(this, PhotoSyncService::class.java)
        bindService(intent, syncConnection, Context.BIND_AUTO_CREATE)
    }

    private fun bindGpsService() {
        val intent = Intent(this, BluetoothGpsRelayService::class.java)
        bindService(intent, gpsConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updateConnectionUI()
        if (::switchAutoConnect.isInitialized && switchAutoConnect.isChecked) {
            val service = syncService
            if (service != null && !service.isCameraConnected && !service.isConnecting && !service.isManualDisconnected) {
                if (NetworkUtils.isWifiConnected(this)) {
                    val ip = etCameraIp.text.toString().trim().ifEmpty { NetworkUtils.getCameraIp(this) }
                    etCameraIp.setText(ip)
                    appendLog("检测到已在相机 Wi-Fi 环境，正在自动连接 ($ip)...")
                    service.startSync(ip, manual = false)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(syncConnection) } catch (_: Exception) {}
        try { unbindService(gpsConnection) } catch (_: Exception) {}
    }
}
