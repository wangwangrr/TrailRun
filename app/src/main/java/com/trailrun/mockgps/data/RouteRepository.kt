package com.trailrun.mockgps.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简本地持久化：SharedPreferences + JSON，不引入 Room / Gson，编译依赖最少。
 * 保存三类数据：路线预设列表、上次编辑的路线、习惯设置。
 */
class RouteRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 路线预设 ----------------

    fun loadPresets(): List<RoutePreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<RoutePreset>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(RoutePreset.fromJson(it)) }
            }
            list
        }.getOrDefault(emptyList())
    }

    fun savePresets(presets: List<RoutePreset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun upsertPreset(preset: RoutePreset): List<RoutePreset> {
        val list = loadPresets().toMutableList()
        val idx = list.indexOfFirst { it.id == preset.id }
        if (idx >= 0) list[idx] = preset else list.add(0, preset)
        savePresets(list)
        return list
    }

    fun deletePreset(id: String): List<RoutePreset> {
        val list = loadPresets().filterNot { it.id == id }
        savePresets(list)
        return list
    }

    // ---------------- 当前编辑中的路线 ----------------

    fun saveDraft(
        waypoints: List<Waypoint>,
        speedKmh: Double,
        mode: String,
        jitter: Double,
        targetDistanceMeters: Double,
    ) {
        val o = JSONObject().apply {
            put("speed", speedKmh)
            put("mode", mode)
            put("jitter", jitter)
            // 目标距离也要存：漏掉它的话，用户设了「跑够 3km 自动停」，
            // 冷启动后会变回「不限」，模拟会一直跑下去。
            put("target", targetDistanceMeters)
            put("points", JSONArray().apply { waypoints.forEach { put(it.toJson()) } })
        }
        prefs.edit().putString(KEY_DRAFT, o.toString()).apply()
    }

    /** 读取草稿；没有任何点位时返回 null（空草稿没有恢复价值）。 */
    fun loadDraft(): DraftRoute? {
        val raw = prefs.getString(KEY_DRAFT, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<Waypoint>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { pts.add(Waypoint.fromJson(it)) }
            }
            if (pts.isEmpty()) return null
            DraftRoute(
                waypoints = pts,
                speedKmh = o.optDouble("speed", 9.0),
                mode = o.optString("mode", "SINGLE"),
                jitter = o.optDouble("jitter", 0.0),
                targetDistanceMeters = o.optDouble("target", 0.0),
            )
        }.getOrNull()
    }

    /** 删除已保存的草稿。 */
    fun clearDraft() {
        prefs.edit().remove(KEY_DRAFT).apply()
    }

    data class DraftRoute(
        val waypoints: List<Waypoint>,
        val speedKmh: Double,
        val mode: String,
        val jitter: Double,
        val targetDistanceMeters: Double = 0.0,
    )

    // ---------------- 设置 ----------------

    var followCamera: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW, true)
        set(v) = prefs.edit().putBoolean(KEY_FOLLOW, v).apply()

    /** 地图配色（MapStyle 枚举名）。 */
    var mapStyle: String
        get() = prefs.getString(KEY_MAP_STYLE, null) ?: ""
        set(v) = prefs.edit().putString(KEY_MAP_STYLE, v).apply()

    /**
     * 用户指定的 OSM 镜像 baseUrl；空字符串表示「自动」。
     *
     * 存 URL 而不是序号：以后在列表中间增删端点时，序号会错位到另一个镜像上。
     */
    var mapMirror: String
        get() = prefs.getString(KEY_MAP_MIRROR, null) ?: ""
        set(v) = prefs.edit().putString(KEY_MAP_MIRROR, v).apply()

    /**
     * 地图是否处于「不联网」模式：只用已缓存的瓦片。
     *
     * 键名带 v3：早期版本用 `tile_provider` / `tile_provider_v2` 存过已删除的瓦片源名，
     * 换键名让旧值一次性失效，避免残留配置把界面带到一个不存在的源上。
     */
    var offlineMap: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MAP, false)
        set(v) = prefs.edit().putBoolean(KEY_OFFLINE_MAP, v).apply()

    /** 清掉历史版本遗留的瓦片源偏好项。 */
    fun purgeLegacyTilePrefs() {
        prefs.edit()
            .remove("tile_provider")
            .remove("tile_provider_v2")
            .apply()
    }

    /**
     * 本设备是否已经输入过进入口令。
     *
     * 只存一个布尔值，**不存口令本身**：存了口令反而多出一份可以被拷走的秘密，
     * 而这里要的只是「这台设备问过一次就不再问」。
     * 卸载重装或清除应用数据都会重置为 false（这是 SharedPreferences 的语义，也是期望行为）。
     */
    var unlocked: Boolean
        get() = prefs.getBoolean(KEY_UNLOCKED, false)
        set(v) = prefs.edit().putBoolean(KEY_UNLOCKED, v).apply()

    private companion object {
        const val PREFS = "trailrun_store"
        const val KEY_PRESETS = "presets"
        const val KEY_DRAFT = "draft"
        const val KEY_FOLLOW = "follow_camera"
        const val KEY_OFFLINE_MAP = "offline_map_v3"
        const val KEY_MAP_STYLE = "map_style"
        const val KEY_MAP_MIRROR = "map_mirror"
        const val KEY_UNLOCKED = "unlocked_v1"
    }
}
