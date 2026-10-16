package com.iconshuffle.auto

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.iconshuffle.core.Prefs
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日自动打乱的调度。
 *
 * 为什么用 WorkManager 而不是 AlarmManager：
 *  - 不需要精确到秒（打乱桌面早几分钟晚几分钟无所谓），因此避开
 *    Android 12+ 的 SCHEDULE_EXACT_ALARM 权限与厂商后台限制；
 *  - 重启后自动恢复，无需额外处理。
 *
 * 首次执行时间用 setInitialDelay 对齐到用户设定的「当天第几分钟」，
 * flex 窗口给系统 15 分钟调度余量。
 */
object DailyShuffleScheduler {

    private const val WORK_NAME = "daily_icon_shuffle"

    fun sync(context: Context, prefs: Prefs) {
        if (!prefs.autoEnabled) {
            cancel(context)
            return
        }
        schedule(context, prefs.autoMinuteOfDay)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun schedule(context: Context, minuteOfDay: Int) {
        val initialDelay = delayUntil(minuteOfDay)

        val request = PeriodicWorkRequestBuilder<DailyShuffleWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE：改时间时保留已有周期语义，避免重复入队
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 距离下一个 minuteOfDay 还有多少毫秒。 */
    fun delayUntil(minuteOfDay: Int, now: Long = System.currentTimeMillis()): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }

    const val WORK_TAG = WORK_NAME
}
