package com.iconshuffle.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 接入的唯一入口：授权流程 + 用户服务绑定 + 状态广播。
 *
 * 三段式状态机，UI 只关心这三态：
 *  1. [State.UNAVAILABLE]  Shizuku 没装 / 没在运行
 *  2. [State.NO_PERMISSION] 在跑，但本应用没被授权
 *  3. [State.READY]        已授权且用户服务已绑定，可以执行特权操作
 *
 * 注意：申请权限用的是 requestPermission(requestCode)，结果通过
 * OnRequestPermissionResultListener 回来，不需要 startActivityForResult。
 */
class ShizukuGateway private constructor() {

    enum class State { UNAVAILABLE, NO_PERMISSION, READY }

    fun interface StateListener { fun onState(state: State, detail: String) }

    private val listeners = mutableListOf<StateListener>()

    @Volatile
    private var service: IPrivilegedService? = null
    private var binder: IBinder? = null

    /**
     * 注意：这不是 val。CountDownLatch 是一次性的，如果第一次绑定失败/超时，
     * 复用同一个 latch 会让后续 awaitService 立刻返回 null（永久卡死）。
     * 所以每次重新发起绑定前都要换一个新的 latch。
     */
    @Volatile
    private var serviceLatch = CountDownLatch(1)

    private val bindLock = Any()

    @Volatile
    private var bindRequested = false

    @Volatile
    private var attached = false

    // ------------------------------------------------------------ 权限

    fun shizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /**
     * Shizuku 服务端的 SELinux 上下文。这是判断「以 root 还是 shell 启动」的权威依据：
     *  - adb / 无线调试启动 → 一定是 u:r:shell:s0
     *  - root（Magisk）启动 → 形如 u:r:magisk:s0
     * 若 root 的 su 不允许与 App 做 binder 调用，Shizuku 会回落成 u:r:shell:s0。
     */
    fun serverSelinuxContext(): String? = try {
        if (shizukuAvailable()) Shizuku.getSELinuxContext() else null
    } catch (t: Throwable) {
        null
    }

    /** Shizuku 服务端 uid：0=root，2000=shell。 */
    fun serverUid(): Int = try {
        if (shizukuAvailable()) Shizuku.getUid() else -1
    } catch (t: Throwable) {
        -1
    }

    fun hasPermission(): Boolean = try {
        shizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun state(): State = when {
        !shizukuAvailable() -> State.UNAVAILABLE
        !hasPermission() -> State.NO_PERMISSION
        else -> State.READY
    }

    /** 已授权就绑定用户服务；未授权返回 false，由调用方去走 requestPermission。 */
    fun ensureBound(): Boolean {
        if (!hasPermission()) return false
        if (service != null) return true
        synchronized(bindLock) {
            if (service != null) return true
            // 换一个干净的 latch，避免上次超时留下的 0 计数让本次立刻假成功
            serviceLatch = CountDownLatch(1)
            bindRequested = false
            bindUserService()
        }
        return true
    }

    fun requestPermission() {
        if (!shizukuAvailable()) return
        Shizuku.requestPermission(REQUEST_CODE)
    }

    fun addStateListener(l: StateListener) {
        synchronized(listeners) { listeners += l }
    }

    fun removeStateListener(l: StateListener) {
        synchronized(listeners) { listeners -= l }
    }

    private fun notifyState() {
        val s = state()
        val detail = when (s) {
            State.UNAVAILABLE -> "Shizuku 未运行或未安装"
            State.NO_PERMISSION -> "等待授权"
            State.READY -> if (service != null) "特权服务已连接" else "特权服务绑定中…"
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onState(s, detail) }
    }

    // ------------------------------------------------------------ 绑定

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i(TAG, "requestPermission result: code=$requestCode grant=$grantResult")
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ensureBound()
            } else {
                notifyState()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "user service connected")
            this@ShizukuGateway.binder = binder
            service = binder?.let { IPrivilegedService.Stub.asInterface(it) }
            serviceLatch.countDown()
            notifyState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "user service disconnected")
            service = null
            binder = null
            // 允许后续重新绑定，否则一次断连就永久卡死
            bindRequested = false
            notifyState()
        }
    }

    private fun bindUserService() {
        if (bindRequested) return
        bindRequested = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(APP_PACKAGE, PrivilegedService::class.java.name)
            )
                .daemon(false)               // 不是常驻守护进程，降低被系统清理的概率影响
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(SERVICE_VERSION)
            Shizuku.bindUserService(args, connection)
            notifyState()
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            bindRequested = false
        }
    }

    /**
     * 等待用户服务就绪。
     * @return 就绪的接口；超时返回 null（超时通常意味着 authorized 了但绑定失败）
     */
    fun awaitService(timeoutMs: Long = 8_000): IPrivilegedService? {
        service?.let { return it }
        if (!ensureBound()) return null
        service?.let { return it }
        return try {
            serviceLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            service
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** 设置监听器。可重复调用，只会真正挂载一次。 */
    fun attach() {
        if (attached) {
            notifyState()
            return
        }
        attached = true
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky { notifyState() }
            Shizuku.addBinderDeadListener { notifyState() }
        } catch (t: Throwable) {
            Log.e(TAG, "attach failed", t)
        }
        notifyState()
    }

    fun detach() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** 便捷包装：把 RemoteException 变成可读异常 */
    @Throws(RemoteException::class)
    fun requireService(): IPrivilegedService =
        service ?: throw RemoteException("Shizuku 用户服务尚未连接")

    companion object {
        private const val TAG = "ShizukuGateway"
        private const val REQUEST_CODE = 4211
        private const val SERVICE_VERSION = 1

        /** 由 Application/Activity 在运行时注入，避免在库代码里硬编码包名。 */
        var APP_PACKAGE: String = "com.iconshuffle"

        @Volatile
        private var instance: ShizukuGateway? = null

        fun get(): ShizukuGateway = instance ?: synchronized(this) {
            instance ?: ShizukuGateway().also { instance = it }
        }
    }
}
