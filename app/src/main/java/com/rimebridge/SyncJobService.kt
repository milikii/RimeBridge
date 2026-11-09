package com.rimebridge

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 作业回调在主线程，所以这里只起线程立刻返回（否则 10 s 内部件超时会 ANR）。
 * 工作线程必须持有 JobParameters，finally 里一定 jobFinished——Android 16 新增
 * 的「作业被遗弃」停止原因就是针对漏调 jobFinished 的。
 */
class SyncJobService : JobService() {

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var syncer: Syncer? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        // 这里抛异常就是整个进程闪退（本次就是靠这条兜住），所以整段都包起来
        return try {
            if (worker?.isAlive == true) {
                // 上一轮还没结束：不排队，直接算这轮完成
                jobFinished(params, false)
                return false
            }
            val s = Syncer(app)
            syncer = s
            val t = Thread({
                try {
                    val r = s.runRound()
                    // 源文件还在写：15 s 后补跑一次；60 s 内只补一次，避免自己把自己叫醒成循环
                    if (r.second) {
                        val now = System.currentTimeMillis()
                        if (now - Prefs.followupAt(app) > 60_000L) {
                            Prefs.setFollowupAt(app, now)
                            Sched.runOnce(app, 15_000L)
                            Journal.w(app, "15 s 后补跑一次")
                        }
                    }
                } catch (t: Throwable) {
                    Journal.crash(app, t)
                    Journal.w(app, "作业异常：$t")
                } finally {
                    runCatching { jobFinished(params, false) }
                    syncer = null
                    worker = null
                }
            }, "rimebridge-sync")
            t.isDaemon = true
            worker = t
            t.start()
            true
        } catch (t: Throwable) {
            Journal.crash(app, t)
            runCatching { jobFinished(params, false) }
            false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        syncer?.cancel()
        worker?.interrupt()
        return true
    }
}
