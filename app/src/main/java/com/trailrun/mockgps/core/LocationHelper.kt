package com.trailrun.mockgps.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * 取一次设备当前位置。
 *
 * 只用 `getLastKnownLocation` 是不够的：刚开机、刚进空旷处或长时间没开定位时，
 * 该方法经常返回 null，界面就会一直停在默认视野上，用户感受就是「定位不到我」。
 * 所以这里先尝试 `getCurrentLocation`（会真正发起一次定位请求），
 * 失败或超时再退回最后一次已知位置。
 */
object LocationHelper {

    data class Fix(val latitude: Double, val longitude: Double, val fromCache: Boolean)

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun manager(context: Context): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 该 provider 是否已启用（用户可能整体关了定位）。 */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = manager(context) ?: return false
        return runCatching { lm.isLocationEnabled }.getOrDefault(false)
    }

    /**
     * 同步取「最后一次已知位置」，按 网络 → GPS → 被动 的顺序，
     * 并优先返回较新的记录。
     */
    @Suppress("MissingPermission")
    fun lastKnown(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val lm = manager(context) ?: return null

        var best: Location? = null
        for (p in listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (loc.latitude == 0.0 && loc.longitude == 0.0) continue
            val current = best
            if (current == null || loc.time > current.time) best = loc
        }
        val b = best ?: return null
        return Fix(b.latitude, b.longitude, fromCache = true)
    }

    /**
     * 异步取当前位置；拿不到时回落到 [lastKnown]；都没有则回调 null。
     * 回调一定在主线程。
     */
    @Suppress("MissingPermission")
    fun requestCurrent(
        context: Context,
        timeoutMs: Long = 6000,
        onResult: (Fix?) -> Unit,
    ) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val lm = manager(context)
        if (lm == null) {
            onResult(lastKnown(context))
            return
        }

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var finished = false
        val lock = Any()
        // 保存超时任务引用：拿到定位后必须取消它。
        // 之前只用一个布尔量做「只回调一次」的守卫，定时消息本身仍然留在队列里，
        // 每次点定位都会泄漏一条持有 Context 的消息（最长 6 秒），反复点会堆积。
        var timeoutTask: Runnable? = null

        fun finish(fix: Fix?) {
            synchronized(lock) {
                if (finished) return
                finished = true
            }
            timeoutTask?.let { main.removeCallbacks(it) }
            main.post { onResult(fix) }
        }

        timeoutTask = Runnable { finish(lastKnown(context)) }
        main.postDelayed(timeoutTask, timeoutMs)
        // 超时兜底：宁可给缓存位置，也不要让界面一直转圈

        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            finish(lastKnown(context))
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(context),
                ) { loc ->
                    finish(
                        if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                            Fix(loc.latitude, loc.longitude, fromCache = false)
                        } else {
                            lastKnown(context)
                        }
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    provider,
                    object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            finish(Fix(location.latitude, location.longitude, fromCache = false))
                        }

                        override fun onProviderDisabled(p: String) {}
                        override fun onProviderEnabled(p: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
                    },
                    main.looper,
                )
            }
        } catch (e: SecurityException) {
            finish(lastKnown(context))
        } catch (e: IllegalArgumentException) {
            finish(lastKnown(context))
        } catch (e: Throwable) {
            // 各厂商 ROM 的 LocationManager 行为差异很大，统一兜底：
            // 取不到位置最多是不定位，绝不能让应用崩溃。
            android.util.Log.w("LocationHelper", "取当前位置失败", e)
            finish(lastKnown(context))
        }
    }
}
