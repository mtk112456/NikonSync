package com.nikonsync.app.ptp

import android.util.Log
import com.kw.ztransfer.NativePtpCore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 现代尼康 PTP/IP 相机通信客户端
 * 支持尼康 Z 系列相机 (Z30, Z50, Zfc, Z5, Z6, Z7, Z8, Z9)
 * 包含双通道握手、状态监听、拍照触发、对象信息检索、缩略图获取与原图流下载
 */
class NikonPtpClient(
    val host: String = "192.168.0.1",
    val port: Int = 15740
) {
    companion object {
        private const val TAG = "NikonPtpClient"
        private const val HANDSHAKE_TIMEOUT_MS = 5000

        // PTP/IP 数据包类型
        const val PKT_INIT_CMD_REQ = 1
        const val PKT_INIT_CMD_ACK = 2
        const val PKT_INIT_EVENT_REQ = 3
        const val PKT_INIT_EVENT_ACK = 4
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

        // PTP 事件码
        const val EV_OBJECT_ADDED = 0x4002
        const val EV_DEVICE_PROP_CHANGED = 0x4006
        const val EV_CAPTURE_COMPLETE = 0x400A

        // 格式码
        const val FORMAT_EXIF_JPEG = 0x3801
        const val FORMAT_TIFF_RAW = 0x3005
        const val FORMAT_NIKON_RAW = 0xB881
    }

    private var cmdSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null

    private var eventSocket: Socket? = null
    private var eventIn: InputStream? = null

    private val nativeCore = NativePtpCore.getInstance()
    private val cmdMutex = Mutex()
    private var connectionNumber = 0
    private var sessionId = 1

    @Volatile
    var isConnected = false
        private set

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventJob: Job? = null

    var onPhotoCaptured: ((handle: Int) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean, message: String) -> Unit)? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "正在建立相机连接 -> $host:$port...")

            // 1. 建立命令通道
            val command = Socket()
            cmdSocket = command
            command.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            command.soTimeout = HANDSHAKE_TIMEOUT_MS
            command.tcpNoDelay = true
            cmdIn = command.getInputStream()
            cmdOut = command.getOutputStream()

            // 2. 发送命令通道握手包
            val guid = UUID.randomUUID()
            val friendlyName = "NikonSync Pro\u0000"
            val nameBytes = friendlyName.toByteArray(Charsets.UTF_16LE)
            val reqPayload = ByteBuffer.allocate(16 + nameBytes.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            reqPayload.putLong(guid.mostSignificantBits)
            reqPayload.putLong(guid.leastSignificantBits)
            reqPayload.put(nameBytes)
            reqPayload.putInt(0x00010000)

            sendPacket(cmdOut!!, PKT_INIT_CMD_REQ, reqPayload.array())

            // 3. 读取命令通道握手确认
            val ackPacket = readPacket(cmdIn!!)
            if (ackPacket.type != PKT_INIT_CMD_ACK) {
                throw IllegalStateException("非预期的命令通道握手回应类型: ${ackPacket.type}")
            }
            val ackBuffer = ByteBuffer.wrap(ackPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
            connectionNumber = ackBuffer.getInt()
            Log.d(TAG, "命令通道握手成功，连接编号 = $connectionNumber")

            // 4. 建立事件通道
            val event = Socket()
            eventSocket = event
            event.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            event.soTimeout = HANDSHAKE_TIMEOUT_MS
            event.tcpNoDelay = true
            eventIn = event.getInputStream()
            val eventOut = event.getOutputStream()

            val eventPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            eventPayload.putInt(connectionNumber)
            sendPacket(eventOut, PKT_INIT_EVENT_REQ, eventPayload.array())

            val eventAck = readPacket(eventIn!!)
            if (eventAck.type != PKT_INIT_EVENT_ACK) {
                throw IllegalStateException("非预期的事件通道握手回应类型: ${eventAck.type}")
            }
            Log.d(TAG, "事件通道握手成功")

            // 5. 开启会话
            openSessionInternal()
            command.soTimeout = 0
            event.soTimeout = 0

            isConnected = true
            nativeCore.onSessionOpened()
            onConnectionStateChanged?.invoke(true, "相机连接成功 (会话已建立)")

            // 6. 启动后台事件监听
            startEventListener()
            true
        } catch (e: Exception) {
            Log.e(TAG, "连接相机异常: ${e.message}", e)
            disconnect()
            onConnectionStateChanged?.invoke(false, "连接失败: ${e.message}")
            false
        }
    }

    private fun openSessionInternal() {
        val txId = nativeCore.nextTransactionId().toInt()
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(1)
        payload.putShort(OC_OPEN_SESSION.toShort())
        payload.putInt(txId)
        payload.putInt(sessionId)

        sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())
        readPacket(cmdIn!!)
    }

    suspend fun triggerShutter(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        cmdMutex.withLock {
            try {
                val txId = nativeCore.nextTransactionId().toInt()
                val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                payload.putInt(1)
                payload.putShort(OC_INITIATE_CAPTURE.toShort())
                payload.putInt(txId)
                payload.putInt(0)
                payload.putInt(0)

                sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())
                readPacket(cmdIn!!)
                true
            } catch (e: Exception) {
                Log.e(TAG, "触发快门异常: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 获取相机所有存储分区的所有对象句柄（照片/视频）
     */
    suspend fun getAllObjectHandles(storageId: Int = -1): List<Int> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext emptyList()
        cmdMutex.withLock {
            try {
                val txId = nativeCore.nextTransactionId().toInt()
                val payload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                payload.putInt(1)
                payload.putShort(OC_GET_OBJECT_HANDLES.toShort())
                payload.putInt(txId)
                payload.putInt(storageId)
                payload.putInt(0) // 全部格式
                payload.putInt(0) // 根目录

                sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

                val dataPacket = readPacket(cmdIn!!)
                val handles = mutableListOf<Int>()
                if (dataPacket.type == PKT_DATA || dataPacket.type == PKT_DATA_END) {
                    val buf = ByteBuffer.wrap(dataPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
                    if (buf.remaining() >= 4) {
                        buf.getInt() // 略过事务编号
                    }
                    if (buf.remaining() >= 4) {
                        val count = buf.getInt()
                        for (i in 0 until count) {
                            if (buf.remaining() >= 4) {
                                handles.add(buf.getInt())
                            }
                        }
                    }
                }
                readPacket(cmdIn!!) // 读取命令应答
                handles
            } catch (e: Exception) {
                Log.e(TAG, "获取对象列表失败: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 读取指定对象的元数据（格式、文件名、压缩尺寸、拍摄时间等）
     */
    suspend fun getObjectInfo(handle: Int): PtpObjectInfo? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val txId = nativeCore.nextTransactionId().toInt()
                val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                payload.putInt(1)
                payload.putShort(OC_GET_OBJECT_INFO.toShort())
                payload.putInt(txId)
                payload.putInt(handle)

                sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

                val dataPacket = readPacket(cmdIn!!)
                var info: PtpObjectInfo? = null
                if (dataPacket.type == PKT_DATA || dataPacket.type == PKT_DATA_END) {
                    val buf = ByteBuffer.wrap(dataPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
                    if (buf.remaining() >= 4) {
                        buf.getInt() // 跳过 4 字节事务 ID
                    }
                    if (buf.remaining() >= 52) {
                        val storageId = buf.getInt()
                        val formatCode = buf.getShort().toInt() and 0xFFFF
                        val protection = buf.getShort().toInt() and 0xFFFF
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

                        info = PtpObjectInfo(
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
                            filename = filename.ifEmpty { "DSC_${handle}.JPG" },
                            dateCreated = dateCreated,
                            dateModified = dateModified
                        )
                    }
                }
                readPacket(cmdIn!!) // 读取命令应答
                info
            } catch (e: Exception) {
                Log.e(TAG, "获取对象信息 handle=$handle 失败: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 获取指定对象的 JPEG 快速缩略图（用于相册画廊极速预览）
     */
    suspend fun getThumb(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val txId = nativeCore.nextTransactionId().toInt()
                val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                payload.putInt(1)
                payload.putShort(OC_GET_THUMB.toShort())
                payload.putInt(txId)
                payload.putInt(handle)

                sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

                val chunks = mutableListOf<ByteArray>()
                var totalBytes = 0
                while (true) {
                    val pkt = readPacket(cmdIn!!)
                    if (pkt.type == PKT_DATA || pkt.type == PKT_DATA_END) {
                        val chunkPayload = if (pkt.payload.size > 4) {
                            pkt.payload.copyOfRange(4, pkt.payload.size)
                        } else ByteArray(0)
                        chunks.add(chunkPayload)
                        totalBytes += chunkPayload.size
                        if (pkt.type == PKT_DATA_END) break
                    } else if (pkt.type == PKT_CMD_RESP) {
                        break
                    }
                }
                readPacket(cmdIn!!)

                if (totalBytes == 0) return@withLock null
                val fullData = ByteArray(totalBytes)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, fullData, offset, chunk.size)
                    offset += chunk.size
                }
                fullData
            } catch (e: Exception) {
                Log.e(TAG, "获取缩略图 handle=$handle 失败: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 下载完整照片或文件字节流
     */
    suspend fun downloadObject(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        cmdMutex.withLock {
            try {
                val txId = nativeCore.nextTransactionId().toInt()
                val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                payload.putInt(1)
                payload.putShort(OC_GET_OBJECT.toShort())
                payload.putInt(txId)
                payload.putInt(handle)

                sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

                val chunks = mutableListOf<ByteArray>()
                var totalBytes = 0
                while (true) {
                    val pkt = readPacket(cmdIn!!)
                    if (pkt.type == PKT_DATA || pkt.type == PKT_DATA_END) {
                        val chunkPayload = if (pkt.payload.size > 4) {
                            pkt.payload.copyOfRange(4, pkt.payload.size)
                        } else ByteArray(0)
                        chunks.add(chunkPayload)
                        totalBytes += chunkPayload.size
                        if (pkt.type == PKT_DATA_END) break
                    } else if (pkt.type == PKT_CMD_RESP) {
                        break
                    }
                }
                readPacket(cmdIn!!)

                val fullData = ByteArray(totalBytes)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, fullData, offset, chunk.size)
                    offset += chunk.size
                }
                Log.d(TAG, "成功下载对象 handle=$handle ($totalBytes 字节)")
                fullData
            } catch (e: Exception) {
                Log.e(TAG, "下载对象 handle=$handle 异常: ${e.message}", e)
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
                            Log.i(TAG, "相机拍摄生成新照片/对象！Handle = $param1")
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
        nativeCore.onSessionClosed()
    }

    private fun sendPacket(out: OutputStream, type: Int, payload: ByteArray) {
        val totalLength = 8 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(totalLength)
        header.putInt(type)
        out.write(header.array())
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readPacket(input: InputStream): PtpPacket {
        val headerBytes = ByteArray(8)
        readFully(input, headerBytes)
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = headerBuf.getInt()
        val type = headerBuf.getInt()

        if (length < 8) {
            throw IllegalStateException("非法的 PTP 包长度: $length")
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
            if (count < 0) throw IllegalStateException("读取 Socket 流提前到达 EOF")
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
}

/**
 * PTP 对象信息描述数据集
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
