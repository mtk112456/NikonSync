package com.nikonsync.app.ptp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.SecureRandom
import java.util.regex.Pattern

/**
 * 尼康相机连接与配对 Profile 管理器（完全对齐原版 ZRelay 存储机制）
 * 1. 首次配对时生成安全随机 16 字节 GUID 与机型 FriendlyName
 * 2. 配对成功后将凭据持久化保存到 SharedPreferences
 * 3. 日常连接时严格复用已配对的 GUID，无需再次触发相机连接向导
 */
object NikonCameraProfile {
    private const val PREF_NAME = "nikon_camera_profiles"
    private const val KEY_PAIRED_GUID = "paired_client_guid"
    private const val KEY_FRIENDLY_NAME = "paired_friendly_name"
    private const val KEY_CAMERA_NAME = "paired_camera_name"
    private const val KEY_CAMERA_IP = "paired_camera_ip"
    private const val KEY_PAIRED_TIME = "paired_timestamp"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 是否已有有效配对记录
     */
    fun hasPairedProfile(context: Context): Boolean {
        val guidHex = prefs(context).getString(KEY_PAIRED_GUID, null)
        return !guidHex.isNullOrEmpty() && guidHex.length == 32
    }

    /**
     * 获取已保存的 16 字节客户端 GUID；若未配对则返回 null
     */
    fun getPairedGuid(context: Context): ByteArray? {
        val hex = prefs(context).getString(KEY_PAIRED_GUID, null) ?: return null
        return hexToBytes(hex)
    }

    /**
     * 获取已保存的 FriendlyName
     */
    fun getPairedFriendlyName(context: Context): String {
        return prefs(context).getString(KEY_FRIENDLY_NAME, null) ?: generateDefaultFriendlyName()
    }

    /**
     * 获取已保存的相机名称
     */
    fun getPairedCameraName(context: Context): String {
        return prefs(context).getString(KEY_CAMERA_NAME, "Nikon Camera") ?: "Nikon Camera"
    }

    /**
     * 为全新配对生成 16 字节专属安全随机 GUID
     */
    fun generateNewPairingGuid(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * 对齐原版：基于 Android 机型清洗生成 FriendlyName (如 NikonSync-Pixel6)
     */
    fun generateDefaultFriendlyName(): String {
        val model = Build.MODEL ?: "Android"
        val clean = Pattern.compile("[^A-Za-z0-9_-]").matcher(model).replaceAll("-")
        val shortModel = if (clean.length > 20) clean.substring(0, 20) else clean
        val name = if (shortModel.isEmpty()) "NikonSync-Android" else "NikonSync-$shortModel"
        return if (name.length > 39) name.substring(0, 39) else name
    }

    /**
     * 保存成功配对的相机 Profile
     */
    fun savePairedProfile(
        context: Context,
        guid: ByteArray,
        friendlyName: String,
        cameraName: String,
        cameraIp: String
    ) {
        prefs(context).edit()
            .putString(KEY_PAIRED_GUID, bytesToHex(guid))
            .putString(KEY_FRIENDLY_NAME, friendlyName)
            .putString(KEY_CAMERA_NAME, cameraName)
            .putString(KEY_CAMERA_IP, cameraIp)
            .putLong(KEY_PAIRED_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * 清除配对信息（用户要求重新配对时调用）
     */
    fun clearProfile(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length != 32) return null
        val bytes = ByteArray(16)
        try {
            for (i in 0 until 16) {
                val index = i * 2
                bytes[i] = hex.substring(index, index + 2).toInt(16).toByte()
            }
            return bytes
        } catch (_: Exception) {
            return null
        }
    }
}
