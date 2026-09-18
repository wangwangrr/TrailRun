package com.trailrun.mockgps.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailrun.mockgps.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动页：林间岔路的背景图 + 弗罗斯特《未选择的路》名句。
 *
 * 关于版权：英文原诗发表于 1916 年，已进入公有领域，可自由使用；
 * 中文译本的版权属于各译者，因此这里的中文是**自行译写**的，未搬运任何现行译本。
 * 界面上也标注了这一点。
 *
 * 设计取舍：
 *   - **点击任意位置进入**，不做自动跳转 —— 用户想看诗句就多看一会儿，不想看就一点即过；
 *   - 但入场动画播完（约 1 秒）之前不响应点击：否则应用刚起来时手一抖就把它点掉了，
 *     等于没有启动页；
 *   - 淡出用 alpha 动画而不是直接切换，避免闪一下；
 *   - 淡出过程中忽略重复点击，防止连续触发 onFinished。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var contentAlpha by remember { mutableStateOf(0f) }
    // 入场动画是否播完。播完之前不接受点击，理由见上面的注释。
    var readyForTap by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 文字晚于背景淡入，形成层次
        delay(150)
        contentAlpha = 1f
        // 文字淡入动画 900ms + 一点余量
        delay(950)
        readyForTap = true
    }

    // 点击进入：先触发淡出，动画结束（400ms）后再通知上层切换界面，
    // 这样「淡出」和「主界面出现」不会在同一帧发生，不会闪。
    val scope = rememberCoroutineScope()
    fun dismiss() {
        if (!visible || !readyForTap) return
        visible = false
        scope.launch {
            delay(420)
            onFinished()
        }
    }

    val alphaOut by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "splashFade",
    )
    val textAlpha by animateFloatAsState(
        targetValue = contentAlpha,
        animationSpec = tween(durationMillis = 900),
        label = "splashText",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashColors.SkyTop)
            .alpha(alphaOut)
            .clickable(
                enabled = readyForTap && visible,
                // 去掉水波纹：整屏都是点击区，波纹没有意义，反而干扰画面
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // 背景：自绘的林间岔路矢量图，随屏幕等比铺满
        Image(
            painter = painterResource(R.drawable.splash_forest),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // 文字区：放在偏下的位置，正好落在背景图预留的压暗区域里
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 64.dp)
                .alpha(textAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            // 应用名
            Text(
                text = "轨迹跑",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "TrailRun",
                fontSize = 12.sp,
                letterSpacing = 4.sp,
                color = Color.White.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(36.dp))

            // 诗句主体
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Poem.HIGHLIGHT_EN,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                    Spacer(Modifier.height(12.dp))
                    // 一条细分隔线，把中英隔开
                    Box(
                        Modifier
                            .fillMaxWidth(0.2f)
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = Poem.HIGHLIGHT_ZH,
                        fontSize = 15.sp,
                        lineHeight = 25.sp,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "${Poem.TITLE_EN} · ${Poem.AUTHOR_EN}",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // 「点击进入」提示：入场播完后才出现，并用缓慢呼吸的透明度暗示可以点。
            // 做成动画而不是静态文字，是为了让「该怎么继续」一眼可懂。
            val hintAlpha by animateFloatAsState(
                targetValue = if (readyForTap) 1f else 0f,
                animationSpec = tween(durationMillis = 500),
                label = "splashHint",
            )
            if (hintAlpha > 0.01f) {
                // 呼吸：0.45 ↔ 0.95，1.1 秒一轮
                val breathe = rememberInfiniteTransition(label = "splashBreath")
                val pulse by breathe.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0.95f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "splashPulse",
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "轻触屏幕进入",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = pulse),
                        modifier = Modifier.alpha(hintAlpha),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "TAP TO CONTINUE",
                        fontSize = 9.sp,
                        letterSpacing = 2.5.sp,
                        color = Color.White.copy(alpha = pulse * 0.7f),
                        modifier = Modifier.alpha(hintAlpha),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = Poem.ATTRIBUTION,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.45f),
            )
        }

        // 顶部一点暖色渐变，和背景图衔接
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(SplashColors.SkyTop.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
    }
}
