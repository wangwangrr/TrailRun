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
 * 未 Root 设备的工作原理：
 *   1. 手机「开发者选项 → 选择模拟位置信息应用」选中本应用；
 *   2. 本应用才能调用 [LocationManager.addTestProvider]；
 *   3. 之后向该 provider 写入的位置会作为「真实 GPS」分发给其他应用。
 *
 * 未授权时 [attach] 会抛出 SecurityException，由上层提示用户去设置。
 *
 * API 说明（编译期已核实）：
 *   - API 31+ 有 `addTestProvider(String, ProviderProperties)`；
 *   - API 30 及以下只有已废弃的 10 参数版本 `addTestProvider(String, Boolean x4, Boolean x3, Int, Int)`；
 *   - 三种重载里**没有**「4 个 Boolean + 3 个 Boolean + ProviderProperties」的组合，
 *     早期写成那样会直接编译失败。
 */
class MockLocationEngine(private val locationManager: LocationManager) {

    private var providerName: String? = null
    private var attached = false

    /** 是否已成功挂载 provider（即已被选为模拟位置应用）。 */
    val isAttached: Boolean get() = attached

    /**
     * 挂载 GPS 模拟 provider。
     * @throws SecurityException 未被选为「模拟位置信息应用」时抛出。
     */
    @SuppressLint("WrongConstant", "Deprecated")
    fun attach() {
        if (attached) return
        val name = LocationManager.GPS_PROVIDER

        runCatching { locationManager.removeTestProvider(name) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(true)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            locationManager.addTestProvider(name, props)
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                name,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }

        locationManager.setTestProviderEnabled(name, true)
        providerName = name
        attached = true
    }

    /** 推送一帧位置给系统。 */
    fun push(fix: SimFix) {
        val name = providerName ?: return
        if (!attached) return

        val location = Location(name).apply {
            latitude = fix.latitude
            longitude = fix.longitude
            altitude = fix.altitude
            accuracy = fix.accuracyMeters
            speed = fix.speedMps.toFloat()
            bearing = fix.bearingDeg.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 1.0f
                speedAccuracyMetersPerSecond = 0.4f
                bearingAccuracyDegrees = 0.5f
            }
        }

        locationManager.setTestProviderLocation(name, location)
    }

    /** 卸载 provider，恢复真实 GPS。 */
    fun detach() {
        val name = providerName ?: LocationManager.GPS_PROVIDER
        runCatching { locationManager.setTestProviderEnabled(name, false) }
        runCatching { locationManager.removeTestProvider(name) }
        providerName = null
        attached = false
    }

    companion object {
        private const val TAG = "MockLocationEngine"

        /**
         * 检测本应用是否已被选为模拟位置应用。
         * 做法：尝试挂载一次再卸载，成功即表示有权限。
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
                    true
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
