package com.trailrun.mockgps.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.trailrun.mockgps.MainActivity
import com.trailrun.mockgps.R
import com.trailrun.mockgps.core.RouteMode
import com.trailrun.mockgps.core.RouteSimulator
import com.trailrun.mockgps.core.SimFix
import com.trailrun.mockgps.data.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 轨迹模拟前台服务。
 *
 * 启动时从 Intent 读取路线与参数，之后每 [TICK_MILLIS] 毫秒推一帧位置给系统。
 * 通知栏常驻，锁屏 / 切后台仍然继续跑（前台服务 + 定位类型）。
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var engine: MockLocationEngine? = null
    private var startedAt = 0L

    /** 是否跑到终点自然结束（true 时保留最终成绩，不被 onDestroy 清空）。 */
    private var completedNaturally = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = MockLocationEngine(getSystemService(Context.LOCATION_SERVICE) as LocationManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        val raw = intent?.getStringExtra(EXTRA_ROUTE)
        if (raw.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        tickJob?.cancel()
        tickJob = scope.launch { runRoute(raw) }
        return START_STICKY
    }

    private suspend fun runRoute(raw: String) {
        val config = runCatching { JSONObject(raw) }.getOrNull()
        if (config == null) {
            toast("路线数据损坏，无法启动")
            stopSelf()
            return
        }

        val points = parsePoints(config.optJSONArray("points"))
        if (points.size < 2) {
            toast("至少需要 2 个坐标点")
            stopSelf()
            return
        }

        val speedKmh = config.optDouble("speed", 9.0)
        val modeName = config.optString("mode", RouteMode.SINGLE.name)
        val mode = runCatching { RouteMode.valueOf(modeName) }.getOrDefault(RouteMode.SINGLE)
        val jitter = config.optDouble("jitter", 0.0)
        val targetMeters = config.optDouble("target", 0.0)
        // 设定了目标距离就跑到目标为止
        val stopAtTarget = targetMeters > 0.0

        val sim = RouteSimulator(
            waypoints = points.map { it.first to it.second },
            speedKmh = speedKmh,
            mode = mode,
            jitterMeters = jitter,
        )

        val mock = engine ?: MockLocationEngine(
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ).also { engine = it }

        try {
            mock.attach()
        } catch (e: SecurityException) {
            val msg = "未获得模拟位置权限：请在「开发者选项 → 选择模拟位置信息应用」中选择本应用"
            LiveState.setError(msg)
            toast(msg)
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
            LiveState.setError(e.message ?: "无法启用模拟位置")
            stopSelf()
            return
        }

        startedAt = System.currentTimeMillis()
        var lastTick = System.currentTimeMillis()
        var lastNotify = 0L
        var lastFix = sim.advance(0)

        // 限定接收者：这里的 lambda 有多层隐式接收者，裸写 isActive 无法解析
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            val fix = sim.advance(delta)
            lastFix = fix
            runCatching { mock.push(fix) }
                .onFailure { Log.w(TAG, "push failed: ${it.message}") }

            val elapsed = (now - startedAt) / 1000L

            // 达到目标距离后停止
            val reachedTarget = stopAtTarget && fix.traveledMeters >= targetMeters

            LiveState.update(
                running = !fix.finished && !reachedTarget,
                fix = fix,
                elapsedSeconds = elapsed,
                error = null,
            )

            if (elapsed - lastNotify >= 2) {
                lastNotify = elapsed
                updateNotification(elapsed, fix.traveledMeters, fix.speedMps * 3.6)
            }

            if (fix.finished || reachedTarget) {
                toast(
                    if (reachedTarget) "已达到目标距离，模拟结束"
                    else "轨迹已到达终点，模拟结束"
                )
                break
            }

            delay(TICK_MILLIS)
        }

        val elapsedFinal = (System.currentTimeMillis() - startedAt) / 1000L
        finishRun(lastFix, elapsedFinal)
    }

    /**
     * 收尾。跑到终点时保留最终成绩供界面展示，被用户手动停止则清空。
     */
    private fun finishRun(lastFix: SimFix?, elapsedSeconds: Long) {
        completedNaturally = true
        engine?.detach()
        if (lastFix != null) {
            LiveState.finish(lastFix, elapsedSeconds)
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        engine?.detach()
        // 手动停止 / 系统回收时才清空状态；自然跑完保留成绩
        if (!completedNaturally) LiveState.reset()
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "停止", stopIntent,
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(elapsedSec: Long, meters: Double, speedKmh: Double) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching {
            manager.notify(
                NOTIF_ID,
                buildNotification(
                    "模拟运行中",
                    "${fmtDistance(meters)} · %.1f km/h · %s".format(
                        speedKmh, fmtDuration(elapsedSec),
                    ),
                ),
            )
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.app_name), "正在准备轨迹模拟…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------------- 工具 ----------------

    private fun parsePoints(arr: JSONArray?): List<Pair<Double, Double>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optDouble("lat", Double.NaN) to o.optDouble("lon", Double.NaN))
        }
        return out.filter { !it.first.isNaN() && !it.second.isNaN() }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun fmtDistance(m: Double): String =
        if (m >= 1000) "%.2f km".format(m / 1000.0) else "${m.roundToInt()} m"

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "trailrun_run"
        private const val NOTIF_ID = 1001

        /** 推送间隔，5Hz。多数校园跑 App 采样 1Hz，5Hz 足够平滑且省电。 */
        const val TICK_MILLIS = 200L

        const val ACTION_STOP = "com.trailrun.mockgps.action.STOP"
        const val EXTRA_ROUTE = "extra_route"

        /** 构造启动用的 Intent；路线以 JSON 传递，避免 Bundle 大小限制。 */
        fun buildStartIntent(
            context: Context,
            waypoints: List<Waypoint>,
            speedKmh: Double,
            mode: RouteMode,
            jitterMeters: Double,
            targetDistanceMeters: Double,
        ): Intent {
            val arr = JSONArray()
            waypoints.forEach {
                arr.put(
                    JSONObject().apply {
                        put("lat", it.latitude)
                        put("lon", it.longitude)
                    }
                )
            }
            val config = JSONObject().apply {
                put("points", arr)
                put("speed", speedKmh)
                put("mode", mode.name)
                put("jitter", jitterMeters)
                put("target", targetDistanceMeters)
            }
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROUTE, config.toString())
            }
        }

        const val ACTION_START = "com.trailrun.mockgps.action.START"

        fun stop(context: Context) {
            context.stopService(Intent(context, MockLocationService::class.java))
        }
    }
}
