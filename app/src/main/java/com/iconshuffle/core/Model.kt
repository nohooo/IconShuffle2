package com.iconshuffle.core

/**
 * favorites 表中的一行。字段语义与 Launcher3 / Lawnchair 的 schema 一致，
 * 详见 GitHub LawnchairLauncher `schemas/` 与 AOSP Launcher3 的 FavoriteItem.
 *
 * 这里只保留打乱所需的最小字段集，其余列原样不动，避免破坏桌面的其它状态。
 */
data class LauncherItem(
    val id: Long,
    /** 0=应用/快捷方式 1=widget 2=文件夹 3=? 4=自定义快捷方式 5=深短路 6=?? 7=搜索栏 */
    val itemType: Int,
    /** -100=桌面 WORKSPACE，-101=Dock(HOTSEAT)，>0=文件夹 id */
    val container: Int,
    /** 所在桌面页；-1 表示 Dock 等非分页容器 */
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val rank: Int,
    val title: String?,
    val profileId: Long,
    /** favorites.hidden == 1：已隐藏但仍在库中的项目，不参与打乱。 */
    val hidden: Boolean = false,
    /** favorites.modified：最后修改时间。用来推测桌面「当前页」。 */
    val modified: Long = 0L,
) {
    /** 是否允许参与位置交换：必须是 1x1 且落在真实网格坐标上的项目。 */
    val isSwappable: Boolean
        get() = spanX == 1 && spanY == 1 && cellX >= 0 && cellY >= 0

    val isDock: Boolean get() = container == CONTAINER_HOTSEAT
    val isOnWorkspace: Boolean get() = container == CONTAINER_DESKTOP

    /** 展示用名字：优先 title，缺失时回落成 id。 */
    val displayName: String get() = title?.takeIf { it.isNotBlank() } ?: "#$id"

    companion object {
        const val CONTAINER_DESKTOP = -100
        const val CONTAINER_HOTSEAT = -101

        const val TYPE_APPLICATION = 0
        const val TYPE_WIDGET = 1
        const val TYPE_FOLDER = 2
        const val TYPE_SHORTCUT = 6
    }
}

/** 一次坐标交换，用于生成撤销快照。 */
data class CellMove(
    val id: Long,
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
)

/**
 * 打乱范围。
 *
 * ⚠️ 两个模式都**严格保证**下面四条不变量（这是产品的硬约束）：
 *   1. 总页数不变；
 *   2. 每页占用的格数不变（页内格子集合就是"谁占哪个格"的池子）；
 *   3. 每个图标只在自己的页面内部换位；
 *   4. 打乱前在哪个页面，打乱后还在哪个页面 —— 绝不发生跨页迁移。
 *
 * 区别只在「一次处理几页」。
 */
enum class ShuffleScope {
    /** 每一页各自独立打乱，一次全部处理完。 */
    ALL_PAGES,

    /** 只打乱「当前页面」这一页，其余页一个图标都不动。 */
    CURRENT_PAGE,
    ;

    companion object {
        fun fromName(name: String?): ShuffleScope = when (name) {
            CURRENT_PAGE.name -> CURRENT_PAGE
            // 历史值兼容：早期版本把「仅当前页面」叫 PER_PAGE
            "PER_PAGE" -> CURRENT_PAGE
            // 早期版本的 ALL_PAGES 允许跨页迁移，语义已废弃，统一按「逐页打乱」处理
            else -> ALL_PAGES
        }
    }
}

/** Shizuku 运行身份。决定 launcher.db 是否真的可读写。 */
enum class ShizukuIdentity { ROOT, SHELL, UNKNOWN }

data class ProbeReport(
    val identity: ShizukuIdentity,
    val launcherPackage: String?,
    val dbPath: String?,
    val dbReadable: Boolean,
    val dbWritable: Boolean,
    val dbSizeBytes: Long,
    val sqliteHeaderOk: Boolean,
    val sqlite3Binary: Boolean,
    val rawText: String,
) {
    /** 是否具备「直接读写 launcher.db」的完整条件。 */
    val canDirectEdit: Boolean get() = dbReadable && dbWritable && sqliteHeaderOk
}
