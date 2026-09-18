package com.trailrun.mockgps.core

import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录 osmdroid **实际请求过的瓦片 URL**。
 *
 * 为什么需要：地图空白时无法判断是「应用请求了错误的地址」还是「请求对了但网络被拦」。
 * 之前只能靠猜，猜错一次用户就要多装一版。
 *
 * 实现说明：`getTileURLString(long)` 定义在 `XYTileSource` 这个**类**上，
 * 并不在 `ITileSource` 接口里，所以没法用「实现接口 + 委托」的方式拦截，
 * 必须继承 `XYTileSource` 并重写它。下载器取每个瓦片时都会经过这里，
 * 是唯一的必经之路。
 *
 * 记录上限 40 条环形覆盖，开销仅为一次字符串拼接，不影响瓦片加载性能。
 */
class RecordingTileSource(
    name: String,
    minZoom: Int,
    maxZoom: Int,
    tileSize: Int,
    ext: String,
    baseUrls: Array<String>,
    copyright: String,
) : XYTileSource(name, minZoom, maxZoom, tileSize, ext, baseUrls, copyright) {

    /**
     * 唯一的活动基址。
     *
     * **必须重写 `getBaseUrl()`**：父类 `OnlineTileSourceBase.getBaseUrl()` 在
     * baseUrls 多于一个时是**随机**挑一个
     * （`return mBaseUrls[random.nextInt(mBaseUrls.length)]`），
     * 而且没有任何失败切换机制。把多个镜像一起传进去，
     * 结果是每块瓦片都有大概率发往一个不可达域名 —— 地图一块块地空、还特别慢。
     *
     * 这里固定返回第一个（也就是调用方选定的那一个），把「选哪个端点」
     * 这件事完全交给 [resolveEndpoint] 在上层决定。
     */
    private val activeBase: String = baseUrls.firstOrNull().orEmpty()

    override fun getBaseUrl(): String = activeBase

    init {
        activeBaseUrl = activeBase
        activeTileSize = tileSize
    }

    override fun getTileURLString(pMapTileIndex: Long): String {
        val url = super.getTileURLString(pMapTileIndex)
        record(url)
        return url
    }

    companion object {
        private const val MAX = 40

        /** 当前生效的端点基址与瓦片边长，供诊断界面显示。 */
        @Volatile
        private var activeBaseUrl: String = ""

        @Volatile
        private var activeTileSize: Int = 0

        fun activeEndpoint(): String = activeBaseUrl

        fun activeTileSizePixels(): Int = activeTileSize

        private val buffer = ArrayDeque<String>()
        private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

        @Volatile
        private var totalRequests: Int = 0

        private fun record(url: String) {
            synchronized(buffer) {
                totalRequests++
                buffer.addLast("${stamp.format(Date())}  $url")
                while (buffer.size > MAX) buffer.removeFirst()
            }
        }

        /** 累计发起的瓦片请求次数。 */
        fun requestCount(): Int = totalRequests

        /** 最近的请求记录，最新在最后。 */
        fun recent(): List<String> = synchronized(buffer) { buffer.toList() }

        fun clear() {
            synchronized(buffer) {
                buffer.clear()
                totalRequests = 0
            }
        }

        /** 生成给用户看的诊断文本。 */
        fun dump(): String {
            val recent = recent()
            return buildString {
                append("当前端点：").append(activeBaseUrl.ifEmpty { "（未初始化）" }).append('\n')
                append("瓦片边长：").append(
                    if (activeTileSize <= 0) "（未初始化）" else "$activeTileSize px"
                ).append('\n')
                append("累计瓦片请求：").append(requestCount()).append(" 次\n")
                if (recent.isEmpty()) {
                    append("还没有任何瓦片请求被发起。\n")
                    append("若地图空白而这里是 0：说明请求根本没走到下载器，")
                    append("问题在应用内部（联网开关 / 瓦片源 / 缓存），不是网络。\n")
                } else {
                    append("最近实际请求的地址（最多 12 条）：\n")
                    recent.takeLast(12).forEach { append("  ").append(it).append('\n') }
                }
            }
        }
    }
}
