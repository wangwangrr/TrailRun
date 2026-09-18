package com.trailrun.mockgps.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.core.view.WindowCompat

/**
 * 小清新配色：浅薄荷底 + 白卡片 + 薄荷绿主色 + 天蓝辅助色。
 * 整体低饱和、高亮度，搭配大圆角与极浅描边，视觉上轻、干净。
 */
val MintPrimary = Color(0xFF35B98A)
val MintDeep = Color(0xFF1E8F69)
val MintSoft = Color(0xFFD9F2E8)
val SkyAccent = Color(0xFF4A90D9)
val SkySoft = Color(0xFFDDEDFB)
val PeachAccent = Color(0xFFFF8A80)
val PeachSoft = Color(0xFFFFE4E1)
val AmberAccent = Color(0xFFE8A33D)
val AmberSoft = Color(0xFFFFF2DC)

// 语义别名：各界面按「用途」引用，避免到处写具体色名。
/** 主色：进度、勾选、成功状态。 */
val TrailGreen = MintPrimary

/** 警示色：未授权、停止、删除。 */
val TrailCoral = PeachAccent

/** 中间态：检测中、提示。 */
val TrailAmber = AmberAccent

/** 辅助色：次要信息。 */
val TrailSky = SkyAccent

private val BgTop = Color(0xFFF4F9F7)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val SurfaceSoft = Color(0xFFEDF5F1)
private val TextMain = Color(0xFF2B3A36)
private val TextSub = Color(0xFF7B8F89)
private val LineSoft = Color(0xFFDCE9E4)

private val LightColors = lightColorScheme(
    primary = MintPrimary,
    onPrimary = Color.White,
    primaryContainer = MintSoft,
    onPrimaryContainer = MintDeep,
    secondary = SkyAccent,
    onSecondary = Color.White,
    secondaryContainer = SkySoft,
    onSecondaryContainer = Color(0xFF1B4A6E),
    tertiary = PeachAccent,
    onTertiary = Color.White,
    tertiaryContainer = PeachSoft,
    onTertiaryContainer = Color(0xFF7A2E28),
    background = BgTop,
    onBackground = TextMain,
    surface = SurfaceWhite,
    onSurface = TextMain,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = TextSub,
    surfaceContainer = SurfaceWhite,
    surfaceContainerHigh = SurfaceSoft,
    outline = LineSoft,
    outlineVariant = Color(0xFFE8F1ED),
    error = PeachAccent,
    onError = Color.White,
)

/** 大圆角，配合浅色显得柔和。 */
private val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val FreshTypography = Typography(
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** 数字展示用。 */
val NumericLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
val NumericMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun TrailRunTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            // 浅色背景 → 状态栏图标要用深色
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
        }
    }
    MaterialTheme(
        colorScheme = LightColors,
        typography = FreshTypography,
        shapes = SoftShapes,
        content = content,
    )
}
