package com.trailrun.mockgps.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志记录。
 *
 * 为什么需要它：这个应用在多轮修改后出现过「一进就闪退」，
 * 而开发者拿不到真机日志时只能靠猜 —— 猜错一次用户就要多等一轮。
 * 这里把未捕获异常的完整堆栈写到应用私有目录，界面里可以直接查看并复制。
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last_crash.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * 在 Application.onCreate 里调用。
     * 注意：这里只做「记录并转交」，不吞掉异常 —— 让系统照常崩溃，
     * 否则应用会停在一个不确定的状态里，问题更难查。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
                .onFailure { Log.w(TAG, "写崩溃日志失败", it) }
            // 交回原处理器，保留系统默认行为
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(app: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        pw.println("线程: " + thread.name)
        pw.println("Android: " + android.os.Build.VERSION.RELEASE +
            " (API " + android.os.Build.VERSION.SDK_INT + ")")
        pw.println("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
        pw.println()
        throwable.printStackTrace(pw)
        pw.flush()
        file(app).writeText(sw.toString())
        Log.e(TAG, "已记录崩溃日志", throwable)
    }

    /** 读取上次崩溃日志；没有则返回 null。 */
    fun read(context: Context): String? {
        val f = file(context)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 是否记录过崩溃。 */
    fun hasCrash(context: Context): Boolean = file(context).isFile
}
