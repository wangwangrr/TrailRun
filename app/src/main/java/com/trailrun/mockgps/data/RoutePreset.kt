package com.trailrun.mockgps.data

import com.trailrun.mockgps.core.Geo
import com.trailrun.mockgps.core.RouteMode
import org.json.JSONArray
import org.json.JSONObject

/** 轨迹上的一个点。 */
data class Waypoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", latitude)
        put("lon", longitude)
        if (name.isNotEmpty()) put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject): Waypoint = Waypoint(
            latitude = o.getDouble("lat"),
            longitude = o.getDouble("lon"),
            name = o.optString("name", ""),
        )
    }
}

/** 一条可保存的路线预设。 */
data class RoutePreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<Waypoint>,
    /** km/h */
    val speedKmh: Double = 9.0,
    val mode: RouteMode = RouteMode.SINGLE,
    val jitterMeters: Double = 0.0,
    val loopCount: Int = 0,
    /** 预设模式：true 表示「刷圈」，用于需要完成固定次数的场景。 */
    val repeatUntilDistance: Boolean = false,
    /** 目标距离（米），0 表示不限制。 */
    val targetDistanceMeters: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 单程长度（米）。 */
    fun singleLegMeters(): Double {
        if (waypoints.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            sum += Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        return sum
    }

    /** 预计总耗时（秒）。 */
    fun estimatedSeconds(): Long {
        if (speedKmh <= 0.0) return 0L
        val mps = speedKmh / 3.6
        val meters = if (targetDistanceMeters > 0.0) {
            targetDistanceMeters
        } else {
            when (mode) {
                RouteMode.SINGLE -> singleLegMeters()
                else -> singleLegMeters() * 2
            }
        }
        return (meters / mps).toLong()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("speed", speedKmh)
        put("mode", mode.name)
        put("jitter", jitterMeters)
        put("loopCount", loopCount)
        put("target", targetDistanceMeters)
        put("createdAt", createdAt)
        put("points", JSONArray().apply { waypoints.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): RoutePreset {
            val arr = o.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<Waypoint>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                pts.add(Waypoint.fromJson(item))
            }
            return RoutePreset(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                name = o.optString("name", "未命名路线"),
                waypoints = pts,
                speedKmh = o.optDouble("speed", 9.0),
                mode = runCatching { RouteMode.valueOf(o.optString("mode", "SINGLE")) }
                    .getOrDefault(RouteMode.SINGLE),
                jitterMeters = o.optDouble("jitter", 0.0),
                loopCount = o.optInt("loopCount", 0),
                targetDistanceMeters = o.optDouble("target", 0.0),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}
