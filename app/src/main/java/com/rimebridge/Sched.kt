package com.rimebridge

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * 调度：一个持久化周期作业当主力，一个 expedited 一次性作业应对「现在就要」。
 * 刻意不用 WorkManager / AlarmManager / 前台服务（见方案 3.5）。
 */
object Sched {

    const val JOB_PERIODIC = 1001
    const val JOB_ONCE = 1002

    private fun cn(c: Context) = ComponentName(c.packageName, SyncJobService::class.java.name)

    private fun js(c: Context): JobScheduler? = c.getSystemService(JobScheduler::class.java)

    /**
     * 没有作业、或作业参数变了才重新排队——避免每次打开 App 都把周期计时重置。
     * 强停之后作业会被系统清空，所以 App 启动、磁贴监听时都调用一次。
     */
    fun ensureScheduled(c: Context) {
        val s = js(c) ?: return
        val min = Prefs.interval(c).coerceIn(15, 1440)
        val flex = maxOf(5, min / 3)
        // JobInfo.Builder.build() 在某些 ROM 上会抛参数异常，别让它把调用方带崩
        val want = runCatching {
            JobInfo.Builder(JOB_PERIODIC, cn(c))
                .setPeriodic(min * 60_000L, flex * 60_000L)
                .setPersisted(true)
                .build()
        }.getOrElse {
            Journal.w(c, "周期作业参数被拒：${it.message}")
            return
        }
        val cur = runCatching { s.getPendingJob(JOB_PERIODIC) }.getOrNull()
        val same = cur != null &&
            cur.intervalMillis == want.intervalMillis &&
            cur.flexMillis == want.flexMillis &&
            cur.isPersisted
        if (same) return
        val r = runCatching { s.schedule(want) }.getOrElse { -1 }
        if (r == JobScheduler.RESULT_SUCCESS) {
            Journal.w(c, "已排周期作业：每 $min 分钟（flex $flex）")
        } else {
            Journal.w(c, "周期作业排队失败：$r")
        }
    }

    /**
     * 排一个「马上跑」的作业；delayMs > 0 时用于源文件未稳定后的补跑。
     *
     * 两级回落，每一级都单独 try：
     *  1. expedited（Android 12+，走配额，通常几秒内就跑）
     *  2. 普通作业 + deadline
     *
     * 第 2 级别的 deadline 必须比 minLatency 晚至少 15 分钟：Android 15 起，
     * 「最早/最晚触发时间相距 < 15 min」会让 JobInfo.Builder.build() 直接抛
     * IllegalArgumentException（在本进程里就是闪退）。以前写成 2 分钟，正好撞这条。
     */
    fun runOnce(c: Context, delayMs: Long = 0L): Boolean {
        val s = js(c) ?: return false
        val minWindow = 15 * 60_000L

        val exp = build(c) { b ->
            b.setExpedited(true)
            if (delayMs > 0) b.setMinimumLatency(delayMs)
        }
        if (exp != null &&
            runCatching { s.schedule(exp) }.getOrNull() == JobScheduler.RESULT_SUCCESS
        ) {
            return true
        }

        val plain = build(c) { b ->
            if (delayMs > 0) b.setMinimumLatency(delayMs)
            b.setOverrideDeadline(delayMs + minWindow + 1_000L)
        }
        return plain != null &&
            runCatching { s.schedule(plain) }.getOrNull() == JobScheduler.RESULT_SUCCESS
    }

    /** build() 抛异常时返回 null，别把调用方带崩。 */
    private inline fun build(c: Context, config: (JobInfo.Builder) -> Unit): JobInfo? =
        runCatching {
            val b = JobInfo.Builder(JOB_ONCE, cn(c))
            config(b)
            b.build()
        }.getOrNull()

    fun pendingPeriodic(c: Context): JobInfo? =
        js(c)?.let { runCatching { it.getPendingJob(JOB_PERIODIC) }.getOrNull() }

    /** 给界面看的一行状态。API 36 才有的暂停原因用反射取，编不过就算了。 */
    fun statusText(c: Context): String {
        val s = js(c) ?: return "作业调度不可用"
        val p = runCatching { s.getPendingJob(JOB_PERIODIC) }.getOrNull()
            ?: return "周期作业：未排队（打开 App 或下拉磁贴可补排）"
        val sb = StringBuilder()
        sb.append("周期作业：每 ").append(p.intervalMillis / 60_000).append(" 分钟")
        sb.append("，flex ").append(p.flexMillis / 60_000).append(" 分钟")
        sb.append("，persisted=").append(p.isPersisted)
        if (Build.VERSION.SDK_INT >= 36) {
            val reasons = runCatching {
                val m = JobScheduler::class.java.getMethod("getPendingJobReasons", Int::class.java)
                m.invoke(s, JOB_PERIODIC) as? IntArray
            }.getOrNull()
            if (reasons != null && reasons.isNotEmpty()) {
                sb.append("\n暂停原因：").append(reasons.joinToString(","))
            }
        }
        return sb.toString()
    }
}
