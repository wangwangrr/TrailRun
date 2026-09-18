package com.trailrun.mockgps.core

import kotlin.random.Random

/**
 * 进入验证的纯逻辑验证（纯 JVM，不需要 Android）。
 *
 * 这里守的是「明明输对了却进不去」这一类问题 —— 它们不会崩溃、不会报错，
 * 只是让人在验证页上反复试，而且极难从代码上一眼看出来：
 *   - 大写 / 小写；
 *   - 前后和中间误打的空格；
 *   - 中文输入法打出来的**全角**字母（ｗｊｗ）与全角数字（１４）；
 *   - 全角空格（U+3000）。
 *
 * 同样也要守住反面：不该通过的一定不能通过，
 * 尤其是空输入、少一个字符、以及「随便填个数字」这种情况。
 */
object PasswordTest {

    private var failures = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            println("  [PASS] $name")
        } else {
            failures++
            println("  [FAIL] $name  $detail")
        }
    }

    private fun ok(input: String) = AppPassword.matches(input)
    private fun no(input: String) = !AppPassword.matches(input)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== 进入验证逻辑（名字缩写 + 随机口算）===")

        println("\n[1] 名字缩写：正确口令")
        check("小写 wjw", ok("wjw"))
        check("大写 WJW", ok("WJW"))
        check("混写 WjW", ok("WjW"))
        check("全大写 wJw", ok("wJw"))

        println("\n[2] 名字缩写：空白容错")
        check("前后有空格 \" wjw \"", ok("  wjw  "))
        check("中间有空格 \"w j w\"", ok("w j w"))
        check("制表符/换行包裹", ok("\twjw\n"))
        check("全角空格包裹 \\u3000wjw\\u3000", ok("\u3000wjw\u3000"))

        println("\n[3] 名字缩写：全角字母（中文输入法）")
        check("全角 ｗｊｗ", ok("ｗｊｗ"))
        check("全角大写 ＷＪＷ", ok("ＷＪＷ"))
        check("全角半角混排 ｗjw", ok("ｗjw"))

        println("\n[4] 名字缩写：必须拒绝的输入")
        check("空串", no(""))
        check("纯空格", no("   "))
        check("纯全角空格", no("\u3000\u3000"))
        check("多一个字符 wjww", no("wjww"))
        check("少一个字符 wj", no("wj"))
        check("错误口令 abc", no("abc"))
        check("全拼 wangjiawei", no("wangjiawei"))
        check("数字 888", no("888"))

        println("\n[5] 归一化的性质")
        check("归一化后不含任何空白", AppPassword.normalize(" W \u3000 j\tw ").none { it.isWhitespace() })
        check("归一化是幂等的", AppPassword.normalize(AppPassword.normalize("Ｗ Ｊ Ｗ")) == AppPassword.normalize("Ｗ Ｊ Ｗ"))
        check("归一化不会把中文吞掉", AppPassword.normalize("王").isNotEmpty())
        check("归一化把全角数字转半角", AppPassword.normalize("１２３") == "123")

        println("\n[6] 提示语")
        check("提示语非空", AppPassword.HINT.isNotBlank())
        check("提示语本身不能就是口令（否则等于白给）", !AppPassword.matches(AppPassword.HINT))
        println("      界面提示：${AppPassword.HINT}")

        // ---------------- 算术题 ----------------

        println("\n[7] 算术题：题面与答案")
        val add = ArithmeticChallenge(37, 24, '+')
        check("加法题面", add.text == "37 + 24 = ?", add.text)
        check("加法答案", add.answer == 61, "${add.answer}")
        val sub = ArithmeticChallenge(52, 17, '-')
        check("减法题面", sub.text == "52 - 17 = ?", sub.text)
        check("减法答案", sub.answer == 35, "${sub.answer}")

        println("\n[8] 算术题：答案校验")
        check("正确数字通过", AppPassword.checkArithmetic(add, "61"))
        check("前后空格通过", AppPassword.checkArithmetic(add, "  61  "))
        check("全角数字通过", AppPassword.checkArithmetic(add, "６１"))
        check("错误答案拒绝", !AppPassword.checkArithmetic(add, "62"))
        check("空输入拒绝", !AppPassword.checkArithmetic(add, ""))
        check("纯空格拒绝", !AppPassword.checkArithmetic(add, "   "))
        check("非数字拒绝", !AppPassword.checkArithmetic(add, "abc"))
        check("小数拒绝（\"61.0\" 不该当作 61）", !AppPassword.checkArithmetic(add, "61.0"))
        check("别题的答案不能通过", !AppPassword.checkArithmetic(add, "${sub.answer}"))
        check("负数输入不通过减法题", !AppPassword.checkArithmetic(sub, "-35"))

        println("\n[9] 算术题：随机生成的约束（采样 500 次）")
        val rnd = Random(20240918)
        var sawAdd = false
        var sawSub = false
        var addMin = 999
        var addMax = -999
        var subMin = 999
        var subMax = -999
        var bad = 0
        repeat(500) {
            val c = AppPassword.newChallenge(rnd)
            when (c.op) {
                '+' -> {
                    sawAdd = true
                    addMin = minOf(addMin, c.answer)
                    addMax = maxOf(addMax, c.answer)
                    if (c.left !in 11..49 || c.right !in 11..49) bad++
                }
                '-' -> {
                    sawSub = true
                    subMin = minOf(subMin, c.answer)
                    subMax = maxOf(subMax, c.answer)
                    // 被减数必须更大，否则答案会是负数
                    if (c.right !in 11..39 || c.left <= c.right) bad++
                }
                else -> bad++
            }
        }
        check("两种运算都出现（不是只会出加法）", sawAdd && sawSub)
        check("所有题目都满足约束", bad == 0, "$bad 道不合规")
        check("加法结果在 22~98", addMin >= 22 && addMax <= 98, "$addMin~$addMax")
        check("减法结果**永远非负**且在 1~39", subMin >= 1 && subMax <= 39, "$subMin~$subMax")
        println("      加法结果区间 $addMin~$addMax，减法结果区间 $subMin~$subMax")

        println("\n[10] 算术题：同一道题能反复校验（判题不消耗题目）")
        val fixed = ArithmeticChallenge(40, 20, '+')
        check("第一次", AppPassword.checkArithmetic(fixed, "60"))
        check("第二次仍然通过", AppPassword.checkArithmetic(fixed, "60"))
        check("正确答案不随次数变化", fixed.answer == 60)

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
