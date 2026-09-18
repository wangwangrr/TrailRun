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

    /** 当前这一帧，供「重新挂载」后立刻补推一次，避免重挂瞬间出现空档。 */
    @Volatile
    private var lastPushedFix: SimFix? = null

    /** 本应用界面是否在后台（用户切去别的 App 了）。 */
    @Volatile
    private var uiInBackground = false

    private var lastRemountAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = MockLocationEngine(getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        instance = this
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

    /**
     * 重新挂载 provider 并立刻补推一帧。
     *
     * 这个动作对应的是用户实测有效的操作序列（详见 [MockLocationEngine.remount] 的注释）：
     * 校园跑已经在运行时，把模拟「停止再开始」，对方才会读到模拟值。
     * 界面一转到后台就做一次，之后在后台期间周期性做，把那次手动操作自动化掉。
     */
    private fun remountProviders(reason: String) {
        val e = engine ?: return
        if (tickJob?.isActive != true) return
        runCatching {
            e.remount()
            // 重挂之后 provider 是空的，先补一帧进去，避免对方恰好在这一刻取到 null
            lastPushedFix?.let { e.push(it) }
        }.onFailure { Log.w(TAG, "重新挂载失败（$reason）：${it.javaClass.simpleName}") }
        lastRemountAt = System.currentTimeMillis()
        Log.i(TAG, "已重新挂载 provider（$reason）")
    }

    /** 界面切到后台。立刻重挂一次 —— 此刻用户多半正在切去校园跑。 */
    private fun onUiBackground() {
        uiInBackground = true
        remountProviders("界面转后台")
    }

    /** 界面回到前台。停止周期性重挂，别在用户操作时打扰系统。 */
    private fun onUiForeground() {
        uiInBackground = false
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
        var lastHeartbeat = -1L
        var lastFix = sim.advance(0)

        // 心跳的起点：从这里开始记「服务有没有一直在推送」。
        // 它写在 SharedPreferences 里而不是内存里 —— 进程被杀之后，
        // 最后一个心跳必须还留得下来，否则这个功能就没有意义。
        MockHeartbeat.onServiceStart(this)
        // 同时开始保活：唤醒锁 + 一个 1×1 的透明窗口。
        // 实测（1.2.5 心跳数据）不保活时进程会被 MIUI 冻结 137 秒，
        // 那段时间里系统 provider 收不到任何模拟值 —— 这就是「切到校园跑就读到真实位置」的原因。
        KeepAlive.start(this)
        var pushCount = 0L

        // 限定接收者：这里的 lambda 有多层隐式接收者，裸写 isActive 无法解析
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val delta = now - lastTick
            lastTick = now

            val fix = sim.advance(delta)
            lastFix = fix
            lastPushedFix = fix
            runCatching { mock.push(fix) }
                .onFailure { Log.w(TAG, "push failed: ${it.message}") }
            // 每个 provider 各写一遍，所以帧数按 provider 数累计 —— 这里只关心「有没有在动」
            pushCount += mock.mountedProviders().size.coerceAtLeast(1)

            val elapsed = (now - startedAt) / 1000L

            // 每秒落一次心跳（10 Hz 的写频率没必要，只会白白占主线程）
            if (elapsed != lastHeartbeat) {
                lastHeartbeat = elapsed
                MockHeartbeat.onTick(this, pushCount)
            }

            // 后台周期性重挂 provider：把「停止再开始」这个被验证有效的操作自动化。
            // 只在界面不在前台时做 —— 用户正在本应用里操作时没必要打扰系统。
            if (uiInBackground && now - lastRemountAt >= REMOUNT_INTERVAL_MS) {
                remountProviders("后台周期性")
            }

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
        KeepAlive.stop(this)
        MockHeartbeat.onServiceStop(this)
        if (lastFix != null) {
            LiveState.finish(lastFix, elapsedSeconds)
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        engine?.detach()
        KeepAlive.stop(this)
        MockHeartbeat.onServiceStop(this)
        if (instance === this) instance = null
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

        /**
         * 后台期间重新挂载 provider 的间隔。
         *
         * 真正解决问题的是界面转入后台时那一次**立即**重挂（[onUiBackground]），
         * 这个周期只是兜底：万一对方 App 自己重启、或者中途又读不到模拟值了。
         *
         * 30 秒是折中 —— 重挂会让系统 provider 有一瞬间的空档，
         * 对方可能把它看成一次「GPS 信号丢失」。用户手动「停止再开始」时
         * 这种空档是被容忍的（重挂后对方仍在正常工作），所以代价可以接受；
         * 但也正因为它不是零代价，间隔不能压得太短。
         */
        private const val REMOUNT_INTERVAL_MS = 30_000L

        /**
         * 正在运行的模拟服务。
         *
         * 用进程内静态引用而不是 binder：服务与界面在同一个进程，
         * 而这里要做的只是「界面切后台时通知服务重挂一次 provider」，
         * 为此搭一套 bindService 的生命周期管理不值得。
         * [onDestroy] 里会清掉，不留悬挂引用。
         */
        @Volatile
        private var instance: MockLocationService? = null

        /** 供界面在 `onStop` 时调用：用户正要切去别的 App（多半是校园跑）。 */
        fun onUiBackground() = instance?.onUiBackground()

        /** 供界面在 `onStart` 时调用。 */
        fun onUiForeground() = instance?.onUiForeground()

        /**
         * 推送间隔，10 Hz。
         *
         * 原本是 200ms（5 Hz），改成 100ms 是为了对齐影梭（ZCShou/GoGoGo）——
         * 它在 HandlerThread 里 `Thread.sleep(100)` 后推一帧，即 10 Hz。
         *
         * 更高的频率有两个作用：位置更平滑；以及**不给真实定位留下插入的间隙** ——
         * 部分系统组件在 provider 长时间不更新时会回落到其他来源。
         * 代价是耗电略增，对这个用途可以接受。
         */
        const val TICK_MILLIS = 100L

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
