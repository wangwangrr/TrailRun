package com.trailrun.mockgps.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailrun.mockgps.core.CrashLog
import com.trailrun.mockgps.ui.theme.TrailAmber
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen

/**
 * 「说明」页。
 *
 * 内容按**使用顺序**分四层，而不是把卡片平铺：
 *
 *   第 1 层 当前状态  —— 一眼看到「现在能不能用」，并给出唯一的下一步动作
 *   第 2 层 首次使用  —— 4 步设置（这是新用户唯一必须做的事）
 *   第 3 层 操作与参考 —— 怎么用（画路线）+ 有哪些功能与设置（可折叠，不占屏）
 *   第 4 层 服务与附录 —— 运行注意、崩溃日志、诗句版权
 *
 * 折叠规则：**当前状态**与**首次使用**始终展开（必须先看）；
 * 免责声明、功能参考、运行说明默认收起（看过一次即可，不必每次占满屏幕）。
 */
@Composable
fun GuideScreen(
    mockReady: Boolean?,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 各折叠区状态。默认只展开「首次使用」，其余收起 —— 进来先看到最该看的。
    var referenceExpanded by remember { mutableStateOf(false) }
    var runtimeExpanded by remember { mutableStateOf(false) }
    var poemExpanded by remember { mutableStateOf(false) }
    var fullPoemShown by remember { mutableStateOf(false) }

    var crashText by remember { mutableStateOf<String?>(null) }
    val hasCrash = remember { CrashLog.hasCrash(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ================= 第 1 层：当前状态 =================
        item { StatusHeader(mockReady = mockReady) }

        item {
            SectionCard {
                // 状态不同，给的动作完全不同 —— 所以这里按状态分支，而不是列一堆按钮让人挑
                when (mockReady) {
                    true -> {
                        Text(
                            text = "已就绪，可以开始模拟",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TrailGreen,
                        )
                        Spacer(Modifier.height(4.dp))
                        StepRow(0, "下一步", "回到「模拟」页 → 画一条路线 → 点「开始模拟」")
                    }

                    false -> {
                        Text(
                            text = "还差一步：把本应用设为系统的模拟位置应用",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TrailCoral,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "否则本应用写入的位置会被系统拒绝，目标应用读到的仍是真实位置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        StepRow(1, "打开开发者选项", "若尚未开启：设置 → 关于手机 → 连续点击「版本号」7 次")
                        StepRow(2, "选择模拟位置应用", "开发者选项 → 选择模拟位置信息应用 → 轨迹跑")
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onOpenDeveloperOptions,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TrailGreen,
                                    contentColor = Color(0xFF06231A),
                                ),
                            ) { Text("去开发者选项") }
                            OutlinedButton(
                                onClick = onRecheck,
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("已设好，重新检测") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "提示：首次授予定位权限时也要允许本应用读取位置。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            text = "正在检测权限…",
                            style = MaterialTheme.typography.titleMedium,
                            color = TrailAmber,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "若长时间停在这里，点下面的按钮重新检测。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onRecheck, shape = RoundedCornerShape(12.dp)) {
                            Text("重新检测")
                        }
                    }
                }
            }
        }

        // ================= 第 2 层：首次使用 =================
        item {
            SectionCard {
                SectionLabel("首次使用 · 4 步", trailing = "按顺序做一次即可")
                Spacer(Modifier.height(10.dp))
                StepRow(1, "开启开发者选项", "设置 → 关于手机 → 连续点击「版本号」7 次")
                StepRow(2, "选择模拟位置应用", "设置 → 系统 → 开发者选项 → 选择模拟位置信息应用 → 轨迹跑")
                StepRow(3, "关闭电池优化", "设置 → 应用 → 轨迹跑 → 耗电管理 → 允许后台高耗电；同时允许「位置信息」与「通知」")
                StepRow(4, "画路线并开始", "「模拟」页选「点选」或「手绘」，画好路线后调速度，点「开始模拟」")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("打开本应用设置页（改权限 / 电池）") }
            }
        }

        // ================= 第 3 层：操作与参考 =================
        item {
            SectionCard {
                SectionLabel("怎么画路线")
                Spacer(Modifier.height(8.dp))
                TipRow("点选模式", "在地图上依次点击：第一个点是起点（绿），最后一个是终点（红），中间都是途经点。点一下已放置的圆点即可删除它。")
                TipRow("手绘模式", "按住屏幕拖动，像画图一样把轨迹画出来，松手即成路线；可以分几笔接着画。")
                TipRow("精确落点", "「更多」→「拖图设点」后，拖动地图让十字准星对准目标，再点底部「在此设点」。")
                TipRow("搜地点 / 输坐标", "标题栏放大镜可搜地名（Photon，免 Key）；「更多」→「按坐标输入」可直接填经纬度。")
                TipRow("改错了", "右上角「撤销」可逐步回退（一笔、一次清空都能一步撤销，最多 40 步）。")
            }
        }

        item {
            CollapsibleCard(
                title = "功能与设置参考",
                subtitle = if (referenceExpanded) "收起" else "速度 / 路线 / 抖动 / 底图 / 预设",
                expanded = referenceExpanded,
                onToggle = { referenceExpanded = !referenceExpanded },
            ) {
                GroupLabel("速度")
                TipRow("预设与微调", "预设 6 / 8 / 10 / 12 / 15 km/h；滑杆可在 2–25 km/h 之间无级调节，界面同步显示配速。")

                Spacer(Modifier.height(10.dp))
                GroupLabel("路线方式")
                TipRow("单程", "从起点跑到终点即结束。")
                TipRow("刷圈", "到终点后自动掉头折返，持续往复，适合设定距离后刷够里程。")
                TipRow("折返", "轨迹与刷圈相同，进度按一趟往返计算。")

                Spacer(Modifier.height(10.dp))
                GroupLabel("高级选项")
                TipRow("目标距离", "设定 1–5 km，达到后自动停止模拟。")
                TipRow("轨迹抖动", "给每个点加米级随机偏移，避免轨迹呈现完美直线。")
                TipRow("视角跟随", "运行时地图自动跟随当前位置。")

                Spacer(Modifier.height(10.dp))
                GroupLabel("底图（「更多」→「底图与离线设置」）")
                TipRow("镜像选择", "默认「自动」按实测速度依次尝试 4 个 OSM 端点；也可手动锁定一个，其余仍作为后备。")
                TipRow("连通性测试", "逐个请求端点并显示耗时与错误，用于判断是网络问题还是应用问题。")
                TipRow("离线模式", "开启后完全停止网络请求，只显示已缓存的区域。")
                TipRow("地图诊断", "显示 osmdroid 实际请求过的瓦片地址与次数 —— 排查地图空白时最有用。")

                Spacer(Modifier.height(10.dp))
                GroupLabel("路线预设（「路线」页）")
                TipRow("保存与切换", "可保存、载入、重命名、删除多套路线与速度方案。")
                TipRow("导入导出", "导出为 JSON 便于备份或分享；粘贴 JSON 即可导入。")
            }
        }

        // ================= 第 4 层：服务与附录 =================
        item {
            CollapsibleCard(
                title = "运行说明与注意事项",
                subtitle = if (runtimeExpanded) "收起" else "后台运行 / 电量 / 失效排查",
                expanded = runtimeExpanded,
                onToggle = { runtimeExpanded = !runtimeExpanded },
            ) {
                TipRow("后台运行", "采用前台服务 + 常驻通知，熄屏、切到其他应用都会继续跑。")
                TipRow("被系统清理", "长时间运行请插上电源，并把本应用加入电池白名单。")
                TipRow("开始后仍是真实位置", "说明还没被选为模拟位置应用；到开发者选项里选中本应用，回来点「重新检测」。部分 ROM 需要先关掉再重开目标应用。")
                TipRow("模拟中途自己停了", "一是设了「目标距离」达到后自动停止，改成「不限」；二是省电策略杀掉了前台服务。")
                TipRow("定位取不到", "本应用会真正发起一次定位请求（而非只读缓存）。失败时上方路线板会写明原因：无权限 / 系统定位关闭 / 暂时取不到。")
                TipRow("轨迹太规整", "建议开启「轨迹抖动」，并把速度设在正常跑步区间（8–12 km/h）。配速恒定本身也是一种异常特征，本工具无法消除。")
            }
        }

        // 崩溃日志：只有真的出现过闪退才显示，避免占位
        if (hasCrash || crashText != null) {
            item {
                SectionCard {
                    SectionLabel("崩溃日志", trailing = "上次闪退的堆栈")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "应用曾经闪退。这里记录了当时的完整错误信息，" +
                            "复制发给开发者可以直接定位问题。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { crashText = CrashLog.read(context) ?: "没有记录到崩溃日志" },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("查看") }
                        OutlinedButton(
                            onClick = {
                                CrashLog.read(context)?.let { clipboard.setText(AnnotatedString(it)) }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("复制") }
                        OutlinedButton(
                            onClick = {
                                CrashLog.clear(context)
                                crashText = null
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("清除") }
                    }
                }
            }
        }

        item {
            CollapsibleCard(
                title = "关于与版权",
                subtitle = if (poemExpanded) "收起" else "启动页诗句 · 免责声明 · 许可",
                expanded = poemExpanded,
                onToggle = { poemExpanded = !poemExpanded },
            ) {
                // ---- 启动页诗句 ----
                GroupLabel("启动页引用的诗句")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = Poem.HIGHLIGHT_EN,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = Poem.HIGHLIGHT_ZH,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${Poem.TITLE_EN} · ${Poem.AUTHOR_EN}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                CollapseRow(
                    expanded = fullPoemShown,
                    collapsedText = "展开全诗（中英对照）",
                    expandedText = "收起全诗",
                ) { fullPoemShown = !fullPoemShown }

                AnimatedVisibility(visible = fullPoemShown) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Poem.FULL_EN.forEachIndexed { i, line ->
                            val zh = Poem.FULL_ZH.getOrNull(i).orEmpty()
                            if (line.isBlank()) {
                                Spacer(Modifier.height(10.dp))
                            } else {
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = zh,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(3.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                NoteBlock(
                    "版权说明",
                    "英文原诗 The Road Not Taken 发表于 1916 年，已进入公有领域，可自由使用；" +
                        "中文译本的版权属于各译者，因此本应用的中文为自行译写，未使用任何现行译本。",
                )

                Spacer(Modifier.height(14.dp))

                // ---- 免责声明 ----
                GroupLabel("免责声明")
                Spacer(Modifier.height(6.dp))
                DisclaimerItem(
                    "仅供合法用途",
                    "本工具用于定位相关功能的开发调试、地图与导航应用测试、" +
                        "以及不希望在运动记录中暴露真实住址的隐私保护场景。",
                )
                DisclaimerItem(
                    "请勿用于作弊",
                    "严禁用于伪造考勤、代跑代打卡、伪造证明材料等用途。" +
                        "部分校园跑 / 运动打卡类应用会检测模拟位置、异常配速与加速度，" +
                        "可能判定成绩无效甚至按校规处理。",
                )
                DisclaimerItem(
                    "遵守所在平台规定",
                    "是否使用、以及使用后果，请自行判断并承担全部责任。" +
                        "开发者不对任何因使用本工具产生的成绩作废、纪律处分或其它损失负责。",
                )
                DisclaimerItem(
                    "不做注入、不破坏系统",
                    "只调用系统公开的模拟位置接口，不修改、不 hook、不注入任何其它应用，" +
                        "不需要 Root，也不影响系统稳定性。",
                )
                DisclaimerItem(
                    "数据仅存本地",
                    "路线、设置等全部保存在本机应用私有目录，不上传任何服务器；卸载即删除。" +
                        "地图瓦片与地点搜索会直接请求对应的公共服务。",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "继续使用即表示你已阅读并同意上述内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    crashText?.let { text ->
        AlertDialog(
            onDismissRequest = { crashText = null },
            title = { Text("崩溃日志") },
            text = {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    crashText = null
                }) { Text("复制并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { crashText = null }) { Text("关闭") }
            },
        )
    }
}

// ---------------------------------------------------------------- 组件

/** 顶部状态条：只显示状态，不重复动作（动作在下一张卡的按钮里）。 */
@Composable
private fun StatusHeader(mockReady: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "使用说明",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        when (mockReady) {
            true -> StatusPill("已就绪", TrailGreen)
            false -> StatusPill("未授权", TrailCoral)
            null -> StatusPill("检测中", TrailAmber)
        }
    }
}

/** 可折叠的卡片：标题行本身就是展开/收起按钮，右侧显示当前状态摘要。 */
@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** 卡片内部的小节标题。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 灰底说明块，用于版权这类需要与正文区分开的内容。 */
@Composable
private fun NoteBlock(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 展开 / 收起一行（用于卡片内部的次级折叠）。 */
@Composable
private fun CollapseRow(
    expanded: Boolean,
    collapsedText: String,
    expandedText: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (expanded) expandedText else collapsedText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 带编号的步骤行。
 * 编号 0 表示「下一步」这类非编号提示，用圆圈图标代替数字。
 */
@Composable
private fun StepRow(index: Int, title: String, detail: String) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(TrailGreen.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (index == 0) "→" else index.toString(),
                color = TrailGreen,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 参考条目的通用行：加粗小标题 + 说明。 */
@Composable
private fun TipRow(title: String, detail: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(
            "· $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 免责声明的一条：琥珀色圆点 + 小标题 + 说明。 */
@Composable
private fun DisclaimerItem(title: String, detail: String) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(TrailAmber, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
