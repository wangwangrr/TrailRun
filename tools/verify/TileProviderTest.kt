package com.trailrun.mockgps.core

/**
 * 端点选择的纯逻辑验证（纯 JVM，不需要 Android/osmdroid）。
 *
 * 重点守住三件事：
 *   1. 默认端点必须是 512px 高清源 —— 「地图清晰」是产品行为，不能靠人手改代码维持；
 *   2. `resolveEndpoint` 必须返回**唯一**一个端点。这条是核心回归测试：
 *      曾经把 4 个端点一起塞给 osmdroid，而 `OnlineTileSourceBase.getBaseUrl()`
 *      是随机挑一个、且没有失败重试，结果大部分瓦片发往不可达域名，
 *      地图又空又糊又慢。只要有人在 `toTileSource` 里重新传多个 baseUrl，
 *      这个逻辑本身测不出来，但至少这里的「唯一性」断言能提醒他。
 *   3. 「测速并选最快」的规则：清晰度优先于速度。
 */
object TileProviderTest {

    private var failures = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name  $detail")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== 端点选择逻辑验证 ===")

        println("\n[1] 默认端点")
        val def = DEFAULT_ENDPOINT
        println("      默认: ${def.label}  ${def.baseUrl}  ${def.tileSize}px  maxZoom=${def.maxZoom}")
        check("默认端点就是列表第一个", def == OSM_ENDPOINTS.first())
        check("默认端点是 512px 高清源", def.tileSize == 512, "实际 ${def.tileSize}px")
        check("resolveEndpoint(null) 等于默认端点", resolveEndpoint(null) == def)

        println("\n[2] 指定每个内置端点：必须原样返回它自己")
        for (target in OSM_ENDPOINTS) {
            val got = resolveEndpoint(target)
            check(
                "选「${target.label}」→ 返回同一个端点",
                got.baseUrl == target.baseUrl && got.tileSize == target.tileSize,
                "实际 ${got.label}",
            )
        }

        println("\n[3] 指定列表外的端点（防御性：老版本存下来的 URL）")
        val retired = TileEndpoint(
            id = "retired",
            label = "已下线的旧镜像",
            baseUrl = "https://a.tile.openstreetmap.fr/osmfr/",
            tileSize = 256,
            maxZoom = 19,
            note = "",
        )
        val resolvedRetired = resolveEndpoint(retired)
        check("列表外的端点原样返回（不抛异常）", resolvedRetired.baseUrl == retired.baseUrl)
        check(
            "但按 URL 反查内置列表时应当查不到 —— 此时应回落到默认",
            endpointByUrl(retired.baseUrl) == null,
        )

        println("\n[4] endpointByUrl 还原")
        check("空串还原为 null（全新安装）", endpointByUrl("") == null)
        check("null 还原为 null", endpointByUrl(null) == null)
        check("未知 URL 还原为 null", endpointByUrl("https://nope.invalid/") == null)
        for (e in OSM_ENDPOINTS) {
            check("可还原「${e.label}」", endpointByUrl(e.baseUrl)?.baseUrl == e.baseUrl)
        }

        println("\n[5] 内置端点的基本约束")
        check("至少 2 个端点", OSM_ENDPOINTS.size >= 2, "${OSM_ENDPOINTS.size}")
        check("baseUrl 全部唯一", OSM_ENDPOINTS.map { it.baseUrl }.toSet().size == OSM_ENDPOINTS.size)
        check(
            "baseUrl 全部以 https:// 开头且以 / 结尾",
            OSM_ENDPOINTS.all { it.baseUrl.startsWith("https://") && it.baseUrl.endsWith("/") },
        )
        check(
            "id 全部非空且唯一",
            OSM_ENDPOINTS.all { it.id.isNotBlank() } &&
                OSM_ENDPOINTS.map { it.id }.toSet().size == OSM_ENDPOINTS.size,
        )
        check(
            "label 全部非空且唯一",
            OSM_ENDPOINTS.all { it.label.isNotBlank() } &&
                OSM_ENDPOINTS.map { it.label }.toSet().size == OSM_ENDPOINTS.size,
        )
        check(
            "tileSize 只取 256 / 512（osmdroid 只支持这两种常见边长）",
            OSM_ENDPOINTS.all { it.tileSize == 256 || it.tileSize == HIGH_RES_TILE_SIZE },
            OSM_ENDPOINTS.joinToString { "${it.id}=${it.tileSize}" },
        )
        check(
            "maxZoom 在 1..19 之间",
            OSM_ENDPOINTS.all { it.maxZoom in 1..19 },
        )
        check(
            "至少有一个高清端点可用作默认",
            OSM_ENDPOINTS.any { it.tileSize >= HIGH_RES_TILE_SIZE },
        )

        println("\n[6] chooseBestEndpoint：清晰度优先于速度")
        val hd = OSM_ENDPOINTS.first { it.tileSize >= HIGH_RES_TILE_SIZE }
        val sd = OSM_ENDPOINTS.first { it.tileSize == 256 }

        val hdSlow = listOf(
            EndpointProbe(hd, ok = true, elapsedMs = 3000),
            EndpointProbe(sd, ok = true, elapsedMs = 100),
        )
        check(
            "高清慢(3000ms) vs 标清快(100ms) → 选高清",
            chooseBestEndpoint(hdSlow)?.baseUrl == hd.baseUrl,
            "实际 ${chooseBestEndpoint(hdSlow)?.label}",
        )

        val hdDown = listOf(
            EndpointProbe(hd, ok = false, elapsedMs = 9000),
            EndpointProbe(sd, ok = true, elapsedMs = 800),
            EndpointProbe(OSM_ENDPOINTS[2], ok = true, elapsedMs = 300),
        )
        check(
            "高清不可用 → 在可用的里选最快的",
            chooseBestEndpoint(hdDown)?.baseUrl == OSM_ENDPOINTS[2].baseUrl,
            "实际 ${chooseBestEndpoint(hdDown)?.label}",
        )

        check(
            "全部不可达 → null（调用方应保持原设置）",
            chooseBestEndpoint(
                OSM_ENDPOINTS.map { EndpointProbe(it, ok = false, elapsedMs = 9000) }
            ) == null,
        )
        check("空结果 → null", chooseBestEndpoint(emptyList()) == null)

        val twoHd = listOf(
            EndpointProbe(hd, ok = true, elapsedMs = 5000),
            EndpointProbe(hd.copy(id = "hd2", baseUrl = "https://hd2.invalid/"), ok = true, elapsedMs = 10),
        )
        check(
            "多个高清可用 → 保持列表顺序，取第一个（结果可预测）",
            chooseBestEndpoint(twoHd)?.baseUrl == hd.baseUrl,
        )

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
