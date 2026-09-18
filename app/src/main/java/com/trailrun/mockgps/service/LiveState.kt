package com.trailrun.mockgps.service

import com.trailrun.mockgps.core.SimFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 模拟运行状态。 */
data class RunStatus(
    val running: Boolean = false,
    val finished: Boolean = false,
    /** 已行进距离（米）。 */
    val traveledMeters: Double = 0.0,
    val singleLegMeters: Double = 0.0,
    val legProgress: Double = 0.0,
    val legCount: Int = 0,
    val speedKmh: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bearingDeg: Double = 0.0,
    val elapsedSeconds: Long = 0,
    val errorMessage: String? = null,
)

/**
 * 界面与前台服务之间的单向状态通道（进程内）。
 * 服务写 [update]，Compose 界面读 [state]。
 */
object LiveState {

    private val _state = MutableStateFlow(RunStatus())
    val state: StateFlow<RunStatus> = _state.asStateFlow()

    internal fun update(
        running: Boolean,
        fix: SimFix?,
        elapsedSeconds: Long,
        error: String? = null,
    ) {
        val prev = _state.value
        if (fix == null) {
            _state.value = prev.copy(running = running, errorMessage = error)
            return
        }
        _state.value = RunStatus(
            running = running,
            finished = fix.finished,
            traveledMeters = fix.traveledMeters,
            singleLegMeters = fix.singleLegMeters,
            legProgress = fix.legProgress,
            legCount = fix.legCount,
            speedKmh = fix.speedMps * 3.6,
            latitude = fix.latitude,
            longitude = fix.longitude,
            bearingDeg = fix.bearingDeg,
            elapsedSeconds = elapsedSeconds,
            errorMessage = error,
        )
    }

    internal fun reset() {
        _state.value = RunStatus()
    }

    /** 跑到终点 / 达到目标距离后保留最终成绩，让界面能显示「本次完成」。 */
    internal fun finish(fix: SimFix, elapsedSeconds: Long, error: String? = null) {
        _state.value = RunStatus(
            running = false,
            finished = true,
            traveledMeters = fix.traveledMeters,
            singleLegMeters = fix.singleLegMeters,
            legProgress = fix.legProgress,
            legCount = fix.legCount,
            speedKmh = 0.0,
            latitude = fix.latitude,
            longitude = fix.longitude,
            bearingDeg = fix.bearingDeg,
            elapsedSeconds = elapsedSeconds,
            errorMessage = error,
        )
    }

    internal fun setError(message: String) {
        _state.value = _state.value.copy(running = false, errorMessage = message)
    }
}
