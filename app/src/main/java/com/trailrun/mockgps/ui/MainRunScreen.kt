package com.trailrun.mockgps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailrun.mockgps.core.OSM_ENDPOINTS
import com.trailrun.mockgps.core.OfflineTiles
import com.trailrun.mockgps.core.PlaceSearch
import com.trailrun.mockgps.core.RecordingTileSource
import com.trailrun.mockgps.core.RouteMode
import com.trailrun.mockgps.core.TileDiagnostics
import com.trailrun.mockgps.core.TileEndpoint
import com.trailrun.mockgps.core.TileProvider
import com.trailrun.mockgps.core.resolveEndpoint
import com.trailrun.mockgps.service.KeepAlive
import com.trailrun.mockgps.service.MockHeartbeat
import com.trailrun.mockgps.service.MockLocationEngine
import com.trailrun.mockgps.service.RunStatus
import com.trailrun.mockgps.ui.components.MapMode
import com.trailrun.mockgps.ui.components.MapStyle
import com.trailrun.mockgps.ui.components.RouteMap
import com.trailrun.mockgps.ui.components.RouteMapController
import com.trailrun.mockgps.ui.theme.MintDeep
import com.trailrun.mockgps.ui.theme.MintSoft
import com.trailrun.mockgps.ui.theme.TrailAmber
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(6.0, 8.0, 10.0, 12.0, 15.0)

/**
 * 地图中心的回写间隔。
 *
 * 200ms ≈ 每秒 5 次，和运行时状态的刷新率一致，肉眼看不出延迟；
 * 而拖动地图时的回调频率是它的十几到二十几倍。
 */
private const val CENTER_PUSH_INTERVAL_MS = 200L

@Composable
fun MainRunScreen(
    vm: MainViewModel,
    editor: EditorState,
    status: RunStatus,
    mockReady: Boolean?,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAppDetails: () -> Unit = {},
) {
    val context = LocalContext.current
    val planner = PlannerInfo.of(editor)
    val searchState by vm.search.collectAsStateWithLifecycle()
    val focusTarget by vm.focusTarget.collectAsStateWithLifecycle()
    val focusNonce by vm.focusNonce.collectAsStateWithLifecycle()
    var controller by remember { mutableStateOf<RouteMapController?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showTilePicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showCoordinateInput by remember { mutableStateOf(false) }
    var showMoreTools by remember { mutableStateOf(false) }
    var showLocationDiag by remember { mutableStateOf(false) }
    var showKeepAlive by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }

    // 拖图设点模式：显示中心准星，并用按钮确认落点
    var crosshairMode by remember { mutableStateOf(false) }
    var mapCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // 地图中心回写的节流时间戳。用普通数组而不是 Compose 状态：
    // 它只是节流用的记账，不该引起重组。
    val centerThrottleAt = remember { longArrayOf(0L) }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶部栏 ----------
        // 一行放下「标题 + 状态 + 搜索」，全部在地图之外。
        // 这样做的好处：地图区域不再有任何横跨的悬浮元素，视觉上干净很多，
        // 也不会再出现「搜索框被地图盖住」这类渲染层级问题。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "轨迹跑",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            when {
                status.running -> StatusPill("运行中", TrailGreen)
                mockReady == false -> StatusPill("未授权", TrailCoral)
                mockReady == true -> StatusPill("就绪", TrailGreen)
                else -> StatusPill("检测中", TrailAmber)
            }
            Spacer(Modifier.weight(1f))

            // 搜索做成图标按钮：紧凑、不抢焦点，且与地图工具按钮同一种形态
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                onClick = { showSearch = true },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = "搜索地点",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        // ---------- 未授权提示 ----------
        if (mockReady == false) {
            PermissionNotice(onOpenDeveloperOptions = onOpenDeveloperOptions)
        }

        // ---------- 路线板 ----------
        // 地图上方留出的一块横带。它不是装饰：内容随状态变化，始终回答
        // 「我现在该做什么」——没路线时给引导，有路线时给摘要，运行时给成绩。
        // 这样原先散落在底部面板里的统计信息被收拢到固定位置，底部得以收窄。
        RouteBoard(
            editor = editor,
            status = status,
            planner = planner,
            locationError = editor.locationError,
            onDismissLocationError = { vm.clearLocationError() },
        )

        // ---------- 地图 ----------
        // clipToBounds() 是必须的，不是保险：
        // AndroidView 默认**不裁剪**自己的绘制范围，滚动/重排时瓦片会溢出到父容器之外，
        // 盖住上方的路线板、甚至一直画到状态栏。实机录屏里表现为
        // 「地图顶到状态栏 + 中间一道横缝 + 路线板消失」，随后又自己恢复。
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            RouteMap(
                modifier = Modifier.fillMaxSize(),
                style = editor.mapStyle,
                mode = editor.mapMode,
                points = editor.waypoints,
                currentLat = status.latitude,
                currentLon = status.longitude,
                running = status.running,
                follow = editor.followCamera,
                offlineMode = editor.offlineMap,
                preferredMirror = editor.mapMirror,
                fitToRouteOnStart = vm.restoredDraft,
                showCrosshair = crosshairMode && editor.mapMode == MapMode.POINT,
                locationFix = editor.locationFix,
                focusTarget = focusTarget,
                focusNonce = focusNonce,
                onFocusConsumed = { vm.consumeFocusTarget() },
                onMapTap = { lat, lon -> vm.addWaypoint(lat, lon) },
                onMapReady = { controller = it },
                onMarkerTap = { index -> vm.removeWaypoint(index) },
                onMapLongPress = { lat, lon -> vm.addWaypoint(lat, lon) },
                onStrokeFinished = { pts -> vm.addFreehandStroke(pts) },
                // 地图中心**节流**回写，而不是每次 onScroll 都写。
                //
                // osmdroid 的 MapView.scrollTo() 每次都会回调 MapListener.onScroll，
                // 而拖动地图时手指每移动一次就 scrollBy 一次（最高 120Hz）。
                // 无条件写 Compose 状态 = 每秒上百次整屏重组，每次重组又重跑
                // AndroidView.update → 重画当前位置标记 → 再触发地图重绘。
                // 平移和缩放之所以「卡」，这一条占很大比重。
                //
                // 这个值只用于准星模式下的坐标**读数**；真正落点 / 搜索取景
                // 都是按下按钮的那一刻直接从 controller 读实时值（见下方），
                // 所以节流不会带来精度问题。
                onMapCenterChanged = { lat, lon ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - centerThrottleAt[0] >= CENTER_PUSH_INTERVAL_MS) {
                        centerThrottleAt[0] = now
                        mapCenter = lat to lon
                    }
                },
            )

            // 点选 / 手绘 模式切换（左上角）
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeTab(
                    label = "点选",
                    icon = Icons.Filled.TouchApp,
                    selected = editor.mapMode == MapMode.POINT,
                ) { vm.setMapMode(MapMode.POINT) }
                ModeTab(
                    label = "手绘",
                    icon = Icons.Filled.Draw,
                    selected = editor.mapMode == MapMode.FREEHAND,
                ) { vm.setMapMode(MapMode.FREEHAND) }
            }

            // 地图控件（右上角）。只留 3 个最常用的，其余全部收进「更多」——
            // 5 个按钮竖排仍然会占掉地图右侧一条，3 个刚好。
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapOverlayButton(
                    Icons.AutoMirrored.Filled.Undo,
                    if (editor.waypoints.isEmpty()) "没有可撤销的操作" else "撤销上一步",
                ) { vm.undo() }

                MapOverlayButton(
                    Icons.Filled.MyLocation,
                    "定位到我的位置",
                ) { vm.refreshMyLocation(context) }

                Box {
                    MapOverlayButton(Icons.Filled.MoreVert, "更多工具") { showMoreTools = true }
                    DropdownMenu(
                        expanded = showMoreTools,
                        onDismissRequest = { showMoreTools = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("一键清空路线") },
                            leadingIcon = {
                                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                            },
                            onClick = { showMoreTools = false; vm.clearRoute() },
                        )
                        DropdownMenuItem(
                            text = { Text("底图与离线设置") },
                            leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                            onClick = { showMoreTools = false; showTilePicker = true },
                        )
                        // 定位诊断：看模拟位置到底覆盖了哪几个来源。
                        // 存在的理由很具体 —— 「某些 App 读不到模拟位置」时，
                        // 第一件要确认的事就是这里，而它从界面上完全看不出来。
                        DropdownMenuItem(
                            text = { Text("定位诊断") },
                            leadingIcon = {
                                Icon(Icons.Filled.BugReport, contentDescription = null)
                            },
                            onClick = { showMoreTools = false; showLocationDiag = true },
                        )
                        // 保活设置：实测「切到校园跑就失效」的真正原因是进程被系统冻结
                        // （1.2.5 心跳数据显示冻结了 137 秒），而不是被反作弊识别。
                        // 修它必须改系统设置，所以把入口直接放到这里，不让用户自己去翻。
                        DropdownMenuItem(
                            text = { Text("保活设置") },
                            leadingIcon = {
                                Icon(Icons.Filled.BatterySaver, contentDescription = null)
                            },
                            onClick = { showMoreTools = false; showKeepAlive = true },
                        )
                        // 首次启动有一道同样内容的关卡，这里提供随时重看的入口 ——
                        // 「不可用于作弊」这句话只出现一次是记不住的。
                        DropdownMenuItem(
                            text = { Text("使用须知") },
                            leadingIcon = {
                                Icon(Icons.Filled.Gavel, contentDescription = null)
                            },
                            onClick = { showMoreTools = false; showDisclaimer = true },
                        )
                        DropdownMenuItem(
                            text = { Text("查看整条路线") },
                            leadingIcon = { Icon(Icons.Filled.FitScreen, contentDescription = null) },
                            onClick = {
                                showMoreTools = false
                                controller?.fitRoute(editor.waypoints)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("按坐标输入") },
                            leadingIcon = { Icon(Icons.Filled.PinDrop, contentDescription = null) },
                            onClick = { showMoreTools = false; showCoordinateInput = true },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (editor.mapStyle == MapStyle.MUTED) "地图配色：清新"
                                    else "地图配色：标准"
                                )
                            },
                            leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                            onClick = { showMoreTools = false; vm.toggleMapStyle() },
                        )
                        if (editor.mapMode == MapMode.POINT) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (crosshairMode) "退出拖图设点" else "拖图设点（十字准星）")
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = null)
                                },
                                onClick = {
                                    showMoreTools = false
                                    crosshairMode = !crosshairMode
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("反转起终点") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = { showMoreTools = false; vm.reverseRoute() },
                        )
                        DropdownMenuItem(
                            text = { Text("重新加载地图瓦片") },
                            leadingIcon = {
                                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                            },
                            onClick = { showMoreTools = false; controller?.reloadTiles() },
                        )
                    }
                }
            }

            // 底图署名：使用各家瓦片服务时必须显示
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
            ) {
                Text(
                    // OpenStreetMap 要求在使用其瓦片时显示署名
                    text = "${TileProvider.DEFAULT.label} · ${TileProvider.DEFAULT.attribution}",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 拖图设点：中心准星 + 确认落点按钮 + 实时坐标
            if (crosshairMode) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    mapCenter?.let { (lat, lon) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.6f, %.6f", lat, lon),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            // 落点必须用实时值：地图中心的状态是节流回写的，
                            // 用它落点最多可能差出几十米。
                            val live = controller?.mapCenter()
                                ?: mapCenter
                                ?: return@Button
                            vm.addWaypoint(live.first, live.second)
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrailGreen,
                            contentColor = Color(0xFF06231A),
                        ),
                    ) {
                        Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("在此设点（第 ${editor.waypoints.size + 1} 个）")
                    }
                }
            }

            // 地图上不再放文字提示：操作引导与定位错误都已收进上方的「路线板」，
            // 这里只留署名与悬浮按钮，地图尽量干净。
        }

        // ---------- 底部控制面板 ----------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    // 收紧内边距：之前纵向 12dp、段落之间还各有 10-12dp 间隔，
                    // 面板占了近半屏。现在统一到 10dp 与 8dp。
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // 统计信息已移到上方的「路线板」，这里不再重复，底部因此收窄约 44dp。

                // 本次运行结束后的成绩小结
                if (!status.running && status.finished && status.traveledMeters > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TrailGreen.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "模拟已结束，可以清空路线重新规划",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TrailGreen,
                                )
                                Text(
                                    "总距离 ${fmtDistance(status.traveledMeters)} · " +
                                        "用时 ${fmtClock(status.elapsedSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.stop(context) }) {
                                Text("关闭", color = TrailGreen)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 速度：滑杆与预设数字合并到同一行，省掉一整行文字标签
                CompactSpeedControl(vm = vm, editor = editor)

                Spacer(Modifier.height(8.dp))

                // 路线方式：前缀标签 + 三个 chip，同样只占一行
                CompactModeRow(vm = vm, editor = editor)

                AdvancedSection(
                    vm = vm,
                    editor = editor,
                    expanded = showAdvanced,
                    onToggle = { showAdvanced = !showAdvanced },
                )

                Spacer(Modifier.height(10.dp))
                PrimaryActionButton(
                    running = status.running,
                    enabled = editor.canRun,
                    onStart = { vm.start(context) },
                    onStop = { vm.stop(context) },
                )
            }
        }
    }

    if (showSearch) {
        PlaceSearchDialog(
            state = searchState,
            onQueryChange = { vm.setSearchQuery(it) },
            // 搜索偏置用实时中心，理由同上面的落点按钮。
            onSubmit = {
                val live = controller?.mapCenter()
                vm.runSearch(live?.first ?: mapCenter?.first, live?.second ?: mapCenter?.second)
            },
            onPick = { place ->
                vm.focusPlace(place)
                showSearch = false
            },
            onAddAsWaypoint = { place ->
                vm.addPlaceAsWaypoint(place)
                showSearch = false
            },
            onDismiss = {
                showSearch = false
                vm.clearSearch()
            },
        )
    }

    if (showCoordinateInput) {
        CoordinateDialog(
            onConfirm = { lat, lon ->
                vm.addWaypointByCoordinate(lat, lon)
                controller?.focusOn(lat, lon, 18.0)
                showCoordinateInput = false
            },
            onDismiss = { showCoordinateInput = false },
        )
    }

    if (showTilePicker) {
        OfflineMapDialog(
            offline = editor.offlineMap,
            onToggleOffline = { vm.toggleOfflineMap() },
            currentMirror = editor.mapMirror,
            onPickMirror = { vm.setMapMirror(it) },
            onDismiss = {
                showTilePicker = false
                controller?.reloadTiles()
            },
            mapCenter = mapCenter,
            onOpenSettings = onOpenAppDetails,
        )
    }

    if (showLocationDiag) {
        LocationDiagDialog(
            context = context,
            onDismiss = { showLocationDiag = false },
        )
    }

    if (showKeepAlive) {
        KeepAliveDialog(
            context = context,
            onDismiss = { showKeepAlive = false },
        )
    }

    if (showDisclaimer) {
        // 这里是非关卡模式：用户主动打开的，随手关掉即可，不需要再同意一次。
        DisclaimerDialog(blocking = false, onDismiss = { showDisclaimer = false })
    }
}

/**
 * 保活设置。
 *
 * 这一页是 1.2.5 真机心跳数据直接催生的：模拟服务在用户切到步道乐跑后被系统
 * **冻结了 137 秒**，145 秒的会话里只有约 8 秒真正在推送位置。
 * 冻结期间系统里没有任何模拟值，对方读到的当然是真实定位 ——
 * 这与「被反作弊识别」结果一样、原因完全不同，而且是可以修的。
 *
 * 修它只能改系统设置（应用无权自己给自己开白名单），所以这里把三个入口摆出来，
 * 并且**实时显示哪一项还没打开** —— MIUI 把这些开关藏在三四个不同的地方。
 */
@Composable
private fun KeepAliveDialog(
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    // 每次打开重算一次：用户可能刚从系统设置里回来。
    var battery by remember { mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(context)) }
    var overlay by remember { mutableStateOf(KeepAlive.canDrawOverlays(context)) }

    // 用户点进系统设置、授权、再返回 —— Activity 不一定重建，
    // 所以这里轮询刷新，否则勾选状态会一直停在「未开启」，让人以为没生效。
    LaunchedEffect(Unit) {
        while (true) {
            battery = KeepAlive.isIgnoringBatteryOptimizations(context)
            overlay = KeepAlive.canDrawOverlays(context)
            kotlinx.coroutines.delay(1_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保活设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "为什么需要：切到校园跑这类 App 后，系统会冻结本应用，" +
                        "那段时间模拟位置完全停止推送 —— 对方读到真实位置，" +
                        "看起来像被反作弊识别，其实是后台被冻住了。\n\n" +
                        "实测数据（1.2.5）：一次 2 分 25 秒的模拟里，有 137 秒被冻结。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                KeepAliveRow(
                    title = "① 电池优化白名单",
                    done = battery,
                    required = true,
                    hint = "必做。省电策略改成「无限制」后系统才会停止冻结后台。",
                    onClick = { KeepAlive.openBatterySettings(context); },
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveRow(
                    title = "② 自启动 + 锁定后台",
                    done = false,
                    required = true,
                    hint = "MIUI：安全中心 → 应用管理 → 权限 → 自启动；" +
                        "再在最近任务里下拉本应用卡片加锁。应用读不到这两项状态，需自行确认。",
                    onClick = { KeepAlive.openAutostartSettings(context) },
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveRow(
                    title = "③ 显示悬浮窗",
                    done = overlay,
                    required = false,
                    hint = "可选。常驻一个看不见的 1×1 窗口，抬高进程优先级 —— " +
                        "影梭的摇杆就是同类做法。不确定一定有效，但没有副作用。",
                    onClick = { KeepAlive.openOverlaySettings(context); },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = {
                KeepAlive.openAppDetails(context)
            }) { Text("应用设置页") }
        },
    )
}

@Composable
private fun KeepAliveRow(
    title: String,
    done: Boolean,
    required: Boolean,
    hint: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (done) MintSoft else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title + if (done) "　已开启 ✓" else if (required) "　未开启 ✗" else "　未授权",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (done) MintDeep else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 定位诊断：把「模拟位置覆盖了哪些来源」摊开给用户看。
 *
 * 为什么要做这个：当某个 App（比如校园跑）仍然显示真实位置时，
 * 第一件要确认的事是「模拟位置到底挂上了哪几个 provider」。
 * 这个信息以前只写在 logcat 里，而看不到 logcat 就等于没有。
 *
 * 读到的内容分两段：
 *   1. 本应用挂载成功的 provider；
 *   2. 系统里**全部** provider 的当前值，以及各自是否被标记为 [模拟]。
 *
 * 第二段能给出一个明确判断：如果所有 provider 都已是模拟值却仍无效，
 * 说明对方根本没读系统定位（大概率自建网络定位），免 root 方案就到此为止了。
 */
@Composable
private fun LocationDiagDialog(
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    // 后台定位权限 —— 这一条比上面那些 provider 状态更可能是「切到别的 App 就失效」的真凶。
    // Android 11+ 不能弹窗申请，必须由用户去应用设置里手动选「始终允许」，所以要给跳转入口。
    val hasBackgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    // 打开对话框的那一刻取一次快照：值会随模拟实时变化，
    // 每次重组都重读会让文字跳个不停。
    //
    // 这里把三段拼成**同一份文本**，显示的和「复制」出去的完全一致 ——
    // 之前「复制」只复制了 provider 那一段，权限状态和心跳都丢了，
    // 结果用户贴回来的诊断缺了最关键的信息。
    val text = remember {
        val permLine = if (hasBackgroundLocation) {
            "✓ 后台定位权限：已授予"
        } else {
            "✗ 后台定位权限：未授予 —— 切到其他 App 后位置推送会被系统掐断"
        }

        val heartbeat = runCatching { MockHeartbeat.summary(context) }
            .getOrElse { "  读取失败：${it.javaClass.simpleName}\n" }

        val providers = runCatching {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
                as? android.location.LocationManager
            if (lm == null) "无法访问定位服务" else MockLocationEngine.diagnose(lm)
        }.getOrElse { "读取失败：${it.javaClass.simpleName} ${it.message}" }

        buildString {
            append("【后台定位权限】\n  ").append(permLine).append("\n\n")
            append("【后台存活心跳】\n").append(heartbeat).append("\n\n")
            append(providers)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位诊断") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 权限状态放在最前面：它是最常见、也最容易修的原因
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasBackgroundLocation) {
                        MintSoft
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Text(
                        text = if (hasBackgroundLocation) {
                            "✓ 后台定位权限：已授予"
                        } else {
                            "✗ 后台定位权限：未授予\n切到其他 App（如校园跑）后，位置推送会被系统掐断 —— " +
                                "这会被误判成「对方有反作弊检测」。请点左下角「去授权」，在设置里选「始终允许」。"
                        },
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasBackgroundLocation) {
                            MintDeep
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "怎么用：开始模拟 → 切到目标 App 待 2~3 分钟 → 切回来打开这里（不要先停止模拟）。\n" +
                        "先看「后台存活心跳」那一段，它会直接给结论：\n" +
                        "· 推送一直正常 → 与本应用无关，对方没读系统定位或主动过滤了模拟值；\n" +
                        "· 有大段中断 → 进程被系统冻结/杀了，去「保活设置」里把省电策略改成「无限制」。\n\n" +
                        "下面两段的读法（和直觉相反，别读错）：\n" +
                        "· [模拟] **不代表有问题** —— 免 root 的模拟位置必定带这个标记且改不掉，" +
                        "实测全部 [模拟] 时目标 App 照样能读到模拟值；\n" +
                        "· 「最后位置：无」或某个 provider 显示 [真实] 也不代表失效 —— " +
                        "getLastKnownLocation 读的是各 provider 自己的缓存，而模拟值是实时推给监听者的，两者不是一回事。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    // 把本应用加入电池优化白名单。
                    //
                    // 这是「切到校园跑后模拟失效」最可能的解药：MIUI / 各家 ROM 的
                    // 省电策略会在应用转入后台后冻结甚至杀掉前台服务，
                    // 冻结期间系统 provider 不再收到模拟值，对方拿到的就是真实定位 ——
                    // 现象和「被反作弊识别」一模一样，光看结果分不出来。
                    //
                    // Android 6 起系统自带这个授权页，一次点击即可；
                    // 部分 ROM 会忽略该 Intent，此时回退到电池优化列表页。
                    val pkg = context.packageName
                    val direct = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    ).setData(android.net.Uri.fromParts("package", pkg, null))
                    val fallback = android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    )
                    val ok = runCatching { context.startActivity(direct) }.isSuccess ||
                        runCatching { context.startActivity(fallback) }.isSuccess
                    if (!ok) {
                        android.widget.Toast.makeText(
                            context, "请手动在 设置 → 应用 → 省电策略 中选「无限制」",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text("省电白名单") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                }) { Text("复制") }
                TextButton(onClick = {
                    // 直接跳到本应用的系统设置页。Android 11+ 的后台定位只能在那里改，
                    // 而它藏得比较深（权限 → 位置信息 → 始终允许），所以给个直达入口。
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null),
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("去授权") }
            }
        },
    )
}

/**
 * 地点搜索对话框。
 *
 * 用 Photon（Komoot 开源的 OSM 地理编码服务）：实测国内网络可直连、中文检索效果好，
 * 且不需要任何 API Key。Nominatim 与维基百科在国内实测超时，高德/腾讯的搜索接口要 Key。
 */
@Composable
private fun PlaceSearchDialog(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPick: (PlaceSearch.Place) -> Unit,
    onAddAsWaypoint: (PlaceSearch.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索地点") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text("输入地名，如「北京大学」「奥体中心」") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "清空")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = onSubmit,
                    enabled = state.hasQuery && !state.searching,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("搜索中…")
                    } else {
                        Text("搜索")
                    }
                }

                state.error?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            if (state.isEmptyResult) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    // Photon 用的是 OSM 数据，国内小地名覆盖不如高德，
                                    // 这里给出可用的替代路径，而不是让用户干瞪眼
                                    text = "可以试试：加「省/市」限定（如「北京 奥体中心」）、" +
                                        "用附近的大地名先定位、或改用地图拖拽 + 「按坐标输入」。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }

                if (state.results.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "找到 ${state.results.size} 个结果 · 点一下移动地图，点 ⊕ 加为坐标点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        state.results.forEach { place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(place) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        place.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (place.subtitle.isNotBlank()) {
                                        Text(
                                            place.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { onAddAsWaypoint(place) }) {
                                    Icon(
                                        Icons.Filled.AddCircleOutline,
                                        contentDescription = "加为坐标点",
                                        tint = TrailGreen,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 按经纬度精确添加一个点。 */
@Composable
private fun CoordinateDialog(
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    val lat = latText.trim().toDoubleOrNull()
    val lon = lonText.trim().toDoubleOrNull()
    val valid = lat != null && lon != null &&
        lat in -90.0..90.0 && lon in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按坐标添加点") },
        text = {
            Column {
                Text(
                    "适合从别人那里拿到精确坐标，或想把起点定死在一个位置上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("纬度 latitude") },
                    placeholder = { Text("39.9042") },
                    isError = latText.isNotBlank() && (lat == null || lat !in -90.0..90.0),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("经度 longitude") },
                    placeholder = { Text("116.4074") },
                    isError = lonText.isNotBlank() && (lon == null || lon !in -180.0..180.0),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(lat!!, lon!!) },
                enabled = valid,
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 「底图与离线」对话框。
 *
 * 只保留 OpenStreetMap 之后，这里不再需要切换瓦片源，只保留两件有用的事：
 *   1. 连通性测试 —— 地图空白时唯一能给出真实原因的手段；
 *   2. 离线模式开关 + 缓存体积，让用户在断网环境也能查看已浏览过的区域。
 */
@Composable
private fun OfflineMapDialog(
    offline: Boolean,
    onToggleOffline: () -> Unit,
    currentMirror: TileEndpoint?,
    onPickMirror: (TileEndpoint?) -> Unit,
    onDismiss: () -> Unit,
    mapCenter: Pair<Double, Double>?,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, TileDiagnostics.Result>>(emptyMap()) }

    val cacheText = remember { OfflineTiles.describeCache() }
    val archives = remember { OfflineTiles.archives(context) }

    // 实际生效的端点。currentMirror 为 null（没手动选过）时是列表第一个，
    // 所以单选按钮必须比较这个值，而不是 currentMirror 本身。
    val effective = resolveEndpoint(currentMirror)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("底图与离线") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "底图使用 OpenStreetMap。若地图一片空白，" +
                        "点下面的「测速并选最快」让手机实际请求一次，" +
                        "结果会显示在这里，并自动切到最好用的那个端点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        testing = true
                        results = emptyMap()
                        val (lat, lon) = mapCenter ?: (39.9042 to 116.4074)
                        scope.launch {
                            val best = TileDiagnostics.testAllAndPick(lat, lon) { r ->
                                results = results + (r.endpoint.baseUrl to r)
                            }
                            if (best != null) onPickMirror(best)
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在逐个请求…")
                    } else {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("测速并选最快")
                    }
                }

                Spacer(Modifier.height(6.dp))

                OutlinedButton(
                    onClick = {
                        testing = true
                        results = emptyMap()
                        val (lat, lon) = mapCenter ?: (39.9042 to 116.4074)
                        scope.launch {
                            TileDiagnostics.testAll(lat, lon) { r ->
                                results = results + (r.endpoint.baseUrl to r)
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("只测速，不改设置")
                }

                Spacer(Modifier.height(10.dp))

                // 逐个端点显示结果：应用**只会**用下面选中的那一个端点
                Text(
                    "各端点实测结果（清晰度优先：512px 可用就选它，否则选最快的）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                OSM_ENDPOINTS.forEach { endpoint ->
                    val r = results[endpoint.baseUrl]
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(
                                        when {
                                            r == null -> MaterialTheme.colorScheme.outline
                                            r.ok -> TrailGreen
                                            else -> TrailCoral
                                        },
                                        CircleShape,
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = endpoint.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (r != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (r.ok) "可用" else "失败",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (r.ok) TrailGreen else TrailCoral,
                                )
                            }
                        }
                        if (r != null) {
                            Text(
                                text = r.detail,
                                modifier = Modifier.padding(start = 15.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else TrailCoral,
                            )
                        }
                    }
                }

                if (results.isNotEmpty() && results.values.none { it.ok }) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "所有端点都不可达。排查顺序：① 换网络（切流量 / 换 Wi-Fi）；" +
                                "② 若都是 DNS 失败，说明域名被污染；③ 若都是连接被重置，" +
                                "说明当前网络出口被限制 —— 此时请开启下面的离线模式，查看已缓存的区域。",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // —— 地图诊断 ——
                // 关键值：osmdroid 实际请求了多少次瓦片。
                // 如果地图空白而这里显示 0 次，说明问题在应用内部（联网开关 / 瓦片源 /
                // 缓存），而不是网络 —— 这一条能把排查范围直接砍一半。
                Text(
                    "地图诊断",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "已缓存 ${OfflineTiles.describeCache()} · " +
                        "累计请求 ${RecordingTileSource.requestCount()} 次瓦片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Text(
                        text = RecordingTileSource.dump(),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { RecordingTileSource.clear() },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("清空记录") }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(RecordingTileSource.dump()))
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("复制诊断") }
                }

                Spacer(Modifier.height(14.dp))

                // —— 底图端点 ——
                Text(
                    "底图端点",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "只用 OpenStreetMap 的数据，但可以从不同的镜像站取。" +
                        "「高清 512px」返回的是 512×512 的瓦片：同样的视野和字号，" +
                        "像素密度是 256px 瓦片的两倍，地图明显更清楚 —— 默认用它。" +
                        "这里只会用选中的那一个端点，不再有随机换站，" +
                        "所以哪个能用必须靠上面的实测结果判断。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                OSM_ENDPOINTS.forEach { endpoint ->
                    val r = results[endpoint.baseUrl]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickMirror(endpoint) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            // 选中态直接反映「实际生效的那个端点」：
                            // 没手动选过时 mapMirror 是 null，实际用的是列表第一个，
                            // 所以这里不能拿 currentMirror == null 去判断。
                            selected = effective.baseUrl == endpoint.baseUrl,
                            onClick = { onPickMirror(endpoint) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TrailGreen,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    endpoint.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (r != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (r.ok) "可用" else "失败",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (r.ok) TrailGreen else TrailCoral,
                                    )
                                }
                            }
                            Text(
                                // 没测过就显示「真实像素尺寸 · 说明」，比甩一个 URL 有用得多
                                text = r?.detail ?: endpoint.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r != null && !r.ok) TrailCoral
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleOffline() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "离线模式（不联网）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "完全不请求瓦片，只显示已缓存的区域",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = offline,
                        onCheckedChange = { onToggleOffline() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TrailGreen,
                            checkedTrackColor = TrailGreen.copy(alpha = 0.35f),
                        ),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "离线说明",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append("联网时把要去的地方浏览一遍，瓦片会缓存到本地；")
                                append("之后开启离线模式即可断网查看。\n\n")
                                append("当前缓存：").append(cacheText).append('\n')
                                append("已导入离线包：").append(
                                    if (archives.isEmpty()) "无" else OfflineTiles.describe(archives)
                                ).append('\n')
                                append("离线包目录（可拷入 .mbtiles / .sqlite / .zip）：\n")
                                append(OfflineTiles.directory(context).absolutePath)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onOpenSettings) {
                            Text("打开应用设置（查看存储/权限）")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
@Composable
private fun StatisticsRow(
    editor: EditorState,
    planner: PlannerInfo,
) {
    // 只放「路线本身是什么样」的三项信息。
    // 速度由下面的速度控制行负责显示（那里有更大的数字），已跑距离由进度区负责，
    // 这里再各放一份属于重复信息 —— 之前四格挤在一行也有点糊。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatCell(
            label = "轨迹点",
            value = editor.waypoints.size.toString(),
            unit = "个",
            highlight = if (editor.waypoints.size >= 2) TrailGreen
            else MaterialTheme.colorScheme.onSurface,
        )
        StatCell(
            label = "单程",
            value = planner.distanceValue,
            unit = planner.distanceUnit,
        )
        StatCell(
            label = "预计用时",
            value = planner.durationText,
        )
        StatCell(
            label = "计划总程",
            value = planner.plannedValue,
            unit = planner.plannedUnit,
            highlight = if (editor.targetDistanceMeters > 0) TrailGreen
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 速度控制：一行内放「速度数字 + 滑杆 + 预设」。
 *
 * 之前是「标题行 + 预设 chips 行 + 滑杆行」三行，占了面板近三分之一高度，
 * 而这三者其实表达的是同一件事。合并成一行后信息量不变、高度降到原来的三分之一。
 */
@Composable
private fun CompactSpeedControl(vm: MainViewModel, editor: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前速度：主色强调，一眼能看到
        Column(Modifier.width(66.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%.1f", editor.speedKmh),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TrailGreen,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "km/h",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                text = paceText(editor.speedKmh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Slider(
            // 再钳一次：Compose 的 Slider 对非有限值或越界值会直接抛异常，而它是常驻控件。
            // 数据入口已经清洗过，这里是防止将来有人绕过入口写入的兜底。
            value = sanitizeDouble(editor.speedKmh, 9.0, 2.0..25.0).toFloat(),
            onValueChange = { vm.setSpeed(it.toDouble()) },
            valueRange = 2f..25f,
            steps = 45,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(
                thumbColor = TrailGreen,
                activeTrackColor = TrailGreen,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

        // 预设改成紧凑的数字按钮：比 chip 窄，5 个排下来也放得下
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SPEED_PRESETS.forEach { preset ->
                val selected = kotlin.math.abs(editor.speedKmh - preset) < 0.05
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (selected) MintSoft else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { vm.setSpeed(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = preset.roundToInt().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MintDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 路线方式：前缀标签 + 三个 chip，一行完成。
 * 当前方式的行为说明放进 chip 的选中态里（选中即代表生效），不再单独占一行副标题。
 */
@Composable
private fun CompactModeRow(vm: MainViewModel, editor: EditorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "路线",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(
                label = "单程",
                selected = editor.mode == RouteMode.SINGLE,
                onClick = { vm.setMode(RouteMode.SINGLE) },
            )
            ChoiceChip(
                label = "刷圈",
                selected = editor.mode == RouteMode.LOOP,
                onClick = { vm.setMode(RouteMode.LOOP) },
            )
            ChoiceChip(
                label = "折返",
                selected = editor.mode == RouteMode.BACK_AND_FORTH,
                onClick = { vm.setMode(RouteMode.BACK_AND_FORTH) },
            )
        }
    }
}

@Composable
private fun AdvancedSection(
    vm: MainViewModel,
    editor: EditorState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // 折叠入口做成一行紧凑的整行按钮，而不是 TextButton（后者自带 48dp 最小高度，
    // 会把面板撑高一截）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "高级选项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        // 收起时把关键设置摘出来，省得用户为了确认一个值而展开面板
        Text(
            text = buildString {
                if (editor.jitterMeters > 0) append("抖动 ")
                if (editor.targetDistanceMeters > 0) {
                    append("目标 ${(editor.targetDistanceMeters / 1000).roundToInt()}km")
                }
            }.ifBlank { "" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AnimatedVisibility(visible = expanded) {
        Column {
            // 轨迹抖动
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "轨迹抖动",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "微小随机偏移，让轨迹更像真实 GPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = editor.jitterMeters > 0.0,
                    onCheckedChange = { on -> vm.setJitter(if (on) 1.5 else 0.0) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TrailGreen,
                        checkedTrackColor = TrailGreen.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 跟车视角
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "视角跟随",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "运行时地图自动跟随当前位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = editor.followCamera,
                    onCheckedChange = { vm.setFollowCamera(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TrailGreen,
                        checkedTrackColor = TrailGreen.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 目标距离
            val targetLabel = if (editor.targetDistanceMeters > 0) {
                "目标距离：${fmtDistance(editor.targetDistanceMeters)}"
            } else {
                "目标距离：不限制"
            }
            SectionLabel(text = "目标距离", trailing = targetLabel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, 1000.0, 2000.0, 3000.0, 5000.0).forEach { target ->
                    ChoiceChip(
                        label = if (target == 0.0) "不限" else "${(target / 1000).roundToInt()}km",
                        selected = kotlin.math.abs(editor.targetDistanceMeters - target) < 1.0,
                        onClick = { vm.setTargetDistance(target) },
                    )
                }
            }
            Text(
                text = "达到目标距离后自动停止模拟（需配合刷圈模式）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    running: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Button(
        onClick = { if (running) onStop() else onStart() },
        enabled = running || enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) TrailCoral else TrailGreen,
            contentColor = if (running) Color.White else Color(0xFF06231A),
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (running) "停止模拟" else "开始模拟",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PermissionNotice(onOpenDeveloperOptions: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = TrailCoral.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, TrailCoral.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "还未被选为「模拟位置信息应用」",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TrailCoral,
                )
                Text(
                    "开发者选项 → 选择模拟位置信息应用 → 轨迹跑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenDeveloperOptions) {
                Text("去设置", color = TrailCoral)
            }
        }
    }
}

@Composable
private fun MapOverlayButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.size(42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 点选 / 手绘 的分段按钮。 */
@Composable
private fun ModeTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MintSoft else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MintDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MintDeep else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- 展示辅助 ----------------


private fun fmtDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    else -> "${meters.roundToInt()} m"
}

private fun fmtClock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 由速度换算配速（每公里分钟数）。 */
private fun paceText(kmh: Double): String {
    if (kmh <= 0.0) return "--"
    val minutesPerKm = 60.0 / kmh
    val m = minutesPerKm.toInt()
    val s = ((minutesPerKm - m) * 60).roundToInt()
    return String.format(Locale.US, "%d'%02d\"", m, if (s == 60) 59 else s)
}

