package com.trailrun.mockgps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.trailrun.mockgps.ui.RootScreen
import com.trailrun.mockgps.ui.theme.TrailRunTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能跑，只是没有通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 整个启动流程包一层：任何一处失败都应该表现为「界面缺一块」，
        // 而不是「点开就闪退」。真机闪退已经出现过一次，代价太高。
        try {
            enableEdgeToEdge()
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "enableEdgeToEdge 失败", e)
        }

        super.onCreate(savedInstanceState)

        runCatching { askNotificationPermission() }
            .onFailure { android.util.Log.w("MainActivity", "申请通知权限失败", it) }

        setContent {
            TrailRunTheme {
                RootScreen(
                    onOpenDeveloperOptions = ::openDeveloperOptions,
                    onOpenAppDetails = ::openAppDetails,
                )
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 跳到开发者选项，方便设置「模拟位置信息应用」。 */
    private fun openDeveloperOptions() {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    /** 跳到本应用详情页（用于授予定位权限、关闭电池优化）。 */
    private fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}
