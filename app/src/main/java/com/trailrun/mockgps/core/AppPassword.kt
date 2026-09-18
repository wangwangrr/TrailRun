package com.trailrun.mockgps.core

/**
 * 进入应用的口令校验。
 *
 * ## 它是什么，不是什么
 *
 * 这是一个**软锁**：挡住「别人拿起手机随手点开」，不是安全机制。
 * 口令以明文写在下面的常量里，任何人把 APK 反编译一下就能看到；
 * 设备上也只是存一个「已解锁」的布尔值，清除应用数据就会重新要求输入。
 *
 * 之所以不把它藏起来（拆字符、异或、塞进 assets），是因为那些做法只能骗过
 * `strings` 一类的粗略搜索，挡不住真想进来的人，却会让这段代码看起来
 * 提供了它并不提供的安全性 —— 那比明说更危险。
 *
 * ## 容错
 *
 * 大小写不敏感，并且顺手容错几种「明明输对了却进不去」的情况：
 *   - 前后空格、中间误打的空格；
 *   - 中文输入法打出来的**全角**字母（ｗｊｗ，U+FF57…）；
 *   - 中文输入法的全角空格（U+3000）。
 *
 * 这三种都是在手机上真实会发生的：中文输入法默认就是全角标点，
 * 字母虽然多数情况下是半角，但切换过输入法状态后并不保证。
 */
object AppPassword {

    /** 界面上显示的提示语。 */
    const val HINT = "作者的名字缩写"

    private const val SECRET = "wjw"

    /** 校验输入。空白、纯空格一律不通过。 */
    fun matches(input: String): Boolean = normalize(input) == SECRET

    /**
     * 归一化：全角转半角 → 去掉所有空白 → 转小写。
     *
     * 顺序是有意的：**必须**在去空白之前做完全角转换，
     * 否则全角空格（U+3000）会漏网 —— 它既不等于半角空格，
     * `isWhitespace()` 在不同 JDK 上对它的判断也不一致。
     */
    internal fun normalize(raw: String): String {
        val halfWidth = buildString(raw.length) {
            for (ch in raw) {
                when {
                    // 全角 ASCII（！..～）整体偏移 0xFEE0 就是对应的半角字符
                    ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                    ch.code == 0x3000 -> append(' ')   // 全角空格
                    else -> append(ch)
                }
            }
        }
        return halfWidth.filterNot { it.isWhitespace() }.lowercase()
    }
}
