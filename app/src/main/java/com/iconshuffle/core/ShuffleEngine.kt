package com.iconshuffle.core

import kotlin.random.Random

/**
 * 打乱算法本体。纯函数，不碰数据库，方便单元测试。
 *
 * 硬约束（四条不变量，任何模式下都成立）：
 *   1. 总页数不变；
 *   2. 每页占用的格数不变 —— 页内格子集合就是「谁占哪个格」的池子，
 *      打乱只是把图标重新指派到同一批格子上，不会新增也不会减少占格；
 *   3. 图标只在自己页面内部换位；
 *   4. 绝不跨页迁移。
 *
 * 两种范围的区别只是「一次处理几页」：
 *   - [ShuffleScope.ALL_PAGES]    每一页各自独立打乱，一次处理完；
 *   - [ShuffleScope.CURRENT_PAGE] 只处理 currentScreen 这一页。
 *
 * 不参与交换的东西（硬性保护，避免把桌面搞坏）：
 *  - widget、跨格图标（spanX/spanY > 1）原地不动；
 *  - 文件夹本身可以换位，但文件夹内部（container > 0）的图标绝不动；
 *  - hidden=1 的项目不参与；
 *  - 非主用户的 profile 不动（跨用户搬运会丢图标）；
 *  - Dock（container == -101）默认不动，可用 keepDock=false 放开。
 */
object ShuffleEngine {

    data class Result(
        val moves: List<CellMove>,
        val groupsTouched: Int,
        val candidates: Int,
        val skippedByCollision: Int,
        /** 本次处理到的页面号，升序。 */
        val screens: List<Int>,
        /** 本次真正发生了图标移动的页面号，升序。 */
        val changedScreens: List<Int>,
    ) {
        val changedCount: Int get() = moves.size
    }

    /**
     * @param currentScreen [ShuffleScope.CURRENT_PAGE] 时生效，指定要打乱哪一页。
     */
    fun plan(
        snapshot: LauncherDb.Snapshot,
        scope: ShuffleScope,
        keepDock: Boolean = true,
        currentScreen: Int = 0,
        random: Random = Random.Default,
    ): Result {
        val all = snapshot.items

        // 多用户场景：以出现次数最多的 profileId 为主用户
        val primaryProfile = all.groupingBy { it.profileId }.eachCount()
            .maxByOrNull { it.value }?.key ?: 0L

        val pool = all.filter { item ->
            item.isSwappable &&
                item.profileId == primaryProfile &&
                // hidden=1 的项目留在库里但不在桌面上，不参与打乱
                !item.hidden &&
                when {
                    item.isOnWorkspace -> true
                    item.isDock -> !keepDock
                    else -> false // 文件夹内部 / 其它容器
                }
        }

        // 固定障碍物：不可交换但占据真实坐标的元素（widget 等），
        // 用于避免把图标换到它们身上。按 (screen, x, y) 记录。
        val blocked = all.filter { !it.isSwappable && it.cellX >= 0 && it.cellY >= 0 }
            .map { Triple(it.screen, it.cellX, it.cellY) }
            .toHashSet()

        val groups: List<List<LauncherItem>> = pool
            .groupBy { it.screen }
            .filterKeys { screen ->
                // 只处理桌面页；Dock 的 screen 是 -1，单独处理
                when (scope) {
                    ShuffleScope.ALL_PAGES -> true
                    ShuffleScope.CURRENT_PAGE -> screen == currentScreen
                }
            }
            .values
            .filter { it.size >= 2 } // 单图标页面无可交换对象

        val moves = mutableListOf<CellMove>()
        val changedScreens = mutableSetOf<Int>()
        var skipped = 0

        groups.forEach { group ->
            // 目标坐标 = 该组当前占用的坐标集合（打乱只重排「谁占哪个格」）。
            // 因此第 2 条不变量天然成立：占格集合按构造不变。
            val slots = group.map { Triple(it.screen, it.cellX, it.cellY) }

            val assignment = assignWithCollisionRetry(group, slots, blocked, random)
            skipped += assignment.skipped

            group.forEachIndexed { index, item ->
                val target = assignment.targets[index] ?: return@forEachIndexed
                // slot 来自本组，screen 必然等于 item.screen → 不变量 3、4 天然成立
                if (target.second != item.cellX || target.third != item.cellY) {
                    changedScreens += item.screen
                    moves += CellMove(
                        id = item.id,
                        fromX = item.cellX,
                        fromY = item.cellY,
                        toX = target.second,
                        toY = target.third,
                    )
                }
            }
        }

        return Result(
            moves = moves,
            groupsTouched = groups.size,
            candidates = groups.sumOf { it.size },
            skippedByCollision = skipped,
            screens = groups.map { it.first().screen }.distinct().sorted(),
            changedScreens = changedScreens.sorted(),
        )
    }

    private class Assignment(
        val targets: List<Triple<Int, Int, Int>?>,
        val skipped: Int,
    )

    /**
     * 把 slots 随机重新分配给 group 中的元素。
     *
     * 冲突处理：widget 只登记了左上角，若某个跨格元素覆盖了池子里的格子（ROM 数据
     * 不一致时可能出现），就重试整个排列；若干次仍冲突则让冲突元素原地不动，
     * 宁可少打乱几个，也不把图标压到 widget 上。
     */
    private fun assignWithCollisionRetry(
        group: List<LauncherItem>,
        slots: List<Triple<Int, Int, Int>>,
        blocked: Set<Triple<Int, Int, Int>>,
        random: Random,
    ): Assignment {
        val n = group.size
        repeat(MAX_RETRY) { attempt ->
            val shuffled = slots.shuffled(random)
            val targets = arrayOfNulls<Triple<Int, Int, Int>>(n)
            var conflict = false
            for (i in 0 until n) {
                val slot = shuffled[i]
                if (slot in blocked) {
                    conflict = true
                    break
                }
                targets[i] = slot
            }
            if (!conflict || attempt == MAX_RETRY - 1) {
                var skipped = 0
                for (i in 0 until n) {
                    if (targets[i] == null) {
                        targets[i] = Triple(group[i].screen, group[i].cellX, group[i].cellY)
                        skipped++
                    }
                }
                return Assignment(targets.toList(), skipped)
            }
        }
        return Assignment(group.map { Triple(it.screen, it.cellX, it.cellY) }, 0)
    }

    private const val MAX_RETRY = 24
}
