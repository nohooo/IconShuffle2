package com.iconshuffle.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.iconshuffle.R
import com.iconshuffle.auto.DailyShuffleScheduler
import com.iconshuffle.core.LauncherDbChannel
import com.iconshuffle.core.Prefs
import com.iconshuffle.core.ShuffleScope
import com.iconshuffle.databinding.ActivityMainBinding
import com.iconshuffle.shizuku.ShizukuGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), ShizukuGateway.StateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val gateway = ShizukuGateway.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        restoreUi()
        binding.btnShuffle.setOnClickListener { runShuffle() }
        binding.btnGrant.setOnClickListener { onGrantClicked() }
        binding.btnDiagnose.setOnClickListener {
            startActivity(Intent(this, DiagnoseActivity::class.java))
        }
        binding.btnTime.setOnClickListener { pickTime() }

        binding.rgScope.setOnCheckedChangeListener { _, checkedId ->
            prefs.scope = when (checkedId) {
                R.id.rbCurrentPage -> ShuffleScope.CURRENT_PAGE
                else -> ShuffleScope.ALL_PAGES
            }
        }
        binding.swAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoEnabled = checked
            binding.rowTime.visibility = if (checked) View.VISIBLE else View.GONE
            DailyShuffleScheduler.sync(this, prefs)
            if (checked && !gateway.hasPermission()) {
                toast("已开启，但需要先授权 Shizuku 才能执行")
            }
        }
        binding.swKeepDock.setOnCheckedChangeListener { _, checked -> prefs.keepDock = checked }

        showLastResult()
    }

    override fun onResume() {
        super.onResume()
        gateway.attach()
        gateway.addStateListener(this)
        onState(gateway.state(), "")
    }

    override fun onPause() {
        gateway.removeStateListener(this)
        super.onPause()
    }

    // ------------------------------------------------------------ UI 同步

    private fun restoreUi() {
        binding.rgScope.check(
            if (prefs.scope == ShuffleScope.CURRENT_PAGE) R.id.rbCurrentPage else R.id.rbAllPages
        )
        binding.swAuto.isChecked = prefs.autoEnabled
        binding.swKeepDock.isChecked = prefs.keepDock
        binding.rowTime.visibility = if (prefs.autoEnabled) View.VISIBLE else View.GONE
        binding.btnTime.text = Prefs.formatMinuteOfDay(prefs.autoMinuteOfDay)
    }

    private fun showLastResult() {
        val last = prefs.lastResult
        binding.tvResult.text = if (last.isBlank()) "尚无执行记录。" else "上次执行：\n$last"
    }

    override fun onState(state: ShizukuGateway.State, detail: String) {
        runOnUiThread {
            when (state) {
                ShizukuGateway.State.UNAVAILABLE -> {
                    binding.tvShizukuState.setText(R.string.status_shizuku_missing)
                    binding.tvShizukuState.setTextColor(color(R.color.bad))
                    binding.tvShizukuDetail.text =
                        "请先安装并启动 Shizuku（本机已装 v13.6.0）。" +
                            "Android 11+ 需要用无线调试启动：开发者选项 → 无线调试 → 配对后启动 Shizuku。"
                    binding.btnGrant.isEnabled = false
                    binding.btnShuffle.isEnabled = false
                }

                ShizukuGateway.State.NO_PERMISSION -> {
                    binding.tvShizukuState.setText(R.string.status_shizuku_no_perm)
                    binding.tvShizukuState.setTextColor(color(R.color.warn))
                    binding.tvShizukuDetail.text = "点右侧「授权 Shizuku」，在弹窗里选「始终允许」。"
                    binding.btnGrant.isEnabled = true
                    binding.btnShuffle.isEnabled = false
                }

                ShizukuGateway.State.READY -> {
                    binding.tvShizukuState.text =
                        getString(R.string.status_shizuku_ready, detail)
                    binding.tvShizukuState.setTextColor(color(R.color.ok))
                    binding.tvShizukuDetail.text =
                        "身份与可写性决定能否直接改 launcher.db，建议先跑一次「能力诊断」。"
                    binding.btnGrant.isEnabled = false
                    binding.btnShuffle.isEnabled = true
                }
            }
        }
    }

    private fun color(res: Int) = androidx.core.content.ContextCompat.getColor(this, res)

    // ------------------------------------------------------------ 动作

    private fun onGrantClicked() {
        if (!gateway.shizukuAvailable()) {
            toast("Shizuku 未运行")
            return
        }
        gateway.requestPermission()
        toast("已弹出授权请求，请选择始终允许")
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(prefs.autoMinuteOfDay / 60)
            .setMinute(prefs.autoMinuteOfDay % 60)
            .setTitleText(getString(R.string.label_auto_time))
            .build()
        picker.addOnPositiveButtonClickListener {
            val minute = picker.hour * 60 + picker.minute
            prefs.autoMinuteOfDay = minute
            binding.btnTime.text = Prefs.formatMinuteOfDay(minute)
            DailyShuffleScheduler.sync(this, prefs)
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun runShuffle() {
        val service = gateway.awaitService(timeoutMs = 6_000)
        if (service == null) {
            toast("Shizuku 特权服务未就绪，请重新授权后重试")
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LauncherDbChannel(applicationContext, gateway).shuffle(
                    scope = prefs.scope,
                    keepDock = prefs.keepDock,
                    restartLauncher = false,
                )
            }
            setBusy(false)
            prefs.lastResult = result.log.joinToString("\n")
            if (result.ok) prefs.lastShuffleAt = System.currentTimeMillis()
            showLastResult()

            if (result.ok) {
                toast(result.message)
            } else {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("打乱失败")
                    .setMessage(result.message + "\n\n建议打开「能力诊断」查看具体是哪一步被拦。")
                    .setPositiveButton("去诊断") { _, _ ->
                        startActivity(Intent(this@MainActivity, DiagnoseActivity::class.java))
                    }
                    .setNegativeButton("知道了", null)
                    .show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnShuffle.isEnabled = !busy && gateway.state() == ShizukuGateway.State.READY
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
