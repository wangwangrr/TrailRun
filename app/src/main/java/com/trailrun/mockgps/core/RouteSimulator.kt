package com.trailrun.mockgps.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轨迹几何工具：距离 / 方位角 / 插值。
 * 使用 WGS-84 近似球体公式，米级误差，足够跑步轨迹使用。
 */
object Geo {

    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val sinDp = sin(dp / 2)
        val sinDl = sin(dl / 2)
        val a = sinDp * sinDp + cos(p1) * cos(p2) * sinDl * sinDl
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** 方位角，单位度；0 = 正北，顺时针增大。 */
    fun bearingDegrees(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
    ): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 以 [lat]/[lon] 为原点做米级偏移，返回 [纬度, 经度]。 */
    fun offsetMeters(lat: Double, lon: Double, east: Double, north: Double): DoubleArray {
        val newLat = lat + Math.toDegrees(north / EARTH_RADIUS_M)
        val cosLat = cos(Math.toRadians(lat))
        val safeCos = if (abs(cosLat) < 1e-9) 1e-9 else cosLat
        val newLon = lon + Math.toDegrees(east / (EARTH_RADIUS_M * safeCos))
        return doubleArrayOf(newLat, newLon)
    }

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}

/** 路线行进方式。 */
enum class RouteMode {
    /** 起点 → 终点，走完即停。 */
    SINGLE,

    /** 环形：到终点后掉头回起点，无限往复（用于刷圈）。 */
    LOOP,

    /** 折返：与环形轨迹一致，仅进度统计按往返计算。 */
    BACK_AND_FORTH,
}

/** 模拟出的一帧定位数据。 */
data class SimFix(
    val latitude: Double,
    val longitude: Double,
    /** m/s，等于设定速度。 */
    val speedMps: Double,
    val bearingDeg: Double,
    val altitude: Double,
    val accuracyMeters: Float,
    /** 本次运行已走过的实际距离（米），折返时会继续累加。 */
    val traveledMeters: Double,
    /** 一个单程的长度（米）。 */
    val singleLegMeters: Double,
    /** 0..1，当前单程进度。 */
    val legProgress: Double,
    /** 已完成的单程数。 */
    val legCount: Int,
    val finished: Boolean,
)

/**
 * 轨迹回放状态机。
 *
 * 构造后按固定间隔反复调用 [advance]，位置严格按「设定速度 × 实际时间」沿折线推进，
 * 因此调用间隔有抖动也不会导致速度偏差。
 */
class RouteSimulator(
    waypoints: List<Pair<Double, Double>>,
    /** km/h */
    speedKmh: Double,
    val mode: RouteMode = RouteMode.SINGLE,
    /** 轨迹抖动幅度（米），0 表示完全规整。 */
    val jitterMeters: Double = 0.0,
    private val random: Random = Random(),
) {
    val points: List<Pair<Double, Double>> = waypoints

    private val legLengths: DoubleArray
    private val singleLegTotal: Double
    private val lastLegIndex: Int

    val speedMps: Double = speedKmh / 3.6

    /** 当前所在段的下标（0..lastLegIndex）。 */
    var legIndex: Int = 0
        private set

    /** 当前段内已前进的距离（按行进方向计）。 */
    var legOffset: Double = 0.0
        private set

    var direction: Int = 1
        private set

    var traveledMeters: Double = 0.0
        private set

    var legCount: Int = 0
        private set

    var finished: Boolean = false
        private set

    init {
        require(points.size >= 2) { "路线至少需要 2 个坐标点" }
        require(speedMps > 0.0) { "速度必须大于 0" }

        val lens = DoubleArray(points.size - 1)
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = Geo.distanceMeters(a.first, a.second, b.first, b.second)
            lens[i] = d
            total += d
        }
        legLengths = lens
        singleLegTotal = total
        lastLegIndex = lens.size - 1
    }

    /** 单程长度（米）。 */
    fun singleLegMeters(): Double = singleLegTotal

    /** 计划总距离：单次为单程长度；循环模式按一圈往返计。 */
    fun plannedTotalMeters(): Double =
        if (mode == RouteMode.SINGLE) singleLegTotal else singleLegTotal * 2

    /** 计划耗时（秒）。循环模式下是一圈的耗时，实际会持续跑下去。 */
    fun plannedDurationSeconds(): Double =
        if (speedMps <= 0.0) 0.0 else plannedTotalMeters() / speedMps

    /**
     * 按真实经过的毫秒数推进轨迹。
     * @param deltaMillis 距上次调用经过的毫秒数，内部会做上限保护以吸收卡顿。
     */
    fun advance(deltaMillis: Long): SimFix {
        if (!finished) {
            var remaining = speedMps * (deltaMillis.coerceIn(0L, 5_000L) / 1000.0)
            var guard = 0
            while (remaining > 0.0 && !finished && guard++ < 8192) {
                val legLen = legLengths[legIndex].coerceAtLeast(0.01)
                val leftInLeg = legLen - legOffset
                if (remaining < leftInLeg) {
                    legOffset += remaining
                    traveledMeters += remaining
                    remaining = 0.0
                } else {
                    // 走到本段尽头
                    remaining -= leftInLeg
                    traveledMeters += leftInLeg
                    legOffset = 0.0
                    stepLeg()
                }
            }
        }
        return currentFix()
    }

    /** 到达一段尽头：继续下一段、折返，或结束。 */
    private fun stepLeg() {
        val next = legIndex + direction
        if (next in 0..lastLegIndex) {
            legIndex = next
            return
        }
        if (mode == RouteMode.SINGLE) {
            finished = true
            return
        }

        // 掉头。
        // 注意 legIndex 是「段」的下标：段 i 连接点 i 与点 i+1。
        // 正向越界时说明刚走完最后一段（到达终点），反向应走段 lastLegIndex-1；
        // 反向越界时说明刚走完第一段（回到起点），此时记一圈，正向应走段 0。
        direction = -direction
        if (direction > 0) {
            legCount++                       // 完成一趟往返
            legIndex = 0
        } else {
            legIndex = lastLegIndex - 1
        }
    }

    private fun currentFix(): SimFix {
        val from: Pair<Double, Double>
        val to: Pair<Double, Double>

        if (finished) {
            // 停在终点
            from = points.last()
            to = points[points.size - 2]
        } else {
            val a = points[legIndex]
            val b = points[legIndex + 1]
            if (direction > 0) {
                from = a; to = b
            } else {
                from = b; to = a
            }
        }

        val legLen = Geo.distanceMeters(from.first, from.second, to.first, to.second)
        val t = if (legLen <= 0.0) 0.0 else (legOffset / legLen).coerceIn(0.0, 1.0)

        var lat = Geo.lerp(from.first, to.first, t)
        var lon = Geo.lerp(from.second, to.second, t)

        if (jitterMeters > 0.0 && !finished) {
            val angle = random.nextDouble() * Math.PI * 2
            val radius = random.nextDouble() * jitterMeters
            val shifted = Geo.offsetMeters(
                lat, lon,
                east = radius * cos(angle),
                north = radius * sin(angle),
            )
            lat = shifted[0]
            lon = shifted[1]
        }

        val legProgress = if (singleLegTotal <= 0.0) {
            0.0
        } else if (mode == RouteMode.SINGLE) {
            (traveledMeters / singleLegTotal).coerceIn(0.0, 1.0)
        } else {
            // 循环模式：当前这一趟单程走了多少
            val within = traveledMeters % singleLegTotal
            (if (within <= 0.0 && traveledMeters > 0.0) 1.0 else within / singleLegTotal)
                .coerceIn(0.0, 1.0)
        }

        return SimFix(
            latitude = lat,
            longitude = lon,
            speedMps = if (finished) 0.0 else speedMps,
            bearingDeg = Geo.bearingDegrees(from.first, from.second, to.first, to.second),
            altitude = 42.0,
            accuracyMeters = if (jitterMeters > 0) 3.5f + random.nextFloat() else 3.0f,
            traveledMeters = traveledMeters,
            singleLegMeters = singleLegTotal,
            legProgress = legProgress,
            legCount = legCount,
            finished = finished,
        )
    }
}
