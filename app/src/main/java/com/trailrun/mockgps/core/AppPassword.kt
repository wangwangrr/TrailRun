package com.trailrun.mockgps.core

import kotlin.random.Random

/**
 * 一道随机口算题。
 *
 * 题目设计成「看一眼就能心算出来」：两位数加两位数，或者两位数减两位数。
 * 太难会让人烦，太简单又没意义 —— 这是给使用者自己过的门，不是考数学。
 *
 * 每次进入口令页、每次点「换一题」都会重新生成，所以答案无法被记住或转告。
 */
data class ArithmeticChallenge(
    val left: Int,
    val right: Int,
    val op: Char,
) {
    /** 正确答案。减法保证非负，见 [AppPassword.newChallenge]。 */
    val answer: Int get() = if (op == '+') left + right else left - right

    /** 界面上显示的题目，例如 `37 + 24 = ?` */
    val text: String get() = "$left $op $right = ?"
}

/**
 * 进入应用的验证逻辑，两种方式：
 *
 *   1. [matches] —— **名字缩写**，固定口令，可以告诉信任的人；
 *   2. [checkArithmetic] —— **随机口算**，每次不同、无法转告。
 *
 * ## 它是什么，不是什么
 *
 * 这是**软锁**：挡住「别人拿起手机随手点开」，不是安全机制。
 *
 * 加了口算方式之后，整体强度取决于**较弱的那一条**：口算题谁都会算，
 * 所以选了它的人实际上等于没有设防。这一点必须写清楚，不能让人误以为
 * 「多了一种验证方式所以更安全」—— 恰恰相反。
 * 口令本身也以明文写在下面的常量里，反编译 APK 就能看到；
 * 设备上只存一个「已解锁」的布尔值，清除应用数据即重置。
 *
 * 之所以不把它藏起来（拆字符、异或、塞进 assets），是因为那些做法只能骗过
 * `strings` 一类的粗略搜索，挡不住真想进来的人，却会让这段代码看起来
 * 提供了它并不提供的安全性 —— 那比明说更危险。
 *
 * ## 容错
 *
 * 两种方式都会先做 [normalize]：全角转半角 → 去掉所有空白 → 转小写。
 * 覆盖的都是手机上真实会发生的情况：中文输入法的全角字母（ｗｊｗ）、
 * 全角数字（１４）、全角空格（U+3000）、以及误打的空格。
 */
object AppPassword {

    /** 名字缩写方式的界面提示。 */
    const val HINT = "作者的名字缩写"

    private const val SECRET = "wjw"

    /** 校验名字缩写。空白、纯空格一律不通过。 */
    fun matches(input: String): Boolean = normalize(input) == SECRET

    /**
     * 随机出一道口算题。
     *
     * - 加法：两个 11~49 的数，结果落在 22~98；
     * - 减法：减数 11~39、被减数比它大 1~39，**保证结果非负** ——
     *   出现负数答案会让人先怀疑自己算错了，而不是题目出得怪。
     *
     * @param random 抽出来是为了让自检脚本能注入固定种子，断言题面约束。
     */
    fun newChallenge(random: Random = Random.Default): ArithmeticChallenge =
        if (random.nextBoolean()) {
            ArithmeticChallenge(random.nextInt(11, 50), random.nextInt(11, 50), '+')
        } else {
            val right = random.nextInt(11, 40)
            ArithmeticChallenge(right + random.nextInt(1, 40), right, '-')
        }

    /** 校验口算答案。全角数字、前后空格都能正确识别。 */
    fun checkArithmetic(challenge: ArithmeticChallenge, input: String): Boolean =
        normalize(input).toIntOrNull() == challenge.answer

    /**
     * 归一化：全角转半角 → 去掉所有空白 → 转小写。
     *
     * 顺序是有意的：**必须**在去空白之前做完全角转换，
     * 否则全角空格（U+3000）会漏网 —— 它既不等于半角空格，
     * `isWhitespace()` 在不同 JDK 上对它的判断也不一致。
     *
     * 顺带也让口算答案能吃下全角数字（U+FF10~U+FF19 同样落在 0xFF01..0xFF5E 里）。
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
