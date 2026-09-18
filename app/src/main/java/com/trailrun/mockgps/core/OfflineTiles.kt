package com.trailrun.mockgps.core

import android.content.Context
import org.osmdroid.tileprovider.modules.SqlTileWriter
import java.io.File

/**
 * 离线瓦片包管理。
 *
 * osmdroid 内置支持四种离线档案格式（见 `ArchiveFileFactory`）：
 *   - `.mbtiles` —— MBTiles（SQLite），最常见
 *   - `.sqlite`  —— osmdroid 自己的瓦片数据库格式
 *   - `.zip`     —— 按 `z/x/y.png` 目录结构打包
 *   - `.gemf`    —— GEMF 格式
 *
 * 把文件放进 `Android/data/<包名>/files/offline/` 即可被自动识别，
 * 切到「离线瓦片（不联网）」后完全不发起网络请求。
 */
object OfflineTiles {

    private val SUPPORTED = arrayOf("mbtiles", "sqlite", "zip", "gemf")

    /** 离线包的存放目录（应用外部私有目录，方便用数据线或文件管理器拷入）。 */
    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "offline").apply {
            if (!exists()) mkdirs()
        }

    /** 列出已导入的离线包。 */
    fun archives(context: Context): List<File> {
        val dir = directory(context)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED }
            .sortedBy { it.name }
    }

    /**
     * 本地瓦片缓存的体积（字节）。
     * 这是「联网时浏览过的区域，断网后还能看」所依赖的那份数据。
     *
     * 注意 SqlTileWriter 不是 Closeable，不能 use{}，用完要显式 onDetach()。
     */
    fun cacheBytes(): Long {
        var writer: SqlTileWriter? = null
        return try {
            writer = SqlTileWriter()
            writer.size
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { writer?.onDetach() }
        }
    }

    /** 缓存可读描述。 */
    fun describeCache(): String {
        val bytes = cacheBytes()
        if (bytes <= 0) return "暂无缓存"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 1) "${bytes / 1024} KB" else "%.1f MB".format(mb)
    }

    /** 人类可读的大小描述。 */
    fun describe(files: List<File>): String {
        if (files.isEmpty()) return "未导入离线包"
        val mb = files.sumOf { it.length() } / 1024.0 / 1024.0
        return "${files.size} 个包 · ${"%.1f".format(mb)} MB"
    }
}
