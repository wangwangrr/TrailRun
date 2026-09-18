package com.trailrun.mockgps.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailrun.mockgps.TrailRunApp
import com.trailrun.mockgps.core.AppPassword
import com.trailrun.mockgps.core.Geo
import com.trailrun.mockgps.core.LocationHelper
import com.trailrun.mockgps.core.PathSimplify
import com.trailrun.mockgps.core.PlaceSearch
import com.trailrun.mockgps.core.RouteMode
import com.trailrun.mockgps.core.TileEndpoint
import com.trailrun.mockgps.core.TileProvider
import com.trailrun.mockgps.core.endpointByUrl
import com.trailrun.mockgps.data.RoutePreset
import com.trailrun.mockgps.data.RouteRepository
import com.trailrun.mockgps.data.Waypoint
import com.trailrun.mockgps.service.LiveState
import com.trailrun.mockgps.service.MockLocationService
import com.trailrun.mockgps.ui.components.MapMode
import com.trailrun.mockgps.ui.components.MapStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主界面状态。 */
data class EditorState(
    val waypoints: List<Waypoint> = emptyList(),
    /**
     * 速度（km/h）。
     *
     * 必须是有限数：Compose 的 Slider 在 value 为 NaN 或无穷时会直接抛
     * IllegalArgumentException（"value must be finite"），而滑块是常驻界面元素，
     * 偏好里一旦存过脏数据就会「启动即闪退」。
     * 清洗放在数据入口（RouteRepository 读盘时）与界面处（Slider 取值前）各做一次，
     * 而不是在这里断言 —— 断言只会把「数据脏」变成「应用打不开」。
     */
    val speedKmh: Double = 9.0,
    val mode: RouteMode = RouteMode.SINGLE,
    val jitterMeters: Double = 0.0,
    val targetDistanceMeters: Double = 0.0,
    val presets: List<RoutePreset> = emptyList(),
    /**
     * 地图配色：标准（默认）/ 清新。
     *
     * 默认从「清新」改回「标准」是刻意的：那个滤镜会抬高黑场、压低对比度，
     * 底图本来就已经偏浅，再叠一层就更「看不清」了。现在滤镜已经改成
     * 只做轻微降饱和，想要柔和一点仍然可以在「更多 → 地图配色」里切。
     */
    val mapStyle: MapStyle = MapStyle.STANDARD,
    val followCamera: Boolean = true,
    /** 地图是否只用已缓存瓦片（不联网）。 */
    val offlineMap: Boolean = false,
    /**
     * 用户指定的 OSM 端点；null 表示「自动」，即内置列表里的第一个（高清 512px）。
     *
     * 注意这里是**唯一**生效的端点，不再是「指定的优先 + 其余后备」——
     * osmdroid 在多 baseUrl 时是随机挑一个，根本没有失败重试。
     */
    val mapMirror: TileEndpoint? = null,
    /** 地图交互模式：点选 / 手绘。 */
    val mapMode: MapMode = MapMode.POINT,
    /** 最近一次取到的设备位置，供地图定位。 */
    val locationFix: LocationHelper.Fix? = null,
    /** 定位失败的原因，null 表示没有错误。 */
    val locationError: String? = null,
    val selectedPresetId: String? = null,
    val message: String? = null,
) {
    /** 路线的单程长度（米）。 */
    val singleLegMeters: Double
        get() {
            if (waypoints.size < 2) return 0.0
            var sum = 0.0
            for (i in 0 until waypoints.size - 1) {
                val a = waypoints[i]
                val b = waypoints[i + 1]
                sum += Geo.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            }
            return sum
        }

    val plannedMeters: Double
        get() = if (targetDistanceMeters > 0) targetDistanceMeters
        else if (mode == RouteMode.SINGLE) singleLegMeters else singleLegMeters * 2

    val plannedSeconds: Long
        get() = if (speedKmh <= 0) 0L else (plannedMeters / (speedKmh / 3.6)).toLong()

    val canRun: Boolean get() = waypoints.size >= 2 && speedKmh > 0
}

/**
 * 把可能被污染的数值规范化到安全区间。
 * 非有限数（NaN / 正负无穷）或超出范围时回落到 [fallback]。
 *
 * 用于数据入口与界面取值处：脏数据只应导致数值被修正，不该让应用崩溃。
 */
internal fun sanitizeDouble(
    value: Double,
    fallback: Double,
    range: ClosedFloatingPointRange<Double>,
): Double = if (!value.isFinite()) fallback else value.coerceIn(range.start, range.endInclusive)

/** 地点搜索的界面状态。 */
data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<PlaceSearch.Place> = emptyList(),
    val error: String? = null,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val isEmptyResult: Boolean get() = !searching && error == null && hasQuery && results.isEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: RouteRepository = (app as TrailRunApp).repository

    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    /** 服务上报的实时状态。 */
    val runStatus = LiveState.state

    /** 是否已被选为「模拟位置信息应用」。 */
    private val _mockReady = MutableStateFlow<Boolean?>(null)
    val mockReady: StateFlow<Boolean?> = _mockReady.asStateFlow()

    /**
     * 是否还需要输入进入口令。
     *
     * 初始值直接从本地读：「这台设备输过一次就再也不用输」，
     * 所以它必须是一次持久化读取，而不是每次启动都问一遍。
     */
    private val _locked = MutableStateFlow(
        runCatching { !repo.unlocked }.getOrDefault(false)
    )
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * 启动时是否恢复了路线。
     *
     * 现在恒为 false：路线刻意不再自动恢复（避免「重进后旧轨迹还在」）。
     * 保留这个字段是因为地图首帧要根据它决定「缩放到整条路线」还是「定位到我」，
     * 将来若加「手动恢复上次路线」也用得上。
     */
    val restoredDraft: Boolean = false

    init {
        // 整个恢复过程包一层：它在 ViewModel 构造期间执行，
        // 任何未捕获异常都会让应用「一进就闪退」。恢复失败最多是回到默认状态，
        // 绝不能因此打不开应用。
        try {
            // ⚠️ 刻意不自动恢复上次的路线。
            // 之前每次冷启动都会把上次画好的轨迹重新画回地图，用户看到的是
            // 「重进应用后之前的轨迹还在」，却又找不到清除入口 —— 直接开空白最省事。
            // 预设（presets）仍会恢复，那是用户主动保存的，与临时草稿不是一回事。
            _editor.value = _editor.value.copy(
                presets = repo.loadPresets(),
                followCamera = repo.followCamera,
                offlineMap = repo.offlineMap,
                // 存的镜像 URL 若已不在列表里，自动回落到「自动」，避免指向不存在的端点
                mapMirror = endpointByUrl(repo.mapMirror),
                mapStyle = runCatching { MapStyle.valueOf(repo.mapStyle) }
                    .getOrDefault(MapStyle.STANDARD),
            )
            // 清掉历史版本存过的瓦片源名，避免残留配置干扰
            repo.purgeLegacyTilePrefs()
            // 既然不再自动恢复路线，旧草稿留着没有意义，
            // 而且会让「进入时是空的」与「存储里还有数据」不一致。
            repo.clearDraft()
        } catch (e: Throwable) {
            android.util.Log.e("MainViewModel", "恢复本地状态失败，已按默认值启动", e)
            _editor.value = EditorState()
        }
        refreshMockPermission(app)
    }

    // ---------------- 进入口令 ----------------

    /**
     * 校验口令。对了就记进本地并解锁，返回 true；错了返回 false 且不改任何状态。
     *
     * 写盘放在这里而不是界面里：解锁是一次持久化事实，
     * 界面只负责把结果画出来。
     */
    fun unlock(input: String): Boolean {
        if (!AppPassword.matches(input)) return false
        runCatching { repo.unlocked = true }
        _locked.value = false
        return true
    }

    // ---------------- 权限 ----------------

    fun refreshMockPermission(context: Context) {
        viewModelScope.launch {
            // 整个检测过程包一层：它在构造函数里就会跑，
            // 一旦抛出未捕获异常会直接崩掉主线程（表现为「一进就闪退」）。
            _mockReady.value = try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE)
                    as? android.location.LocationManager
                if (lm == null) false
                else com.trailrun.mockgps.service.MockLocationEngine.canMock(lm)
            } catch (e: Throwable) {
                android.util.Log.w("MainViewModel", "模拟位置权限检测失败", e)
                false
            }
        }
    }

    // ---------------- 路线编辑 ----------------

    fun addWaypoint(lat: Double, lon: Double) {
        val list = _editor.value.waypoints.toMutableList()
        list.add(Waypoint(lat, lon))
        pushUndo()
        val hint = when (list.size) {
            1 -> "已设置起点（绿色）· 再点一下设置终点"
            2 -> "已设置终点（红色）· 可继续加点画出拐弯"
            else -> "已添加第 ${list.size} 个点（途经点）"
        }
        update { it.copy(waypoints = list, selectedPresetId = null, message = hint) }
    }

    /** 直接按经纬度加一个点（用于搜索定位、坐标精确输入）。 */
    fun addWaypointByCoordinate(lat: Double, lon: Double, label: String? = null) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            update { it.copy(message = "经纬度超出有效范围") }
            return
        }
        val list = _editor.value.waypoints.toMutableList()
        list.add(Waypoint(lat, lon))
        pushUndo()
        update {
            it.copy(
                waypoints = list,
                selectedPresetId = null,
                message = label?.let { "已添加：$it" } ?: "已按坐标添加一个点",
            )
        }
    }

    fun removeWaypoint(index: Int) {
        val list = _editor.value.waypoints.toMutableList()
        if (index !in list.indices) return
        pushUndo()
        list.removeAt(index)
        update { it.copy(waypoints = list, selectedPresetId = null, message = "已删除该点") }
    }

    fun clearRoute() {
        if (_editor.value.waypoints.isEmpty()) {
            update { it.copy(message = "路线本来就是空的") }
            return
        }
        pushUndo()
        update {
            it.copy(
                waypoints = emptyList(),
                targetDistanceMeters = 0.0,
                selectedPresetId = null,
                message = "已清空路线",
            )
        }
        // 立刻把草稿也删掉：否则「已清空」只停留在内存里，
        // 存储里还留着旧路线，一旦被别处重新读取就会「清空了又回来」。
        persistDraft()
    }

    /** 反向：终点变起点。 */
    fun reverseRoute() {
        if (_editor.value.waypoints.size < 2) return
        pushUndo()
        update {
            it.copy(waypoints = it.waypoints.reversed(), selectedPresetId = null, message = "已反转起终点")
        }
    }

    fun setSpeed(kmh: Double) = update { it.copy(speedKmh = kmh.coerceIn(1.0, 40.0)) }

    fun setMode(mode: RouteMode) = update { it.copy(mode = mode) }

    fun setJitter(meters: Double) = update { it.copy(jitterMeters = meters.coerceIn(0.0, 8.0)) }

    fun setTargetDistance(meters: Double) =
        update { it.copy(targetDistanceMeters = meters.coerceIn(0.0, 100_000.0)) }

    /** 地图配色：切换并持久化（之前每次启动都会被重置回默认值）。 */
    fun toggleMapStyle() {
        val next = if (_editor.value.mapStyle == MapStyle.MUTED) MapStyle.STANDARD else MapStyle.MUTED
        repo.mapStyle = next.name
        update {
            it.copy(
                mapStyle = next,
                message = if (next == MapStyle.MUTED) "地图配色：清新" else "地图配色：标准",
            )
        }
    }

    /** 切换点选 / 手绘模式。 */
    fun setMapMode(mode: MapMode) = update {
        it.copy(
            mapMode = mode,
            message = when (mode) {
                MapMode.POINT -> "点选模式：点地图加点，长按也可以"
                MapMode.FREEHAND -> "手绘模式：按住屏幕拖动，像画图一样画出轨迹"
            },
        )
    }

    /**
     * 接收一笔手绘轨迹：先按最小间距粗过滤，再用 Douglas-Peucker 抽稀，
     * 然后**追加**到现有路线（手绘时可以分几笔画）。
     */
    fun addFreehandStroke(raw: List<Pair<Double, Double>>) {
        if (raw.size < 2) {
            update { it.copy(message = "这一笔太短了，按住屏幕拖动来画轨迹") }
            return
        }
        val filtered = PathSimplify.dropTooClose(raw, minSpacingMeters = 2.0)
        val simplified = PathSimplify.simplify(filtered, toleranceMeters = 6.0)

        val list = _editor.value.waypoints.toMutableList()
        list.addAll(simplified.map { Waypoint(it.first, it.second) })
        val addedMeters = PathSimplify.totalLengthMeters(simplified)

        pushUndo()
        update {
            it.copy(
                waypoints = list,
                selectedPresetId = null,
                message = "已画入 ${simplified.size} 个点（约 ${addedMeters.toInt()} 米）· " +
                    "共 ${list.size} 个点",
            )
        }
    }
    /** 切换「不联网」模式：只用已缓存的瓦片。 */
    fun toggleOfflineMap() {
        val next = !_editor.value.offlineMap
        repo.offlineMap = next
        update {
            it.copy(
                offlineMap = next,
                message = if (next) {
                    "离线模式：不再请求瓦片，只显示已缓存的区域"
                } else {
                    "已恢复联网加载地图"
                },
            )
        }
    }

    /**
     * 指定使用的 OSM 端点；传 null 表示「自动」（用内置列表的第一个）。
     * 返回后界面会重建瓦片源，所以调用方需要让地图重新加载。
     */
    fun setMapMirror(endpoint: TileEndpoint?) {
        repo.mapMirror = endpoint?.baseUrl ?: ""
        update {
            it.copy(
                mapMirror = endpoint,
                message = if (endpoint == null) {
                    "底图：自动（高清 512px）"
                } else {
                    "底图：${endpoint.label} · ${endpoint.tileSize}px"
                },
            )
        }
    }

    fun setFollowCamera(enabled: Boolean) {
        repo.followCamera = enabled
        update { it.copy(followCamera = enabled) }
    }

    fun consumeMessage() = update { it.copy(message = null) }

    // ---------------- 预设 ----------------

    fun saveCurrentAsPreset(name: String) {
        val s = _editor.value
        if (s.waypoints.size < 2) {
            update { it.copy(message = "至少需要 2 个点才能保存路线") }
            return
        }
        val preset = RoutePreset(
            name = name.ifBlank { "路线 ${s.presets.size + 1}" },
            waypoints = s.waypoints,
            speedKmh = s.speedKmh,
            mode = s.mode,
            jitterMeters = s.jitterMeters,
            targetDistanceMeters = s.targetDistanceMeters,
        )
        val list = repo.upsertPreset(preset)
        update { it.copy(presets = list, selectedPresetId = preset.id, message = "已保存「${preset.name}」") }
    }

    fun loadPreset(id: String) {
        val preset = _editor.value.presets.firstOrNull { it.id == id } ?: return
        pushUndo()
        update {
            it.copy(
                waypoints = preset.waypoints,
                speedKmh = preset.speedKmh,
                mode = preset.mode,
                jitterMeters = preset.jitterMeters,
                targetDistanceMeters = preset.targetDistanceMeters,
                selectedPresetId = preset.id,
                message = "已载入「${preset.name}」",
            )
        }
    }

    fun deletePreset(id: String) {
        val list = repo.deletePreset(id)
        update {
            it.copy(
                presets = list,
                selectedPresetId = if (it.selectedPresetId == id) null else it.selectedPresetId,
                message = "已删除预设",
            )
        }
    }

    fun renamePreset(id: String, newName: String) {
        val preset = _editor.value.presets.firstOrNull { it.id == id } ?: return
        val list = repo.upsertPreset(preset.copy(name = newName))
        update { it.copy(presets = list) }
    }

    /** 导出为 JSON 文本，便于备份 / 分享给别人。 */
    fun exportJson(): String {
        val s = _editor.value
        val preset = RoutePreset(
            name = "导出路线",
            waypoints = s.waypoints,
            speedKmh = s.speedKmh,
            mode = s.mode,
            jitterMeters = s.jitterMeters,
            targetDistanceMeters = s.targetDistanceMeters,
        )
        return preset.toJson().toString(2)
    }

    /**
     * 从 JSON 导入路线预设；支持单条对象或以数组形式导出的多条。
     * @return 是否成功导入
     */
    fun importJson(text: String): Boolean {
        if (text.isBlank()) {
            update { it.copy(message = "内容为空") }
            return false
        }
        val parsed = runCatching {
            when {
                text.trimStart().startsWith("[") -> {
                    val arr = org.json.JSONArray(text)
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { RoutePreset.fromJson(it) }
                    }
                }

                else -> listOf(RoutePreset.fromJson(org.json.JSONObject(text)))
            }
        }.getOrNull()

        val valid = parsed?.filter { it.waypoints.size >= 2 }.orEmpty()
        if (valid.isEmpty()) {
            update { it.copy(message = "导入失败：需要至少 2 个坐标点") }
            return false
        }

        val list = repo.loadPresets().toMutableList()
        valid.forEach { preset ->
            val imported = preset.copy(id = java.util.UUID.randomUUID().toString())
            val exists = list.indexOfFirst { it.name == imported.name }
            if (exists >= 0) list[exists] = imported else list.add(0, imported)
        }
        repo.savePresets(list)

        val first = list.first()
        update {
            it.copy(
                presets = list,
                waypoints = first.waypoints,
                speedKmh = first.speedKmh,
                mode = first.mode,
                jitterMeters = first.jitterMeters,
                targetDistanceMeters = first.targetDistanceMeters,
                selectedPresetId = first.id,
                message = "已导入 ${valid.size} 条路线",
            )
        }
        return true
    }

    // ---------------- 运行 ----------------

    fun start(context: Context) {
        val s = _editor.value
        if (!s.canRun) {
            update { it.copy(message = "请先在地图上点出至少 2 个坐标点") }
            return
        }
        persistDraft()
        val intent = MockLocationService.buildStartIntent(
            context = context,
            waypoints = s.waypoints,
            speedKmh = s.speedKmh,
            mode = s.mode,
            jitterMeters = s.jitterMeters,
            targetDistanceMeters = s.targetDistanceMeters,
        )
        com.trailrun.mockgps.service.ServiceStarter.start(context, intent)
        update { it.copy(message = null) }
    }

    fun stop(context: Context) {
        MockLocationService.stop(context)
    }

    private inline fun update(block: (EditorState) -> EditorState) {
        val next = block(_editor.value)
        _editor.value = next
        schedulePersist(next)
    }

    // ---------------- 草稿自动保存 ----------------

    private var persistJob: Job? = null
    private var pendingState: EditorState? = null

    /**
     * 防抖落盘。
     *
     * 之前只有 loadPreset / start / undo 等少数几处会保存，而**点选和手绘加的点完全不落盘**，
     * 应用被强杀后路线就丢了。这里把保存挂到所有状态变更上，并延迟 500ms 合并连续变更：
     * 手绘时每个点都会触发 update，不能每次都写磁盘。
     */
    private fun schedulePersist(state: EditorState) {
        pendingState = state
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            // 落盘失败只应导致「本次没保存」，不该崩掉应用。
            // 这个协程在每次状态变更时都会启动，是崩溃面最大的入口。
            try {
                delay(PERSIST_DEBOUNCE_MS)
                val snapshot = pendingState ?: return@launch
                pendingState = null
                repo.saveDraft(
                    snapshot.waypoints,
                    snapshot.speedKmh,
                    snapshot.mode.name,
                    snapshot.jitterMeters,
                    snapshot.targetDistanceMeters,
                )
            } catch (e: Throwable) {
                android.util.Log.w("MainViewModel", "草稿保存失败（本次未保存）", e)
            }
        }
    }

    /** 立即落盘，用于「开始模拟」这种不能等防抖的时刻。 */
    private fun persistDraft() {
        persistJob?.cancel()
        pendingState = null
        val s = _editor.value
        repo.saveDraft(
            s.waypoints,
            s.speedKmh,
            s.mode.name,
            s.jitterMeters,
            s.targetDistanceMeters,
        )
    }

    // ---------------- 撤销栈 ----------------

    private val undoStack = ArrayDeque<List<Waypoint>>()

    /**
     * 记录一次可撤销操作。
     *
     * 之前只有「退一个点」，但手绘一笔会写入几十个点，用户要连按几十次才能撤掉，
     * 体验很差。改成快照式撤销栈后，一笔、一次载入、一次清空都能一步撤销。
     */
    private fun pushUndo() {
        undoStack.addLast(_editor.value.waypoints)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    /** 是否还有可撤销的操作。 */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: run {
            update { it.copy(message = "没有可撤销的操作了") }
            return
        }
        update {
            it.copy(waypoints = previous, selectedPresetId = null, message = "已撤销上一步")
        }
    }

    private companion object {
        const val MAX_UNDO = 40

        /** 草稿落盘防抖窗口。 */
        const val PERSIST_DEBOUNCE_MS = 500L
    }

    /** 预置一条演示路线：以给定坐标为中心生成一个约 350m 的方形环线。 */
    fun loadDemoRoute(centerLat: Double, centerLon: Double) {
        val d = 0.0008 // 约 ±90m
        val pts = listOf(
            Waypoint(centerLat + d, centerLon - d),
            Waypoint(centerLat + d, centerLon + d),
            Waypoint(centerLat - d, centerLon + d),
            Waypoint(centerLat - d, centerLon - d),
        )
        update {
            it.copy(
                waypoints = pts,
                mode = RouteMode.LOOP,
                message = "已生成演示环线（约 350m），可直接开始",
            )
        }
    }

    /** 读取设备最后一次已知位置，用作演示路线的中心。 */
    fun lastKnownCenter(context: Context): Pair<Double, Double>? {
        val fix = LocationHelper.lastKnown(context) ?: return null
        return fix.latitude to fix.longitude
    }

    /**
     * 取一次当前位置：会真正发起一次定位请求，而不是只读缓存。
     * 结果写入 [EditorState.locationFix] 供地图聚焦。
     */
    fun refreshMyLocation(context: Context) {
        if (!LocationHelper.hasPermission(context)) {
            update {
                it.copy(
                    locationFix = null,
                    locationError = "没有定位权限，无法定位到当前位置",
                )
            }
            return
        }
        if (!LocationHelper.isLocationEnabled(context)) {
            update {
                it.copy(
                    locationFix = null,
                    locationError = "系统定位已关闭，请在通知栏或设置里打开「位置信息」",
                )
            }
            return
        }

        LocationHelper.requestCurrent(context) { fix ->
            if (fix == null) {
                update {
                    it.copy(
                        locationFix = null,
                        locationError = "暂时取不到位置，请到空旷处或稍后重试",
                    )
                }
            } else {
                update {
                    it.copy(
                        locationFix = fix,
                        locationError = null,
                        message = if (fix.fromCache) "已按最后一次已知位置定位" else "已定位到当前位置",
                    )
                }
            }
        }
    }

    fun clearLocationError() = update { it.copy(locationError = null) }

    // ---------------- 地点搜索 ----------------

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    /**
     * 搜索/定位要聚焦的坐标。
     *
     * 配一个递增令牌：如果只把坐标当 key，连续两次搜到同一个地点时值不变、
     * StateFlow 不发射，Effect 不会重启，地图就不会移动（点了没反应）。
     */
    private val _focusTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val focusTarget: StateFlow<Pair<Double, Double>?> = _focusTarget.asStateFlow()

    private val _focusNonce = MutableStateFlow(0)
    val focusNonce: StateFlow<Int> = _focusNonce.asStateFlow()

    private var searchJob: Job? = null

    private fun requestFocus(lat: Double, lon: Double) {
        _focusTarget.value = lat to lon
        _focusNonce.value = _focusNonce.value + 1
    }

    fun setSearchQuery(text: String) {
        _search.value = _search.value.copy(query = text, error = null)
    }

    /** 按关键词搜索；会取消上一次未完成的搜索，避免结果乱序。 */
    fun runSearch(nearLat: Double? = null, nearLon: Double? = null) {
        val q = _search.value.query.trim()
        if (q.isEmpty()) return

        searchJob?.cancel()
        _search.value = _search.value.copy(searching = true, error = null)

        searchJob = viewModelScope.launch {
            // PlaceSearch 内部已把网络异常转成 Outcome.Failure，但解析阶段仍可能抛，
            // 这里统一兜底：搜索失败最多是提示错误，不该崩掉应用。
            val outcome = try {
                PlaceSearch.search(q, nearLat, nearLon)
            } catch (e: Throwable) {
                android.util.Log.w("MainViewModel", "搜索失败", e)
                PlaceSearch.Outcome.Failure(
                    "搜索出错：${e.javaClass.simpleName}"
                )
            }
            when (outcome) {
                is PlaceSearch.Outcome.Success -> {
                    _search.value = _search.value.copy(
                        searching = false,
                        results = outcome.places,
                        error = if (outcome.places.isEmpty()) "没有找到「$q」，换个关键词试试" else null,
                    )
                }

                is PlaceSearch.Outcome.Failure -> {
                    _search.value = _search.value.copy(
                        searching = false,
                        results = emptyList(),
                        error = outcome.message,
                    )
                }
            }
        }
    }

    /** 选中一个搜索结果：把地图移过去（是否加点由界面决定）。 */
    fun focusPlace(place: PlaceSearch.Place) {
        requestFocus(place.latitude, place.longitude)
    }

    /** 界面完成聚焦后调用，避免这个值残留导致地图被反复拉回。 */
    fun consumeFocusTarget() {
        _focusTarget.value = null
    }

    /** 把搜索结果直接作为路线的一个点。 */
    fun addPlaceAsWaypoint(place: PlaceSearch.Place) {
        addWaypointByCoordinate(place.latitude, place.longitude, place.name)
        requestFocus(place.latitude, place.longitude)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    /** 把地图定位到设备当前位置。 */
    fun locateMe(context: Context) {
        refreshMyLocation(context)
    }
}
