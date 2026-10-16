package com.iconshuffle

import android.app.Application
import com.iconshuffle.auto.DailyShuffleScheduler
import com.iconshuffle.core.Prefs
import com.iconshuffle.shizuku.ShizukuGateway

class IconShuffleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Shizuku UserService 的 ComponentName 需要绝对包名，这里注入一次
        ShizukuGateway.APP_PACKAGE = packageName
        ShizukuGateway.get().attach()

        // 开机/升级后把每日任务补回来（WorkManager 自己也会持久化，这里是双保险）
        val prefs = Prefs(this)
        DailyShuffleScheduler.sync(this, prefs)
    }
}
