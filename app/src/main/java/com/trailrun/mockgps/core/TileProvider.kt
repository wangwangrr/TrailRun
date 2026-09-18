package com.trailrun.mockgps.core

import org.osmdroid.tileprovider.tilesource.ITileSource

/**
 * 底图瓦片源：只保留 OpenStreetMap。
 *
 * 端点定义与选择逻辑在 [OsmEndpoints] 里（那个文件不依赖 osmdroid / Android，
 * 因此可以在电脑上直接断言）。本文件只负责把它接到 osmdroid 上。
 *
 * 版权署名见 [TileProvider.OSM.attribution]，界面底部常驻显示，
 * User-Agent 在 [com.trailrun.mockgps.TrailRunApp] 里设置为包名（符合 OSM 瓦片使用政策）。
 */
enum class TileProvider(
    val label: String,
    val detail: String,
    val attribution: String,
) {
    OSM(
        label = "OpenStreetMap",
        detail = "可选镜像，默认自动选择最快的一个",
        attribution = "© OpenStreetMap contributors",
    ),
    ;

    companion object {
        val DEFAULT = OSM
    }
}

/**
 * 把枚举 + 端点转换成 osmdroid 的瓦片源。
 *
 * @param preferred 用户指定的端点；null 表示「自动」，即 [OSM_ENDPOINTS] 的第一个。
 *
 * 三个关键点，每一个都对应一个真实踩过的坑：
 *
 * 1. **只传一个 baseUrl。** osmdroid 的 `getBaseUrl()` 在多元素时随机挑一个，
 *    没有失败重试。传多个 = 大部分瓦片发往不可达域名。详见 [resolveEndpoint]。
 *
 * 2. **tileSize 必须用端点的真实尺寸。** 它决定 `TileSystem` 的瓦片边长，
 *    进而决定 512px 的源是被放大 1.3 倍（清晰）还是 2.6 倍（糊）。
 *
 * 3. **name 里必须带端点与尺寸。** osmdroid 的缓存键是
 *    `tileSource.getName() + "/" + z + "/" + x + "/" + y`（见 SqlTileWriter），
 *    换源而名字不变的话，会在 256 与 512 的瓦片之间串用缓存。
 *
 * 另外：换源必须构造一个**新的**对象。`MapTileProviderArray` 会缓存 tileSource 实例，
 * 只改原对象的字段不会被感知。
 */
fun TileProvider.toTileSource(preferred: TileEndpoint? = null): ITileSource {
    val endpoint = resolveEndpoint(preferred)
    return RecordingTileSource(
        name = "trailrun-${endpoint.id}-${endpoint.tileSize}",
        minZoom = 0,
        maxZoom = endpoint.maxZoom,
        tileSize = endpoint.tileSize,
        ext = ".png",
        baseUrls = arrayOf(endpoint.baseUrl),
        copyright = "© OpenStreetMap contributors",
    )
}
