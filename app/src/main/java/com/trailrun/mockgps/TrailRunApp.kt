package com.trailrun.mockgps

import android.app.Application
import androidx.preference.PreferenceManager
import com.trailrun.mockgps.core.CrashLog
import com.trailrun.mockgps.data.RouteRepository
import org.osmdroid.config.Configuration

class TrailRunApp : Application() {

    /** 全局仓库，ViewModel 从这里取。 */
    val repository: RouteRepository by lazy { RouteRepository(this) }

    override fun onCreate() {
        super.onCreate()

        // 最先安装崩溃记录：后面的任何一步出错都能留下堆栈，
        // 否则真机闪退时只能靠猜。
        runCatching { CrashLog.install(this) }

        // osmdroid 需要在创建 MapView 之前完成初始化。
        // 签名是 load(Context, SharedPreferences)：两个参数都要给。
        // 包一层 runCatching：osmdroid 的初始化只是为了让地图能联网取瓦片，
        // 即使它在某些 ROM 上失败，也绝不能让整个应用起不来。
        runCatching {
            Configuration.getInstance().apply {
                load(this@TrailRunApp, PreferenceManager.getDefaultSharedPreferences(this@TrailRunApp))
                userAgentValue = packageName

                // 下面两个必须在 load() **之后**设置：
                // load() 会用 SharedPreferences 里的值把这两个字段覆盖掉。
                //
                // 下载线程默认只有 2 个。一屏要 8~20 块瓦片，2 个线程排队的结果
                // 就是「地图一块一块地慢慢往外冒」。4 个线程首屏明显更快，
                // 又不至于让镜像站觉得在被刷。
                tileDownloadThreads = 4
                // 读缓存默认 8 个线程，可底下只有一个 SQLite 连接，线程多了反而互相等锁。
                tileFileSystemThreads = 4
            }
        }.onFailure {
            android.util.Log.w("TrailRunApp", "osmdroid 初始化失败（地图可能无法加载）", it)
        }
    }
}
