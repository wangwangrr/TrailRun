package com.trailrun.mockgps.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trailrun.mockgps.core.RouteMode
import com.trailrun.mockgps.data.RoutePreset
import com.trailrun.mockgps.ui.theme.TrailCoral
import com.trailrun.mockgps.ui.theme.TrailGreen
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RoutesScreen(vm: MainViewModel, editor: EditorState) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var showSaveDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RoutePreset?>(null) }
    var newName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                SectionLabel("当前路线", trailing = "${editor.waypoints.size} 个点")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (editor.waypoints.size < 2) {
                        "还没有路线。回到「模拟」页，在地图上点击即可添加起点与终点。"
                    } else {
                        "单程 ${fmtDistance(editor.singleLegMeters)} · " +
                            "设定 ${String.format(Locale.US, "%.1f", editor.speedKmh)} km/h · " +
                            modeLabel(editor.mode)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { newName = ""; showSaveDialog = true },
                        enabled = editor.waypoints.size >= 2,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TrailGreen,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF06231A),
                        ),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("存为预设")
                    }
                    OutlinedButton(
                        onClick = {
                            val center = vm.lastKnownCenter(context)
                                ?: (39.9087 to 116.3975) // 无定位权限时用天安门做占位，可自行拖动地图
                            vm.loadDemoRoute(center.first, center.second)
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("生成演示路线")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportText = vm.exportJson()
                        },
                        enabled = editor.waypoints.size >= 2,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("导出")
                    }
                    OutlinedButton(
                        onClick = { importText = ""; showImportDialog = true },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("导入")
                    }
                }
            }
        }

        item {
            SectionLabel(
                text = "路线预设",
                trailing = if (editor.presets.isEmpty()) "暂无" else "${editor.presets.size} 条",
            )
        }

        if (editor.presets.isEmpty()) {
            item {
                Text(
                    text = "预设会保存起终点、轨迹、速度与路线方式，下次一键切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(editor.presets, key = { it.id }) { preset ->
            val selected = preset.id == editor.selectedPresetId
            SectionCard(modifier = Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Place,
                                contentDescription = null,
                                tint = if (selected) TrailGreen
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${preset.waypoints.size} 点 · " +
                                "${fmtDistance(preset.singleLegMeters())} · " +
                                "${String.format(Locale.US, "%.1f", preset.speedKmh)} km/h · " +
                                modeLabel(preset.mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        renaming = preset; newName = preset.name
                    }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "重命名",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.deletePreset(preset.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = TrailCoral,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.loadPreset(preset.id) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) TrailGreen
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) androidx.compose.ui.graphics.Color(0xFF06231A)
                            else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(if (selected) "已载入" else "载入并编辑")
                    }
                    OutlinedButton(
                        onClick = { exportText = preset.toJson().toString(2) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("导出")
                    }
                }
            }
        }
    }

    // ---------------- 对话框 ----------------

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存为预设") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("路线名称") },
                    placeholder = { Text("例如：操场 400m 环线") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveCurrentAsPreset(newName.trim())
                    showSaveDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入路线") },
            text = {
                Column {
                    Text(
                        "粘贴之前导出的 JSON 文本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        placeholder = { Text("{\"name\": ...}") },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = vm.importJson(importText.trim())
                    if (ok) showImportDialog = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            },
        )
    }

    exportText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("导出路线") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    exportText = null
                }) { Text("复制到剪贴板") }
            },
            dismissButton = {
                TextButton(onClick = { exportText = null }) { Text("关闭") }
            },
        )
    }

    renaming?.let { preset ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("路线名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) vm.renamePreset(preset.id, newName.trim())
                    renaming = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }
}

private fun modeLabel(mode: RouteMode): String = when (mode) {
    RouteMode.SINGLE -> "单程"
    RouteMode.LOOP -> "环线刷圈"
    RouteMode.BACK_AND_FORTH -> "折返"
}

private fun fmtDistance(meters: Double): String = when {
    meters >= 1000 -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    meters > 0 -> "${meters.roundToInt()} m"
    else -> "--"
}
