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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.nikonsync.app.R
import com.nikonsync.app.service.BluetoothGpsRelayService
import com.nikonsync.app.service.PhotoSyncService
import com.nikonsync.app.update.AppUpdateManager
import com.nikonsync.app.util.NetworkUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnOpenGuide: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var etCameraIp: EditText
    private lateinit var btnScanLocalCamera: MaterialButton
    private lateinit var btnResetDefaultIp: MaterialButton
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
    private lateinit var layoutLogToggle: View
    private lateinit var layoutLogContainer: View
    private lateinit var tvLogToggleIcon: TextView
    private lateinit var tvLogBadge: TextView
    private lateinit var btnClearLog: TextView

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
                    // 仅关键业务生命周期事件与错误才打入控制台
                    if (msg.contains("相机连接成功")) {
                        appendLog(msg)
                        tvLogBadge.text = "已连接"
                        tvLogBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sync_green))
                    } else if (msg.contains("已与相机断开") || msg.contains("已手动断开")) {
                        appendLog(msg)
                        tvLogBadge.text = "未连接"
                        tvLogBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sync_muted))
                    } else if (msg.contains("已存入系统相册") || msg.contains("远程快门") || msg.contains("格式策略")) {
                        appendLog(msg)
                    } else if (msg.contains("失败") || msg.contains("异常") || msg.contains("中断")) {
                        appendLog(msg, isError = true)
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

            // 若用户主动开启了自动连接且当前连上了 Wi-Fi，静默发起连接（不污染日志）
            if (::switchAutoConnect.isInitialized && switchAutoConnect.isChecked) {
                if (syncService?.isCameraConnected == false && syncService?.isConnecting == false && syncService?.isManualDisconnected == false) {
                    if (NetworkUtils.isWifiConnected(this@MainActivity)) {
                        val ip = NetworkUtils.normalizeIp(etCameraIp.text.toString().trim().ifEmpty { NetworkUtils.getCameraIp(this@MainActivity) })
                        etCameraIp.setText(ip)
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
        btnScanLocalCamera = findViewById(R.id.btnScanLocalCamera)
        btnResetDefaultIp = findViewById(R.id.btnResetDefaultIp)
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
        layoutLogToggle = findViewById(R.id.layoutLogToggle)
        layoutLogContainer = findViewById(R.id.layoutLogContainer)
        tvLogToggleIcon = findViewById(R.id.tvLogToggleIcon)
        tvLogBadge = findViewById(R.id.tvLogBadge)
        btnClearLog = findViewById(R.id.btnClearLog)

        layoutLogToggle.setOnClickListener {
            val isShown = layoutLogContainer.visibility == View.VISIBLE
            layoutLogContainer.visibility = if (isShown) View.GONE else View.VISIBLE
            tvLogToggleIcon.text = if (isShown) "展开 ▾" else "收起 ▴"
        }

        btnClearLog.setOnClickListener {
            tvLogConsole.text = getString(R.string.log_ready)
            tvLogBadge.text = ""
            tvLogBadge.setTextColor(ContextCompat.getColor(this, R.color.sync_muted))
        }

        val prefs = getPreferences(Context.MODE_PRIVATE)
        val savedIp = prefs.getString("camera_ip", getString(R.string.default_ip)) ?: getString(R.string.default_ip)
        etCameraIp.setText(NetworkUtils.normalizeIp(savedIp))

        // 焦点离开时自动规范化 IP 格式（如清除 192.168.031.213 的前导零）
        etCameraIp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val normalized = NetworkUtils.normalizeIp(etCameraIp.text.toString())
                if (normalized != etCameraIp.text.toString()) {
                    etCameraIp.setText(normalized)
                    prefs.edit().putString("camera_ip", normalized).apply()
                }
            }
        }

        // 局域网扫描相机
        btnScanLocalCamera.setOnClickListener {
            if (!NetworkUtils.isWifiConnected(this@MainActivity)) {
                Toast.makeText(this@MainActivity, "⚠️ 手机未连接 Wi-Fi，无法扫描局域网设备", Toast.LENGTH_SHORT).show()
                appendLog("⚠️ 手机当前处于移动蜂窝网络，未连接 Wi-Fi，无法扫描局域网相机！请先打开手机 Wi-Fi", isError = true)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                btnScanLocalCamera.isEnabled = false
                btnScanLocalCamera.text = "扫描中..."
                appendLog("正在扫描局域网相机...")
                val foundIp = NetworkUtils.scanLocalCamera(this@MainActivity)
                if (foundIp != null) {
                    val normalized = NetworkUtils.normalizeIp(foundIp)
                    etCameraIp.setText(normalized)
                    prefs.edit().putString("camera_ip", normalized).apply()
                    appendLog("已发现相机 IP: $normalized")
                    Toast.makeText(this@MainActivity, "找到相机: $normalized", Toast.LENGTH_SHORT).show()
                } else {
                    appendLog("未扫描到相机，请确认相机处于[连接到计算机]待命状态", isError = true)
                    Toast.makeText(this@MainActivity, "未扫描到相机，请确认相机在同一 Wi-Fi 下", Toast.LENGTH_LONG).show()
                }
                btnScanLocalCamera.isEnabled = true
                btnScanLocalCamera.text = "自动扫描"
            }
        }

        // 重置为直连默认 IP 192.168.0.1
        btnResetDefaultIp.setOnClickListener {
            val defaultIp = getString(R.string.default_ip)
            etCameraIp.setText(defaultIp)
            prefs.edit().putString("camera_ip", defaultIp).apply()
            appendLog("已重置相机 IP 为默认直连热点地址: $defaultIp")
            Toast.makeText(this, "已重置为 $defaultIp", Toast.LENGTH_SHORT).show()
        }

        val savedFormat = prefs.getInt("format_mode", PhotoSyncService.FORMAT_JPG_ONLY)
        when (savedFormat) {
            PhotoSyncService.FORMAT_ALL -> rbFormatAll.isChecked = true
            PhotoSyncService.FORMAT_RAW_ONLY -> rbFormatRaw.isChecked = true
            else -> rbFormatJpg.isChecked = true
        }

        val autoConnectSaved = prefs.getBoolean("auto_connect", false)
        switchAutoConnect.isChecked = autoConnectSaved
        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_connect", isChecked).apply()
            syncService?.isAutoReconnectEnabled = isChecked
            if (isChecked) {
                appendLog("已开启连上 Wi-Fi 自动连接相机")
            }
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
            val rawIp = etCameraIp.text.toString().trim().ifEmpty { getString(R.string.default_ip) }
            val ip = NetworkUtils.normalizeIp(rawIp)
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
                val rawIp = etCameraIp.text.toString().trim().ifEmpty { NetworkUtils.getCameraIp(this) }
                val ip = NetworkUtils.normalizeIp(rawIp)
                etCameraIp.setText(ip)
                etCameraIp.clearFocus()
                prefs.edit().putString("camera_ip", ip).apply()

                if (!NetworkUtils.isWifiConnected(this)) {
                    Toast.makeText(this, "⚠️ 手机当前未连接 Wi-Fi！请先打开 Wi-Fi 连入与相机相同的网络", Toast.LENGTH_LONG).show()
                    appendLog("⚠️ 手机当前处于移动网络（未连接 Wi-Fi），无法访问局域网相机 IP $ip。请先打开手机 Wi-Fi 连入同一网络！", isError = true)
                    return@setOnClickListener
                }

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
        val isWifi = NetworkUtils.isWifiConnected(this)
        val (label, hint, color) = when {
            connecting -> Triple(getString(R.string.connecting), getString(R.string.connecting_hint), R.color.sync_gold_ink)
            connected -> Triple(getString(R.string.connected), getString(R.string.connected_hint), R.color.sync_green)
            !isWifi -> Triple(getString(R.string.idle), "⚠️ 手机当前未连接 Wi-Fi（处于移动蜂窝网络）。请先打开 Wi-Fi 连入与相机相同的网络！", R.color.sync_muted)
            else -> Triple(getString(R.string.idle), getString(R.string.wifi_hint), R.color.sync_muted)
        }
        tvStatusBadge.text = label
        tvStatusBadge.setTextColor(ContextCompat.getColor(this, color))
        tvStatusText.text = hint
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

    private fun appendLog(msg: String, isError: Boolean = false) {
        if (msg == lastLoggedMessage) return
        lastLoggedMessage = msg
        val line = "[${android.text.format.DateFormat.format("HH:mm:ss", java.util.Date())}] $msg\n"
        tvLogConsole.append(line)
        if (isError) {
            tvLogBadge.text = "出现异常"
            tvLogBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        }
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
