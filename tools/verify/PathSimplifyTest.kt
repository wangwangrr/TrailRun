package com.trailrun.mockgps.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * PathSimplify 的独立验证程序（纯 JVM，不依赖 Android）。
 * 用 kotlinc 编译后直接跑，检查抽稀结果是否既压缩了点数量、又保住了形状。
 */
object PathSimplifyTest {

    private var failures = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name  $detail")
        }
    }

    /** 生成一条椭圆的轨迹，模拟绕操场跑一圈。 */
    private fun ellipseTrack(
        centerLat: Double,
        centerLon: Double,
        semiMajorM: Double,
        semiMinorM: Double,
        n: Int,
    ): List<Pair<Double, Double>> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(centerLat))
        return (0 until n).map { i ->
            val t = 2 * PI * i / n
            val east = semiMajorM * cos(t)
            val north = semiMinorM * sin(t)
            (centerLat + north / mPerDegLat) to (centerLon + east / mPerDegLon)
        }
    }

    /** 生成一条锯齿状轨迹，模拟手指抖动。 */
    private fun jitteryPath(n: Int, amplitudeM: Double): List<Pair<Double, Double>> {
        val baseLat = 39.9042
        val baseLon = 116.4074
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(baseLat))
        return (0 until n).map { i ->
            val along = i * 3.0                       // 每点前进 3 米
            val wobble = if (i % 2 == 0) amplitudeM else -amplitudeM
            (baseLat + wobble / mPerDegLat) to (baseLon + along / mPerDegLon)
        }
    }

    /** 点到折线的最大偏离（米），用于评估抽稀后形状是否走样。 */
    private fun maxDeviation(
        original: List<Pair<Double, Double>>,
        simplified: List<Pair<Double, Double>>,
    ): Double {
        var worst = 0.0
        for (p in original) {
            var best = Double.MAX_VALUE
            for (i in 0 until simplified.size - 1) {
                val d = pointToSegment(p, simplified[i], simplified[i + 1])
                if (d < best) best = d
            }
            if (best > worst) worst = best
        }
        return worst
    }

    private fun pointToSegment(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * abs(cos(Math.toRadians(a.first))).coerceAtLeast(1e-6)
        val bx = (b.second - a.second) * mPerDegLon
        val by = (b.first - a.first) * mPerDegLat
        val px = (p.second - a.second) * mPerDegLon
        val py = (p.first - a.first) * mPerDegLat
        val lenSq = bx * bx + by * by
        if (lenSq < 1e-9) return kotlin.math.sqrt(px * px + py * py)
        val t = ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        val ex = px - t * bx
        val ey = py - t * by
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== PathSimplify 抽稀算法验证 ===")

        // --- 用例 1：400m 标准操场椭圆，密集采样 ---
        println("\n[1] 400m 操场椭圆（400 个采样点，容差 6m）")
        val track = ellipseTrack(39.9042, 116.4074, 60.0, 35.0, 400)
        val trackLen = PathSimplify.totalLengthMeters(track)
        val s1 = PathSimplify.simplify(track, 6.0)
        val dev1 = maxDeviation(track, s1)
        println("      原始 ${track.size} 点 / 周长 ${"%.1f".format(trackLen)} m")
        println("      抽稀后 ${s1.size} 点 / 最大偏离 ${"%.2f".format(dev1)} m")
        check("点数被有效压缩（< 原始 15%）", s1.size < track.size * 0.15, "实际 ${s1.size}")
        check("形状没走样（最大偏离 <= 容差）", dev1 <= 6.0 + 0.5, "偏离 ${"%.2f".format(dev1)} m")
        check("保留首尾点", s1.first() == track.first() && s1.last() == track.last())
        check("抽稀后弧长与原始接近（误差 <8%）",
            abs(PathSimplify.totalLengthMeters(s1) - trackLen) / trackLen < 0.08,
            "抽稀后 ${"%.1f".format(PathSimplify.totalLengthMeters(s1))} m")
        check("结果至少 2 点", s1.size >= 2)

        // --- 用例 2：锯齿抖动，验证抖动被抹平 ---
        println("\n[2] 手指抖动轨迹（200 点，±1.5m 锯齿，容差 6m）")
        val jitter = jitteryPath(200, 1.5)
        val s2 = PathSimplify.simplify(jitter, 6.0)
        println("      原始 ${jitter.size} 点 → 抽稀后 ${s2.size} 点")
        check("抖动被抹平（压到 5 点以内）", s2.size <= 5, "实际 ${s2.size}")

        // --- 用例 3：容差越大点越少（单调性） ---
        println("\n[3] 容差单调性")
        val loose = PathSimplify.simplify(track, 20.0)
        println("      容差 6m → ${s1.size} 点；容差 20m → ${loose.size} 点")
        check("容差越大点数越少", loose.size <= s1.size, "${loose.size} vs ${s1.size}")

        // --- 用例 4：退化输入 ---
        println("\n[4] 退化输入")
        check("空列表返回空", PathSimplify.simplify(emptyList(), 6.0).isEmpty())
        check("单点返回单点", PathSimplify.simplify(listOf(1.0 to 2.0), 6.0).size == 1)
        val two = listOf(39.9 to 116.4, 39.91 to 116.41)
        check("两点原样返回", PathSimplify.simplify(two, 6.0) == two)
        val dup = List(10) { 39.9 to 116.4 }
        val s4 = PathSimplify.simplify(dup, 6.0)
        check("全部重合的点不崩溃且至少 2 点", s4.size >= 2, "实际 ${s4.size}")

        // --- 用例 5：dropTooClose 粗过滤 ---
        println("\n[5] dropTooClose 最小间距过滤")
        val dense = ellipseTrack(39.9042, 116.4074, 60.0, 35.0, 2000)
        val dropped = PathSimplify.dropTooClose(dense, 2.0)
        println("      2000 点 → 过滤后 ${dropped.size} 点")
        check("密集点被抽到合理数量", dropped.size < 2000)
        check("过滤后仍 >= 2 点", dropped.size >= 2)
        check("首尾保留", dropped.first() == dense.first() && dropped.last() == dense.last())

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
