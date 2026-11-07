package com.nikonsync.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 网络与网关辅助类
 * 智能探测当前 Wi-Fi 网关（通常为微单相机的 IP，如 192.168.0.1）及 Wi-Fi 连接状态
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getCameraIp(context: Context, defaultIp: String = "192.168.0.1"): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return defaultIp
            val activeNet = cm.activeNetwork ?: return defaultIp
            val caps = cm.getNetworkCapabilities(activeNet) ?: return defaultIp
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return defaultIp
            }
            val lp = cm.getLinkProperties(activeNet) ?: return defaultIp
            for (route in lp.routes) {
                val gateway = route.gateway
                if (gateway != null && !gateway.isAnyLocalAddress) {
                    val ip = gateway.hostAddress
                    if (ip != null && !ip.startsWith("0.") && !ip.contains(":") && ip.isNotEmpty()) {
                        Log.d(TAG, "成功从 Wi-Fi 路由表探测到相机网关 IP: $ip")
                        return ip
                    }
                }
            }
            defaultIp
        } catch (e: Exception) {
            Log.e(TAG, "探测相机网关异常: ${e.message}")
            defaultIp
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
