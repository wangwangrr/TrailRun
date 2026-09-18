package com.trailrun.mockgps.ui

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 路线规划信息的展示模型：把 [EditorState] 里的原始数值换算成带单位的短字符串。
 *
 * 单独成文件（internal 而非文件私有）是因为它同时被 MainRunScreen 与 RouteBoard 使用，
 * 而「路线板」正是展示这些信息的主要位置。
 */
internal data class PlannerInfo(
    /** 单程距离的值部分。 */
    val distanceValue: String,
    val distanceUnit: String,
    val durationText: String,
    /** 计划总程：设了目标距离时是目标值，否则按路线方式推算（单程 / 往返一圈）。 */
    val plannedValue: String,
    val plannedUnit: String,
) {
    companion object {
        private fun split(meters: Double): Pair<String, String> = if (meters >= 1000) {
            String.format(Locale.US, "%.2f", meters / 1000.0) to "km"
        } else {
            meters.roundToInt().toString() to "m"
        }

        fun of(editor: EditorState): PlannerInfo {
            val (value, unit) = split(editor.singleLegMeters)

            val seconds = editor.plannedSeconds
            val duration = when {
                seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
                seconds > 0 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "--"
            }

            val (plannedValue, plannedUnit) = split(editor.plannedMeters)
            return PlannerInfo(value, unit, duration, plannedValue, plannedUnit)
        }
    }
}
