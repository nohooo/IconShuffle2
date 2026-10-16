package com.iconshuffle.ui

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iconshuffle.core.LauncherDetector
import com.iconshuffle.core.LauncherDbChannel
import com.iconshuffle.databinding.ActivityDiagnoseBinding
import com.iconshuffle.shizuku.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 能力诊断。
 *
 * 存在的意义：在 HyperOS / Android 16 这类加固 ROM 上，「Shizuku 直读写 launcher.db」
 * 到底能不能成立，没有任何文档能给保证。与其让程序在打乱时抛一个含糊的异常，
 * 不如把所有前置条件一条条测出来，失败点直接指向具体原因。
 */
class DiagnoseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnoseBinding
    private val gateway = ShizukuGateway.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnoseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRun.setOnClickListener { runDiagnosis() }
        runDiagnosis()
    }

    private fun runDiagnosis() {
        binding.btnRun.isEnabled = false
        binding.tvReport.text = "探测中…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { buildReport() }
            binding.tvReport.text = report
            binding.btnRun.isEnabled = true
        }
    }

    private fun buildReport(): String {
        val sb = StringBuilder()

        fun section(title: String) {
            sb.append("\n===== ").append(title).append(" =====\n")
        }

        section("设备")
        sb.append("型号: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("指纹: ${Build.FINGERPRINT}\n")
        sb.append("ROM: ${runCatching { Build.DISPLAY }.getOrDefault("?")}\n")

        section("桌面 (Launcher)")
        val current = LauncherDetector.currentHome(this)
        sb.append("默认桌面: ${current?.packageName ?: "<未检测到>"}  ${current?.label ?: ""}\n")
        sb.append("系统内所有 HOME 应用:\n")
        LauncherDetector.allHomes(this).forEach {
            sb.append("  - ${it.packageName} (${it.label})\n")
        }
        current?.let {
            sb.append("launcher.db 候选路径:\n")
            LauncherDetector.candidateDbPaths(it.packageName).forEach { p -> sb.append("  - $p\n") }
        }

        section("Shizuku")
        val available = gateway.shizukuAvailable()
        val granted = gateway.hasPermission()
        sb.append("运行中: $available\n")
        sb.append("已授权本应用: $granted\n")
        sb.append("状态机: ${gateway.state()}\n")

        if (available) {
            val uid = gateway.serverUid()
            val secontext = gateway.serverSelinuxContext()
            sb.append("服务端 uid: $uid (${when (uid) { 0 -> "root"; 2000 -> "shell"; else -> "未知" }})\n")
            sb.append("服务端 SELinux 上下文: ${secontext ?: "<取不到>"}\n")
            sb.append(
                when {
                    uid == 0 -> "→ 以 root 启动：可以绕过 SELinux 的 app_data_file 限制，路径 ② 有望直接跑通。\n"
                    uid == 2000 -> "→ 以 shell（无线调试）启动：读 /data/data/<桌面> 会被 SELinux 拦，路径 ② 大概率失败。\n"
                    else -> "→ 身份未知。\n"
                }
            )
        }

        if (!granted) {
            sb.append("\n未授权 → 后面的特权探测无法进行。请回主界面点「授权 Shizuku」。\n")
            return sb.toString()
        }

        val service = gateway.awaitService(timeoutMs = 10_000)
        if (service == null) {
            sb.append("\n用户服务绑定超时！\n")
            sb.append("常见原因：\n")
            sb.append("  1. Shizuku 版本与 dev.rikka.shizuku:api 版本不匹配（本工程用 13.1.5）；\n")
            sb.append("  2. SystemUI 弹窗里这次绑定没被允许；\n")
            sb.append("  3. ROM 杀了 Shizuku 的用户服务进程。\n")
            return sb.toString()
        }

        section("特权身份（Shizuku 用户服务）")
        sb.append(runCatching { service.probe() }.getOrElse { "probe 失败: ${it.message}" })

        section("launcher.db 可读性")
        val detect = runCatching { LauncherDbChannel(this, gateway).detect(includePrivilegedReport = false) }
            .getOrElse {
                sb.append("detect 抛异常: ${it}\n")
                return sb.toString()
            }
        sb.append("包名: ${detect.launcherPackage}\n")
        sb.append("路径: ${detect.dbPath}\n")
        sb.append("存在: ${detect.exists}\n")
        sb.append("可读: ${detect.readable}\n")
        sb.append("可写: ${detect.writable}\n")
        sb.append("大小: ${detect.sizeBytes} 字节\n")
        sb.append("有 sqlite3 可执行文件: ${detect.sqlite3Available}\n")
        detect.notes.forEach { sb.append("备注: $it\n") }

        section("结论")
        val uid = gateway.serverUid()
        sb.append(
            when {
                uid == 2000 ->
                    "❌ 前置条件不成立：Shizuku 以 shell 身份运行（uid=2000）。\n" +
                        "Android 11+ 的 SELinux 不允许 shell 访问 app_data_file，所以读 launcher.db 基本必然失败。\n" +
                        "→ 出路：① 用 root（Magisk/KernelSU）启动 Shizuku；\n" +
                        "       ② 改走 Launcher3「布局提供者」通道（免 root，但需 Launcher3 系桌面）；\n" +
                        "       ③ 无障碍模拟拖拽。"

                !detect.exists ->
                    "❌ 没找到 launcher.db。桌面可能把布局存在别处（部分 ROM 用 XML 或别的库名）。\n" +
                        "→ 下一步：把上面「桌面」小节里的包名发我，我按真实路径改探测列表。"

                !detect.readable ->
                    "❌ 文件存在但特权身份读不到。\n" +
                        "→ 用 root 启动 Shizuku，或改走「布局提供者」/无障碍通道。"

                !detect.writable ->
                    "⚠️ 能读不能写。databases 目录属主是桌面 App，当前身份无写权限。\n" +
                        "→ 用 root 启动 Shizuku，或走布局提供者通道。"

                else ->
                    "✅ 读取条件齐备，可以直接尝试「一键打乱」。\n" +
                        "能否写回还要看 databases 目录权限，打乱流程里会实测并报告。"
            }
        )
        sb.append("\n提示：想看 favorites 表真实结构，打乱一次即可，主界面会显示行数、页数与列数。\n")
        return sb.toString()
    }
}
