package com.trailrun.mockgps.core

/**
 * 单个 OSM 瓦片端点。
 *
 * [tileSize] 不是装饰，它直接决定地图清不清晰：
 *
 * osmdroid 在 `setTilesScaledToDpi(true)` 时的绘制尺寸是
 * ```
 * density = displayDensity * 256 / tileSize
 * size    = tileSize * density        // == 256 * displayDensity
 * ```
 * （见 `MapView.updateTileSizeForDensity`）
 * —— 屏幕上的绘制尺寸**与源分辨率无关**，恒等于 256 × 屏幕密度。
 * 于是 256px 的源被放大 displayDensity 倍（典型 2.6 倍）→ 糊；
 * 512px 的源只被放大 displayDensity/2 倍（约 1.3 倍）→ 清晰一倍。
 * 两者在屏幕上的视野范围、字号完全一致 —— 换 512px 源是纯赚，没有取舍。
 *
 * 实测结论（tools/probe-retina.mjs、probe-zoom.mjs）：
 *   - 官方站 `tile.openstreetmap.org` 与 Carto `basemaps.cartocdn.com` 在本网络下全部超时；
 *   - 法国 / 德国 / 日本镜像都只返回 256px，且不支持 @2x（404）；
 *   - 只有 `osm.rrze.fau.de/osmhd` 返回真正的 512×512，而且实测最快（热连接约 240ms）。
 *
 * 本文件刻意不依赖 osmdroid / Android —— 端点选择的逻辑可以在电脑上直接断言
 * （见 tools/verify/TileProviderTest.kt）。
 */
data class TileEndpoint(
    /** 稳定短标识，参与瓦片缓存键（换源必须换键，否则 256 与 512 会混用同一份缓存）。 */
    val id: String,
    val label: String,
    val baseUrl: String,
    /** 服务端真实返回的瓦片边长（像素），已实测。 */
    val tileSize: Int,
    val maxZoom: Int,
    val note: String,
) {
    /** 界面上的副标题：一眼看出清晰度。 */
    val summary: String get() = "$tileSize×$tileSize · $note"
}

/**
 * OSM 瓦片端点，顺序即优先级，也是诊断界面的显示顺序。
 *
 * 单一来源很重要：诊断结果必须与实际取瓦片的端点一致，
 * 否则会出现「诊断说这个通、实际却在用另一个」这种误导。
 */
val OSM_ENDPOINTS: List<TileEndpoint> = listOf(
    TileEndpoint(
        id = "osmhd",
        label = "高清 512px（推荐）",
        baseUrl = "https://osm.rrze.fau.de/osmhd/",
        tileSize = 512,
        maxZoom = 19,
        note = "德国高校镜像，实测最快最清晰",
    ),
    TileEndpoint(
        id = "fr-hot",
        label = "法国镜像",
        baseUrl = "https://a.tile.openstreetmap.fr/hot/",
        tileSize = 256,
        maxZoom = 19,
        note = "备选，标准清晰度",
    ),
    TileEndpoint(
        id = "de",
        label = "德国镜像",
        baseUrl = "https://tile.openstreetmap.de/",
        tileSize = 256,
        maxZoom = 19,
        note = "备选，标准清晰度",
    ),
    TileEndpoint(
        id = "jp",
        label = "日本镜像",
        baseUrl = "https://tile.openstreetmap.jp/",
        tileSize = 256,
        maxZoom = 19,
        note = "备选，标准清晰度",
    ),
    TileEndpoint(
        id = "official",
        label = "官方站",
        baseUrl = "https://tile.openstreetmap.org/",
        tileSize = 256,
        maxZoom = 19,
        note = "国内通常不可达",
    ),
)

/** 没有指定偏好时使用的端点。 */
val DEFAULT_ENDPOINT: TileEndpoint get() = OSM_ENDPOINTS.first()

/** URL 需要在界面与持久化之间做等值比较，故用 baseUrl 作为标识。 */
fun endpointByUrl(url: String?): TileEndpoint? =
    OSM_ENDPOINTS.firstOrNull { it.baseUrl == url }

/**
 * 把「用户偏好」解析成**唯一**一个端点。
 *
 * 这里刻意只返回一个，而不是「指定的排第一、其余作为后备」。
 *
 * osmdroid 的 `OnlineTileSourceBase.getBaseUrl()` 在 baseUrls 多于一个时是
 * **随机挑一个**（源码：`return mBaseUrls[random.nextInt(mBaseUrls.length)]`），
 * 库内不存在任何「依次重试 / 失败切换」机制。之前把 4 个端点一起传进去，
 * 等于每块瓦片都有 3/4 的概率发往一个不可达或极慢的域名：
 * 下载线程被超时请求占住、瓦片一块一块地空、缺席的瓦片只能拿上级低清瓦片放大顶着画
 * —— 这才是「又卡又不清晰」最大的单一原因。
 *
 * 指定了一个不在内置列表里的端点时，原样返回它（防御性，理论上不会发生）。
 */
fun resolveEndpoint(preferred: TileEndpoint?): TileEndpoint {
    if (preferred == null) return DEFAULT_ENDPOINT
    return OSM_ENDPOINTS.firstOrNull { it.baseUrl == preferred.baseUrl } ?: preferred
}

/** 一次实测的结果：端点、是否可用、耗时（毫秒）。 */
data class EndpointProbe(
    val endpoint: TileEndpoint,
    val ok: Boolean,
    val elapsedMs: Long,
)

/**
 * 从一组实测结果里挑出最值得用的端点；全部不可达时返回 null。
 *
 * 排序原则：**清晰度优先于速度**。
 * 512px 的高清源就算慢一点，也比 256px 被放大 2.6 倍清楚得多，
 * 所以只要高清端点可用就直接选它；高清不可用时才在其余可用端点里挑最快的。
 *
 * 返回 null 时调用方应当**保持**当前设置，而不是把用户切到另一个同样不可用的端点上。
 *
 * 纯函数，不依赖 Android/网络 —— 可以在电脑上直接断言。
 */
fun chooseBestEndpoint(probes: List<EndpointProbe>): TileEndpoint? {
    val usable = probes.filter { it.ok }
    if (usable.isEmpty()) return null
    return usable.firstOrNull { it.endpoint.tileSize >= HIGH_RES_TILE_SIZE }?.endpoint
        ?: usable.minByOrNull { it.elapsedMs }?.endpoint
}

/** 达到这个边长就算「高清」，值得优先于速度。 */
const val HIGH_RES_TILE_SIZE = 512
