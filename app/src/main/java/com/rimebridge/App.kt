package com.rimebridge

import android.app.Application

/**
 * 只干一件事：把未捕获异常的堆栈落到 filesDir/crash.txt。
 * 崩溃时进程立刻死，界面上什么都来不及显示；写盘之后下次打开 App 会把这段贴到状态区顶部。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Journal.crash(this, e) }
            // 交回系统（或原来的处理者），保持正常的崩溃行为
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
