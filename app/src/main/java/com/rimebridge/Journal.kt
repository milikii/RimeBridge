package com.rimebridge

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * filesDir/log.txt 的环形日志：超过 32 KB 就把前半段丢掉，只留最近一段。
 * 不写 logcat 之外的地方，也不发通知。
 */
object Journal {

    private const val TAG = "RimeBridge"
    private const val MAX = 32 * 1024
    private const val KEEP = 24 * 1024

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun w(c: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(c.filesDir, "log.txt")
            val line = fmt.format(Date()) + " " + msg + "\n"
            f.appendText(line)
            if (f.length() > MAX) trim(f)
        } catch (t: Throwable) {
            Log.w(TAG, "写日志失败", t)
        }
    }

    private fun trim(f: File) {
        val text = f.readText()
        val tail = if (text.length > KEEP) text.substring(text.length - KEEP) else text
        val cut = tail.indexOf('\n')
        f.writeText(if (cut in 0 until tail.length - 1) tail.substring(cut + 1) else tail)
    }

    fun read(c: Context, maxLines: Int = 50, maxChars: Int = 12_000): String {
        return try {
            val f = File(c.filesDir, "log.txt")
            if (!f.exists()) return "（还没有日志）"
            val text = f.readText()
            val body = if (text.length > maxChars) text.substring(text.length - maxChars) else text
            val lines = body.split('\n').filter { it.isNotBlank() }
            if (lines.size <= maxLines) body.trim() else lines.takeLast(maxLines).joinToString("\n")
        } catch (t: Throwable) {
            "日志读取失败：$t"
        }
    }

    fun clear(c: Context) {
        try {
            File(c.filesDir, "log.txt").delete()
        } catch (_: Throwable) {
        }
    }

    // ---- 崩溃记录：崩溃时进程会死，日志只能先落盘，下次打开再给用户看 ----

    private const val CRASH = "crash.txt"

    @Synchronized
    fun crash(c: Context, t: Throwable) {
        Log.e(TAG, "崩溃", t)
        try {
            val f = File(c.filesDir, CRASH)
            val head = fmt.format(Date()) + " " + t.javaClass.name + ": " + (t.message ?: "") + "\n"
            val body = t.stackTraceToString()
            f.appendText(head + body + "\n\n")
            if (f.length() > MAX) trim(f)
        } catch (_: Throwable) {
        }
    }

    fun readCrash(c: Context, maxChars: Int = 4000): String? {
        return try {
            val f = File(c.filesDir, CRASH)
            if (!f.exists() || f.length() == 0L) return null
            val text = f.readText()
            if (text.length > maxChars) text.substring(text.length - maxChars) else text
        } catch (_: Throwable) {
            null
        }
    }

    fun clearCrash(c: Context) {
        try {
            File(c.filesDir, CRASH).delete()
        } catch (_: Throwable) {
        }
    }
}
