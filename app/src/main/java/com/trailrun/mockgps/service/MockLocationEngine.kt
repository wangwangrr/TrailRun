package com.trailrun.mockgps.service

import android.annotation.SuppressLint
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.trailrun.mockgps.core.SimFix

/**
 * 通过系统 test provider 注入位置。
 *
 * ## 为什么要挂**一组** provider，而不是只挂 gps
 *
 * Android 的位置有多个来源，App 读哪个各凭本事：
 *
 *   - `gps`     —— 纯卫星定位。高德地图、小米运动健康读的是它；
 *   - `network` —— 网络定位（WiFi / 基站）；
 *   - `fused`   —— 融合定位。系统组件与各家定位 SDK 常常优先用它；
 *   - `passive` —— 被动接收其他 App 触发的定位。
 *
 * 早先这里只挂了 `gps` 一个，结果就是「有的 App 有效、有的无效」：
 * 高德能读到模拟位置，而**步道乐跑这类读融合定位的 App 拿到的仍是真实位置** ——
 * 看起来像「被检测出模拟位置」，其实是它读的那个来源根本没被接管。
 *
 * 现在逐个挂载，[push] 时每个 provider 各写一遍，无论对方读哪个都是模拟值。
 *
 * ## 未 Root 设备的工作原理
 *
 *   1. 手机「开发者选项 → 选择模拟位置信息应用」选中本应用；
 *   2. 本应用才能调用 [LocationManager.addTestProvider]；
 *   3. 之后向这些 provider 写入的位置会作为「真实定位」分发给其他应用。
 *
 * 未被选中时 [attach] 会抛 SecurityException，由上层提示用户去设置。
 *
 * ## API 说明（编译期已核实）
 *
 *   - API 31+ 有 `addTestProvider(String, ProviderProperties)`；
 *   - API 30 及以下只有已废弃的 10 参数版本；
 *   - 三种重载里**没有**「4 个 Boolean + 3 个 Boolean + ProviderProperties」的组合。
 */
class MockLocationEngine(private val locationManager: LocationManager) {

    /**
     * 已经成功挂载的 provider。
     *
     * 用列表而不是单个名字：不同 ROM 支持的 provider 不一样，
     * 挂不上某一个（比如设备上根本没有 `fused`）不该影响其余的。
     */
    private val mounted = mutableListOf<String>()

    /** 是否已成功挂载（即已被选为模拟位置应用）。 */
    val isAttached: Boolean get() = mounted.isNotEmpty()

    /** 当前挂上了哪些 provider，供诊断显示。 */
    fun mountedProviders(): List<String> = mounted.toList()

    /**
     * 挂载全部可用的模拟 provider。
     *
     * @throws SecurityException 未被选为「模拟位置信息应用」，
     *   且**一个 provider 都没挂上**时抛出。
     *   部分 ROM 只对某些 provider 抛权限异常，所以要试完一整轮再决定：
     *   只要有一个挂上了，就认为授权有效。
     */
    @SuppressLint("WrongConstant", "Deprecated")
    fun attach() {
        if (isAttached) return
        mounted.clear()

        var denied: SecurityException? = null

        for (name in CANDIDATE_PROVIDERS) {
            try {
                mount(name)
                mounted.add(name)
            } catch (e: SecurityException) {
                // 权限不够。记下第一个继续试别的 ——
                // 有些 ROM 对 `fused` 之类会单独抛，不代表整体没授权。
                if (denied == null) denied = e
                Log.w(TAG, "provider $name 无权限：${e.message}")
            } catch (e: Throwable) {
                // 该 provider 在本设备上不存在 / 被系统占用 / 属性不受支持 —— 跳过。
                Log.w(TAG, "provider $name 挂载失败，跳过：${e.javaClass.simpleName} ${e.message}")
            }
        }

        if (mounted.isEmpty() && denied != null) throw denied
        lastMounted = mounted.toList()
        Log.i(TAG, "已挂载 provider：$mounted")
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant", "Deprecated")
    private fun mount(name: String) {
        // 先清掉可能残留的同名 provider（上一次异常退出留下的）
        runCatching { locationManager.removeTestProvider(name) }

        // 参数逐项对齐影梭（ZCShou/GoGoGo）。这不是照抄形式，而是**语义**：
        // 这些属性决定系统用 Criteria 匹配 provider 时能不能选中它。
        //
        //   - gps：requiresSatellite=**true**、accuracy=FINE、power=HIGH
        //     —— 伪装成「需要卫星的真实 GPS」。若设成 false，
        //     一个用 getBestProvider(要求卫星/高精度) 找定位源的 App 就匹配不上它。
        //   - network：requiresNetwork=true、requiresCell=true、accuracy=COARSE、power=LOW
        //     —— 与真实网络定位的属性一致。
        //
        // ⚠️ 这里**故意用已废弃的 10 参数重载**，而不是 API 31 的
        // `addTestProvider(String, ProviderProperties)` —— 因为影梭就是这么做的，
        // 而它在本机上是「已知可用」的那份实现。
        // 两个重载在 AOSP 里最终构造出的 `ProviderProperties` 是同一个东西，
        // 但既然目标是「和影梭逐项一致」，就没有理由保留一个多余的差异点：
        // 这次排查已经因为「以为某个差异无所谓」反复绕了弯路。
        //
        // 参数（gps / network 两套，就是影梭的值）：
        //              requiresNetwork requiresSatellite requiresCell cost altitude speed bearing power      accuracy
        //   gps             false            true          false    false  true    true   true   HIGH(3)    FINE(1)
        //   network         true             false          true     true  true    true   true   LOW(1)     COARSE(2)
        val isGps = name == LocationManager.GPS_PROVIDER

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationManager.addTestProvider(
                name,
                !isGps, // requiresNetwork
                isGps,  // requiresSatellite
                !isGps, // requiresCell
                !isGps, // hasMonetaryCost
                true,   // supportsAltitude
                true,   // supportsSpeed
                true,   // supportsBearing
                if (isGps) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW,
                if (isGps) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE,
            )
        } else {
            locationManager.addTestProvider(
                name,
                !isGps,
                isGps,
                !isGps,
                !isGps,
                true,
                true,
                true,
                if (isGps) Criteria.POWER_HIGH else Criteria.POWER_LOW,
                if (isGps) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE,
            )
        }

        locationManager.setTestProviderEnabled(name, true)
    }

    /**
     * 推送一帧位置给系统。
     *
     * 每个 provider 都要写一遍，且 [Location] 的 provider 名必须与之对应 ——
     * 名字对不上系统会直接忽略这一帧。
     */
    fun push(fix: SimFix) {
        if (mounted.isEmpty()) return

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        for (name in mounted) {
            val location = buildLocation(name, fix, now, elapsed)
            runCatching { locationManager.setTestProviderLocation(name, location) }
                .onFailure { Log.w(TAG, "写入 $name 失败：${it.javaClass.simpleName}") }
        }
    }

    private fun buildLocation(name: String, fix: SimFix, now: Long, elapsedNanos: Long): Location {
        val location = Location(name).apply {
            latitude = fix.latitude
            longitude = fix.longitude
            altitude = fix.altitude
            accuracy = fix.accuracyMeters
            speed = fix.speedMps.toFloat()
            bearing = fix.bearingDeg.toFloat()
            time = now
            elapsedRealtimeNanos = elapsedNanos
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 1.0f
                speedAccuracyMetersPerSecond = 0.4f
                bearingAccuracyDegrees = 0.5f
            }
            // 伪造卫星数。影梭（ZCShou/GoGoGo）往位置里塞了 `satellites=7`，
            // 本项目照做 —— 某些 App 或其定位 SDK 会读这个 extras 来判断
            // 「这是不是一次真实 GPS 定位」。7 是照抄影梭的经验值。
            extras = android.os.Bundle().apply { putInt("satellites", 7) }
        }
        return location
    }

    /**
     * ## 这里原先有一段反射代码，1.2.8 删掉了
     *
     * 它用来尝试抹掉位置的「模拟」标志（`Location.setMock(false)`，API 31 之前叫
     * `setIsFromMockProvider`）—— 当时的猜测是：某些打卡类 App 检查这个标志，
     * 发现是模拟位置就丢弃、回落到自己的定位。
     *
     * 删除的依据是**真机数据，不是判断**：
     *
     *   1. 诊断显示全部 provider 都被标成 `[模拟]`，而模拟位置在目标 App 里
     *      **已经能正常生效**了 —— 说明对方根本不看这个标志，这个猜测是错的；
     *   2. 反射调用虽然「成功」，读回来仍然是 `[模拟]`：
     *      `setTestProviderLocation()` 是跨 binder 的，系统收到后**强制**重新打标记，
     *      客户端无论如何改不掉；
     *   3. 代价却是实打实的：`getDeclaredMethod` + `invoke` 每个 provider 每帧各跑一次，
     *      10 Hz × 2 个 provider = **每秒 20 次反射查找**，全程零收益。
     *
     * 也就是说，删掉它同时满足两件事：去掉一处纯开销，以及不再做
     * 「试图把模拟位置伪装成真实定位」这件事 —— 那既没用，也不是本项目该做的。
     *
     * ⚠️ 结论仍然要留着，因为它是最容易被误解的一点：
     * **免 root 的模拟位置必定带 `[模拟]` 标记，改不掉。**
     * 任何做基本校验的应用都能据此识别出来。
     */

    /** 卸载全部 provider，恢复真实定位。漏掉任何一个都会让假位置残留。 */
    fun detach() {
        for (name in mounted) {
            runCatching { locationManager.setTestProviderEnabled(name, false) }
            runCatching { locationManager.removeTestProvider(name) }
        }
        mounted.clear()
    }

    /**
     * 把已挂载的 provider 全部**重新挂一遍**（先 remove 再 add），不等价于什么都不做。
     *
     * ## 为什么需要它 —— 这是用户实测出来的操作，不是推测
     *
     * 真机反馈的固定现象：
     *
     *   - 先开始模拟、再打开校园跑 → **不生效**
     *   - 校园跑已经在运行时，把模拟**停止再开始**、切回校园跑 → **生效**
     *   - 之后再切回本应用、又切回校园跑 → 再次失效
     *
     * 也就是说「重新挂载 provider」这个动作本身能让对方开始读到模拟值。
     * 具体为什么，我没能从系统行为上确认（写在这里的是事实，不是解释）——
     * 但既然它是一条被验证有效的操作，就把它自动化，而不是继续猜机制。
     *
     * ## 与 [detach] 的区别
     *
     * [detach] 是结束模拟，会清空 [mounted]；这里保持挂载状态不变，只刷新注册。
     * 单个 provider 失败不影响其余，也绝不抛出 —— 它跑在推送循环里。
     */
    fun remount() {
        if (mounted.isEmpty()) return
        for (name in mounted.toList()) {
            runCatching { mount(name) }
                .onFailure { Log.w(TAG, "重新挂载 $name 失败：${it.javaClass.simpleName}") }
        }
        Log.i(TAG, "已重新挂载 provider：$mounted")
    }

    companion object {
        private const val TAG = "MockLocationEngine"

        /**
         * 依次尝试挂载的 provider —— **与影梭（ZCShou/GoGoGo）保持一致**。
         *
         * 曾经这里挂了四个（gps / network / passive / fused），想法是「覆盖越全越好」，
         * 但那在真机上是无效的，而影梭只挂 gps + network 却有效。差异分析后改成照抄它：
         *
         *   - **不接管 `fused`**：系统的融合定位由厂商位置服务提供，直接把它替换成 test provider
         *     可能让依赖系统融合的 App 拿不到预期行为。而只要 gps / network 是假的，
         *     系统融合出来的 fused 自然也是假的 —— 不需要也不能去接管它。
         *   - **不接管 `passive`**：它本就是转发其他 provider 的结果，同样不需要。
         *
         * `gps` 放第一个：权限不足时抛出的也是它（[attach] 取第一个 SecurityException 上报）。
         */
        private val CANDIDATE_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,      // "gps"
            LocationManager.NETWORK_PROVIDER,  // "network"
        )

        /**
         * 最近一次成功挂载的 provider，供界面上的「定位诊断」显示。
         *
         * 为什么要暴露它：挂不上某个 provider 只会写一行 logcat，而用户看不到 logcat。
         * 「模拟位置到底覆盖了哪几个来源」是排查「某些 App 无效」时唯一需要先确认的事实。
         */
        @Volatile
        private var lastMounted: List<String> = emptyList()

        fun lastMountedProviders(): List<String> = lastMounted

        /**
         * 收集定位诊断文本。
         *
         * 摊开两件事：
         *   1. 本应用成功挂载了哪些 provider；
         *   2. **系统里实际存在哪些 provider，各自当前是什么值、是否被标记为模拟**。
         *
         * ⚠️ 读法（这是被真机数据纠正过的，不要再按直觉读）：
         *
         *   - 显示 `[模拟]` **不代表有问题**。免 root 的模拟位置必定带这个标记，
         *     而且改不掉（详见 [buildLocation] 上方那段注释）。目标 App 能不能读到
         *     模拟值，与这个标记无关 —— 实测全部 `[模拟]` 时它照样生效。
         *   - `最后位置：无` 或某个 provider 显示 `[真实]`，**也不代表模拟失效**。
         *     `getLastKnownLocation()` 走的是各 provider 自己的缓存，
         *     而模拟值是实时推给监听者的，两者不是一回事。
         *   - **真正决定成败的是「后台存活心跳」那一段** ——
         *     进程被冻结或被杀的期间，provider 收不到任何模拟值，对方只能读到真实位置。
         */
        @SuppressLint("MissingPermission")
        fun diagnose(locationManager: LocationManager): String = buildString {
            append("【本应用挂载的 provider】\n")
            val mine = lastMounted
            if (mine.isEmpty()) {
                append("  无 —— 还没开始过模拟，或未获得模拟位置权限\n")
            } else {
                mine.forEach { append("  ").append(it).append('\n') }
            }

            append("\n【系统全部 provider 与当前值】\n")
            val all = runCatching { locationManager.allProviders }.getOrNull()
            if (all.isNullOrEmpty()) {
                append("  读取失败（可能缺少定位权限）\n")
                return@buildString
            }
            for (name in all.sorted()) {
                val enabled = runCatching { locationManager.isProviderEnabled(name) }
                    .getOrDefault(false)
                val loc = runCatching { locationManager.getLastKnownLocation(name) }.getOrNull()

                append("  ").append(name.padEnd(9))
                append(if (enabled) "启用 " else "停用 ")
                if (loc == null) {
                    append(" 最后位置：无\n")
                } else {
                    append(
                        String.format(
                            java.util.Locale.US, " %.6f,%.6f  精度%.0fm ",
                            loc.latitude, loc.longitude, loc.accuracy,
                        )
                    )
                    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        loc.isMock
                    } else {
                        @Suppress("DEPRECATION")
                        loc.isFromMockProvider
                    }
                    append(if (mock) "[模拟]" else "[真实]")
                    append('\n')
                }
            }
        }

        /**
         * 检测本应用是否已被选为模拟位置应用。
         * 做法：尝试挂载一次再卸载，**真的挂上了**才算有权限。
         *
         * ⚠️ 这里必须是「永不抛异常」的：
         * 它在 ViewModel 构造期间被调用，而 Kotlin 协程里未捕获的异常会直接崩掉主线程。
         * 各厂商 ROM 的 LocationManager 行为差异极大 —— 除了 SecurityException，
         * 还见过 IllegalStateException、NullPointerException、UnsupportedOperationException
         * （例如 ProviderProperties.Builder().build() 在部分实现上会抛 IllegalStateException）。
         * 因此最后统一兜底 catch (e: Throwable)：检测失败最多是「显示未授权」，
         * 绝不能因此让应用起不来。
         */
        fun canMock(locationManager: LocationManager): Boolean {
            return try {
                val engine = MockLocationEngine(locationManager)
                try {
                    engine.attach()
                    // 关键：不能只看「没抛异常」。改造后 attach 会吞掉单个 provider 的失败，
                    // 必须确认真的挂上了至少一个，否则会把「全都没挂上」误判为已授权。
                    engine.isAttached
                } finally {
                    // 卸载也可能抛，单独包一层，且不能盖掉真正的异常
                    runCatching { engine.detach() }
                }
            } catch (e: SecurityException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: UnsupportedOperationException) {
                false
            } catch (e: Throwable) {
                Log.w(TAG, "canMock 检测失败（按未授权处理）: ${e.javaClass.simpleName} ${e.message}")
                false
            }
        }
    }
}
