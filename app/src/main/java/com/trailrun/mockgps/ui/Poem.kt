package com.trailrun.mockgps.ui

import androidx.compose.ui.graphics.Color

/**
 * 启动页引用的诗句。
 *
 * 版权说明（这点必须写清楚，否则是法律问题）：
 *   - 英文原文 The Road Not Taken 发表于 1916 年，**已进入公有领域**，可自由使用；
 *   - 但**中文译本的权利属于各译者**（顾子欣、杨通荣等译本均仍在版权期内），
 *     因此这里**没有搬运任何现行译本**，而是自行重写的一版中文，
 *     仅求达意，不追求文学性。
 *   - 应用内也标注了这一点。
 */
object Poem {

    const val TITLE_EN = "The Road Not Taken"
    const val AUTHOR_EN = "Robert Frost, 1916"

    const val TITLE_ZH = "未选择的路"
    const val AUTHOR_ZH = "罗伯特·弗罗斯特"

    /** 启动页展示的核心两行（也是全诗最著名的两句）。 */
    const val HIGHLIGHT_EN = "Two roads diverged in a yellow wood,\nAnd sorry I could not travel both"

    /** 自行重写的中文，非任何现行译本。 */
    const val HIGHLIGHT_ZH = "金黄色的树林里分出两条路，\n可惜我不能同时去涉足"

    /** 启动页底部的说明。 */
    const val ATTRIBUTION = "英文原诗已进入公有领域 · 中文为自行译写"

    /** 完整诗作（英文原文，公有领域）。 */
    val FULL_EN = listOf(
        "Two roads diverged in a yellow wood,",
        "And sorry I could not travel both",
        "And be one traveler, long I stood",
        "And looked down one as far as I could",
        "To where it bent in the undergrowth;",
        "",
        "Then took the other, as just as fair,",
        "And having perhaps the better claim,",
        "Because it was grassy and wanted wear;",
        "Though as for that the passing there",
        "Had worn them really about the same,",
        "",
        "And both that morning equally lay",
        "In leaves no step had trodden black.",
        "Oh, I kept the first for another day!",
        "Yet knowing how way leads on to way,",
        "I doubted if I should ever come back.",
        "",
        "I shall be telling this with a sigh",
        "Somewhere ages and ages hence:",
        "Two roads diverged in a wood, and I—",
        "I took the one less traveled by,",
        "And that has made all the difference.",
    )

    /** 完整诗作（自行译写的中文，逐段对应）。 */
    val FULL_ZH = listOf(
        "金黄色的树林里分出两条路，",
        "可惜我不能同时去涉足，",
        "我久久站在那路口，",
        "向一条路极目望去，",
        "直到它弯入灌木丛深处；",
        "",
        "于是我选择了另一条，",
        "也许它更值得一走，",
        "因为那里草木繁茂、少有人行；",
        "虽说论起往来的足迹，",
        "两条路其实磨损得相差无几，",
        "",
        "那天清晨两条路都静卧在",
        "无人踩黑的落叶之上。",
        "啊，我把第一条留给了改日！",
        "可我知道路总是连着路，",
        "我怀疑自己是否还能回返。",
        "",
        "许多许多年以后，",
        "我将在某处叹息着讲述：",
        "树林里分出两条路，而我——",
        "我选择了人迹更少的那一条，",
        "从此决定了我一生的道路。",
    )
}

/** 启动页用的配色（比主界面更深一档，形成「进入前」的仪式感）。 */
object SplashColors {
    val SkyTop = Color(0xFFFDF6E3)
    val SkyBottom = Color(0xFFF6E7C4)
    val CanopyNear = Color(0xFF2F5D4A)
    val CanopyFar = Color(0xFF7FA891)
    val PathLight = Color(0xFFE8D5A9)
    val TextPrimary = Color(0xFF2B3A36)
    val TextSecondary = Color(0xFF6B7F78)
}
