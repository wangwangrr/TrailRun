package com.trailrun.mockgps.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 启动前台服务的统一入口。
 * Android 12+ 前台服务启动可能被系统拒绝（ForegroundServiceStartNotAllowedException），
 * 这里统一兜底并把错误写进 [LiveState]，避免界面闪退。
 */
object ServiceStarter {

    fun start(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            try {
                context.startService(intent)
            } catch (inner: Exception) {
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "启动被系统拦截，请保持本应用在前台后重试"
                } else {
                    "无法启动模拟：${e.message ?: "未知原因"}"
                }
                LiveState.update(
                    running = false,
                    fix = null,
                    elapsedSeconds = 0,
                    error = hint,
                )
            }
        }
    }
}
