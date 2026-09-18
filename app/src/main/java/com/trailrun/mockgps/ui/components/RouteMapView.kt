package com.trailrun.mockgps.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.graphics.Point
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.trailrun.mockgps.core.LocationHelper
import com.trailrun.mockgps.core.TileEndpoint
import com.trailrun.mockgps.core.TileProvider
import com.trailrun.mockgps.core.resolveEndpoint
import com.trailrun.mockgps.core.toTileSource
import com.trailrun.mockgps.data.Waypoint
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max
import kotlin.math.min

/** 底图风格。 */
enum class MapStyle { STANDARD, MUTED }

/** 地图交互模式。 */
enum class MapMode {
    /** 点击 / 长按加点。 */
    POINT,

    /** 手指按住拖动，像画图一样一笔画出轨迹。 */
    FREEHAND,
}

/** 与 Compose 之间的桥。 */
internal class MapBridge(var controller: RouteMapController?) {
    var onTap: (Double, Double) -> Unit = { _, _ -> }
    var onLongPress: (Double, Double) -> Unit = { _, _ -> }
    var onMarkerTap: (Int) -> Unit = {}
    var onStrokeFinished: (List<Pair<Double, Double>>) -> Unit = {}
}

/**
 * 支持手绘轨迹的 MapView。
 *
 * 直接拦截 dispatchTouchEvent：手绘模式下把触摸事件全部自己消费掉，
 * 这样地图不会跟着平移，手指划过的轨迹才能被准确记录下来。
 * 非手绘模式一律交给父类，保证拖动、缩放、点选圆点全部正常。
 *
 * 注：手绘时地图中心不动，屏幕上「不动笔、动地图」的体验与画图软件一致。
 */
internal class FreehandMapView(context: Context) : MapView(context) {

    var drawMode: Boolean = false
    var onStrokeStart: () -> Unit = {}
    var onStrokePoint: (Double, Double) -> Unit = { _, _ -> }
    var onStrokeEnd: () -> Unit = {}

    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f

    /** 相邻采样点的最小屏幕间距（像素），避免一个像素记一个点。 */
    private val minStepPx = 5f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!drawMode) return super.dispatchTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                lastX = event.x
                lastY = event.y
                onStrokeStart()
                emit(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (dx * dx + dy * dy >= minStepPx * minStepPx) {
                    lastX = event.x
                    lastY = event.y
                    emit(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    drawing = false
                    onStrokeEnd()
                }
                return true
            }
        }
        return true
    }

    private fun emit(x: Float, y: Float) {
        val gp = projection.fromPixels(x.toInt(), y.toInt()) ?: return
        onStrokePoint(gp.latitude, gp.longitude)
    }
}

/** 点击加点层：重写 onSingleTapUp（见文件末尾注释说明 osmdroid 的坑）。 */
internal class TapOverlay(private val bridge: MapBridge) : Overlay() {
    override fun onSingleTapUp(e: MotionEvent, mapView: MapView): Boolean {
        val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) ?: return false
        bridge.onTap(p.latitude, p.longitude)
        return true
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) ?: return false
        bridge.onLongPress(p.latitude, p.longitude)
        return true
    }
}

/**
 * 绘制中的笔画预览（跟着手指实时显示）。
 *
 * 性能约定：`draw()` 每帧都会被调用，所以这里**一个对象都不许分配**。
 * 之前的写法是每帧新建一个 ArrayList + N 个 PointF，再用 drawLine 画 2N 条线；
 * 一笔 300 个点时等于每帧 300 次分配 + 600 次绘制调用，
 * 结果就是手绘时掉帧、GC 抖动。现在改成复用同一个 Path，
 * 整条笔画只用两次 drawPath（一次描边光晕、一次主线）。
 */
internal class StrokeOverlay : Overlay() {
    private val points = ArrayList<GeoPoint>()
    private val path = android.graphics.Path()
    private val reuse = Point()
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FF4FC3A1")
    }
    private val glow = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#334FC3A1")
    }

    fun begin() {
        points.clear()
    }

    fun add(lat: Double, lon: Double) {
        points.add(GeoPoint(lat, lon))
    }

    fun finish() {
        points.clear()
    }

    /** 取当前笔画的副本（纬度, 经度），供抽稀后写入路线。 */
    fun snapshot(): List<Pair<Double, Double>> =
        points.map { it.latitude to it.longitude }

    fun isEmpty() = points.isEmpty()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (points.size < 2) return
        val proj = mapView.projection
        path.rewind()
        var first = true
        for (gp in points) {
            proj.toPixels(gp, reuse)
            val x = reuse.x.toFloat()
            val y = reuse.y.toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, paint)
    }
}

/** 中心十字准星。 */
internal class CrosshairOverlay(context: Context) : Overlay() {
    var visible: Boolean = false
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#FF35B98A")
    }
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#FF35B98A")
    }
    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.parseColor("#66FFFFFF")
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (!visible) return
        val cx = mapView.width / 2f
        val cy = mapView.height / 2f
        val arm = 14f * density
        val gap = 5f * density
        // 不用 listOf(shadowPaint, paint)：那会每帧分配一个 List。
        for (i in 0..1) {
            val p = if (i == 0) shadowPaint else paint
            canvas.drawLine(cx - arm, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + arm, cy, p)
            canvas.drawLine(cx, cy - arm, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + arm, p)
        }
        canvas.drawCircle(cx, cy, 2.5f * density, dotPaint)
    }
}

/**
 * 地图控制器：把 osmdroid 的命令式 API 收敛到少量方法里，
 * 避免在 Compose 中反复重建 MapView（重建会导致瓦片闪烁与内存上涨）。
 */
class RouteMapController(
    val map: MapView,
    private val context: Context,
) {
    private val bridge: MapBridge = MapBridge(this)
    private val tapOverlay = TapOverlay(bridge)
    private val strokeOverlay = StrokeOverlay()
    private val crosshair = CrosshairOverlay(context)

    private var routeLine: Polyline? = null
    private var routeCasing: Polyline? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private var currentMarker: Marker? = null
    private var centerListener: MapListener? = null

    private val density = context.resources.displayMetrics.density

    /** 路线线宽。之前写死 9px，在 3 倍屏上只有 3dp，又细又看不清。 */
    private val routeLineWidth = 5f * density

    /** 圆点图标的缓存，键是 (颜色, 半径)。 */
    private val iconCache = HashMap<Long, android.graphics.drawable.Drawable>()

    companion object {
        /**
         * 超过这个点数就不再逐点建 Marker。
         *
         * 手点路线一般十几个点，完全不受影响；
         * 手绘轨迹动辄几百点，逐点建 Marker 会直接拖垮帧率。
         */
        const val POINT_MARKER_LIMIT = 40
    }

    /** 是否处于「不联网」模式：只用已缓存的瓦片。 */
    var offlineMode: Boolean = false
        private set

    private val markerColor = Color.parseColor("#FF35B98A")
    private val markerColorEnd = Color.parseColor("#FFFF8A80")

    init {
        map.tag = bridge
        // 顺序即绘制顺序，越靠后越在上层。
        // tapOverlay / crosshair 的 onTouchEvent 均返回 false，不会影响地图手势。
        map.overlays.add(tapOverlay)
        map.overlays.add(crosshair)
        map.overlays.add(strokeOverlay)
    }

    internal fun setTapHandler(block: (Double, Double) -> Unit) { bridge.onTap = block }
    internal fun setMarkerTapHandler(block: (Int) -> Unit) { bridge.onMarkerTap = block }
    internal fun setLongPressHandler(block: (Double, Double) -> Unit) { bridge.onLongPress = block }
    internal fun setStrokeFinishedHandler(block: (List<Pair<Double, Double>>) -> Unit) {
        bridge.onStrokeFinished = block
    }

    internal fun setCrosshairVisible(visible: Boolean) {
        if (crosshair.visible != visible) {
            crosshair.visible = visible
            map.invalidate()
        }
    }

    // ---------------- 手绘 ----------------

    /** 开关手绘模式。开启时关掉多点触控，避免双指手势干扰落笔。 */
    internal fun setFreehandMode(enabled: Boolean, mapView: FreehandMapView) {
        mapView.drawMode = enabled
        mapView.setMultiTouchControls(!enabled)
        tapOverlay.isEnabled = !enabled
        if (!enabled) {
            strokeOverlay.finish()
            map.invalidate()
        }
    }

    internal fun wireFreehand(view: FreehandMapView) {
        view.onStrokeStart = {
            strokeOverlay.begin()
            map.invalidate()
        }
        view.onStrokePoint = { lat, lon ->
            strokeOverlay.add(lat, lon)
            map.invalidate()
        }
        view.onStrokeEnd = {
            val captured = strokeOverlay.snapshot()
            strokeOverlay.finish()
            bridge.onStrokeFinished(captured)
            map.invalidate()
        }
    }

    // ---------------- 路线 ----------------

    /**
     * 路线渲染。
     *
     * 性能要点 —— 这是长路线卡顿的主因：
     * 之前这里是 `points.forEachIndexed { ... Marker(map) ... }`，
     * **每个点都建一个 Marker 覆盖物**。手绘一条几百点的轨迹就会产生几百个 Marker，
     * 而每个 Marker 每帧都要 save / 定位 / setBounds / draw 一个 BitmapDrawable，
     * 几百个就是每帧十几毫秒 —— 平移和缩放必然掉帧，内存也白白多出几 MB 位图。
     *
     * 现在按点数分两档：
     *   - 手点出来的路线（≤ [POINT_MARKER_LIMIT]）行为完全不变，
     *     每个点仍是可点删的 Marker；
     *   - 手绘出来的长轨迹只保留起点 / 终点两个 Marker，中间点交给折线表达 ——
     *     几百个圆点既画不起，视觉上本来也只是一团噪点。
     */
    fun renderRoute(points: List<Waypoint>, fit: Boolean) {
        waypointMarkers.forEach { map.overlays.remove(it) }
        waypointMarkers.clear()
        routeLine?.let { map.overlays.remove(it) }
        routeCasing?.let { map.overlays.remove(it) }
        routeLine = null
        routeCasing = null

        if (points.isNotEmpty()) {
            val geo = points.map { GeoPoint(it.latitude, it.longitude) }

            if (geo.size >= 2) {
                // 白色描边垫在下面：底图本身是浅色的，单色线容易糊进路网里看不清。
                val casing = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = Color.parseColor("#E8FFFFFF")
                    outlinePaint.strokeWidth = routeLineWidth + 3f * density
                    outlinePaint.isAntiAlias = true
                    outlinePaint.style = Paint.Style.STROKE
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
                map.overlays.add(casing)
                routeCasing = casing

                val line = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = markerColor
                    outlinePaint.strokeWidth = routeLineWidth
                    outlinePaint.isAntiAlias = true
                    outlinePaint.style = Paint.Style.STROKE
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
                map.overlays.add(line)
                routeLine = line
            }

            val showEveryPoint = geo.size <= POINT_MARKER_LIMIT
            geo.forEachIndexed { index, gp ->
                val isEndpoint = index == 0 || index == geo.lastIndex
                if (!isEndpoint && !showEveryPoint) return@forEachIndexed

                val marker = Marker(map).apply {
                    position = gp
                    // 起点终点用实心圆点区分，途经点用小一号的浅色点
                    icon = circleIcon(
                        if (index == 0) markerColor
                        else if (index == geo.lastIndex) markerColorEnd
                        else Color.parseColor("#FFB9C6C0"),
                        if (isEndpoint) 11f else 7f,
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = when {
                        geo.size == 1 || index == 0 -> "起点"
                        index == geo.lastIndex -> "终点"
                        else -> "途经点 ${index + 1}"
                    }
                    setOnMarkerClickListener { _, _ ->
                        (map.tag as? MapBridge)?.onMarkerTap?.invoke(index)
                        true
                    }
                }
                map.overlays.add(marker)
                waypointMarkers.add(marker)
            }
        }

        if (fit) fitRoute(points)
        map.invalidate()
    }

    /**
     * 现画一个圆形图标，保证圆点边缘干净、颜色可控。
     *
     * 结果按 (颜色, 半径) 缓存：以前每个 Marker 都新建一张 Bitmap，
     * 撤销 / 重画一次就重造一批，既慢又费内存。
     */
    private fun circleIcon(color: Int, radiusDp: Float): android.graphics.drawable.Drawable {
        val key = (color.toLong() shl 16) or (radiusDp * 10).toLong()
        iconCache[key]?.let { return it }

        val r = (radiusDp * density).toInt().coerceAtLeast(4)
        val size = r * 2
        val bmp = android.graphics.Bitmap.createBitmap(
            size + 4, size + 4, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        val fill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color
        }
        val ring = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            this.color = Color.WHITE
        }
        val cx = (size + 4) / 2f
        canvas.drawCircle(cx, cx, r.toFloat(), fill)
        canvas.drawCircle(cx, cx, r.toFloat() - density, ring)
        val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        iconCache[key] = drawable
        return drawable
    }

    fun fitRoute(points: List<Waypoint>) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(GeoPoint(points[0].latitude, points[0].longitude))
            return
        }

        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        points.forEach {
            north = max(north, it.latitude)
            south = min(south, it.latitude)
            east = max(east, it.longitude)
            west = min(west, it.longitude)
        }

        if (north - south < 1e-5 && east - west < 1e-5) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(GeoPoint(north, east))
            return
        }

        runCatching {
            map.post { map.zoomToBoundingBox(BoundingBox(north, east, south, west), true, 120) }
        }.onFailure {
            map.controller.setZoom(16.0)
            map.controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
        }
    }

    /**
     * 移动视野到指定坐标。
     *
     * 用 `setCenter` 而不是 `animateTo`：跟随模式下这个函数每秒会被调用 5 次，
     * 每次都启动一个滚动动画的话，动画永远播不完，而且动画的每一帧都会回调
     * 地图的 ScrollEvent —— 那是一条 5Hz → 60Hz 放大的反馈链，
     * 结果就是「跟随的时候地图一直在抖」。直接设中心没有这个问题。
     */
    fun moveCameraTo(lat: Double, lon: Double) {
        map.controller.setCenter(GeoPoint(lat, lon))
    }

    fun focusOn(lat: Double, lon: Double, zoom: Double = 17.0) {
        map.controller.setZoom(zoom)
        map.controller.animateTo(GeoPoint(lat, lon))
    }

    fun mapCenter(): Pair<Double, Double> {
        val c = map.mapCenter
        return c.latitude to c.longitude
    }

    /**
     * 监听地图中心变化。不使用 MapListener 的 ScrollEvent 之外的时机，
     * 因为拖动过程中 ScrollEvent 不会持续触发，这里额外在地图 post 里轮询一次。
     */
    fun setCenterListener(onCenterChanged: (Double, Double) -> Unit) {
        centerListener?.let { map.removeMapListener(it) }
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val c = map.mapCenter
                onCenterChanged(c.latitude, c.longitude)
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val c = map.mapCenter
                onCenterChanged(c.latitude, c.longitude)
                return false
            }
        }
        map.addMapListener(listener)
        centerListener = listener
        val c = map.mapCenter
        onCenterChanged(c.latitude, c.longitude)
    }

    // ---------------- 实时定位 ----------------

    fun renderCurrent(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        val gp = GeoPoint(lat, lon)
        val existing = currentMarker
        if (existing == null) {
            val marker = Marker(map).apply {
                position = gp
                icon = circleIcon(Color.parseColor("#FF4A90D9"), 12f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "当前位置"
                setOnMarkerClickListener { _, _ -> true }
            }
            map.overlays.add(marker)
            currentMarker = marker
        } else {
            existing.position = gp
        }
        map.invalidate()
    }

    fun clearCurrent() {
        currentMarker?.let { map.overlays.remove(it) }
        currentMarker = null
        map.invalidate()
    }

    // ---------------- 外观 ----------------

    private var appliedStyle: MapStyle? = null

    /**
     * 底图配色。
     *
     * 两个要点：
     *  1. **只在真正变化时才动手。** 这个函数在每次重组时都会被调用（运行时每秒 5 次），
     *     无条件 `setColorFilter` 会不断让瓦片层失效重绘，白白掉帧。
     *  2. **「清新」滤镜不能抬高黑场。** 旧矩阵带了 +12 的偏移（提亮），
     *     把原本黑色的道路描边、文字一起抬成灰的，整张图对比度下降 ——
     *     这本身就是「不清晰」的观感来源之一。现在只做轻微降饱和，不动黑场。
     */
    fun setMapStyle(style: MapStyle) {
        if (appliedStyle == style) return
        appliedStyle = style
        map.overlayManager.tilesOverlay.setColorFilter(
            if (style == MapStyle.MUTED) mutedFilter else null
        )
        map.setBackgroundColor(Color.parseColor("#F2F7F5"))
        map.invalidate()
    }

    /** 轻微降饱和；对角线系数之外全为 0，保证黑场不动、对比度不丢。 */
    private val mutedFilter = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix(
            floatArrayOf(
                0.94f, 0f, 0f, 0f, 0f,
                0f, 0.97f, 0f, 0f, 0f,
                0f, 0f, 0.96f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    /**
     * 设置底图与联网开关。
     *
     * 分两件事：
     *   1. 首次调用时把 MapView 自带的 provider 换成我们可控的那个
     *      （带可变联网开关），之后就再也不换 provider 了；
     *   2. 镜像变化时用 `setTileSource` 换源 —— `MapTileProviderArray.setTileSource()`
     *      会把新源转发给列表里每个模块（下载器 / 文件缓存 / 离线档案）并清一次缓存。
     */
    fun applyTileSettings(
        provider: TileProvider,
        preferredMirror: TileEndpoint?,
        offline: Boolean,
        context: Context,
    ) {
        ensureProviderInitialized(provider, preferredMirror, context)

        // 端点变化时换源。必须构造一个**新的** ITileSource 对象：
        // `MapTileProviderArray` 缓存 tileSource 实例，只改原对象的字段不会被感知。
        //
        // 这里特意用 `MapView.setTileSource()` 而不是 `tileProvider.setTileSource()`：
        // 前者会额外调用 `updateTileSizeForDensity()`，把 `TileSystem` 的瓦片边长
        // 同步成新源的真实尺寸。少这一步，从 256px 端点切到 512px 端点时
        // TileSystem 仍是 256，512 的图会被硬塞进 256 的格子 —— 比不换还糊。
        val endpoint = resolveEndpoint(preferredMirror)
        val key = "${endpoint.id}-${endpoint.tileSize}"
        if (appliedSourceKey != key) {
            appliedSourceKey = key
            runCatching {
                map.setTileSource(provider.toTileSource(preferredMirror))
            }.onFailure {
                android.util.Log.w("RouteMapController", "切换底图端点失败", it)
            }
        }

        networkGate.offline = offline
        offlineMode = offline
        map.invalidate()
    }

    /**
     * 首次把 MapView 自带的 provider 换成我们可控的那个（带可变联网开关）。
     * 只在第一次调用时执行，之后就再也不换 provider 了。
     */
    private fun ensureProviderInitialized(
        provider: TileProvider,
        preferredMirror: TileEndpoint?,
        context: Context,
    ) {
        if (providerInitialized) return
        providerInitialized = true
        appliedSourceKey = resolveEndpoint(preferredMirror).let { "${it.id}-${it.tileSize}" }

        val previous = map.tileProvider
        val fresh = MapTileProviderBasic(
            // 用 osmdroid 自带的实现，而不是传 null：
            // 传 null 依赖库内部的空值处理，一旦某个版本改成直接使用就会 NPE 崩溃。
            SimpleRegisterReceiver(context),
            networkGate, // 可变的联网开关
            provider.toTileSource(preferredMirror),
            context,
            SqlTileWriter(),
        )
        // setTileProvider 内部会重建 TilesOverlay 并调 OverlayManager.setTilesOverlay()，
        // 绘制层能正确拿到新 provider（已核对字节码），无需额外处理。
        map.setTileProvider(fresh)

        // 换掉默认 provider，避免它的线程池残留
        if (previous != null && previous !== fresh) {
            runCatching { previous.detach() }
        }
    }

    /**
     * 可切换的网络检查。
     * osmdroid 会持续持有这个对象，所以只要改 [offline]，行为立刻生效，无需重建 provider。
     */
    private class NetworkGate : INetworkAvailablityCheck {
        @Volatile
        var offline: Boolean = false

        override fun getNetworkAvailable() = !offline
        override fun getWiFiNetworkAvailable() = !offline
        override fun getCellularDataNetworkAvailable() = !offline

        @Deprecated("接口里已废弃，但必须实现")
        override fun getRouteToPathExists(p: Int) = !offline
    }

    private val networkGate = NetworkGate()
    private var providerInitialized = false

    /** 上一次应用的端点标识（id + 尺寸），用于判断是否需要更换 tileSource。 */
    private var appliedSourceKey: String? = null

    /** 清空瓦片缓存并重新加载。 */
    fun reloadTiles() {
        runCatching { map.tileProvider.clearTileCache() }
        map.invalidate()
    }

    fun onDetach() {
        runCatching { map.onPause() }
        runCatching { map.onDetach() }
        // 缓存的圆点图标是 BitmapDrawable，持有位图；地图销毁后没有理由留着。
        iconCache.clear()
    }
}

private class MapControllerHolder {
    private var value: RouteMapController? = null
    var routeRendered = false
    /** 上一次画进地图的点位签名，用于避免无谓重建。 */
    var renderedSignature: String? = null
    /** 由 Composable 注入，避免持有 Compose 的 lambda 引用造成泄漏。 */
    var onCenter: ((Double, Double) -> Unit)? = null
    fun attach(c: RouteMapController) { value = c }
    fun get(): RouteMapController? = value
}

@Composable
fun RouteMap(
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle.MUTED,
    tileProvider: TileProvider = TileProvider.DEFAULT,
    mode: MapMode = MapMode.POINT,
    points: List<Waypoint>,
    currentLat: Double,
    currentLon: Double,
    running: Boolean,
    follow: Boolean,
    fitToRouteOnStart: Boolean = false,
    showCrosshair: Boolean = false,
    /** 不联网：只用已缓存的瓦片。 */
    offlineMode: Boolean = false,
    /** 指定的 OSM 镜像；null 表示自动（按内置顺序尝试）。 */
    preferredMirror: TileEndpoint? = null,
    /** 取到的新位置；变化时把地图移过去。 */
    locationFix: LocationHelper.Fix? = null,
    /** 搜索选中地点后要聚焦的坐标。 */
    focusTarget: Pair<Double, Double>? = null,
    /** 递增令牌：即使坐标相同也要重新聚焦，并用于触发「已消费」回调。 */
    focusNonce: Int = 0,
    /** 聚焦完成后通知上层清空 focusTarget，避免残留值把地图反复拉回。 */
    onFocusConsumed: () -> Unit = {},
    onMapTap: (Double, Double) -> Unit,
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
    onStrokeFinished: (List<Pair<Double, Double>>) -> Unit = {},
    onMapCenterChanged: (Double, Double) -> Unit = { _, _ -> },
    onMapReady: (RouteMapController) -> Unit,
    onMarkerTap: (Int) -> Unit,
) {
    val holder = remember { MapControllerHolder() }
    val freehand = mode == MapMode.FREEHAND

    // 拿到新定位就移过去。放在这里而不是 AndroidView 的 update 里，
    // 是为了让「定位」这个动作与地图重组解耦，允许多次定位。
    LaunchedEffect(locationFix) {
        val fix = locationFix ?: return@LaunchedEffect
        holder.get()?.focusOn(fix.latitude, fix.longitude, 17.0)
    }

    // 搜索选中地点后移动视野；随后立刻消费掉，避免这个值残留
    LaunchedEffect(focusNonce, focusTarget) {
        val target = focusTarget ?: return@LaunchedEffect
        holder.get()?.focusOn(target.first, target.second, 18.0)
        onFocusConsumed()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val mapView = FreehandMapView(ctx)
            mapView.setTileSource(tileProvider.toTileSource())
            mapView.setMultiTouchControls(true)
            mapView.setTilesScaledToDpi(true)
            mapView.setUseDataConnection(true)
            mapView.minZoomLevel = 4.0
            mapView.maxZoomLevel = 19.0
            mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            mapView.setBackgroundColor(Color.parseColor("#F2F7F5"))

            // MapView 默认中心是 (0,0) 缩放 0（大西洋上的空白海域）。
            // 有权限时按真实位置起步；没有权限时给一个可用的初始视野，
            // 并在权限授予后由 refreshMyLocation + LaunchedEffect(locationFix) 纠正。
            if (LocationHelper.hasPermission(ctx)) {
                val fix = LocationHelper.lastKnown(ctx)
                if (fix != null) {
                    mapView.controller.setZoom(17.0)
                    mapView.controller.setCenter(GeoPoint(fix.latitude, fix.longitude))
                } else {
                    mapView.controller.setZoom(4.0)
                    mapView.controller.setCenter(GeoPoint(34.5, 108.9))
                }
            } else {
                mapView.controller.setZoom(4.0)
                mapView.controller.setCenter(GeoPoint(34.5, 108.9))
            }

            val rc = RouteMapController(mapView, ctx)
            rc.wireFreehand(mapView)
            holder.attach(rc)
            rc.setCenterListener { lat, lon -> holder.onCenter?.invoke(lat, lon) }
            onMapReady(rc)
            mapView
        },
        update = { view ->
            val rc = holder.get() ?: return@AndroidView
            holder.onCenter = onMapCenterChanged
            rc.setMarkerTapHandler { index -> onMarkerTap(index) }
            rc.setTapHandler { lat, lon -> onMapTap(lat, lon) }
            rc.setLongPressHandler { lat, lon -> onMapLongPress(lat, lon) }
            rc.setStrokeFinishedHandler { pts -> onStrokeFinished(pts) }
            rc.setFreehandMode(freehand, view)
            rc.setCrosshairVisible(showCrosshair)
            rc.setMapStyle(style)
            rc.applyTileSettings(tileProvider, preferredMirror, offlineMode, view.context)

            // 只在点位真的变化时重建覆盖物。
            // 之前这里无条件调用 renderRoute，而 update 在每次重组都会跑：
            // 运行时状态 5Hz 刷新 → 每秒重建 5 次全部标记与折线；手绘时几乎每帧重建，
            // 白白掉帧。用点位签名做守卫后，只有真正改动才重建。
            //
            // 签名必须覆盖**全部**点：如果只看首尾，中间点被替换而首尾不变时就不会重绘。
            // 这里用「点数 + 首点 + 末点 + 各点哈希的异或」做一次廉价指纹。
            val signature = buildString {
                append(points.size)
                var hash = 17
                for (p in points) {
                    hash = hash * 31 + (p.latitude.hashCode() xor p.longitude.hashCode())
                }
                append('|').append(hash)
                if (points.isNotEmpty()) {
                    val first = points.first()
                    val last = points.last()
                    append('|').append(first.latitude).append(',').append(first.longitude)
                    append('|').append(last.latitude).append(',').append(last.longitude)
                }
            }
            if (!holder.routeRendered || holder.renderedSignature != signature) {
                val firstDraw = !holder.routeRendered
                rc.renderRoute(points, fit = firstDraw && fitToRouteOnStart)
                holder.routeRendered = true
                holder.renderedSignature = signature
            }
            if (running) {
                rc.renderCurrent(currentLat, currentLon)
                if (follow) rc.moveCameraTo(currentLat, currentLon)
            } else {
                rc.clearCurrent()
            }
            view.invalidate()
        },
    )

    DisposableEffect(Unit) {
        onDispose { holder.get()?.onDetach() }
    }
}
