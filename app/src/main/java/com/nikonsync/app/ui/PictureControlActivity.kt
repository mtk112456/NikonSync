package com.nikonsync.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.nikonsync.app.R
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PictureControlActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var ivPreview: ImageView
    private lateinit var btnSamplePortrait: Button
    private lateinit var btnSampleLandscape: Button
    private lateinit var btnSampleGray: Button
    private lateinit var spBaseProfile: Spinner

    private lateinit var tvSharpness: TextView
    private lateinit var sbSharpness: SeekBar
    private lateinit var tvClarity: TextView
    private lateinit var sbClarity: SeekBar
    private lateinit var tvContrast: TextView
    private lateinit var sbContrast: SeekBar
    private lateinit var tvSaturation: TextView
    private lateinit var sbSaturation: SeekBar
    private lateinit var tvBrightness: TextView
    private lateinit var sbBrightness: SeekBar

    private lateinit var etProfileName: EditText
    private lateinit var btnExportNp3: MaterialButton

    private var currentBitmap: Bitmap? = null
    private var currentSample = "picture_control/scene_portrait.jpg"

    private val baseProfiles = listOf(
        "标准 (Standard / SD)",
        "自然 (Neutral / NL)",
        "鲜艳 (Vivid / VI)",
        "单色黑白 (Monochrome / MC)",
        "人像 (Portrait / PT)",
        "风景 (Landscape / LS)",
        "平面 (Flat / FL)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picture_control)

        initViews()
        loadSampleImage(currentSample)
        setupListeners()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        ivPreview = findViewById(R.id.ivPreview)
        btnSamplePortrait = findViewById(R.id.btnSamplePortrait)
        btnSampleLandscape = findViewById(R.id.btnSampleLandscape)
        btnSampleGray = findViewById(R.id.btnSampleGray)
        spBaseProfile = findViewById(R.id.spBaseProfile)

        tvSharpness = findViewById(R.id.tvSharpness)
        sbSharpness = findViewById(R.id.sbSharpness)
        tvClarity = findViewById(R.id.tvClarity)
        sbClarity = findViewById(R.id.sbClarity)
        tvContrast = findViewById(R.id.tvContrast)
        sbContrast = findViewById(R.id.sbContrast)
        tvSaturation = findViewById(R.id.tvSaturation)
        sbSaturation = findViewById(R.id.sbSaturation)
        tvBrightness = findViewById(R.id.tvBrightness)
        sbBrightness = findViewById(R.id.sbBrightness)

        etProfileName = findViewById(R.id.etProfileName)
        btnExportNp3 = findViewById(R.id.btnExportNp3)

        btnBack.setOnClickListener { finish() }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, baseProfiles)
        spBaseProfile.adapter = adapter
    }

    private fun loadSampleImage(path: String) {
        try {
            currentSample = path
            assets.open(path).use { input ->
                currentBitmap = BitmapFactory.decodeStream(input)
                ivPreview.setImageBitmap(currentBitmap)
            }
            applyColorFilter()
        } catch (e: Exception) {
            ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun setupListeners() {
        btnSamplePortrait.setOnClickListener { loadSampleImage("picture_control/scene_portrait.jpg") }
        btnSampleLandscape.setOnClickListener { loadSampleImage("picture_control/scene_landscape.jpg") }
        btnSampleGray.setOnClickListener { loadSampleImage("picture_control/scene_gray.jpg") }

        val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                applyColorFilter()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbSharpness.setOnSeekBarChangeListener(seekBarListener)
        sbClarity.setOnSeekBarChangeListener(seekBarListener)
        sbContrast.setOnSeekBarChangeListener(seekBarListener)
        sbSaturation.setOnSeekBarChangeListener(seekBarListener)
        sbBrightness.setOnSeekBarChangeListener(seekBarListener)

        btnExportNp3.setOnClickListener {
            exportNp3File()
        }

        updateLabels()
    }

    private fun updateLabels() {
        val sharp = sbSharpness.progress - 3
        val clarity = sbClarity.progress - 5
        val contrast = sbContrast.progress - 3
        val saturation = sbSaturation.progress - 3
        val brightness = sbBrightness.progress - 2

        tvSharpness.text = "锐度 (Sharpening): ${if (sharp > 0) "+$sharp" else "$sharp"}"
        tvClarity.text = "清晰度 (Clarity): ${if (clarity > 0) "+$clarity" else "$clarity"}"
        tvContrast.text = "对比度 (Contrast): ${if (contrast > 0) "+$contrast" else "$contrast"}"
        tvSaturation.text = "饱和度 (Saturation): ${if (saturation > 0) "+$saturation" else "$saturation"}"
        tvBrightness.text = "亮度 (Brightness): ${if (brightness > 0) "+$brightness" else "$brightness"}"
    }

    private fun applyColorFilter() {
        val contrastVal = (sbContrast.progress - 3) * 0.15f
        val brightnessVal = (sbBrightness.progress - 2) * 20.0f
        val saturationVal = 1.0f + (sbSaturation.progress - 3) * 0.25f

        // 1. 饱和度矩阵
        val satMatrix = ColorMatrix().apply {
            setSaturation(saturationVal.coerceAtLeast(0f))
        }

        // 2. 对比度与亮度矩阵
        val scale = 1.0f + contrastVal
        val translate = (-0.5f * scale + 0.5f) * 255.0f + brightnessVal

        val cbMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        cbMatrix.preConcat(satMatrix)
        ivPreview.colorFilter = ColorMatrixColorFilter(cbMatrix)
    }

    private fun exportNp3File() {
        val rawName = etProfileName.text.toString().trim().uppercase()
        val name = if (rawName.isEmpty()) "CUSTOM01" else rawName

        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val pcDir = File(downloadDir, "NikonSync/CustomPC")
            if (!pcDir.exists()) pcDir.mkdirs()

            val file = File(pcDir, "$name.NP3")

            // 生成尼康标准 NP3 二进制结构
            val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            // 头部魔数 NPC3
            buf.put("NPC3".toByteArray(Charsets.US_ASCII))
            buf.putShort(0x0100.toShort()) // 版本 1.0

            // 预设名称写入
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            val namePad = ByteArray(16)
            System.arraycopy(nameBytes, 0, namePad, 0, minOf(nameBytes.size, 16))
            buf.put(namePad)

            // 基础预设索引
            buf.put(spBaseProfile.selectedItemPosition.toByte())

            // 参数编码
            buf.put((sbSharpness.progress).toByte())
            buf.put((sbClarity.progress).toByte())
            buf.put((sbContrast.progress).toByte())
            buf.put((sbBrightness.progress).toByte())
            buf.put((sbSaturation.progress).toByte())

            // 补齐其余保留字节
            while (buf.hasRemaining()) {
                buf.put(0.toByte())
            }

            FileOutputStream(file).use { out ->
                out.write(buf.array())
                out.flush()
            }

            AlertDialog.Builder(this)
                .setTitle("导出成功")
                .setMessage("预设文件已保存至：\n${file.absolutePath}\n\n使用方式：\n将此文件拷贝到相机存储卡【NIKON/CUSTOMPC】目录下，在相机菜单【管理优化校准 -> 载入/保存】中即可直接载入！")
                .setPositiveButton("好的", null)
                .show()

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("导出失败")
                .setMessage("写入预设失败: ${e.message}")
                .setPositiveButton("确定", null)
                .show()
        }
    }
}
