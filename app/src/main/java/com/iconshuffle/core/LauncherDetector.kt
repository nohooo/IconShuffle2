package com.iconshuffle.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * 识别当前桌面（Launcher）与其数据库路径。
 *
 * 顺序很重要：
 *  1. 先问系统「谁是 HOME」，这是唯一权威答案（HyperOS 上是 com.miui.home）；
 *  2. 再按候选相对路径逐个探测 databases/launcher.db 是否存在；
 *  3. 都拿不到就回落成候选包名列表，交给特权侧实际探测文件系统。
 */
object LauncherDetector {

    /** 常见桌面包名 → databases 目录下的候选文件名。 */
    private val KNOWN_HOMES = listOf(
        "com.miui.home" to listOf("launcher.db", "launcher.db-journal"),
        "com.android.launcher3" to listOf("launcher.db"),
        "com.google.android.apps.nexuslauncher" to listOf("launcher.db"),
        "app.lawnchair" to listOf("launcher.db"),
        "com.android.launcher" to listOf("launcher.db"),
    )

    data class Home(val packageName: String, val label: String)

    /** 当前默认桌面。 */
    fun currentHome(context: Context): Home? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates: List<ResolveInfo> =
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        // 优先取系统标记为 preferred 的那个
        val preferred = candidates.firstOrNull {
            it.activityInfo?.applicationInfo?.let { ai ->
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } == true
        } ?: candidates.firstOrNull()
        val pkg = preferred?.activityInfo?.packageName ?: return null
        val label = preferred.loadLabel(pm).toString()
        return Home(pkg, label)
    }

    /** 系统里所有可当桌面的包（诊断用，可以看到 HyperOS 装了几个桌面）。 */
    fun allHomes(context: Context): List<Home> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                Home(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
    }

    /**
     * 该包名下 launcher.db 的候选绝对路径。
     * 同时给出 /data/data 与 /data/user/0 两套（前者是符号链接，MIUI 上两者等价）。
     */
    fun candidateDbPaths(packageName: String): List<String> {
        val names = KNOWN_HOMES.firstOrNull { it.first == packageName }?.second
            ?: listOf("launcher.db")
        val roots = listOf("/data/data/$packageName", "/data/user/0/$packageName")
        return roots.flatMap { root -> names.map { "$root/databases/$it" } }
    }

    /** 备份目录也放在同一 databases 目录下，方便特权侧写入。 */
    fun backupDir(packageName: String): String = "/data/data/$packageName/databases"

    /**
     * 从数据库绝对路径反推包名。
     * 需要同时支持 /data/data/<pkg>/... 与 /data/user/0/<pkg>/... 两种布局。
     */
    fun packageOf(dbPath: String): String? {
        val parts = dbPath.split('/').filter { it.isNotEmpty() }
        // /data/data/<pkg>/databases/launcher.db  → [data, data, pkg, databases, launcher.db]
        if (parts.size >= 3 && parts[0] == "data" && parts[1] == "data") return parts[2]
        // /data/user/0/<pkg>/databases/launcher.db → [data, user, 0, pkg, databases, launcher.db]
        if (parts.size >= 4 && parts[0] == "data" && parts[1] == "user") return parts[3]
        return null
    }
}
