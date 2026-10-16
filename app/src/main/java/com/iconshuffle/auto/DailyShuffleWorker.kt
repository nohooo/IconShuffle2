package com.iconshuffle.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iconshuffle.IconShuffleApp
import com.iconshuffle.R
import com.iconshuffle.core.LauncherDbChannel
import com.iconshuffle.core.Prefs
import com.iconshuffle.shizuku.ShizukuGateway

/**
 * 每日自动打乱的实际执行体。
 *
 * 注意：Worker 跑在后台，Shizuku 的用户服务需要重新绑定，
 * 因此这里 awaitService 超时要给足（冷启动 bindUserService 可能要几秒）。
 */
class DailyShuffleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val gateway = ShizukuGateway.get()

        if (!prefs.autoEnabled) return Result.success()

        // 兜底：即使调度被重复触发，一天也只打乱一次
        val sinceLast = System.currentTimeMillis() - prefs.lastShuffleAt
        if (prefs.lastShuffleAt > 0 && sinceLast < MIN_INTERVAL_MS) {
            Log.i(TAG, "skip: 距上次打乱仅 ${sinceLast / 1000}s")
            return Result.success()
        }

        if (!gateway.hasPermission()) {
            Log.w(TAG, "skip: 无 Shizuku 权限")
            notify("自动打乱未执行", "Shizuku 未授权，请打开应用重新授权")
            return Result.failure()
        }

        val service = gateway.awaitService(timeoutMs = 15_000)
        if (service == null) {
            Log.w(TAG, "skip: 用户服务未就绪")
            notify("自动打乱未执行", "Shizuku 特权服务连接超时")
            return Result.retry()
        }

        val channel = LauncherDbChannel(applicationContext, gateway)
        val result = channel.shuffle(
            scope = prefs.scope,
            keepDock = prefs.keepDock,
            restartLauncher = false,
        )

        prefs.lastResult = result.log.joinToString("\n")
        if (result.ok) {
            prefs.lastShuffleAt = System.currentTimeMillis()
            notify("桌面已自动打乱", result.message)
            return Result.success()
        }
        notify("自动打乱失败", result.message)
        return Result.failure()
    }

    private fun notify(title: String, text: String) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动打乱结果", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "无通知权限，跳过通知：$title / $text")
            return
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "DailyShuffleWorker"
        private const val CHANNEL_ID = "auto_shuffle"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_INTERVAL_MS = 20L * 60 * 60 * 1000 // 20 小时
    }
}
