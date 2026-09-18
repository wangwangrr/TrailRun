package com.trailrun.mockgps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailrun.mockgps.core.AppPassword
import com.trailrun.mockgps.ui.theme.MintDeep
import com.trailrun.mockgps.ui.theme.MintSoft
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen
import kotlinx.coroutines.delay

/**
 * 进入口令页。启动页之后、主界面之前。
 *
 * 只在**本设备第一次**进入时出现一次：输对了就写进本地，
 * 之后冷启动、杀进程重开、重启手机都不会再问（见 RouteRepository.unlocked）。
 * 卸载重装或清除应用数据会重新要求输入 —— 那是 SharedPreferences 的语义，
 * 也是期望行为。
 *
 * 视觉上刻意弱化「门禁」感、延续小清新：浅薄荷渐变底 + 白卡片 + 圆角，
 * 没有大红大黑的警告色，输错也只是珊瑚色的一行小字。
 */
@Composable
fun LockScreen(onUnlock: (String) -> Boolean) {
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 自动弹出键盘。延后 200ms 是必要的：窗口刚完成切换时请求焦点
    // 常常拿不到，键盘也不会弹出来，用户就得自己点一下输入框。
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    fun submit() {
        if (AppPassword.matches(input)) {
            keyboard?.hide()
            onUnlock(input)
        } else {
            attempts++
            error = if (attempts >= 3) {
                "还是不对。提示就在下面，是三个字母。"
            } else {
                "口令不对，再看一眼下面的提示"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MintSoft, Color(0xFFF4F9F7), Color.White)
                )
            )
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 圆角方块里的锁图标
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(MintSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MintDeep,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Spacer(Modifier.size(16.dp))

                    Text(
                        text = "轨迹跑",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "输入口令后进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.size(22.dp))

                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            // 一开始打字就把错误清掉：错误信息留着会让人以为
                            // 新输入的内容也是错的。
                            if (error != null) error = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        isError = error != null,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("口令") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )

                    Spacer(Modifier.size(14.dp))

                    // 提示：这是用户明确要求在界面上给出的
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Lightbulb,
                            contentDescription = null,
                            tint = TrailGreen,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text(
                                text = "提示：${AppPassword.HINT}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "不区分大小写",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val err = error
                    if (err != null) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = err,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TrailCoral,
                            textAlign = TextAlign.Start,
                        )
                    }

                    Spacer(Modifier.size(20.dp))

                    Button(
                        onClick = { submit() },
                        // 空输入时禁用，省得点一下只换来一句「不对」
                        enabled = input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrailGreen,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("进入", fontSize = 15.sp)
                    }
                }
            }

            Spacer(Modifier.size(18.dp))

            Text(
                text = "本设备输入一次后不再询问",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
