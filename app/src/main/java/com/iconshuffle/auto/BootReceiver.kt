package com.iconshuffle.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.iconshuffle.core.Prefs

/**
 * 开机 / 应用升级后把每日任务补回队列。
 * WorkManager 自身也持久化任务，这里是双保险（部分 ROM 会清理 WorkManager 数据库）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "reschedule after $action")
        DailyShuffleScheduler.sync(context, Prefs(context))
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
