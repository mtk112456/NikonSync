package com.nikonsync.app.ptp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import com.nikonsync.app.util.NetworkUtils

/**
 * 现代尼康 PTP/IP 协议通信客户端
 * 完全对齐原版 ZRelay 底层状态机：
 * 1. 严格分离【首次配对流程】(Pairing Session) 与【日常传输流程】(Transfer Session)
 * 2. 首次配对时与相机向导握手，读取 4 位数字配对挑战并提交确认，随后主动关闭配对通道等待相机按键确认
 * 3. 日常传输复用已保存的专属 GUID，直接 OpenSession 进入即拍即传工作状态
 * 4. sendPacket 严格单次写入完整字节数组，避免 TCP 拆包导致嵌入式协议栈断开
 */
class NikonPtpClient(
    host: String = "192.168.0.1",
    val port: Int = 15740
) {
    val host: String = NetworkUtils.normalizeIp(host)

    companion object {
        private const val TAG = "NikonPtpClient"
        private const val HANDSHAKE_TIMEOUT_MS = 60000 // 握手与配对等待超时 60 秒

        // PTP/IP 数据包类型
        const val PKT_INIT_CMD_REQ = 1
        const val PKT_INIT_CMD_ACK = 2
        const val PKT_INIT_EVENT_REQ = 3
        const val PKT_INIT_EVENT_ACK = 4
        const val PKT_INIT_FAIL = 5
        const val PKT_CMD_REQ = 6
        const val PKT_CMD_RESP = 7
        const val PKT_EVENT = 8
        const val PKT_DATA_START = 9
        const val PKT_DATA = 10
        const val PKT_DATA_END = 12

        // 标准 PTP 操作码 (OpCodes)
        const val OC_GET_DEVICE_INFO = 0x1001
        const val OC_OPEN_SESSION = 0x1002
        const val OC_CLOSE_SESSION = 0x1003
        const val OC_GET_STORAGE_IDS = 0x1004
        const val OC_GET_STORAGE_INFO = 0x1005
        const val OC_GET_NUM_OBJECTS = 0x1006
        const val OC_GET_OBJECT_HANDLES = 0x1007
        const val OC_GET_OBJECT_INFO = 0x1008
        const val OC_GET_OBJECT = 0x1009
        const val OC_GET_THUMB = 0x100A
        const val OC_INITIATE_CAPTURE = 0x100E

        // 尼康厂商 WTU 配对专用操作码与确认参数 (对齐 ZRelay ck0.java:2251 与 libzrelay_core.so)
        const val OC_NIKON_GET_PAIRING_CODE = 0x952B
        const val OC_NIKON_CONFIRM_PAIRING = 0x935A
        // 关键对齐：ConfirmPairing (0x935A) 的入参为 0x2001 (RC_OK)，向相机固件确认配对接受
        const val NIKON_PAIRING_ACCEPT = 0x2001

        // PTP 响应码
        const val RC_OK = 0x2001
        const val RC_SESSION_NOT_OPEN = 0x2003

        // PTP 事件
        const val EV_OBJECT_ADDED = 0x4002
        const val EV_DEVICE_PROP_CHANGED = 0x4006
        const val EV_CAPTURE_COMPLETE = 0x400A

        // 格式代码
        const val FORMAT_EXIF_JPEG = 0x3801
        const val FORMAT_TIFF_RAW = 0x3005
        const val FORMAT_NIKON_RAW = 0xB881

        // 默认备用客户端标识 (兼容旧调用)
        val CLIENT_DEFAULT_GUID: ByteArray = byteArrayOf(
            0x6e, 0x69, 0x6b, 0x6f, 0x6e, 0x73, 0x79, 0x6e,
            0x63, 0x70, 0x72, 0x6f, 0x32, 0x30, 0x32, 0x36
        )
    }

    private var cmdSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null

    private var eventSocket: Socket? = null
    private var eventIn: InputStream? = null

    private val txIdCounter = AtomicInteger(1)
    private fun nextTransactionId(): Int = txIdCounter.getAndIncrement()

    private val cmdMutex = Mutex()
    private var connectionNumber = 0
    private var sessionId = 1
    var cameraFriendlyName: String = "Nikon Camera"
        private set

    @Volatile
    var isConnected = false
        private set

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventJob: Job? = null

    var onPhotoCaptured: ((handle: Int) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean, message: String) -> Unit)? = null

    /**
     * 配对结果封装
     */
    sealed class PairingResult {
        data class Success(val cameraName: String, val pairingCode: String) : PairingResult()
        data class Error(val message: String) : PairingResult()
    }

    /**
     * 执行原版相机首次配对流程：
     * 1. 使用全新专属 GUID 与 FriendlyName 发起基础 PTP/IP 握手
     * 2. 发送 GetDeviceInfo (0x1001) 与 OpenSession (0x1002)
     * 3. 发送 GetPairingCode (0x952B) 唤醒相机显示 4 位数认证码，并从数据流提取
     * 4. 发送 ConfirmPairing (0x935A, 0xD061) 提交确认，使相机屏幕显示【配对完成】
     * 5. 主动关闭连接，提示用户在机身上按 OK 键保存
     */
    suspend fun pairCamera(
        guid: ByteArray,
        friendlyName: String,
        onProgress: ((String) -> Unit)? = null,
        onCodeDiscovered: ((String) -> Unit)? = null
    ): PairingResult = withContext(Dispatchers.IO) {
        cmdMutex.withLock {
            try {
                Log.i(TAG, "开始执行相机首次配对向导 -> $host:$port (主机: $friendlyName)")
                initTransport(guid, friendlyName, onProgress)

                // 1. GetDeviceInfo (0x1001)
                onProgress?.invoke("正在获取相机设备信息 (0x1001)...")
                try {
                    val devInfoResp = executePtpCommand(OC_GET_DEVICE_INFO.toShort())
                    Log.d(TAG, "GetDeviceInfo 响应成功: 0x${devInfoResp.responseCode.toString(16)}")
                } catch (e: Exception) {
                    Log.w(TAG, "GetDeviceInfo 协商跳过: ${e.message}")
                }

                // 2. OpenSession (0x1002)
                onProgress?.invoke("正在打开 PTP 会话 (0x1002)...")
                val openSessionResp = executePtpCommand(OC_OPEN_SESSION.toShort(), sessionId)
                if (openSessionResp.responseCode != RC_OK) {
                    throw IllegalStateException("打开会话失败: 0x${openSessionResp.responseCode.toString(16)}")
                }

                // 3. GetPairingCode (0x952B) 触发相机显示认证码
                onProgress?.invoke("正在读取相机屏幕配对挑战码 (0x952B)...")
                val codeResp = executePtpCommand(OC_NIKON_GET_PAIRING_CODE.toShort())
                var pairingCode = ""
                if (codeResp.data.size >= 8) {
                    val buf = ByteBuffer.wrap(codeResp.data).order(ByteOrder.LITTLE_ENDIAN)
                    val numDigits = buf.getInt()
                    if (numDigits in 4..16 && codeResp.data.size >= 4 + numDigits) {
                        val sb = StringBuilder()
                        for (i in 0 until numDigits) {
                            val digit = codeResp.data[4 + i].toInt() and 0xFF
                            sb.append(digit)
                        }
                        pairingCode = sb.toString()
                        Log.i(TAG, "已成功从相机提取 $numDigits 位配对挑战码: $pairingCode")
                        onCodeDiscovered?.invoke(pairingCode)
                    }
                }
                if (pairingCode.isEmpty()) {
                    pairingCode = "已核验"
                    onCodeDiscovered?.invoke(pairingCode)
                }

                // 4. ConfirmPairing (0x935A, 0x2001) 提交确认 (参数 0x2001 即 PTP RC_OK)
                onProgress?.invoke("已获取配对码 [$pairingCode]，正在向相机提交确认 (0x935A)...")
                val confirmResp = executePtpCommand(OC_NIKON_CONFIRM_PAIRING.toShort(), NIKON_PAIRING_ACCEPT)
                if (confirmResp.responseCode != RC_OK) {
                    throw IllegalStateException("相机拒绝配对确认 (响应码: 0x${confirmResp.responseCode.toString(16)})")
                }
                onProgress?.invoke("相机已确认配对！向导完成，准备固化配置...")

                // 5. 对齐原版：配对完成后立即主动关闭配对 Socket，让相机就绪并等待用户按 OK 键
                disconnect()

                PairingResult.Success(cameraFriendlyName, pairingCode)
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "配对向导异常中断: $errorMsg", e)
                disconnect()
                PairingResult.Error(errorMsg)
            }
        }
    }

    /**
     * 执行原版日常传输连接（Transfer Session）：
     * 1. 使用已配对的专属 GUID 和 FriendlyName 发起底层握手
     * 2. 对齐原版 NikonTransferService：OpenSession 之前必须执行 GetDeviceInfo (0x1001)
     * 3. 发送 OpenSession (0x1002) 激活照片传输会话
     * 4. 监听后台事件流 (EV_OBJECT_ADDED 0x4002) 实现即拍即传
     */
    suspend fun connect(
        guid: ByteArray = CLIENT_DEFAULT_GUID,
        friendlyName: String = "NikonSync Pro",
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        cmdMutex.withLock {
            try {
                Log.d(TAG, "正在建立日常传输连接 -> $host:$port (主机: $friendlyName)...")
                initTransport(guid, friendlyName, onProgress)

                // 1. 对齐原版 NikonTransferService.java:1510：OpenSession 之前必须先调用 GetDeviceInfo (0x1001)
                onProgress?.invoke("正在获取相机设备信息 (0x1001)...")
                try {
                    val devInfoResp = executePtpCommand(OC_GET_DEVICE_INFO.toShort())
                    Log.d(TAG, "GetDeviceInfo 响应成功: 0x${devInfoResp.responseCode.toString(16)}")
                } catch (e: Exception) {
                    Log.w(TAG, "GetDeviceInfo 协商跳过: ${e.message}")
                }

                // 2. 打开日常传输会话 (0x1002)
                onProgress?.invoke("正在打开相机传输会话 (0x1002)...")
                val resp = executePtpCommand(OC_OPEN_SESSION.toShort(), sessionId)
                if (resp.responseCode != RC_OK && resp.responseCode != RC_SESSION_NOT_OPEN) {
                    throw IllegalStateException("打开传输会话失败: 0x${resp.responseCode.toString(16)}")
                }

                // 3. 对齐原版：探测可伸缩镜头状态 (0xD09C)，保持固件通信链路平稳
                try {
                    executePtpCommand(0x1014.toShort(), 0xD09C)
                } catch (_: Exception) {}

                cmdSocket?.soTimeout = 0
                eventSocket?.soTimeout = 0

                isConnected = true
                onConnectionStateChanged?.invoke(true, "连接成功 (相机传输就绪)")

                // 启动后台事件监听
                startEventListener()
                true
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "日常连接异常: $errorMsg", e)
                disconnect()
                onConnectionStateChanged?.invoke(false, "连接失败: $errorMsg")
                false
            }
        }
    }

    /**
     * 建立底层 Command 和 Event 两个 TCP Socket 并完成 PTP/IP 握手
     */
    private fun initTransport(
        guid: ByteArray,
        friendlyName: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        disconnect()

        // 1. 建立 Command Socket
        onProgress?.invoke("正在建立 TCP 控制通道 (端口 $port)...")
        val command = Socket()
        cmdSocket = command
        command.tcpNoDelay = true
        command.keepAlive = true
        command.receiveBufferSize = 4 * 1024 * 1024
        command.connect(InetSocketAddress(host, port), 5000)
        command.soTimeout = 15000
        cmdIn = command.getInputStream()
        cmdOut = command.getOutputStream()

        // 2. 发送 InitCommandRequest (16字节 GUID + FriendlyName + 协议版本)
        onProgress?.invoke("已连接 TCP，正在发送 InitCommandRequest (主机: $friendlyName)...")
        val nameWithTerminator = if (friendlyName.endsWith("\u0000")) friendlyName else "$friendlyName\u0000"
        val nameBytes = nameWithTerminator.toByteArray(Charsets.UTF_16LE)
        val reqPayload = ByteBuffer.allocate(16 + nameBytes.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        reqPayload.put(guid)
        reqPayload.put(nameBytes)
        reqPayload.putInt(0x00010000)

        sendPacket(cmdOut!!, PKT_INIT_CMD_REQ, reqPayload.array())

        // 3. 读取 InitCommandAck
        onProgress?.invoke("等待相机会话确认 (InitCommandAck)...")
        val ackPacket = readPacket(cmdIn!!)
        if (ackPacket.type == PKT_INIT_FAIL) {
            val reason = if (ackPacket.payload.size >= 4) {
                ByteBuffer.wrap(ackPacket.payload).order(ByteOrder.LITTLE_ENDIAN).getInt()
            } else -1
            throw IllegalStateException("相机拒绝连接 (InitFail=0x${Integer.toHexString(reason)})")
        }
        if (ackPacket.type != PKT_INIT_CMD_ACK || ackPacket.payload.size < 4) {
            throw IllegalStateException("意外的 InitCommandAck (type=${ackPacket.type})")
        }

        val ackBuf = ByteBuffer.wrap(ackPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
        connectionNumber = ackBuf.getInt()

        // 尝试提取相机的 FriendlyName (跳过 16 字节相机 GUID)
        if (ackBuf.remaining() >= 16) {
            ackBuf.position(ackBuf.position() + 16)
            cameraFriendlyName = readPtpString(ackBuf).ifEmpty { "Nikon Camera" }
        }
        onProgress?.invoke("相机已接受控制连接：$cameraFriendlyName (会话号 $connectionNumber)")

        // 4. 建立 Event Socket
        onProgress?.invoke("正在建立 Event 事件通道...")
        val event = Socket()
        eventSocket = event
        event.tcpNoDelay = true
        event.keepAlive = true
        event.receiveBufferSize = 4 * 1024 * 1024
        event.connect(InetSocketAddress(host, port), 5000)
        event.soTimeout = 15000
        eventIn = event.getInputStream()
        val eventOut = event.getOutputStream()

        // 5. 发送 InitEventRequest
        onProgress?.invoke("正在发送 InitEventRequest...")
        val eventReqPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        eventReqPayload.putInt(connectionNumber)
        sendPacket(eventOut, PKT_INIT_EVENT_REQ, eventReqPayload.array())

        // 6. 读取 InitEventAck
        onProgress?.invoke("等待 Event 通道握手确认...")
        val eventAckPacket = readPacket(eventIn!!)
        if (eventAckPacket.type != PKT_INIT_EVENT_ACK) {
            throw IllegalStateException("Event 握手失败 (type=${eventAckPacket.type})")
        }
        onProgress?.invoke("Event 事件通道握手完成！")
    }

    /**
     * 统一执行带数据阶段的 PTP 操作命令 (完全复刻原版 ck0.b0 流程)
     */
    private fun executePtpCommand(opCode: Short, vararg params: Int): PtpResponse {
        val txId = nextTransactionId()
        val payload = buildCommandPayload(opCode, txId, *params)
        sendPacket(cmdOut!!, PKT_CMD_REQ, payload)

        val dataStream = ByteArrayOutputStream()
        while (true) {
            val pkt = readPacket(cmdIn!!)
            when (pkt.type) {
                PKT_DATA_START -> {
                    // StartData: 前 4 字节为 txId，后续 8 字节为总长度
                    if (pkt.payload.size > 12) {
                        dataStream.write(pkt.payload, 12, pkt.payload.size - 12)
                    }
                }
                PKT_DATA, PKT_DATA_END -> {
                    // Data / EndData: 前 4 字节为 txId，后续为真实数据
                    if (pkt.payload.size > 4) {
                        dataStream.write(pkt.payload, 4, pkt.payload.size - 4)
                    }
                }
                PKT_CMD_RESP -> {
                    val buf = ByteBuffer.wrap(pkt.payload).order(ByteOrder.LITTLE_ENDIAN)
                    val respCode = if (buf.remaining() >= 2) buf.getShort().toInt() and 0xFFFF else 0
                    val respTxId = if (buf.remaining() >= 4) buf.getInt() else 0
                    val respParams = mutableListOf<Int>()
                    while (buf.remaining() >= 4) {
                        respParams.add(buf.getInt())
                    }
                    return PtpResponse(respCode, respTxId, respParams, dataStream.toByteArray())
                }
                PKT_INIT_FAIL -> {
                    throw IllegalStateException("执行 PTP 指令 0x${opCode.toString(16)} 时收到 InitFail")
                }
                else -> {
                    Log.d(TAG, "执行命令中忽略包类型: ${pkt.type}")
                }
            }
        }
    }

    private fun buildCommandPayload(opCode: Short, txId: Int, vararg params: Int): ByteArray {
        val size = 4 + 2 + 4 + (params.size * 4)
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1) // Data Phase Info (1 = No Outbound Data)
        buf.putShort(opCode)
        buf.putInt(txId)
        for (p in params) {
            buf.putInt(p)
        }
        return buf.array()
    }

    /**
     * 远程按快门 (对齐原版 InitiateCapture 0x100E)
     */
    suspend fun triggerShutter(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        cmdMutex.withLock {
            try {
                val resp = executePtpCommand(OC_INITIATE_CAPTURE.toShort(), 0, 0)
                resp.responseCode == RC_OK
            } catch (e: Exception) {
                Log.e(TAG, "远程快门异常: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 获取存储介质中照片/视频句柄列表 (0x1007)
     */
    suspend fun getAllObjectHandles(storageId: Int = -1): List<Int> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext emptyList()
        cmdMutex.withLock {
            try {
                val resp = executePtpCommand(OC_GET_OBJECT_HANDLES.toShort(), storageId, 0, 0)
                val handles = mutableListOf<Int>()
                if (resp.data.size >= 4) {
                    val buf = ByteBuffer.wrap(resp.data).order(ByteOrder.LITTLE_ENDIAN)
                    val count = buf.getInt()
                    for (i in 0 until count) {
                        if (buf.remaining() >= 4) {
                            handles.add(buf.getInt())
                        }
                    }
                }
                handles
            } catch (e: Exception) {
                Log.e(TAG, "获取 ObjectHandles 异常: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 获取对象元信息 (0x1008)
     */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val resp = executePtpCommand(OC_GET_OBJECT_INFO.toShort(), handle)
                if (resp.data.isEmpty()) return@withContext null

                val buf = ByteBuffer.wrap(resp.data).order(ByteOrder.LITTLE_ENDIAN)
                val storageId = buf.getInt()
                val formatCode = buf.getShort().toInt() and 0xFFFF
                val protectionStatus = buf.getShort().toInt() and 0xFFFF
                val compressedSize = buf.getInt().toLong() and 0xFFFFFFFFL
                val thumbFormat = buf.getShort().toInt() and 0xFFFF
                val thumbCompressedSize = buf.getInt().toLong() and 0xFFFFFFFFL
                val thumbPixWidth = buf.getInt()
                val thumbPixHeight = buf.getInt()
                val imagePixWidth = buf.getInt()
                val imagePixHeight = buf.getInt()
                val imageBitDepth = buf.getInt()
                val parentObject = buf.getInt()
                val associationType = buf.getShort().toInt() and 0xFFFF
                val associationDesc = buf.getInt()
                val sequenceNumber = buf.getInt()

                val filename = readPtpString(buf)
                val dateCreated = readPtpString(buf)
                val dateModified = readPtpString(buf)

                PtpObjectInfo(
                    handle = handle,
                    storageId = storageId,
                    formatCode = formatCode,
                    compressedSize = compressedSize,
                    thumbFormat = thumbFormat,
                    thumbCompressedSize = thumbCompressedSize,
                    thumbPixWidth = thumbPixWidth,
                    thumbPixHeight = thumbPixHeight,
                    imagePixWidth = imagePixWidth,
                    imagePixHeight = imagePixHeight,
                    filename = filename,
                    dateCreated = dateCreated,
                    dateModified = dateModified
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取 ObjectInfo handle=$handle 异常: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 获取缩略图 (0x100A)
     */
    suspend fun getThumb(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val resp = executePtpCommand(OC_GET_THUMB.toShort(), handle)
                if (resp.data.isNotEmpty()) resp.data else null
            } catch (e: Exception) {
                Log.e(TAG, "获取缩略图 handle=$handle 异常: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 下载完整原图/RAW/视频 (0x1009)
     */
    suspend fun downloadObject(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val resp = executePtpCommand(OC_GET_OBJECT.toShort(), handle)
                if (resp.data.isNotEmpty()) resp.data else null
            } catch (e: Exception) {
                Log.e(TAG, "下载原图 handle=$handle 异常: ${e.message}", e)
                null
            }
        }
    }

    private fun startEventListener() {
        eventJob?.cancel()
        eventJob = clientScope.launch {
            try {
                while (isActive && isConnected) {
                    val pkt = readPacket(eventIn!!)
                    if (pkt.type == PKT_EVENT) {
                        val buf = ByteBuffer.wrap(pkt.payload).order(ByteOrder.LITTLE_ENDIAN)
                        val eventCode = buf.getShort().toInt() and 0xFFFF
                        val txId = buf.getInt()
                        val param1 = if (buf.remaining() >= 4) buf.getInt() else 0

                        Log.d(TAG, "收到相机事件: 0x${eventCode.toString(16)}, Param1=$param1")
                        if (eventCode == EV_OBJECT_ADDED) {
                            Log.i(TAG, "检测到新照片写入 Handle = $param1")
                            onPhotoCaptured?.invoke(param1)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Log.w(TAG, "事件通道断开: ${e.message}")
                    disconnect()
                    onConnectionStateChanged?.invoke(false, "相机连接已断开")
                }
            }
        }
    }

    fun disconnect() {
        isConnected = false
        eventJob?.cancel()
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { eventSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        eventSocket = null
    }

    /**
     * 对齐原版 ek0.j：将 Header 与 Payload 组装为单个完整字节数组，单次 write 写入网络流
     * 避免因分两次写入导致 TCP 产生 8 字节小包分节而使相机固件提前 EOF
     */
    private fun sendPacket(out: OutputStream, type: Int, payload: ByteArray) {
        val totalLength = 8 + payload.size
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(totalLength)
        buf.putInt(type)
        if (payload.isNotEmpty()) {
            buf.put(payload)
        }
        out.write(packet)
        out.flush()
    }

    private fun readPacket(input: InputStream): PtpPacket {
        val headerBytes = ByteArray(8)
        readFully(input, headerBytes)
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = headerBuf.getInt()
        val type = headerBuf.getInt()

        if (length < 8) {
            throw IllegalStateException("非法 PTP 包长度: $length")
        }
        val payloadSize = length - 8
        val payload = ByteArray(payloadSize)
        if (payloadSize > 0) {
            readFully(input, payload)
        }
        return PtpPacket(type, payload)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var readTotal = 0
        while (readTotal < buffer.size) {
            val count = input.read(buffer, readTotal, buffer.size - readTotal)
            if (count < 0) throw IllegalStateException("读取 Socket 提前遭遇 EOF")
            readTotal += count
        }
    }

    private fun readPtpString(buf: ByteBuffer): String {
        if (buf.remaining() < 1) return ""
        val len = buf.get().toInt() and 0xFF
        if (len == 0 || buf.remaining() < len * 2) return ""
        val charArray = CharArray(len)
        for (i in 0 until len) {
            charArray[i] = buf.getShort().toInt().toChar()
        }
        val s = String(charArray)
        return if (s.endsWith("\u0000")) s.substring(0, s.length - 1) else s
    }

    data class PtpPacket(val type: Int, val payload: ByteArray)

    data class PtpResponse(
        val responseCode: Int,
        val transactionId: Int,
        val parameters: List<Int>,
        val data: ByteArray
    )
}

/**
 * PTP 对象信息数据集
 */
data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val formatCode: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val thumbPixWidth: Int,
    val thumbPixHeight: Int,
    val imagePixWidth: Int,
    val imagePixHeight: Int,
    val filename: String,
    val dateCreated: String,
    val dateModified: String
) {
    val isRaw: Boolean
        get() = filename.endsWith(".nef", ignoreCase = true) ||
                filename.endsWith(".nrw", ignoreCase = true) ||
                formatCode == NikonPtpClient.FORMAT_TIFF_RAW ||
                formatCode == NikonPtpClient.FORMAT_NIKON_RAW

    val isJpeg: Boolean
        get() = filename.endsWith(".jpg", ignoreCase = true) ||
                filename.endsWith(".jpeg", ignoreCase = true) ||
                formatCode == NikonPtpClient.FORMAT_EXIF_JPEG
}
