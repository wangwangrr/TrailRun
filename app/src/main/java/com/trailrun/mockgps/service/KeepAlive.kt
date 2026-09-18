package com.trailrun.mockgps.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 让模拟服务在后台活下来。
 *
 * ## 为什么需要（这是实测出来的结论，不是推测）
 *
 * 1.2.5 版真机测试的后台心跳数据：
 *
 * ```
 *   本次已运行：2 分 25 秒
 *   累计推送：166 帧，实际 1.1 帧/秒（正常应为 20 帧/秒）
 *   最长心跳间隔：137.3 秒
 *     · 22:22:23 起冻结 137.3 秒
 * ```
 *
 * 用户从本应用切到步道乐跑的那一刻开始，进程被 MIUI **冻结**了 137 秒，
 * 直到切回来才解冻 —— 整场 145 秒里只有约 8 秒真正在推送。
 * 那两分多钟里系统 provider 收不到任何模拟值，对方读到的只能是真实定位。
 *
 * 这与「被反作弊识别」的现象完全一样，但原因毫不相干，而且**是可以修的**。
 *
 * ## 两件事
 *
 * 1. **[PARTIAL_WAKE_LOCK]**：让 CPU 在熄屏时不被挂起。
 *    Doze 会把没有唤醒锁的应用挂起，而位置推送是靠我们进程里的定时循环做的 ——
 *    进程一睡，推送就断。
 * 2. **一个 1×1 的透明悬浮窗**：这是与影梭（ZCShou/GoGoGo）最结构性的一处差异 ——
 *    它常驻一个摇杆悬浮窗，而我们什么都没有。
 *    系统对「有可见窗口的进程」会保持更高的优先级，
 *    ROM 的冻结/清理策略通常也会放过这类进程。
 *
 *    ⚠️ 老实说：第 2 条**是推测**。在 AOSP 上前台服务进程本来就不该被冻结，
 *    所以这次冻结是 MIUI 自己的策略，它认不认「有窗口」我没有把握。
 *    它不需要额外代码就能保证无害（拿不到权限就不加窗口），所以先放着。
 *    **真正的解药是系统设置里的「省电策略 → 无限制」**，见 [status] 的提示。
 *
 * ## 用法
 *
 * [start] / [stop] 必须严格配对，[stop] 要保证在服务被销毁时一定执行 ——
 * 唤醒锁泄漏会一直耗电，悬浮窗泄漏会一直在窗口列表里留一个幽灵视图。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"
    private const val WAKE_LOCK_TAG = "TrailRun::MockLocation"

    /** 唤醒锁的兜底超时。进程若异常退出没来得及 [stop]，最多耗电这么久。 */
    private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayView: View? = null

    /** 悬浮窗是否真的加上去了（拿不到权限时为 false）。 */
    @Volatile
    private var overlayShown = false

    fun start(context: Context) {
        acquireWakeLock(context)
        addOverlay(context)
    }

    fun stop(context: Context) {
        removeOverlay(context)
        releaseWakeLock()
    }

    fun isOverlayShown(): Boolean = overlayShown

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
            Log.i(TAG, "已持有 PARTIAL_WAKE_LOCK")
        }.onFailure { Log.w(TAG, "获取唤醒锁失败：${it.javaClass.simpleName}") }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "释放唤醒锁失败：${it.javaClass.simpleName}") }
        wakeLock = null
    }

    private fun addOverlay(context: Context) {
        if (overlayView != null) return
        // 没有悬浮窗权限就直接放弃 —— addView 会抛，且弹不出申请框（后台不能弹）。
        // 断点由界面上的「保活设置」引导用户去授权。
        if (!canDrawOverlays(context)) {
            Log.i(TAG, "无悬浮窗权限，跳过常驻窗口")
            return
        }
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // 1×1、不可点、不可聚焦：占位而已，用户看不见也点不到。
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            val view = View(context)
            wm.addView(view, params)
            overlayView = view
            overlayShown = true
            Log.i(TAG, "常驻窗口已添加")
        }.onFailure {
            overlayShown = false
            Log.w(TAG, "添加常驻窗口失败：${it.javaClass.simpleName} ${it.message}")
        }
    }

    private fun removeOverlay(context: Context) {
        val view = overlayView ?: return
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        }.onFailure { Log.w(TAG, "移除常驻窗口失败：${it.javaClass.simpleName}") }
        overlayView = null
        overlayShown = false
    }

    // ---------------- 状态与设置入口 ----------------

    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 诊断里显示的「系统限制状态」——直接告诉用户还有哪一项没打开。 */
    fun status(context: Context): String = buildString {
        val ignoreOpt = isIgnoringBatteryOptimizations(context)
        val overlay = canDrawOverlays(context)

        append("【系统限制状态】\n")
        append("  电池优化白名单：").append(if (ignoreOpt) "已加入 ✓\n" else "未加入 ✗ ← 关掉它才会停止后台冻结\n")
        append("  悬浮窗权限：").append(
            if (overlay) {
                if (overlayShown) "已授权，常驻窗口已添加 ✓\n" else "已授权，但窗口没加上（见 logcat）\n"
            } else {
                "未授权（可选项，用于抬高进程优先级）\n"
            }
        )
        append("  唤醒锁：").append(if (wakeLock?.isHeld == true) "已持有 ✓\n" else "未持有\n")
        append("  自启动 / 锁定后台：系统设置项，应用无法读取，请自行确认\n")
    }

    /**
     * 把用户送到能真正解决问题的那个设置页。
     *
     * 这些 Intent 各 ROM 都不一样，全部 runCatching 并逐级回退 ——
     * 跳转失败最多是「没跳过去」，绝不能因此崩掉。
     */
    fun openBatterySettings(context: Context): Boolean {
        val pkg = context.packageName
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", pkg, null)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(context),
        )
        return launchFirst(context, candidates)
    }

    fun openOverlaySettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.fromParts("package", context.packageName, null)),
            appDetailsIntent(context),
        )
        return launchFirst(context, candidates)
    }

    /** MIUI 的「自启动」页藏在安全中心里，没有公开的 Settings 常量，只能硬指组件。 */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
            ),
            appDetailsIntent(context),
        )
        return launchFirst(context, candidates)
    }

    fun openAppDetails(context: Context): Boolean =
        runCatching { context.startActivity(appDetailsIntent(context)) }.isSuccess

    private fun appDetailsIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    private fun launchFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val ok = runCatching {
                // 从 Service 启动 Activity 需要 NEW_TASK
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** 供界面判断是否需要提示用户去设置。 */
    fun needsAttention(context: Context): Boolean =
        !isIgnoringBatteryOptimizations(context) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !canDrawOverlays(context))
}
