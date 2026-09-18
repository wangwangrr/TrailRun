package com.trailrun.mockgps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

enum class RootTab(val label: String, val icon: ImageVector) {
    RUN("模拟", Icons.Filled.Place),
    ROUTES("路线", Icons.AutoMirrored.Filled.List),
    GUIDE("说明", Icons.Filled.Info),
}

@Composable
fun RootScreen(
    onOpenDeveloperOptions: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val status by vm.runStatus.collectAsStateWithLifecycle()
    val mockReady by vm.mockReady.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHost = remember { SnackbarHostState() }

    // 启动页：每次冷启动展示一次。
    // rememberSaveable 让它在屏幕旋转后不再重复播放（旋转不该重看启动页）。
    //
    // ⚠️ 这里的 if/else 是**整个 composable 的唯一条件分支**，
    // 上面那些 remember / collectAsState 都必须无条件执行 ——
    // Compose 要求同一 composition 点在每次组合中都被调用，
    // 若用「提前 return」把后半段 remember 跳过去，会破坏这个约定并可能抛异常。
    // 因此下面把主界面整体抽成 RootContent，两边各自是完整的 composable。
    //
    // 同理，口令页是这条链上的**第三个**完整 composable，不是插入到 RootContent 里的分支。
    var showSplash by rememberSaveable { mutableStateOf(true) }
    when {
        showSplash -> SplashScreen(onFinished = { showSplash = false })

        // 启动页之后、主界面之前。locked 来自本地持久化：
        // 本设备输对过一次之后它永远是 false，这里就再也不会经过。
        locked -> LockScreen(onUnlock = { vm.unlock() })

        else -> RootContent(
            vm = vm,
            editor = editor,
            status = status,
            mockReady = mockReady,
            context = context,
            tab = tab,
            onTabChange = { tab = it },
            snackbarHost = snackbarHost,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            onOpenAppDetails = onOpenAppDetails,
        )
    }
}

@Composable
private fun RootContent(
    vm: MainViewModel,
    editor: EditorState,
    status: com.trailrun.mockgps.service.RunStatus,
    mockReady: Boolean?,
    context: android.content.Context,
    tab: Int,
    onTabChange: (Int) -> Unit,
    snackbarHost: SnackbarHostState,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAppDetails: () -> Unit,
) {

    // 启动时申请定位权限（Android 14 前台定位服务要求）
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        vm.refreshMockPermission(context)
        // 权限刚授予 → 立刻取一次位置并让地图聚焦过去
        if (result.values.any { it }) vm.refreshMyLocation(context)
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) {
            vm.refreshMyLocation(context)
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
        vm.refreshMockPermission(context)
    }

    // 每次回到前台（含从「开发者选项」切回来）都自动重新检测。
    //
    // 之前只在首次进入和切换底部标签页时检测，所以用户去开发者选项里
    // 把本应用设为「模拟位置信息应用」之后切回来，顶部仍然显示「未授权」，
    // 必须退出应用重进才刷新 —— 这正是反馈里的第 1 个问题。
    // ON_RESUME 覆盖了「切回来」「从最近任务恢复」「解锁」等所有回到前台的路径。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshMockPermission(context)
    }

    // 顺带在切换标签页时也刷一次，成本很低
    LaunchedEffect(tab) { vm.refreshMockPermission(context) }
    LaunchedEffect(editor.message) {
        val msg = editor.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        vm.consumeMessage()
    }

    LaunchedEffect(status.errorMessage) {
        val err = status.errorMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(err)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
            ) {
                RootTab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { onTabChange(index) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        // ⚠️ 必须应用**完整**的 innerPadding，不能只取 bottom。
        // 之前写的是 padding(bottom = inner.calculateBottomPadding())，
        // 顶部 inset（状态栏高度）被丢掉了 —— 标题栏因此一直贴在屏幕最上沿，
        // 与状态栏重叠；配合地图的绘制溢出，标题栏会整条看不见。
        // 实机录屏里就有这种「标题栏突然消失」的帧。
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Column(Modifier.fillMaxSize()) {
                when (RootTab.entries[tab]) {
                    RootTab.RUN -> MainRunScreen(
                        vm = vm,
                        editor = editor,
                        status = status,
                        mockReady = mockReady,
                        onOpenDeveloperOptions = onOpenDeveloperOptions,
                        onOpenAppDetails = onOpenAppDetails,
                    )

                    RootTab.ROUTES -> RoutesScreen(vm = vm, editor = editor)

                    RootTab.GUIDE -> GuideScreen(
                        mockReady = mockReady,
                        onOpenDeveloperOptions = onOpenDeveloperOptions,
                        onOpenAppDetails = onOpenAppDetails,
                        onRecheck = { vm.refreshMockPermission(context) },
                    )
                }
            }
        }
    }
}
