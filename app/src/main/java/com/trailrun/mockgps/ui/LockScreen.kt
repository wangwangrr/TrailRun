package com.trailrun.mockgps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
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
import com.trailrun.mockgps.core.ArithmeticChallenge
import com.trailrun.mockgps.ui.theme.MintDeep
import com.trailrun.mockgps.ui.theme.MintSoft
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen
import kotlinx.coroutines.delay

/** 进入方式。两种任选其一即可进入。 */
private enum class UnlockMode(val label: String) {
    NAME("名字缩写"),
    MATH("算术题"),
}

/**
 * 进入验证页。启动页之后、主界面之前。
 *
 * 只在**本设备第一次**进入时出现一次：通过后写进本地，
 * 之后冷启动、杀进程重开、重启手机都不会再问（见 RouteRepository.unlocked）。
 * 卸载重装或清除应用数据会重新要求验证 —— 那是 SharedPreferences 的语义，
 * 也是期望行为。
 *
 * 两种方式任选：
 *   - **名字缩写**：固定口令，可以告诉信任的人；
 *   - **算术题**：每次随机，无法转告，但**任何会算数的人都能通过**。
 *
 * ⚠️ 因此整体强度取决于较弱的那一条 —— 加了算术方式并不等于更安全。
 * 详见 AppPassword 的注释。
 *
 * 视觉上刻意弱化「门禁」感、延续小清新：浅薄荷渐变底 + 白卡片 + 圆角，
 * 没有大红大黑的警告色，输了也只是珊瑚色的一行小字。
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // 用 Int 存模式：rememberSaveable 对 enum 的支持依赖 Serializable，
    // 用下标最省心，旋转屏幕后不会跳回第一个。
    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    val mode = if (modeIndex == 0) UnlockMode.NAME else UnlockMode.MATH

    var nameInput by rememberSaveable { mutableStateOf("") }
    var mathInput by rememberSaveable { mutableStateOf("") }
    // 题目用 remember 而非 rememberSaveable：旋转后换一道无害，不值得为它写 Saver。
    var challenge by remember { mutableStateOf(AppPassword.newChallenge()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 自动弹出键盘。延后 200ms 是必要的：窗口刚完成切换时请求焦点
    // 常常拿不到，键盘也不会弹出来，用户就得自己点一下输入框。
    // 切到另一种方式时也重新聚焦（此时输入框是新的实例）。
    LaunchedEffect(mode) {
        delay(200)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    fun submit() {
        val ok = when (mode) {
            UnlockMode.NAME -> AppPassword.matches(nameInput)
            UnlockMode.MATH -> AppPassword.checkArithmetic(challenge, mathInput)
        }
        if (ok) {
            keyboard?.hide()
            onUnlock()
            return
        }
        attempts++
        when (mode) {
            UnlockMode.NAME -> {
                error = if (attempts >= 3) {
                    "还是不对。提示就在下面，是三个字母。"
                } else {
                    "口令不对，再看一眼下面的提示"
                }
            }
            UnlockMode.MATH -> {
                // 算错就换一道，免得卡在同一题上反复试。
                challenge = AppPassword.newChallenge()
                mathInput = ""
                error = "算错了，已经换了一题，再试试"
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
                        text = "选一种方式验证后进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.size(20.dp))

                    // ---------- 方式切换 ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MintSoft.copy(alpha = 0.55f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        UnlockMode.entries.forEach { m ->
                            ModeChip(
                                label = m.label,
                                selected = m == mode,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (m != mode) {
                                        modeIndex = m.ordinal
                                        error = null
                                        attempts = 0
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.size(20.dp))

                    // ---------- 输入区 ----------
                    when (mode) {
                        UnlockMode.NAME -> {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = {
                                    nameInput = it
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
                        }

                        UnlockMode.MATH -> {
                            // 题面
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MintSoft.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 14.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = challenge.text,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                        color = MintDeep,
                                    )
                                }
                            }

                            Spacer(Modifier.size(12.dp))

                            OutlinedTextField(
                                value = mathInput,
                                onValueChange = {
                                    // 只收数字：算术答案没有别的字符，键盘上也可能是误触
                                    val digits = it.filter { c -> c.isDigit() }
                                    mathInput = digits
                                    if (error != null) error = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                isError = error != null,
                                shape = RoundedCornerShape(16.dp),
                                placeholder = { Text("填答案") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                            )

                            Spacer(Modifier.size(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        challenge = AppPassword.newChallenge()
                                        mathInput = ""
                                        error = null
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = TrailGreen,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("换一题", color = TrailGreen, fontSize = 13.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "每次都不一样",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    val err = error
                    if (err != null) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = err,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TrailCoral,
                            textAlign = TextAlign.Start,
                        )
                    }

                    Spacer(Modifier.size(18.dp))

                    Button(
                        onClick = { submit() },
                        // 空输入时禁用，省得点一下只换来一句「不对」
                        enabled = when (mode) {
                            UnlockMode.NAME -> nameInput.isNotBlank()
                            UnlockMode.MATH -> mathInput.isNotBlank()
                        },
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
                text = "本设备通过一次后不再询问",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 方式切换的小胶囊。选中的那个浮起来，和背景拉开层次。 */
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color.White else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MintDeep else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
