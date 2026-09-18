package com.trailrun.mockgps.core

/**
 * 进入口令的纯逻辑验证（纯 JVM，不需要 Android）。
 *
 * 这里守的是「明明输对了却进不去」这一类问题 —— 它们不会崩溃、不会报错，
 * 只是让人在锁屏上反复试，而且极难从代码上一眼看出来：
 *   - 大写 / 小写；
 *   - 前后和中间误打的空格；
 *   - 中文输入法打出来的**全角**字母（ｗｊｗ）；
 *   - 全角空格（U+3000）。
 *
 * 另外也要守住反面：不该通过的一定不能通过，
 * 尤其是空输入和只输了一个字符的情况。
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
        println("=== 进入口令验证 ===")

        println("\n[1] 正确口令")
        check("小写 wjw", ok("wjw"))
        check("大写 WJW", ok("WJW"))
        check("混写 WjW", ok("WjW"))
        check("全大写 wJw", ok("wJw"))

        println("\n[2] 空白容错")
        check("前后有空格 \" wjw \"", ok("  wjw  "))
        check("中间有空格 \"w j w\"", ok("w j w"))
        check("制表符/换行包裹", ok("\twjw\n"))
        check("全角空格包裹 \\u3000wjw\\u3000", ok("\u3000wjw\u3000"))

        println("\n[3] 全角字母（中文输入法）")
        check("全角 ｗｊｗ", ok("ｗｊｗ"))
        check("全角大写 ＷＪＷ", ok("ＷＪＷ"))
        check("全角半角混排 ｗjw", ok("ｗjw"))

        println("\n[4] 必须拒绝的输入")
        check("空串", no(""))
        check("纯空格", no("   "))
        check("纯全角空格", no("\u3000\u3000"))
        check("null 语义的空输入不越界（长度不足）", no("wj"))
        check("多一个字符 wjww", no("wjww"))
        check("少一个字符 wj", no("wj"))
        check("错误口令 abc", no("abc"))
        check("名字全拼 wangjiawei", no("wangjiawei"))
        check("相似但不同 vvv", no("vvv"))
        check("数字 888", no("888"))

        println("\n[5] 归一化本身的性质")
        check("归一化后不含任何空白", AppPassword.normalize(" W \u3000 j\tw ").none { it.isWhitespace() })
        check("归一化是幂等的", AppPassword.normalize(AppPassword.normalize("Ｗ Ｊ Ｗ")) == AppPassword.normalize("Ｗ Ｊ Ｗ"))
        check("归一化不吞掉非 ASCII 内容（不会把中文变成空）", AppPassword.normalize("王").isNotEmpty())

        println("\n[6] 提示语")
        check("提示语非空", AppPassword.HINT.isNotBlank())
        check("提示语本身不能就是口令（否则等于白给）", !AppPassword.matches(AppPassword.HINT))
        println("      界面提示：${AppPassword.HINT}")

        println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
        if (failures > 0) kotlin.system.exitProcess(1)
    }
}
