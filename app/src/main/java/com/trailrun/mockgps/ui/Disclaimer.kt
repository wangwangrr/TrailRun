package com.trailrun.mockgps.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 使用须知与免责声明。
 *
 * ## 为什么把它做成一道必须点掉的关卡
 *
 * 这不是走形式的样板文字，核心是一句话：**本应用不可用于作弊，而且技术上也不成立。**
 * 免 root 的模拟位置会被系统强制打上 `Location.isMock = true`，
 * 任何做基本校验的应用都能直接读出来 —— 本项目曾试图用反射抹掉它，
 * 实测无效，那段代码已在 1.2.8 删除（见 `MockLocationEngine.buildLocation` 上方注释）。
 *
 * 也就是说，「能不能作弊」这件事不需要靠道德约束来回答，
 * 系统本身的机制就否定了它。把这一点摆在使用者面前，比含糊的提醒有用。
 *
 * 首次启动时必须点掉 [DisclaimerDialog] 的确认按钮才能进入主界面；
 * 之后可以随时从模拟页的「更多工具 → 使用须知」重新查看。
 */
object Disclaimer {

    private const val PREF = "trailrun_disclaimer"
    private const val KEY_ACCEPTED = "accepted_version"

    /** 改动条款时把它 +1，所有用户会重新看到一次。 */
    private const val CURRENT_VERSION = 1

    const val TITLE = "使用须知与免责声明"

    val BODY: String = """
本应用是一个 Android 定位机制的学习与研究工具 —— 用来了解 LocationManager、
test provider、模拟位置标记这些东西是怎么工作的，以及测试自己开发的定位类应用
在位置变化下的表现。

【明确不建议、也不允许用于以下用途】
  · 校园跑、运动打卡等任何形式的成绩代跑
  · 考勤打卡、签到、外勤轨迹
  · 游戏或其他依赖真实位置的服务

【「不可用于作弊」不只是立场，技术上也不成立】
  免 root 的模拟位置会被系统**强制**标记为模拟
  （Location.isMock = true，旧版本叫 isFromMockProvider）。
  这个标记在客户端改不掉：setTestProviderLocation() 是跨进程调用，
  系统收到后会重新打标记，写入前怎么改都没用。

  本项目曾尝试用反射抹除这个标记，真机实测无效 —— 相关代码已于 1.2.8 删除。
  也就是说，本应用**不再尝试**把模拟位置伪装成真实定位；
  任何做基本校验的应用都能一眼识别出模拟位置。
  靠它绕过定位校验，本来就走不通。

【责任】
  使用本应用产生的一切后果由使用者自行承担。
  作者不提供任何与作弊相关的支持，也不对因此造成的任何损失负责。
""".trimIndent()

    fun hasAccepted(context: Context): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED, 0) >= CURRENT_VERSION
    }.getOrDefault(false)

    fun accept(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ACCEPTED, CURRENT_VERSION)
                .apply()
        }
    }
}

/**
 * 声明对话框。
 *
 * [blocking] = true 时是首次启动的关卡：点返回键、点外部都关不掉，必须点确认按钮。
 * 从菜单再次打开时传 false，让它像个普通对话框那样可以随手关掉。
 */
@Composable
fun DisclaimerDialog(
    blocking: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        // 关卡模式下屏蔽一切「顺手关掉」的途径 —— 没读到就不能用。
        onDismissRequest = { if (!blocking) onDismiss() },
        title = { Text(Disclaimer.TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = Disclaimer.BODY,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (blocking) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "点下面的按钮表示你已阅读并同意遵守上述限制。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (blocking) "我已知悉并遵守" else "关闭")
            }
        },
    )
}
