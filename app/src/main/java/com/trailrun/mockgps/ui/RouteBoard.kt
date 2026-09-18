package com.trailrun.mockgps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailrun.mockgps.service.RunStatus
import com.trailrun.mockgps.ui.components.MapMode
import com.trailrun.mockgps.ui.theme.MintDeep
import com.trailrun.mockgps.ui.theme.MintSoft
import com.trailrun.mockgps.ui.theme.TrailAmber
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen
import java.util.Locale

/**
 * 地图上方的那块区域。
 *
 * 为什么放在这里、放什么：地图如果从标题栏一直铺到底部面板，视觉上会显得「一整块」、
 * 缺少收口。这里留出一条约 130dp 的横带，内容做成**随状态变化的路况板**，
 * 始终回答「我现在该做什么」：
 *
 *   运行中  → 突出已跑距离与进度（这时候用户最关心这个）
 *   已完成  → 突出成绩小结
 *   有路线  → 总结这条路线（多长、几点、几种跑法、预计多久）
 *   没路线  → 给出下一条操作提示（按当前是点选还是手绘模式给不同指引）
 *
 * 这样它就不是「占用屏幕的装饰」，而是原本散落在别处的信息被收拢到了一个固定位置。
 */
@Composable
internal fun RouteBoard(
    editor: EditorState,
    status: RunStatus,
    planner: PlannerInfo,
    locationError: String? = null,
    onDismissLocationError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 背景：薄荷到白的极浅渐变，比纯白更透气，也不会抢地图的注意力
    //
    // 用 heightIn(min=) 而不是固定 height：定位错误提示出现时内容会多一行，
    // 固定高度会把下面的元素挤出可视区（Compose 的 Column 不会自动滚动）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .background(
                Brush.verticalGradient(
                    listOf(MintSoft.copy(alpha = 0.55f), MaterialTheme.colorScheme.background)
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // 定位失败时优先提示：它会影响后续所有操作，必须出现在视线第一落点
            if (locationError != null) {
                LocationErrorRow(message = locationError, onDismiss = onDismissLocationError)
                Spacer(Modifier.height(8.dp))
            }

            when {
                status.running || (status.finished && status.traveledMeters > 0) ->
                    LiveBoard(status = status)

                editor.waypoints.size >= 2 ->
                    PlannedBoard(editor = editor, planner = planner)

                else ->
                    EmptyBoard(editor = editor)
            }
        }
    }
}

/** 定位失败的紧凑提示行：地图上不再放文字，这类提示统一收在这里。 */
@Composable
private fun LocationErrorRow(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = TrailCoral,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("知道了", color = TrailCoral, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 运行中 / 已完成的成绩板。 */
@Composable
private fun LiveBoard(status: RunStatus) {
    val running = status.running

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (running) "正在模拟" else "本次已完成",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (running) TrailGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (running) fmtClockText(status.elapsedSeconds) else "用时 ${fmtClockText(status.elapsedSeconds)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (status.legCount > 0) {
            Text(
                text = "已完成 ${status.legCount} 趟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(6.dp))

    // 主角：已跑距离用大号字
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = fmtBoardDistance(status.traveledMeters),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = if (running) TrailGreen else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 5.dp)) {
            if (status.singleLegMeters > 0) {
                Text(
                    text = "单程 ${fmtBoardDistance(status.singleLegMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "速度 ${String.format(Locale.US, "%.1f", status.speedKmh)} km/h",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    // 当前这趟的进度
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(status.legProgress.toFloat().coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(if (running) TrailGreen else MaterialTheme.colorScheme.outline)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(status.legProgress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 已有路线、尚未开始的规划板。 */
@Composable
private fun PlannedBoard(editor: EditorState, planner: PlannerInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "路线已就绪",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = modeLabel(editor.mapMode),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))

    // 三项关键信息并排，比之前挤在底部四格里更清楚
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BoardMetric(
            label = "轨迹点",
            value = editor.waypoints.size.toString(),
            unit = "个",
        )
        BoardMetric(
            label = "单程",
            value = planner.distanceValue,
            unit = planner.distanceUnit,
            highlight = TrailGreen,
        )
        BoardMetric(
            label = "预计用时",
            value = planner.durationText,
        )
    }

    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (editor.offlineMap) TrailAmber else TrailGreen,
                    CircleShape,
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = buildString {
                append(routeModeName(editor))
                append(" · 计划总程 ")
                append(planner.plannedValue).append(' ').append(planner.plannedUnit)
                if (editor.jitterMeters > 0) append(" · 已开抖动")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 还没有路线时的引导板。 */
@Composable
private fun EmptyBoard(editor: EditorState) {
    val freehand = editor.mapMode == MapMode.FREEHAND

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MintSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (freehand) Icons.Filled.Draw else Icons.Filled.TouchApp,
                contentDescription = null,
                tint = MintDeep,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (freehand) "在这里画出你的路线" else "点两下定出起点与终点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (freehand) {
                    "按住下面的地图拖动，像画图一样把轨迹画出来，松手即成路线"
                } else {
                    "点一下地图加一个点，第一个是起点、最后一个是终点"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "在上方切换「点选 / 手绘」，或先",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        MiniHint(icon = Icons.Filled.PlayArrow, text = "生成演示路线")
    }
}

/** 顶栏上的小提示条（图标 + 文字），仅作视觉引导，不可点。 */
@Composable
private fun MiniHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TrailGreen, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 板上的一个指标。 */
@Composable
private fun BoardMetric(
    label: String,
    value: String,
    unit: String = "",
    highlight: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = highlight,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

internal fun modeLabel(mode: MapMode): String =
    if (mode == MapMode.FREEHAND) "手绘模式" else "点选模式"

internal fun routeModeName(editor: EditorState): String = when (editor.mode) {
    com.trailrun.mockgps.core.RouteMode.SINGLE -> "单程"
    com.trailrun.mockgps.core.RouteMode.LOOP -> "环线刷圈"
    com.trailrun.mockgps.core.RouteMode.BACK_AND_FORTH -> "折返"
}

internal fun fmtBoardDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    else -> "${meters.toInt()} m"
}

internal fun fmtClockText(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
