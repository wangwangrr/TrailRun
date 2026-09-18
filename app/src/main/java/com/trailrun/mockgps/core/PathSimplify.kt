package com.trailrun.mockgps.core

import kotlin.math.abs
import kotlin.math.max

/**
 * 轨迹简化：Douglas-Peucker 抽稀。
 *
 * 手指在屏幕上拖动会采样出成百上千个点，直接全部存下来会让路线数据臃肿、
 * 回放时的折线也毫无必要地密集。这里按「垂直距离容差」把近似共线的中间点丢掉，
 * 只保留决定形状的拐点。
 */
object PathSimplify {

    /**
     * @param points 原始点列（纬度, 经度）
     * @param toleranceMeters 允许的最大偏离距离，越小平滑度越高、点越多
     * @return 抽稀后的点列，至少包含首尾两点
     */
    fun simplify(
        points: List<Pair<Double, Double>>,
        toleranceMeters: Double = 6.0,
    ): List<Pair<Double, Double>> {
        if (points.size <= 2) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        douglasPeucker(points, 0, points.size - 1, toleranceMeters, keep)

        val out = ArrayList<Pair<Double, Double>>()
        for (i in points.indices) {
            if (keep[i]) out.add(points[i])
        }
        // 极端情况下（比如所有点重合）保证至少两点
        if (out.size < 2 && points.size >= 2) {
            return listOf(points.first(), points.last())
        }
        return out
    }

    private fun douglasPeucker(
        pts: List<Pair<Double, Double>>,
        start: Int,
        end: Int,
        tolerance: Double,
        keep: BooleanArray,
    ) {
        if (end <= start + 1) return

        var maxDist = 0.0
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistanceMeters(
                pts[i], pts[start], pts[end],
            )
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }

        if (maxDist > tolerance && index > start) {
            keep[index] = true
            douglasPeucker(pts, start, index, tolerance, keep)
            douglasPeucker(pts, index, end, tolerance, keep)
        }
    }

    /**
     * 点 p 到线段 a-b 的垂直距离（米）。
     * 用起点处的局部等距投影把经纬度换算成米，跑步尺度上误差可以忽略。
     */
    private fun perpendicularDistanceMeters(
        p: Pair<Double, Double>,
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ): Double {
        // 以 a 为原点做局部平面投影
        val cosLat = kotlin.math.cos(Math.toRadians(a.first))
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * abs(cosLat).coerceAtLeast(1e-6)

        val ax = 0.0
        val ay = 0.0
        val bx = (b.second - a.second) * mPerDegLon
        val by = (b.first - a.first) * mPerDegLat
        val px = (p.second - a.second) * mPerDegLon
        val py = (p.first - a.first) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy

        if (lenSq < 1e-9) {
            // a 与 b 重合，退化为点距
            return kotlin.math.sqrt(px * px + py * py)
        }

        // 投影参数 t 截断到 [0,1]，得到线段上的最近点
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + tc * dx
        val cy = ay + tc * dy
        val ex = px - cx
        val ey = py - cy
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }

    /** 相邻两点间的累计长度（米），用于界面提示。 */
    fun totalLengthMeters(points: List<Pair<Double, Double>>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until points.size - 1) {
            sum += Geo.distanceMeters(
                points[i].first, points[i].second,
                points[i + 1].first, points[i + 1].second,
            )
        }
        return sum
    }

    /** 去掉彼此过近的点，作为抽稀前的粗过滤。 */
    fun dropTooClose(
        points: List<Pair<Double, Double>>,
        minSpacingMeters: Double = 2.0,
    ): List<Pair<Double, Double>> {
        if (points.size < 2) return points
        val out = ArrayList<Pair<Double, Double>>(points.size)
        out.add(points.first())
        for (i in 1 until points.size) {
            val last = out.last()
            val d = Geo.distanceMeters(
                last.first, last.second,
                points[i].first, points[i].second,
            )
            if (d >= minSpacingMeters) out.add(points[i])
        }
        if (out.size < 2) {
            return listOf(points.first(), points.last())
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }
}
