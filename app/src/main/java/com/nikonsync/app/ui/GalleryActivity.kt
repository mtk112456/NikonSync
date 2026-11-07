package com.nikonsync.app.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nikonsync.app.R
import com.nikonsync.app.ptp.NikonPtpClient
import kotlinx.coroutines.*
import java.util.Collections

class GalleryActivity : AppCompatActivity() {

    companion object {
        const val TAG = "GalleryActivity"
        const val EXTRA_CAMERA_IP = "extra_camera_ip"
    }

    private lateinit var btnBack: ImageButton
    private lateinit var tvTitle: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var loadingLayout: LinearLayout
    private lateinit var tvLoadingStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvGallery: RecyclerView
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnDownloadSelected: MaterialButton

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var ptpClient: NikonPtpClient? = null
    private var ownsClient = false

    private val items = Collections.synchronizedList(mutableListOf<GalleryItem>())
    private lateinit var adapter: GalleryAdapter

    private val thumbCache = object : LruCache<Int, Bitmap>(50 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    data class GalleryItem(
        val handle: Int,
        var filename: String = "",
        var isRaw: Boolean = false,
        var thumb: Bitmap? = null,
        var isSelected: Boolean = false,
        var isLoaded: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        initViews()
        val ip = intent.getStringExtra(EXTRA_CAMERA_IP) ?: "192.168.0.1"
        connectAndLoadGallery(ip)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvTitle = findViewById(R.id.tvTitle)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        loadingLayout = findViewById(R.id.loadingLayout)
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus)
        progressBar = findViewById(R.id.progressBar)
        rvGallery = findViewById(R.id.rvGallery)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnDownloadSelected = findViewById(R.id.btnDownloadSelected)

        btnBack.setOnClickListener { finish() }

        rvGallery.layoutManager = GridLayoutManager(this, 3)
        adapter = GalleryAdapter()
        rvGallery.adapter = adapter

        btnSelectAll.setOnClickListener {
            val allSelected = items.all { it.isSelected }
            items.forEach { it.isSelected = !allSelected }
            adapter.notifyDataSetChanged()
            updateSelectionBar()
        }

        btnDownloadSelected.setOnClickListener {
            val selected = items.filter { it.isSelected }
            if (selected.isNotEmpty()) {
                startBatchDownload(selected)
            }
        }
    }

    private fun connectAndLoadGallery(ip: String) {
        activityScope.launch {
            loadingLayout.visibility = View.VISIBLE
            tvLoadingStatus.text = "正在连接相机 ($ip)..."

            val client = NikonPtpClient(host = ip)
            ptpClient = client
            ownsClient = true

            val ok = client.connect()
            if (!ok) {
                tvLoadingStatus.text = "连接相机失败，请确认手机已连接到相机的 Wi-Fi 热点"
                progressBar.visibility = View.GONE
                return@launch
            }

            tvLoadingStatus.text = "正在读取存储卡内的所有照片索引..."
            val handles = withContext(Dispatchers.IO) {
                client.getAllObjectHandles()
            }

            if (handles.isEmpty()) {
                tvLoadingStatus.text = "相机存储卡内未找到照片或视频"
                progressBar.visibility = View.GONE
                return@launch
            }

            // 初始化占位列表（倒序排列，最新拍的显示在最上面）
            val reversedHandles = handles.reversed()
            items.clear()
            reversedHandles.forEach { h ->
                items.add(GalleryItem(handle = h, filename = "加载中..."))
            }
            adapter.notifyDataSetChanged()
            loadingLayout.visibility = View.GONE

            tvTitle.text = "相机卡内画廊 (${items.size}张)"

            // 后台异步逐项加载元数据与缩略图
            loadMetadataAndThumbnails()
        }
    }

    private fun loadMetadataAndThumbnails() {
        val client = ptpClient ?: return
        activityScope.launch(Dispatchers.IO) {
            for (i in items.indices) {
                if (!isActive || !client.isConnected) break
                val item = items[i]
                if (item.isLoaded) continue

                // 1. 读取元数据
                val info = client.getObjectInfo(item.handle)
                if (info != null) {
                    item.filename = info.filename
                    item.isRaw = info.isRaw
                } else {
                    item.filename = "DSC_${item.handle}.JPG"
                }

                // 2. 读取缩略图
                var cachedBmp = thumbCache.get(item.handle)
                if (cachedBmp == null) {
                    val thumbBytes = client.getThumb(item.handle)
                    if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                        cachedBmp = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                        if (cachedBmp != null) {
                            thumbCache.put(item.handle, cachedBmp)
                        }
                    }
                }
                item.thumb = cachedBmp
                item.isLoaded = true

                withContext(Dispatchers.Main) {
                    adapter.notifyItemChanged(i)
                }
            }
        }
    }

    private fun updateSelectionBar() {
        val count = items.count { it.isSelected }
        tvSelectedCount.text = "已选择 $count 项"
        btnDownloadSelected.isEnabled = count > 0
        btnSelectAll.text = if (items.isNotEmpty() && items.all { it.isSelected }) "取消全选" else "全选"
    }

    private fun startBatchDownload(selectedItems: List<GalleryItem>) {
        val client = ptpClient ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("批量下载中")
            .setMessage("准备下载 1/${selectedItems.size}...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        activityScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (idx in selectedItems.indices) {
                val item = selectedItems[idx]
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("正在下载第 ${idx + 1}/${selectedItems.size} 张: ${item.filename}...")
                }

                val bytes = client.downloadObject(item.handle)
                if (bytes != null && bytes.isNotEmpty()) {
                    saveDownloadedPhoto(bytes, item.filename, item.isRaw)
                    successCount++
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@GalleryActivity)
                    .setTitle("下载完成")
                    .setMessage("成功下载并保存 $successCount 张照片至手机相册 (Pictures/NikonSync)！")
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    private fun saveDownloadedPhoto(bytes: ByteArray, filename: String, isRaw: Boolean) {
        try {
            val mime = if (isRaw) "image/x-nikon-nef" else "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NikonSync")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存相册失败: ${e.message}")
        }
    }

    inner class GalleryAdapter : RecyclerView.Adapter<GalleryViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_photo, parent, false)
            return GalleryViewHolder(view)
        }

        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val item = items[position]
            holder.tvFileName.text = item.filename
            holder.tvFormatBadge.text = if (item.isRaw) "RAW" else "JPG"
            holder.tvFormatBadge.visibility = if (item.isLoaded) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = item.isSelected

            if (item.thumb != null) {
                holder.ivThumb.setImageBitmap(item.thumb)
            } else {
                holder.ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.itemView.setOnClickListener {
                item.isSelected = !item.isSelected
                holder.cbSelect.isChecked = item.isSelected
                updateSelectionBar()
            }

            holder.cbSelect.setOnClickListener {
                item.isSelected = holder.cbSelect.isChecked
                updateSelectionBar()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvFormatBadge: TextView = view.findViewById(R.id.tvFormatBadge)
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ownsClient) {
            ptpClient?.disconnect()
        }
        activityScope.cancel()
    }
}
