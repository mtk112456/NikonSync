package com.nikonsync.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 网络与网关辅助类
 * 智能探测相机 IP、局域网并发秒级扫描相机以及 Wi-Fi 连接状态
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * 规范化 IP 地址：去除空格并清除前导零（例如相机界面显示的 192.168.031.213 规范化为 192.168.31.213）
     * 避免底层网络栈将带前导零的段按八进制解析导致连接失败
     */
    fun normalizeIp(raw: String): String {
        val trimmed = raw.trim()
        val parts = trimmed.split(".")
        if (parts.size == 4) {
            val parsed = parts.mapNotNull { it.trim().toIntOrNull() }
            if (parsed.size == 4 && parsed.all { it in 0..255 }) {
                return parsed.joinToString(".")
            }
        }
        return trimmed
    }

    fun getCameraIp(context: Context, defaultIp: String = "192.168.0.1"): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return defaultIp
            val activeNet = cm.activeNetwork ?: return defaultIp
            val caps = cm.getNetworkCapabilities(activeNet) ?: return defaultIp
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return defaultIp
            }

            // 如果当前 Wi-Fi 有外网 (例如家用小米路由器)，网关必然是路由器而不是相机，绝不能将网关误当做相机 IP
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val lp = cm.getLinkProperties(activeNet) ?: return defaultIp
            for (route in lp.routes) {
                val gateway = route.gateway
                if (gateway != null && !gateway.isAnyLocalAddress) {
                    val ip = gateway.hostAddress
                    if (ip != null && !ip.startsWith("0.") && !ip.contains(":") && ip.isNotEmpty()) {
                        // 仅当是相机的默认 192.168.0.1 或者当前是无外网的相机热点时，网关才有效
                        if (ip == "192.168.0.1" || !hasInternet) {
                            Log.d(TAG, "从相机 Wi-Fi 热点探测到网关 IP: $ip")
                            return ip
                        }
                    }
                }
            }
            defaultIp
        } catch (e: Exception) {
            Log.e(TAG, "探测相机网关异常: ${e.message}")
            defaultIp
        }
    }

    /**
     * 在局域网中并发秒级扫描开放 15740 端口的尼康相机 (适用于模式二：相机连家里 Wi-Fi)
     */
    suspend fun scanLocalCamera(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@withContext null
            val activeNet = cm.activeNetwork ?: return@withContext null
            val lp = cm.getLinkProperties(activeNet) ?: return@withContext null
            val myIp = lp.linkAddresses.firstOrNull {
                val addr = it.address.hostAddress
                addr != null && (addr.startsWith("192.168.") || addr.startsWith("10.") || addr.startsWith("172."))
            }?.address?.hostAddress ?: return@withContext null

            val prefix = myIp.substringBeforeLast(".")
            val myLastOctet = myIp.substringAfterLast(".").toIntOrNull() ?: 0

            Log.d(TAG, "开始并发扫描局域网 $prefix.* 上的尼康相机 (端口 15740)...")

            // 并发探测 1..254，排除路由器网关 .1 和本机 IP，单 IP 250ms 超时
            val targets = (2..254).filter { it != myLastOctet }
            val jobs = targets.map { i ->
                async {
                    val targetIp = "$prefix.$i"
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(targetIp, 15740), 250)
                        socket.close()
                        Log.i(TAG, "成功发现局域网相机 IP: $targetIp")
                        targetIp
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            jobs.awaitAll().firstOrNull { it != null }
        } catch (e: Exception) {
            Log.e(TAG, "局域网扫描相机异常: ${e.message}")
            null
        }
    }

    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }
}
