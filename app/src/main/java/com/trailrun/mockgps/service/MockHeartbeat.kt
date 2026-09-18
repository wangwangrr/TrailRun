package com.trailrun.mockgps.service

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模拟服务的「后台存活心跳」。
 *
 * ## 为什么需要它
 *
 * 「切到校园跑类 App 后模拟失效」有三种完全不同的原因，靠猜是分不开的：
 *
 *   1. **服务被系统杀掉**：本应用转入后台，MIUI 等 ROM 回收了前台服务，
 *      provider 被卸载，对方读到真实位置。**这是最好修的一种** ——
 *      开自启动、省电策略改成「无限制」、锁定后台任务即可。
 *   2. **服务被冻结**：进程还在，但被放进了 frozen cgroup / Doze，
 *      推送出现几十秒的空档，空档期间对方拿到真实位置。
 *   3. **服务一直正常推送，对方仍然读到真实位置**：
 *      说明对方没读系统定位（自己做网络定位），或主动过滤了 `isMock` 标记 ——
 *      免 root 方案到此为止，没有继续试的余地。
 *
 * 这三种情况在界面上看不出任何区别：通知栏还在，坐标还在跳。
 * 所以这里把「服务到底有没有一直在推送」变成可读的数字，写进 SharedPreferences
 * （而不是内存），这样即使进程被杀，最后一个心跳仍然留得下来。
 *
 * 判读方式见 [summary]：**本次运行时长**对不上用户实际切走的时间，或
 * **最长推送间隔**远大于 0.1 秒，就说明是 1 或 2。
 *
 * ## 代价
 *
 * 每秒一次 `SharedPreferences.apply()`（异步入盘），对耗电的影响可以忽略。
 * 不做「每帧都写」是因为 10 Hz 的写频率既没必要，也会让主线程忙于提交。
 */
object MockHeartbeat {

    private const val PREF = "trailrun_heartbeat"
    private const val KEY_INSTANCE = "instance"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_START_WALL = "start_wall"
    private const val KEY_PUSHES = "pushes"
    private const val KEY_LAST_ELAPSED = "last_elapsed"
    private const val KEY_LAST_WALL = "last_wall"
    private const val KEY_MAX_GAP = "max_gap_ms"
    private const val KEY_FROZEN = "frozen_ms"
    private const val KEY_GAP_LOG = "gap_log"

    /**
     * 超过这个间隔才算「中断」。
     *
     * ⚠️ 门槛**不能**设成 1000ms：心跳本身就是每秒落一次，
     * 那样会把每一次正常心跳都记成「冻结 1.0 秒」，
     * 真出问题的那一次反而被淹没在噪声里（1.2.5 版就踩了这个坑）。
     * 正常情况下心跳间隔约 1.0 秒，所以门槛取 2.5 秒。
     */
    private const val GAP_THRESHOLD_MS = 2_500L

    /** 日志里最多保留几条冻结记录。 */
    private const val GAP_LOG_MAX = 4

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 开始一次模拟时调用（每点一次「开始」+1）—— 用来标记下面的数字属于哪一次会话。
     *
     * ⚠️ 它**不能**用来判断「服务有没有被杀」：被系统杀掉后 `START_STICKY` 拉起的实例
     * 拿不到路线 Intent，会立刻 `stopSelf()`，不会再调到这里。
     * 判断被杀要看的是「最后一次推送」是不是停在了很久以前（[summary] 里会摆出来）。
     */
    fun onServiceStart(context: Context) {
        val p = prefs(context)
        val nowElapsed = SystemClock.elapsedRealtime()
        p.edit()
            .putInt(KEY_INSTANCE, p.getInt(KEY_INSTANCE, 0) + 1)
            .putLong(KEY_START_ELAPSED, nowElapsed)
            .putLong(KEY_START_WALL, System.currentTimeMillis())
            .putLong(KEY_PUSHES, 0L)
            .putLong(KEY_LAST_ELAPSED, nowElapsed)
            .putLong(KEY_LAST_WALL, System.currentTimeMillis())
            .putLong(KEY_MAX_GAP, 0L)
            .putLong(KEY_FROZEN, 0L)
            .putString(KEY_GAP_LOG, "")
            .apply()
    }

    /**
     * 推送心跳。[totalPushes] 是本次服务实例累计推给系统的帧数。
     *
     * 调用方每秒调一次即可（不必每帧）。
     */
    fun onTick(context: Context, totalPushes: Long) {
        val p = prefs(context)
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastElapsed = p.getLong(KEY_LAST_ELAPSED, nowElapsed)
        val gap = nowElapsed - lastElapsed

        val editor = p.edit()
            .putLong(KEY_PUSHES, totalPushes)
            .putLong(KEY_LAST_ELAPSED, nowElapsed)
            .putLong(KEY_LAST_WALL, System.currentTimeMillis())

        if (gap > GAP_THRESHOLD_MS) {
            // 记下这次冻结：起止时刻 + 时长，最多留最近几条。
            val frozenFrom = clock.format(Date(System.currentTimeMillis() - gap))
            val entry = "$frozenFrom 起冻结 ${"%.1f".format(gap / 1000.0)} 秒"
            val old = p.getString(KEY_GAP_LOG, "").orEmpty()
            val merged = (listOf(entry) + old.split('\n').filter { it.isNotBlank() })
                .take(GAP_LOG_MAX)
                .joinToString("\n")
            editor
                .putLong(KEY_MAX_GAP, maxOf(p.getLong(KEY_MAX_GAP, 0L), gap))
                .putLong(KEY_FROZEN, p.getLong(KEY_FROZEN, 0L) + gap)
                .putString(KEY_GAP_LOG, merged)
        }
        editor.apply()
    }

    /** 模拟正常结束（跑到终点或用户手动停止）时清掉「正在运行」的语义。 */
    fun onServiceStop(context: Context) {
        val p = prefs(context)
        // 把「最后一次推送」定在此刻，这样界面上的「最后一次推送」不会一直停在旧值上骗人。
        p.edit()
            .putLong(KEY_LAST_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(KEY_LAST_WALL, System.currentTimeMillis())
            .apply()
    }

    /**
     * 生成给人看的判读文本。
     *
     * 这里**不做任何猜测**，只把数字摆出来并给出对应的结论分支 ——
     * 之前吃过「看到 [模拟] 就以为标志没抹掉」的亏，凡是推断都要有可核对的依据。
     */
    fun summary(context: Context): String {
        val p = prefs(context)
        val instance = p.getInt(KEY_INSTANCE, 0)
        if (instance == 0) {
            return "  还没有运行过模拟，无法判断。\n"
        }

        val now = SystemClock.elapsedRealtime()
        val startElapsed = p.getLong(KEY_START_ELAPSED, now)
        val pushes = p.getLong(KEY_PUSHES, 0L)
        val lastWall = p.getLong(KEY_LAST_WALL, 0L)
        val maxGap = p.getLong(KEY_MAX_GAP, 0L)
        val frozen = p.getLong(KEY_FROZEN, 0L)
        val gapLog = p.getString(KEY_GAP_LOG, "").orEmpty()

        val runSeconds = (now - startElapsed) / 1000L
        val sinceLastPush = if (lastWall > 0) (System.currentTimeMillis() - lastWall) / 1000L else -1L

        // 期望帧率：2 个 provider（gps + network）× 10 Hz。
        // 实际帧率远低于它，就说明有相当一部分时间根本没在推送。
        val expectedPerSecond = 20.0
        val actualPerSecond = if (runSeconds > 0) pushes.toDouble() / runSeconds else 0.0
        val lostSeconds = if (actualPerSecond > 0) {
            (runSeconds * (1 - actualPerSecond / expectedPerSecond)).coerceAtLeast(0.0)
        } else {
            runSeconds.toDouble()
        }

        return buildString {
            append("  这是第 ").append(instance).append(" 次模拟会话\n")

            append("  本次已运行：").append(fmtDuration(runSeconds)).append('\n')
            append("  累计推送：").append(pushes).append(" 帧，实际 ")
                .append("%.1f".format(actualPerSecond)).append(" 帧/秒")
                .append("（正常应为 ").append("%.0f".format(expectedPerSecond))
                .append(" 帧/秒 = 2 个 provider × 10 Hz）\n")
            append("  最后一次推送：")
            if (sinceLastPush < 0) {
                append("无记录\n")
            } else if (sinceLastPush <= 3) {
                append(sinceLastPush).append(" 秒前（刚刚）\n")
            } else {
                append(sinceLastPush).append(" 秒前 —— 已经停了这么久\n")
            }
            append("  最长心跳间隔：").append("%.1f".format(maxGap / 1000.0)).append(" 秒")
            if (frozen > 0) {
                append("，累计中断 ").append("%.1f".format(frozen / 1000.0)).append(" 秒")
            }
            append("（正常约 1.0 秒，超过 2.5 秒才计入中断）\n")
            if (gapLog.isNotBlank()) {
                gapLog.split('\n').forEach { append("    · ").append(it).append('\n') }
            }

            append('\n')
            append(KeepAlive.status(context))
            append('\n')

            // 结论只写在**有依据**的时候。宁可说「看不出来」，也不要给一个猜的结论 ——
            // 之前把「反射调用 setMock 成功」当成「标志已抹除」，就是这么错的一次。
            append("  结论：")
            when {
                lostSeconds >= 5.0 -> {
                    append("后台有大段时间没在推送（约 ")
                        .append("%.0f".format(lostSeconds))
                        .append(" 秒）。这**就是**目标 App 读到真实位置的原因 —— ")
                        .append("那段时间里系统里根本没有模拟值。\n")
                    append("    修：点下面的「保活设置」，把省电策略改成「无限制」；")
                    append("MIUI 还要开「自启动」，并在最近任务里把本应用加锁。")
                }
                sinceLastPush > 10 -> {
                    append("服务已经停止推送 ").append(sinceLastPush)
                        .append(" 秒，多半是被系统杀掉了（通知栏可能已经消失）。\n")
                    append("    修：同上，并确认省电策略为「无限制」。")
                }
                runSeconds < 30 -> {
                    append("本次运行太短，还看不出来。请让它跑够 1 分钟以上再看。")
                }
                else -> {
                    append("推送一直正常，没有中断。\n")
                    append("    那么目标 App 读到真实位置就与本应用无关了：")
                    append("它要么没读系统定位（自己发请求做网络定位），要么主动过滤了模拟标记 —— ")
                    append("免 root 方案到此为止。")
                }
            }
        }
    }

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d 时 %d 分 %d 秒".format(h, m, s) else "%d 分 %d 秒".format(m, s)
    }
}
