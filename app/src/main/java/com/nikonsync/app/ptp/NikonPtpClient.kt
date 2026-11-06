package com.nikonsync.app.ptp

import android.util.Log
import com.kw.ztransfer.NativePtpCore
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Clean, modern Nikon PTP/IP Camera Client.
 * Connects over Wi-Fi / Hotspot to Nikon Z series cameras (Z30, Z50, Zfc, etc.)
 */
class NikonPtpClient(
    private val host: String = "192.168.0.1",
    private val port: Int = 15740
) {
    companion object {
        private const val TAG = "NikonPtpClient"

        // PTP/IP Packet Types
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

        // Standard PTP Opcodes
        const val OC_GET_DEVICE_INFO = 0x1001
        const val OC_OPEN_SESSION = 0x1002
        const val OC_CLOSE_SESSION = 0x1003
        const val OC_GET_STORAGE_IDS = 0x1004
        const val OC_GET_OBJECT_HANDLES = 0x1007
        const val OC_GET_OBJECT_INFO = 0x1008
        const val OC_GET_OBJECT = 0x1009
        const val OC_GET_THUMB = 0x100A
        const val OC_INITIATE_CAPTURE = 0x100E

        // PTP Events
        const val EV_OBJECT_ADDED = 0x4002
        const val EV_DEVICE_PROP_CHANGED = 0x4006
        const val EV_CAPTURE_COMPLETE = 0x400A

        // Response Codes
        const val RC_OK = 0x2001
    }

    private var cmdSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null

    private var eventSocket: Socket? = null
    private var eventIn: InputStream? = null

    private val nativeCore = NativePtpCore.getInstance()
    private var connectionNumber = 0
    private var sessionId = 1
    private var isConnected = false

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var eventJob: Job? = null

    var onPhotoCaptured: ((handle: Int) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean, message: String) -> Unit)? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to camera at $host:$port...")
            onConnectionStateChanged?.invoke(false, "正在连接相机 $host:$port...")

            // 1. Establish Command Channel
            cmdSocket = Socket().apply {
                connect(InetSocketAddress(host, port), 5000)
                tcpNoDelay = true
            }
            cmdIn = cmdSocket!!.getInputStream()
            cmdOut = cmdSocket!!.getOutputStream()

            // 2. Send Init Command Request
            val guid = UUID.randomUUID()
            val friendlyName = "NikonSync Client\u0000"
            val nameBytes = friendlyName.toByteArray(Charsets.UTF_16LE)
            val reqPayload = ByteBuffer.allocate(16 + nameBytes.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            reqPayload.putLong(guid.mostSignificantBits)
            reqPayload.putLong(guid.leastSignificantBits)
            reqPayload.put(nameBytes)
            reqPayload.putInt(0x00010000) // Protocol version 1.0

            sendPacket(cmdOut!!, PKT_INIT_CMD_REQ, reqPayload.array())

            // 3. Read Init Command Ack
            val ackPacket = readPacket(cmdIn!!)
            if (ackPacket.type != PKT_INIT_CMD_ACK) {
                throw IllegalStateException("Unexpected Init Cmd Ack type: ${ackPacket.type}")
            }
            val ackBuffer = ByteBuffer.wrap(ackPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
            connectionNumber = ackBuffer.getInt()
            Log.d(TAG, "Command Channel Established, ConnectionNumber = $connectionNumber")

            // 4. Establish Event Channel
            eventSocket = Socket().apply {
                connect(InetSocketAddress(host, port), 5000)
                tcpNoDelay = true
            }
            eventIn = eventSocket!!.getInputStream()
            val eventOut = eventSocket!!.getOutputStream()

            val eventPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            eventPayload.putInt(connectionNumber)
            sendPacket(eventOut, PKT_INIT_EVENT_REQ, eventPayload.array())

            val eventAck = readPacket(eventIn!!)
            if (eventAck.type != PKT_INIT_EVENT_ACK) {
                throw IllegalStateException("Unexpected Init Event Ack type: ${eventAck.type}")
            }
            Log.d(TAG, "Event Channel Established")

            // 5. Open PTP Session (OpCode 0x1002)
            openSession()

            isConnected = true
            nativeCore.onSessionOpened()
            onConnectionStateChanged?.invoke(true, "相机连接成功 (会话已建立)")

            // 6. Start Event Listener Loop
            startEventListener()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            disconnect()
            onConnectionStateChanged?.invoke(false, "连接失败: ${e.message}")
            false
        }
    }

    private fun openSession() {
        val txId = nativeCore.nextTransactionId().toInt()
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(1) // Data Phase Info (No Data)
        payload.putShort(OC_OPEN_SESSION.toShort())
        payload.putInt(txId)
        payload.putInt(sessionId)

        sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())
        val resp = readPacket(cmdIn!!)
        Log.d(TAG, "OpenSession response received (type=${resp.type})")
    }

    suspend fun triggerShutter(): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        try {
            val txId = nativeCore.nextTransactionId().toInt()
            val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(1) // Data Phase: No Data
            payload.putShort(OC_INITIATE_CAPTURE.toShort())
            payload.putInt(txId)
            payload.putInt(0) // StorageID 0 (default)
            payload.putInt(0) // Capture Format (All)

            sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())
            val resp = readPacket(cmdIn!!)
            Log.d(TAG, "InitiateCapture sent, response type=${resp.type}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Trigger shutter error: ${e.message}", e)
            false
        }
    }

    suspend fun getRecentObjectHandles(): List<Int> = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext emptyList()
        try {
            val txId = nativeCore.nextTransactionId().toInt()
            val payload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(1)
            payload.putShort(OC_GET_OBJECT_HANDLES.toShort())
            payload.putInt(txId)
            payload.putInt(-1) // 0xFFFFFFFF (All Storage)
            payload.putInt(0)  // ObjectFormatCode 0 (All)
            payload.putInt(0)  // ObjectHandle 0 (Root)

            sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

            // Read Data Packet
            val dataPacket = readPacket(cmdIn!!)
            val handles = mutableListOf<Int>()
            if (dataPacket.type == PKT_DATA || dataPacket.type == PKT_DATA_END) {
                val buf = ByteBuffer.wrap(dataPacket.payload).order(ByteOrder.LITTLE_ENDIAN)
                val count = buf.getInt()
                for (i in 0 until count) {
                    if (buf.remaining() >= 4) {
                        handles.add(buf.getInt())
                    }
                }
            }
            // Read final Response Packet
            readPacket(cmdIn!!)
            handles
        } catch (e: Exception) {
            Log.e(TAG, "Get object handles error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun downloadObject(handle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        try {
            val txId = nativeCore.nextTransactionId().toInt()
            val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(1)
            payload.putShort(OC_GET_OBJECT.toShort())
            payload.putInt(txId)
            payload.putInt(handle)

            sendPacket(cmdOut!!, PKT_CMD_REQ, payload.array())

            // Accumulate Data chunks until DATA_END
            val chunks = mutableListOf<ByteArray>()
            var totalBytes = 0
            while (true) {
                val pkt = readPacket(cmdIn!!)
                if (pkt.type == PKT_DATA || pkt.type == PKT_DATA_END) {
                    // Skip 4-byte Transaction ID in PTP/IP data payload
                    val chunkPayload = pkt.payload.copyOfRange(4, pkt.payload.size)
                    chunks.add(chunkPayload)
                    totalBytes += chunkPayload.size
                    if (pkt.type == PKT_DATA_END) break
                } else if (pkt.type == PKT_CMD_RESP) {
                    break
                }
            }
            // Read response
            readPacket(cmdIn!!)

            val fullData = ByteArray(totalBytes)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, fullData, offset, chunk.size)
                offset += chunk.size
            }
            Log.d(TAG, "Downloaded photo handle=$handle ($totalBytes bytes)")
            fullData
        } catch (e: Exception) {
            Log.e(TAG, "Download object $handle error: ${e.message}", e)
            null
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

                        Log.d(TAG, "Received Camera Event: 0x${eventCode.toString(16)}, Param1=$param1")
                        if (eventCode == EV_OBJECT_ADDED) {
                            Log.i(TAG, "New Photo Captured! Object Handle = $param1")
                            onPhotoCaptured?.invoke(param1)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Log.w(TAG, "Event listener closed: ${e.message}")
                    onConnectionStateChanged?.invoke(false, "相机连接断开")
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
            throw IllegalStateException("Invalid PTP packet length: $length")
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
            if (count < 0) throw IllegalStateException("Unexpected EOF while reading socket")
            readTotal += count
        }
    }

    data class PtpPacket(val type: Int, val payload: ByteArray)
}
