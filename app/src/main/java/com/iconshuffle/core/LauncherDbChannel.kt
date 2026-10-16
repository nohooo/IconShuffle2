package com.iconshuffle.core

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.iconshuffle.shizuku.IPrivilegedService
import com.iconshuffle.shizuku.PrivilegedService
import com.iconshuffle.shizuku.ShizukuGateway
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * launcher.db 直读直写通道（需求里的「路径②」）。
 *
 * 端到端流程：
 *   1. 特权侧探测：确认桌面包名、launcher.db 是否存在、可读、可写、SQLite 头正常；
 *   2. 备份：特权侧把 launcher.db 复制到暂存目录（带时间戳），随时可回滚；
 *   3. 拉取：宿主把暂存文件复制进自己的 files 目录（自己的目录才敢写）；
 *   4. 解析 + 打乱：LauncherDb 读 favorites，ShuffleEngine 产出坐标交换方案；
 *   5. 落盘：事务内只 UPDATE cellX/cellY；
 *   6. 校验：重新打开读一遍，确认坐标真的变了；
 *   7. 写回：宿主把改好的文件写到暂存目录，特权侧覆盖回 databases/launcher.db 并 chmod 600；
 *   8. 重启桌面（可选）：pkill 桌面包名，由系统重新拉起。
 *
 * 暂存目录必须选「宿主和 shell 双向可访问」的位置：App 的 externalFilesDir。
 * 不用 /data/local/tmp，因为 Android 11+ 的 app 进程访问 /data/local/tmp 会被拦截。
 */
class LauncherDbChannel(
    private val context: Context,
    private val gateway: ShizukuGateway,
) {

    private val service: IPrivilegedService
        get() = gateway.requireService()

    // ------------------------------------------------------------- 探测

    data class DetectResult(
        val launcherPackage: String?,
        val launcherLabel: String?,
        val dbPath: String?,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean,
        val sizeBytes: Long,
        val sqlite3Available: Boolean,
        val privilegedProbe: String,
        val notes: MutableList<String> = mutableListOf(),
    ) {
        val ok: Boolean get() = exists && readable && dbPath != null
    }

    fun detect(includePrivilegedReport: Boolean = true): DetectResult {
        val s = service
        val home = LauncherDetector.currentHome(context)
        val notes = mutableListOf<String>()
        val pkg = home?.packageName
        val probeText = if (includePrivilegedReport) runCatching { s.probe() }.getOrDefault("") else ""

        if (pkg == null) {
            notes += "系统没有报告默认桌面（queryIntentActivities(CATEGORY_HOME) 为空）"
            return DetectResult(null, null, null, false, false, false, -1, false, probeText, notes)
        }

        val candidates = LauncherDetector.candidateDbPaths(pkg)
        val found = candidates.firstOrNull { runCatching { s.canRead(it) }.getOrDefault(false) }
            ?: candidates.firstOrNull { runCatching { s.length(it) > 0 }.getOrDefault(false) }

        val path = found ?: candidates.first()
        val exists = runCatching { s.length(path) > 0 }.getOrDefault(false)
        val readable = runCatching { s.canRead(path) }.getOrDefault(false)
        // 用 root 身份当「写权限代理」：root 无视 Unix 权限与 SELinux，
        // 能写即说明目录可写（再看当前身份是不是 root 就能推断自身能否写）
        val uid = runCatching {
            s.exec("id -u").let { out -> out.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull() }
        }.getOrNull()
        val rootWritable = runCatching {
            val probe = "$path.iconShuffleWriteTest"
            val ok = s.exec("cp '$path' '$probe'").trimStart().startsWith("0")
            if (ok) s.exec("rm -f '$probe'")
            ok
        }.getOrDefault(false)
        val writable = rootWritable && uid == 0

        val size = runCatching { s.length(path) }.getOrDefault(-1L)
        val sqlite3 = runCatching {
            s.exec("command -v sqlite3 >/dev/null 2>&1; echo $?").lineSequence()
                .drop(1).firstOrNull { it.isNotBlank() }?.trim() == "0"
        }.getOrDefault(false)

        if (found == null) {
            notes += "候选路径都读不到，已列出：$candidates"
        }
        if (exists && !readable) {
            notes += "文件存在但特权身份读不到 → Shizuku 以 shell 身份运行，且该 ROM 不允许 shell 访问 app 数据目录（SELinux app_data_file / MLS 类别不匹配）"
        }
        if (readable && uid != null && uid != 0) {
            notes += "当前特权身份 uid=$uid（非 root）。即使能读，写入 databases 目录也大概率被拒（目录属主是桌面 App）"
        }
        if (readable && !sqlite3) {
            notes += "系统没有 sqlite3 可执行文件 → 走「复制到暂存目录 + Android SQLiteDatabase 改写」路线（不依赖 sqlite3，已实现）"
        }

        return DetectResult(pkg, home.label, path, exists, readable, writable, size, sqlite3, probeText, notes)
    }

    // ------------------------------------------------------------- 主流程

    data class ApplyResult(
        val ok: Boolean,
        val message: String,
        val moves: Int,
        val backupPath: String?,
        val log: List<String>,
    )

    fun shuffle(
        scope: ShuffleScope,
        keepDock: Boolean,
        restartLauncher: Boolean = false,
        random: Random = Random.Default,
    ): ApplyResult {
        val log = mutableListOf<String>()
        fun step(msg: String) {
            Log.i(TAG, msg)
            log += msg
        }

        val detect = detect(includePrivilegedReport = false)
        if (!detect.ok) {
            return ApplyResult(false, "无法读取 launcher.db：${detect.notes.joinToString("；")}", 0, null, log)
        }
        val dbPath = detect.dbPath!!
        step("桌面 = ${detect.launcherPackage} (${detect.launcherLabel})")
        step("数据库 = $dbPath (${detect.sizeBytes} 字节, 可写=${detect.writable})")

        val staging = stagingDir()
        if (!staging.exists() && !staging.mkdirs()) {
            return ApplyResult(false, "无法创建暂存目录 ${staging.absolutePath}", 0, null, log)
        }

        val s = service
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        // 1) 备份到桌面自己的 databases 目录 + 暂存目录各一份
        val backupInPlace = "${LauncherDetector.backupDir(detect.launcherPackage!!)}/launcher.db.bak-$stamp"
        val backupCode = runCatching { s.copyFile(dbPath, backupInPlace) }.getOrDefault(-99L)
        if (backupCode >= 0) {
            step("已备份（设备内）: $backupInPlace ($backupCode 字节)")
            runCatching { s.exec("chmod 600 '$backupInPlace'") }
        } else {
            step("⚠️ 设备内备份失败（错误码 $backupCode），继续但请注意风险")
        }

        val stagedOriginal = File(staging, "launcher.db.original")
        val pulled = runCatching { s.copyFile(dbPath, stagedOriginal.absolutePath) }.getOrDefault(-99L)
        if (pulled < 0) {
            return ApplyResult(
                false,
                "复制 launcher.db 到暂存目录失败（错误码 $pulled）。" +
                    "若 Shizuku 以 shell 身份运行，多数 HyperOS/Android 16 会在这里失败。",
                0, null, log
            )
        }
        if (!stagedOriginal.exists()) {
            // 特权侧写完，宿主可能因 FUSE 缓存看不到，重试一次
            Thread.sleep(150)
            if (!stagedOriginal.exists()) {
                return ApplyResult(
                    false,
                    "暂存文件对宿主不可见：${stagedOriginal.absolutePath}。" +
                        "externalFilesDir 可能被 ROM 限制，请把这份日志发给我换暂存目录方案。",
                    0, null, log
                )
            }
        }
        step("已拉取到宿主: ${stagedOriginal.absolutePath} (${stagedOriginal.length()} 字节)")

        // 2) 在宿主自己的目录里工作
        val workFile = File(context.filesDir, "launcher.work.db")
        stagedOriginal.copyTo(workFile, overwrite = true)
        stagedOriginal.delete() // 暂存目录里不留原始数据

        val db = LauncherDb(workFile)
        if (!db.hasSqliteHeader()) {
            return ApplyResult(false, "拉取到的文件不是标准 SQLite 库，可能被 ROM 加密或只是 WAL", 0, backupInPlace, log)
        }
        db.headerInfo()?.let { (pageSize, pageCount, cookie) ->
            step("SQLite: pageSize=$pageSize pages=$pageCount schemaCookie=$cookie")
        }

        val snapshot = db.read()
        step("读到 favorites 共 ${snapshot.itemCount} 行，桌面 ${snapshot.pageCount} 页，列=${snapshot.columns.size} 个")

        // 「仅当前页面」需要知道打乱哪一页。用 modified 时间戳推测用户刚操作过的那页。
        val perScreen = db.itemsPerScreen(snapshot)
        val currentScreen = if (scope == ShuffleScope.CURRENT_PAGE) db.guessCurrentScreen(snapshot) else 0
        step("每页图标数：" + perScreen.entries.sortedBy { it.key }.joinToString(" ") { "第${it.key + 1}页=${it.value}" })

        val result = ShuffleEngine.plan(snapshot, scope, keepDock, currentScreen, random)
        val scopeText = if (scope == ShuffleScope.ALL_PAGES) {
            "所有页面（逐页独立打乱）"
        } else {
            "仅第 ${currentScreen + 1} 页"
        }
        step("范围=$scopeText，候选 ${result.candidates} 个，产生 ${result.moves.size} 次换位，冲突跳过 ${result.skippedByCollision}")
        step("处理页面：" + result.screens.joinToString(" ") { "第${it + 1}页" })
        step("实际发生变化：" + result.changedScreens.joinToString(" ") { "第${it + 1}页" })

        if (result.moves.isEmpty()) {
            return ApplyResult(
                false,
                if (perScreen.values.all { it < 2 }) {
                    "没有可打乱的图标：每页都少于 2 个可交换图标（widget 不参与）"
                } else {
                    "该页没有可打乱的图标（可能全是 widget，或只有 1 个图标）"
                },
                0, backupInPlace, log
            )
        }

        val changed = db.applyMoves(result.moves)
        step("已事务提交 $changed 行 UPDATE")

        val problems = db.verifyMoves(result.moves)
        if (problems.isNotEmpty()) {
            step("⚠️ 校验发现问题：${problems.take(5).joinToString("; ")}")
        } else {
            step("校验通过：所有目标坐标已落库")
        }

        // 3) 写回设备
        val stagedModified = File(staging, "launcher.db.modified")
        workFile.copyTo(stagedModified, overwrite = true)
        val pushBack = pushBackToDevice(s, stagedModified, dbPath, detect.launcherPackage!!, ::step)
        if (pushBack < 0) {
            return ApplyResult(
                false,
                "写回 launcher.db 失败（错误码 $pushBack）。" +
                    "launcher.db 属于桌面 App 且通常是 0600，shell 身份直接覆盖会被拒。" +
                    "此时请用 root 启动 Shizuku，或改用「布局提供者」通道。",
                0, backupInPlace, log
            )
        }
        step("已写回 $dbPath ($pushBack 字节)")
        stagedModified.delete()

        if (restartLauncher) {
            val killed = runCatching { s.killProcess(detect.launcherPackage!!) }.getOrDefault(false)
            step(if (killed) "已结束桌面进程，等待系统重新拉起" else "重启桌面失败（请手动按 Home 或重启手机）")
        } else {
            step("桌面可能仍在使用旧数据库的内存副本，若界面没变化请在最近任务里清掉桌面或重启手机")
        }

        workFile.delete()

        return ApplyResult(
            ok = true,
            message = "完成：交换 ${result.moves.size} 个图标位置",
            moves = result.moves.size,
            backupPath = backupInPlace,
            log = log,
        )
    }

    /**
     * 把改好的数据库放回设备。
     *
     * 这里不能简单地「原地覆盖」：launcher.db 属于桌面 App、权限一般是 0600，
     * shell 身份即使能读也写不进去。所以按可靠性依次尝试：
     *
     *   A. 直接复制（root 身份下这一步就成功了）；
     *   B. 先 cp 到 databases/ 下的临时文件 → chown 成桌面 App 的 uid:gid →
     *      chmod 600 → 用 mv 原子替换。mv 只要求「目录可写」，不要求目标文件可写，
     *      因此这一条是 shell 身份下最可能成功的路径；
     *   C. 如果 chown 失败（shell 无 CAP_CHOWN），退一步用 0644 让桌面至少能读。
     *
     * @return 成功返回写入字节数，失败返回负错误码。
     */
    private fun pushBackToDevice(
        s: IPrivilegedService,
        local: File,
        dbPath: String,
        launcherPackage: String,
        step: (String) -> Unit,
    ): Long {
        // A. 直接覆盖
        val direct = runCatching { s.copyFile(local.absolutePath, dbPath) }.getOrDefault(-99L)
        if (direct >= 0) return direct

        step("直接覆盖被拒（错误码 $direct），改用「临时文件 + 原子替换」路径")

        // B/C. 临时文件 + mv
        val tmp = "$dbPath.new"
        val staged = runCatching { s.copyFile(local.absolutePath, tmp) }.getOrDefault(-99L)
        if (staged < 0) {
            step("连临时文件都写不进去（错误码 $staged）→ databases 目录对当前身份不可写")
            return staged
        }

        // 取桌面 App 的 uid:gid，尽量把属主改回去，避免桌面读自己的库被 SELinux 拦
        val owner = runCatching {
            s.exec("stat -c '%u:%g' /data/data/$launcherPackage").let { out ->
                out.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.trim()
            }
        }.getOrNull()

        var chownOk = false
        if (!owner.isNullOrBlank()) {
            val out = runCatching { s.exec("chown $owner '$tmp'") }.getOrDefault("-1")
            chownOk = out.trimStart().startsWith("0")
            step("chown $owner '$tmp' => ${if (chownOk) "成功" else "失败（无 CAP_CHOWN）"}")
        }

        // 属主改不动时，至少让文件可读，否则桌面可能连读都读不了
        runCatching { s.exec("chmod ${if (chownOk) "600" else "644"} '$tmp'") }

        val mvOut = runCatching { s.exec("mv '$tmp' '$dbPath'") }.getOrDefault("-1")
        val mvOk = mvOut.trimStart().startsWith("0")
        if (!mvOk) {
            step("mv 替换失败：${mvOut.lineSequence().drop(1).firstOrNull { it.isNotBlank() } ?: ""}")
            return -4L
        }
        return runCatching { s.length(dbPath) }.getOrDefault(-5L)
    }

    // ------------------------------------------------------------- 回滚

    /** 列出设备内已有的备份文件。 */
    fun listBackups(dbPath: String): List<String> {
        val dir = dbPath.substringBeforeLast('/')
        return runCatching { service.listDir(dir).filter { it.contains("launcher.db.bak") }.sorted() }
            .getOrDefault(emptyList())
    }

    /**
     * 从备份恢复。
     *
     * 走和打乱相同的写回路径，不要直接 copyFile：
     * 备份文件是 shell 身份创建的，直接覆盖到 0600 的 launcher.db 会失败。
     */
    fun restore(backupPath: String, dbPath: String, launcherPackage: String): String {
        val s = service
        val staging = stagingDir()
        if (!staging.exists() && !staging.mkdirs()) {
            return "无法创建暂存目录 ${staging.absolutePath}"
        }
        val local = File(staging, "launcher.db.restore")
        val pulled = runCatching { s.copyFile(backupPath, local.absolutePath) }.getOrDefault(-99L)
        if (pulled < 0) return "取回备份失败，错误码 $pulled"

        val code = pushBackToDevice(s, local, dbPath, launcherPackage) { /* 回滚不需要逐步日志 */ }
        local.delete()
        return if (code >= 0) "已从 $backupPath 恢复（$code 字节）" else "恢复失败，错误码 $code"
    }

    // ------------------------------------------------------------- 工具

    private fun stagingDir(): File {
        val ext = context.getExternalFilesDir(null) ?: context.filesDir
        return File(ext, "staging")
    }

    companion object {
        private const val TAG = "LauncherDbChannel"
    }
}
