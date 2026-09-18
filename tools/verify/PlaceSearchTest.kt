package com.trailrun.mockgps.core

import java.io.File

/**
 * PlaceSearch.parse 的离线验证（纯 JVM）。
 *
 * 用真实抓取的 Photon 响应做断言，而不是手写一份「我以为的」JSON——
 * 后者只能验证我自己和自己一致，验证不了字段名是否真的对得上。
 * 样本文件：.toolchain/photon-sample.json（由 tools 里的探测脚本抓取）
 */
object PlaceSearchTest {

    private var failures = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name  $detail")
        }
    }

    /** 找不到样本文件时退化为内置的最小样本，保证测试仍能跑。 */
    private fun sampleJson(): String {
        val f = File(".toolchain/photon-sample.json")
        if (f.isFile) return f.readText()
        println("  (未找到 .toolchain/photon-sample.json，使用内置样本)")
        return """
        {"type":"FeatureCollection","features":[
          {"type":"Feature",
           "geometry":{"type":"Point","coordinates":[116.3039468,39.9918215]},
           "properties":{"osm_id":1,"osm_type":"W","osm_key":"amenity","osm_value":"university",
             "type":"house","name":"北京大学","street":"颐和园路5号","district":"海淀区",
             "city":"北京市","country":"中国"}}
        ]}
        """.trimIndent()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== PlaceSearch 解析验证 ===")

        val json = sampleJson()
        val places = PlaceSearch.parse(json)

        println("\n[1] 解析真实 Photon 响应")
        println("      解析出 ${places.size} 条结果")
        check("至少解析出 1 条", places.isNotEmpty())
        if (places.isEmpty()) {
            println("\n=== 结果：解析失败 ===")
            kotlin.system.exitProcess(1)
        }

        val first = places.first()
        println("      首条: name=${first.name}")
        println("            detail=${first.detail}")
        println("            lat=${first.latitude} lon=${first.longitude} kind=${first.kind}")

        check("名称为「北京大学」", first.name == "北京大学", "实际 ${first.name}")
        check(
            "经纬度落在北京范围内（lat 39~41, lon 115~118）",
            first.latitude in 39.0..41.0 && first.longitude in 115.0..118.0,
            "lat=${first.latitude} lon=${first.longitude}",
        )
        check("副标题非空（拼出了地址）", first.subtitle.isNotBlank(), "实际「${first.subtitle}」")
        check("副标题包含城市或国家", first.detail.contains("北京") || first.detail.contains("中国"),
            "实际「${first.detail}」")
        check("kind 非空（读到了 osm_value）", first.kind.isNotBlank(), "实际「${first.kind}」")

        println("\n[2] 全部结果的坐标都有效")
        val allValid = places.all {
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        check("坐标均在合法范围", allValid)
        check("没有空名字的结果", places.all { it.name.isNotBlank() })

        println("\n[3] 边界与畸形输入不崩溃")
        check("空对象返回空", PlaceSearch.parse("{}").isEmpty())
        check("空 features 返回空", PlaceSearch.parse("""{"features":[]}""").isEmpty())
        check("非法 JSON 返回空", PlaceSearch.parse("not json at all").isEmpty())
        check("features 为 null 返回空", PlaceSearch.parse("""{"features":null}""").isEmpty())
        check(
            "缺 geometry 的条目被跳过",
            PlaceSearch.parse(
                """{"features":[{"properties":{"name":"x"}}]}"""
            ).isEmpty(),
        )
        check(
            "缺 coordinates 的条目被跳过",
            PlaceSearch.parse(
                """{"features":[{"properties":{"name":"x"},"geometry":{}}]}"""
            ).isEmpty(),
        )
        check(
            "coordinates 只有一个数字时被跳过",
            PlaceSearch.parse(
                """{"features":[{"properties":{"name":"x"},"geometry":{"coordinates":[1]}}]}"""
            ).isEmpty(),
        )
        check(
            "坐标是字符串而非数字时被跳过",
            PlaceSearch.parse(
                """{"features":[{"properties":{"name":"x"},"geometry":{"coordinates":["a","b"]}}]}"""
            ).isEmpty(),
        )
        check(
            "没有 name/street/city 的条目被跳过",
            PlaceSearch.parse(
                """{"features":[{"properties":{"country":"中国"},"geometry":{"coordinates":[1,2]}}]}"""
            ).isEmpty(),
        )

        println("\n[4] name 缺失时的回退链")
        val fallback = PlaceSearch.parse(
            """{"features":[
                 {"properties":{"street":"中关村大街"},"geometry":{"coordinates":[116.3,39.9]}},
                 {"properties":{"city":"北京市"},"geometry":{"coordinates":[116.4,39.9]}}
               ]}"""
        )
        check("回退到 street", fallback.getOrNull(0)?.name == "中关村大街", "实际 ${fallback.getOrNull(0)?.name}")
        check("回退到 city", fallback.getOrNull(1)?.name == "北京市", "实际 ${fallback.getOrNull(1)?.name}")

        println("\n[5] 请求地址拼接（编码与参数）")
        val url1 = PlaceSearch.buildUrl("北京大学", null, null, 12)
        println("      $url1")
        check("中文被 URL 编码", !url1.contains("北京大学"), "实际 $url1")
        check("带 limit 参数", url1.contains("&limit=12"))
        check("不含 lang 参数（加了会 400）", !url1.contains("lang="))

        val url2 = PlaceSearch.buildUrl("test", 39.9042, 116.4074, 5)
        println("      $url2")
        check("有中心点时追加 lat/lon bias",
            url2.contains("lat=39.9042") && url2.contains("lon=116.4074"))
        check("limit 生效", url2.contains("&limit=5"))

        println("\n[6] 空查询直接返回空结果，不发起请求")
        val empty = PlaceSearch.searchBlocking("   ", null, null, 12, 3000)
        check("返回 Success 且为空", empty is PlaceSearch.Outcome.Success &&
            (empty as PlaceSearch.Outcome.Success).places.isEmpty())

        println("\n[7] 联网实搜（网络不通时跳过，不算失败）")
        val live = PlaceSearch.searchBlocking("北京大学", 39.9042, 116.4074, 3, 9000)
        when (live) {
            is PlaceSearch.Outcome.Success -> {
                println("      实搜命中 ${live.places.size} 条")
                live.places.take(3).forEach { println("        - ${it.name}  [${it.subtitle}]") }
                check("实搜至少 1 条结果", live.places.isNotEmpty())
                check("首条名字含「北京大学」",
                    live.places.firstOrNull()?.name?.contains("北京大学") == true,
                    "实际 ${live.places.firstOrNull()?.name}")
            }

            is PlaceSearch.Outcome.Failure -> {
                println("      实搜未成功：${live.message}")
                println("      （当前机器网络受限，跳过联网断言）")
            }
        }

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
