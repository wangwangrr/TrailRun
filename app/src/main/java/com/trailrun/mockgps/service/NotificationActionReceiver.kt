package com.trailrun.mockgps.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 处理通知栏上的「停止」按钮与滑动删除。 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MockLocationService.ACTION_STOP -> context.stopService(
                Intent(context, MockLocationService::class.java)
            )
        }
    }
}
