package com.nikonsync.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nikonsync.app.ui.MainActivity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * 尼康相机低功耗蓝牙 (BLE) GPS 定位实时中继服务
 * 获取手机高精度卫星定位，实时注入到尼康相机的 EXIF 信息中
 */
class BluetoothGpsRelayService : Service(), LocationListener {

    companion object {
        const val TAG = "NikonGpsRelay"
        const val CHANNEL_ID = "nikon_gps_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START_GPS = "com.nikonsync.action.START_GPS"
        const val ACTION_STOP_GPS = "com.nikonsync.action.STOP_GPS"

        // 尼康 SnapBridge 专用 BLE 服务与特征 UUID
        val NIKON_SERVICE_UUID: UUID = UUID.fromString("00002000-3dd4-4255-8d62-6dc7b9bd5561")
        val NIKON_ALT_SERVICE_UUID: UUID = UUID.fromString("0000de00-3dd4-4255-8d62-6dc7b9bd5561")
        val NIKON_GPS_CHAR_UUID: UUID = UUID.fromString("00002002-3dd4-4255-8d62-6dc7b9bd5561")
        val NIKON_CONTROL_CHAR_UUID: UUID = UUID.fromString("00002008-3dd4-4255-8d62-6dc7b9bd5561")
    }

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var gpsChar: BluetoothGattCharacteristic? = null

    @Volatile var isGpsRunning = false
        private set
    @Volatile var isBleConnected = false
        private set
    @Volatile var lastLocation: Location? = null
        private set
    @Volatile var statusMessage: String = "未启动"
        private set

    var onGpsStatusChanged: ((message: String, isConnected: Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothGpsRelayService = this@BluetoothGpsRelayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GPS -> {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动蓝牙 GPS 中继..."))
                startRelay()
            }
            ACTION_STOP_GPS -> {
                stopRelay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRelay() {
        if (isGpsRunning) return
        isGpsRunning = true
        statusMessage = "正在监听手机 GPS 定位并扫描尼康相机..."
        notifyStatus(statusMessage, false)

        // 1. 注册手机位置监听
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 1.0f, this)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5.0f, this)
            val lastKnown = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnown != null) {
                lastLocation = lastKnown
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册位置监听失败: ${e.message}")
        }

        // 2. 扫描相机 BLE
        startBleScan()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || bluetoothAdapter?.isEnabled != true) {
            statusMessage = "手机蓝牙未开启，请在系统设置中启用蓝牙"
            notifyStatus(statusMessage, false)
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NIKON_SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NIKON_ALT_SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        statusMessage = "正在搜索附近的尼康微单蓝牙广播..."
        notifyStatus(statusMessage, false)

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "带过滤器扫描失败，尝试无过滤广播扫描: ${e.message}")
            try {
                scanner.startScan(scanCallback)
            } catch (ex: Exception) {
                Log.e(TAG, "启动蓝牙扫描彻底失败: ${ex.message}")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: ""

            // 识别尼康相机广播 (通常以 Nikon_ 开头或包含机型名称)
            if (name.contains("Nikon", ignoreCase = true) ||
                name.contains("Z 30", ignoreCase = true) ||
                name.contains("Z30", ignoreCase = true) ||
                name.contains("Z 50", ignoreCase = true) ||
                name.contains("Z fc", ignoreCase = true) ||
                result.scanRecord?.serviceUuids?.any { it.uuid == NIKON_SERVICE_UUID || it.uuid == NIKON_ALT_SERVICE_UUID } == true
            ) {
                Log.i(TAG, "发现目标尼康相机: $name (${device.address})")
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectToCameraGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            statusMessage = "蓝牙扫描失败 (错误码: $errorCode)"
            notifyStatus(statusMessage, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToCameraGatt(device: BluetoothDevice) {
        statusMessage = "正在连接相机蓝牙 GATT: ${device.name ?: device.address}..."
        notifyStatus(statusMessage, false)

        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isBleConnected = true
                    statusMessage = "相机蓝牙已连接，正在发现 GPS 同步服务..."
                    notifyStatus(statusMessage, true)
                    updateNotification("相机蓝牙已连接，正在同步 GPS")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isBleConnected = false
                    gpsChar = null
                    statusMessage = "相机蓝牙已断开"
                    notifyStatus(statusMessage, false)
                    updateNotification("相机蓝牙已断开")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    val service = gatt.getService(NIKON_SERVICE_UUID) ?: gatt.getService(NIKON_ALT_SERVICE_UUID)
                    if (service != null) {
                        gpsChar = service.getCharacteristic(NIKON_GPS_CHAR_UUID)
                        statusMessage = "GPS 写入通道已就绪，正在持续中继坐标！"
                        notifyStatus(statusMessage, true)
                        updateNotification("GPS 实时中继运行中")
                        // 立即写入一次当前定位
                        lastLocation?.let { sendLocationToCamera(it) }
                    } else {
                        statusMessage = "未能找到相机的 GPS GATT 服务"
                        notifyStatus(statusMessage, false)
                    }
                }
            }
        })
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        if (isBleConnected && gpsChar != null) {
            sendLocationToCamera(location)
        }
    }

    /**
     * 将经纬度、海拔及 UTC 时间打包并写入尼康特征通道
     */
    @SuppressLint("MissingPermission")
    private fun sendLocationToCamera(loc: Location) {
        val gatt = bluetoothGatt ?: return
        val ch = gpsChar ?: return

        try {
            // 尼康 BLE GPS 数据包结构 (Little-Endian)
            // 纬度 (int32, * 10,000,000)
            // 经度 (int32, * 10,000,000)
            // 海拔 (int32, 米)
            // 年(uint16), 月(uint8), 日(uint8), 时(uint8), 分(uint8), 秒(uint8)
            val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            val latInt = (loc.latitude * 10000000.0).toInt()
            val lonInt = (loc.longitude * 10000000.0).toInt()
            val altInt = if (loc.hasAltitude()) loc.altitude.toInt() else 0

            buffer.putInt(latInt)
            buffer.putInt(lonInt)
            buffer.putInt(altInt)

            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = loc.time
            buffer.putShort(cal.get(Calendar.YEAR).toShort())
            buffer.put((cal.get(Calendar.MONTH) + 1).toByte())
            buffer.put(cal.get(Calendar.DAY_OF_MONTH).toByte())
            buffer.put(cal.get(Calendar.HOUR_OF_DAY).toByte())
            buffer.put(cal.get(Calendar.MINUTE).toByte())
            buffer.put(cal.get(Calendar.SECOND).toByte())

            val payload = buffer.array()
            ch.value = payload
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = gatt.writeCharacteristic(ch)
            if (ok) {
                val coordText = String.format("%.5f, %.5f (%.0fm)", loc.latitude, loc.longitude, loc.altitude)
                statusMessage = "已同步 GPS 坐标至相机: $coordText"
                notifyStatus(statusMessage, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入 GPS 数据包异常: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    @SuppressLint("MissingPermission")
    fun stopRelay() {
        isGpsRunning = false
        isBleConnected = false
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        gpsChar = null
        statusMessage = "GPS 中继已停止"
        notifyStatus(statusMessage, false)
    }

    private fun notifyStatus(msg: String, connected: Boolean) {
        onGpsStatusChanged?.invoke(msg, connected)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NikonSync 蓝牙 GPS 同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "向相机实时写入地理位置定位信息"
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
            .setContentTitle("NikonSync GPS 定位中继")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
        stopRelay()
    }
}
