package com.trailrun.mockgps.core

import kotlin.math.abs
import kotlin.math.cos

/**
 * RouteSimulator 的独立验证（纯 JVM）。
 * 重点验证「位置严格按 速度 × 时间 推进」以及折返/循环的分支处理。
 */
object RouteSimulatorTest {

    private var failures = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name  $detail")
        }
    }

    private fun approx(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

    private fun line(startLat: Double, startLon: Double, meters: Double, n: Int):
        List<Pair<Double, Double>> {
        val mPerDegLat = 111_320.0
        return (0 until n).map { i ->
            val d = meters * i / (n - 1)
            (startLat + d / mPerDegLat) to startLon
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== RouteSimulator 验证 ===")

        // --- 用例 1：速度与位移严格对应 ---
        println("\n[1] 速度 10 km/h 沿 1000m 直线推进")
        val pts = line(39.9042, 116.4074, 1000.0, 11)
        val sim = RouteSimulator(pts, speedKmh = 10.0, mode = RouteMode.SINGLE)
        check("单程长度约 1000m", approx(sim.singleLegMeters(), 1000.0, 5.0),
            "实际 ${"%.1f".format(sim.singleLegMeters())}")

        // 以 200ms 步长推进 60 秒 → 应前进 10/3.6*60 = 166.67m
        var fix = sim.advance(0)
        repeat(300) { fix = sim.advance(200) }
        val expected = 10.0 / 3.6 * 60.0
        check("60 秒后位移正确（±3m）", approx(fix.traveledMeters, expected, 3.0),
            "期望 ${"%.1f".format(expected)} 实际 ${"%.1f".format(fix.traveledMeters)}")
        check("速度上报为 10 km/h", approx(fix.speedMps * 3.6, 10.0, 0.01))
        check("尚未结束", !fix.finished)
        check("进度约 16.7%", approx(fix.legProgress, expected / 1000.0, 0.02),
            "实际 ${"%.3f".format(fix.legProgress)}")

        // --- 用例 2：跑到终点后停止（SINGLE） ---
        println("\n[2] 单程模式：跑完 1000m 后停止")
        val sim2 = RouteSimulator(pts, speedKmh = 36.0, mode = RouteMode.SINGLE) // 10 m/s
        var f2 = sim2.advance(0)
        var guard = 0
        while (!f2.finished && guard++ < 5000) f2 = sim2.advance(200)
        check("会结束", f2.finished)
        check("结束时速度归零", f2.speedMps == 0.0)
        check("总位移接近 1000m", approx(f2.traveledMeters, 1000.0, 10.0),
            "实际 ${"%.1f".format(f2.traveledMeters)}")
        check("停在终点", approx(f2.latitude, pts.last().first, 1e-4),
            "实际 ${"%.6f".format(f2.latitude)} 期望 ${"%.6f".format(pts.last().first)}")

        // --- 用例 3：循环模式会折返且持续运行 ---
        println("\n[3] 环线模式：到终点后折返，不会结束")
        val sim3 = RouteSimulator(pts, speedKmh = 36.0, mode = RouteMode.LOOP)
        var f3 = sim3.advance(0)
        var g3 = 0
        while (g3++ < 5000 && f3.traveledMeters < 2500.0) f3 = sim3.advance(200)
        check("走过 2500m 仍未结束", !f3.finished)
        check("位移已超过单程长度（说明确实折返了）", f3.traveledMeters >= 2500.0,
            "实际 ${"%.1f".format(f3.traveledMeters)}")
        // 单程 1000m：走满一趟往返恰好 2000m → 应正好记 1 趟；2500m 时仍是 1 趟
        check("完成 2500m 后已记录 1 趟往返", f3.legCount == 1, "legCount=${f3.legCount}")

        // 再走满第二趟，应变成 2
        var g3b = 0
        while (g3b++ < 5000 && f3.traveledMeters < 4100.0) f3 = sim3.advance(200)
        check("完成 4100m 后已记录 2 趟往返", f3.legCount == 2, "legCount=${f3.legCount}")

        check("折返后位置仍在路线纬度范围内",
            f3.latitude in (pts.first().first - 1e-4)..(pts.last().first + 1e-4),
            "lat=${"%.6f".format(f3.latitude)}")

        // --- 用例 4：大时间步长被上限保护，不会瞬移穿透 ---
        println("\n[4] 卡顿保护：单次 10 秒的 delta 被截断到 5 秒")
        val sim4 = RouteSimulator(pts, speedKmh = 36.0, mode = RouteMode.SINGLE) // 10 m/s
        val f4 = sim4.advance(10_000)
        check("位移不超过 5 秒对应的 50m", f4.traveledMeters <= 50.0 + 0.01,
            "实际 ${"%.1f".format(f4.traveledMeters)}")
        check("位移正好是 50m（截断生效）", approx(f4.traveledMeters, 50.0, 0.01),
            "实际 ${"%.1f".format(f4.traveledMeters)}")

        // --- 用例 5：抖动开关 ---
        println("\n[5] 轨迹抖动")
        val sim5 = RouteSimulator(pts, speedKmh = 10.0, mode = RouteMode.SINGLE, jitterMeters = 3.0)
        var f5 = sim5.advance(0)
        repeat(20) { f5 = sim5.advance(200) }
        // 抖动后位置应偏离理想直线的经度方向很小但不为 0（这里纬度方向是前进方向）
        val idealLat = 39.9042 + f5.traveledMeters / 111_320.0
        val offsetMeters = abs(f5.latitude - idealLat) * 111_320.0
        check("抖动幅度不超过设定值 3m", offsetMeters <= 3.0 + 0.5,
            "实际偏离 ${"%.2f".format(offsetMeters)} m")
        check("抖动模式下速度仍然准确", approx(f5.speedMps * 3.6, 10.0, 0.01))

        // --- 用例 6：参数校验 ---
        println("\n[6] 参数校验")
        var threw = false
        try {
            RouteSimulator(listOf(39.9 to 116.4), speedKmh = 10.0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        check("少于 2 点会抛 IllegalArgumentException", threw)

        var threw2 = false
        try {
            RouteSimulator(pts, speedKmh = 0.0)
        } catch (e: IllegalArgumentException) {
            threw2 = true
        }
        check("速度为 0 会抛 IllegalArgumentException", threw2)

        // --- 用例 7：计划总距离 ---
        println("\n[7] 计划总距离")
        val single = RouteSimulator(pts, 10.0, RouteMode.SINGLE)
        val loop = RouteSimulator(pts, 10.0, RouteMode.LOOP)
        check("单程 = 单程长度", approx(single.plannedTotalMeters(), 1000.0, 5.0),
            "实际 ${"%.1f".format(single.plannedTotalMeters())}")
        check("环线 = 单程 × 2", approx(loop.plannedTotalMeters(), 2000.0, 10.0),
            "实际 ${"%.1f".format(loop.plannedTotalMeters())}")

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
