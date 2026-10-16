package com.iconshuffle.shizuku

import android.os.Process
import com.iconshuffle.shizuku.IPrivilegedService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku（shell 或 root）身份下的用户服务实现。
 *
 * 关键点：这个类在 **另一个进程** 里执行，进程 UID 是 shell(2000) 或 root(0)，
 * 不是本 App 的 UID。所以这里拿到的 File 权限 = 特权身份拥有的权限。
 *
 * 设计取舍：本类只做「文件搬运 + 身份探测 + 执行命令」。
 * 真正的 SQLite 解析放在 App 进程（见 LauncherDb.kt），原因：
 *  - shell 身份下调用 android.database.sqlite.SQLiteDatabase 常因 SELinux
 *    对 app_data_file 的 MLS 约束失败，不可靠；
 *  - 把文件复制到双方都能访问的暂存目录后再解析，行为可控、易排错。
 */
class PrivilegedService : IPrivilegedService.Stub() {

    override fun probe(): String = buildString {
        val uid = Process.myUid()
        val who = when (uid) {
            0 -> "root"
            2000 -> "shell"
            else -> "unknown(uid=$uid)"
        }
        appendLine("uid=$uid ($who)")
        appendLine("java.home=${System.getProperty("java.home")}")

        appendLine("--- 桌面数据库目录 ---")
        listOf(
            "/data/data/com.miui.home/databases",
            "/data/user/0/com.miui.home/databases",
            "/data/data/com.android.launcher3/databases",
            "/data/data/com.google.android.apps.nexuslauncher/databases",
            "/data/data/app.lawnchair/databases",
        ).forEach { p ->
            val f = File(p)
            val entries = if (f.isDirectory) (f.list()?.joinToString(",") ?: "<list()==null>") else "-"
            appendLine("$p exists=${f.exists()} read=${f.canRead()} write=${f.canWrite()}")
            appendLine("    entries=$entries")
        }

        appendLine("--- sqlite3 可执行文件 ---")
        listOf("/system/bin/sqlite3", "/system/xbin/sqlite3", "/apex/com.android.runtime/bin/sqlite3")
            .forEach { appendLine("$it exists=${File(it).exists()}") }

        appendLine("--- 系统身份 ---")
        appendLine("getenforce => ${exec("getenforce").trim().replace("\n", " / ")}")
        appendLine("id => ${exec("id").trim().replace("\n", " / ")}")
    }

    override fun listDir(path: String): Array<String> = try {
        File(path).list() ?: emptyArray()
    } catch (t: Throwable) {
        emptyArray()
    }

    override fun canRead(path: String): Boolean = try {
        val f = File(path)
        f.exists() && f.canRead() && f.isFile
    } catch (t: Throwable) {
        false
    }

    override fun length(path: String): Long = try {
        val f = File(path)
        if (f.exists() && f.isFile) f.length() else -1L
    } catch (t: Throwable) {
        -1L
    }

    override fun copyFile(src: String, dst: String): Long = try {
        val from = File(src)
        when {
            !from.exists() -> ERR_NOT_FOUND
            !from.canRead() -> ERR_NO_READ
            else -> {
                val to = File(dst)
                to.parentFile?.mkdirs()
                from.inputStream().use { input ->
                    to.outputStream().use { output -> input.copyTo(output) }
                }
                // 让宿主 App 一定能读到（宿主 UID 不同）
                to.setReadable(true, false)
                to.length()
            }
        }
    } catch (t: Throwable) {
        ERR_IO
    }

    override fun readFileHead(path: String, length: Int): ByteArray = try {
        val f = File(path)
        if (!f.exists() || !f.canRead()) {
            ByteArray(0)
        } else {
            val buf = ByteArray(length)
            var off = 0
            f.inputStream().use { input ->
                while (off < length) {
                    val r = input.read(buf, off, length - off)
                    if (r < 0) break
                    off += r
                }
            }
            if (off == length) buf else buf.copyOf(off)
        }
    } catch (t: Throwable) {
        ByteArray(0)
    }

    override fun statFile(path: String): String {
        val out = exec("stat -c '%a %U %s %Y' '$path'")
        val first = out.lineSequence().firstOrNull()?.trim() ?: return ""
        if (first != "0") return ""
        return out.lineSequence().drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    override fun chmod(path: String, mode: Int): Boolean {
        val octal = Integer.toOctalString(mode)
        val out = exec("chmod $octal '$path'")
        return out.lineSequence().firstOrNull()?.trim() == "0"
    }

    override fun exec(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val code = if (finished) process.exitValue() else -1
        "$code\n$stdout\n$stderr"
    } catch (t: Throwable) {
        "-1\n\n${t.message}"
    }

    override fun killProcess(processName: String): Boolean {
        // 桌面被结束后由系统重新拉起（首次会短暂黑屏，按 Home 可恢复）
        val out = exec("pkill -f '$processName'")
        return out.lineSequence().firstOrNull()?.trim() == "0"
    }

    companion object {
        const val ERR_NOT_FOUND = -1L
        const val ERR_NO_READ = -2L
        const val ERR_IO = -3L
    }
}
