package com.iconshuffle.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 打乱算法的纯逻辑测试。
 *
 * 本文件的核心职责是**钉死四条产品级不变量**（用户明确提出的硬约束）：
 *   1. 总页数不变；
 *   2. 每页占用的格数不变；
 *   3. 图标只在页内换位；
 *   4. 绝不跨页迁移。
 *
 * 不需要设备，`./gradlew :app:testDebugUnitTest` 即可运行。
 */
class ShuffleEngineTest {

    private fun item(
        id: Long,
        screen: Int,
        x: Int,
        y: Int,
        type: Int = LauncherItem.TYPE_APPLICATION,
        container: Int = LauncherItem.CONTAINER_DESKTOP,
        spanX: Int = 1,
        spanY: Int = 1,
        title: String = "app$id",
        hidden: Boolean = false,
        modified: Long = 0L,
    ) = LauncherItem(id, type, container, screen, x, y, spanX, spanY, 0, title, 0L, hidden, modified)

    private fun snapshot(items: List<LauncherItem>) =
        LauncherDb.Snapshot(items, items.maxOfOrNull { it.screen }?.plus(1) ?: 0, setOf("_id"), items.size)

    /** 把 moves 应用回原始集合，得到「打乱后」的完整状态。 */
    private fun apply(items: List<LauncherItem>, moves: List<CellMove>): List<LauncherItem> {
        val byId = moves.associateBy { it.id }
        return items.map { it.copy(cellX = byId[it.id]?.toX ?: it.cellX, cellY = byId[it.id]?.toY ?: it.cellY) }
    }

    private fun slotsOf(items: List<LauncherItem>, screen: Int) =
        items.filter { it.container == LauncherItem.CONTAINER_DESKTOP && it.screen == screen }
            .map { it.cellX to it.cellY }.toSet()

    private fun screensOf(items: List<LauncherItem>) =
        items.filter { it.container == LauncherItem.CONTAINER_DESKTOP && it.screen >= 0 }
            .map { it.screen }.toSet()

    /** 三页数据：0 页 3 个 + 1 个 widget，1 页 6 个，2 页 2 个。 */
    private fun threePages() = listOf(
        item(1, 0, 0, 0), item(2, 0, 1, 0), item(3, 0, 2, 0),
        item(4, 0, 0, 1, type = LauncherItem.TYPE_WIDGET, spanX = 2, spanY = 1),
        item(5, 1, 0, 0), item(6, 1, 1, 0), item(7, 1, 0, 1),
        item(8, 1, 1, 1), item(9, 1, 0, 2), item(10, 1, 1, 2),
        item(11, 2, 0, 0), item(12, 2, 1, 0),
    )

    // ------------------------------------------------------- 不变量 1：页数不变

    @Test
    fun `invariant 1 - page set never changes, both scopes`() {
        val items = threePages()
        val before = screensOf(items)

        ShuffleScope.entries.forEach { scope ->
            repeat(30) { seed ->
                val moves = ShuffleEngine.plan(
                    snapshot(items), scope, currentScreen = 1, random = Random(seed)
                ).moves
                assertEquals("$scope 改变了页面集合", before, screensOf(apply(items, moves)))
            }
        }
    }

    // ------------------------------------------------- 不变量 2：每页占格数不变

    @Test
    fun `invariant 2 - occupied slots per page are identical after shuffle`() {
        val items = threePages()

        // 先筛出「确实产生了换位」的样本，否则不变式成立是空洞的
        val productive = (0..40).mapNotNull { seed ->
            val moves = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.ALL_PAGES, random = Random(seed)
            ).moves
            if (moves.isEmpty()) null else moves
        }
        assertTrue("40 个种子都没产生任何换位，测试本身失去意义", productive.size > 20)

        productive.forEach { moves ->
            val after = apply(items, moves)
            // 占格集合按页比对：既保证「每页格数不变」，也保证「格子本身没变」
            (0..2).forEach { screen ->
                assertEquals(
                    "第$screen 页占格集合变了",
                    slotsOf(items, screen),
                    slotsOf(after, screen)
                )
            }
        }
    }

    // ------------------------------------------- 不变量 3 + 4：页内换位、绝不跨页

    @Test
    fun `invariant 3 and 4 - every icon stays on its original page`() {
        val items = threePages()
        val screenById = items.associate { it.id to it.screen }

        ShuffleScope.entries.forEach { scope ->
            repeat(30) { seed ->
                val moves = ShuffleEngine.plan(
                    snapshot(items), scope, currentScreen = 1, random = Random(seed)
                ).moves
                apply(items, moves).forEach { after ->
                    assertEquals(
                        "$scope 下 id=${after.id} 跨页迁移了",
                        screenById[after.id],
                        after.screen
                    )
                }
            }
        }
    }

    @Test
    fun `all pages scope shuffles every page with enough icons`() {
        val items = threePages()
        // changedScreens 只统计「真的动了图标」的页；
        // 三页各 3/6/2 个图标，多试几个种子必然三页都动过
        val trulyChanged = (0..20).map { seed ->
            ShuffleEngine.plan(
                snapshot(items), ShuffleScope.ALL_PAGES, random = Random(seed)
            ).changedScreens
        }
        assertTrue(
            "ALL_PAGES 应让第 1/2/3 页都发生过移动，实际样本：$trulyChanged",
            trulyChanged.any { it == listOf(0, 1, 2) }
        )
        // 且从不会有页面之外的东西被算进来
        assertTrue(trulyChanged.flatten().toSet().all { it in setOf(0, 1, 2) })
    }

    @Test
    fun `current page scope only changes the target page`() {
        val items = threePages()
        repeat(40) { seed ->
            val moves = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.CURRENT_PAGE, currentScreen = 1, random = Random(seed)
            ).moves
            val touched = moves.map { it.id }.toSet()
            assertTrue(
                "只应动第 1 页的图标（id 5..10），实际动了 $touched",
                touched.all { it in 5L..10L }
            )
            assertTrue("第 1 页有 6 个图标，应该产生换位", moves.isNotEmpty())
        }
    }

    @Test
    fun `current page scope on a page with one icon produces nothing`() {
        val items = listOf(
            item(1, 0, 0, 0), item(2, 0, 1, 0), item(3, 1, 0, 0),
        )
        repeat(10) { seed ->
            val moves = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.CURRENT_PAGE, currentScreen = 1, random = Random(seed)
            ).moves
            assertTrue("第 1 页只有 1 个图标，不该有换位", moves.isEmpty())
        }
    }

    // -------------------------------------------------------- 保护规则

    @Test
    fun `widgets are never moved`() {
        val items = threePages()
        repeat(30) { seed ->
            val scope = ShuffleScope.entries[seed % 2]
            val moves = ShuffleEngine.plan(
                snapshot(items), scope, currentScreen = 0, random = Random(seed)
            ).moves
            assertTrue("widget(id=4) 被移动了", moves.none { it.id == 4L })
        }
    }

    @Test
    fun `nothing lands on a widget occupied slot`() {
        val items = threePages()
        repeat(50) { seed ->
            val moves = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.CURRENT_PAGE, currentScreen = 0, random = Random(seed)
            ).moves
            moves.forEach { m ->
                // widget 在 (0,1)，跨到 (1,1)
                assertNotEquals("图标被压到 widget 上", 0 to 1, m.toX to m.toY)
            }
        }
    }

    @Test
    fun `folder contents stay inside their folder`() {
        val items = listOf(
            item(1, 0, 0, 0), item(2, 0, 1, 0),
            // container=1 表示在 id=100 的文件夹里
            item(10, -1, 0, 0, container = 1),
            item(11, -1, 1, 0, container = 1),
        )
        val moves = ShuffleEngine.plan(snapshot(items), ShuffleScope.ALL_PAGES, random = Random(5)).moves
        val movedIds = moves.map { it.id }.toSet()
        assertTrue("文件夹内图标被移动了：$movedIds", 10L !in movedIds && 11L !in movedIds)
    }

    @Test
    fun `hidden items are never moved`() {
        val items = listOf(
            item(1, 0, 0, 0), item(2, 0, 1, 0),
            item(3, 0, 2, 0, hidden = true),
        )
        repeat(20) { seed ->
            val moves = ShuffleEngine.plan(snapshot(items), ShuffleScope.ALL_PAGES, random = Random(seed)).moves
            assertTrue("hidden 项被移动了", moves.none { it.id == 3L })
        }
    }

    @Test
    fun `dock is untouched by default but can be released`() {
        val items = listOf(
            item(1, 0, 0, 0), item(2, 0, 1, 0),
            item(3, -1, 0, 0, container = LauncherItem.CONTAINER_HOTSEAT),
            item(4, -1, 1, 0, container = LauncherItem.CONTAINER_HOTSEAT),
        )
        repeat(20) { seed ->
            val kept = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.ALL_PAGES, keepDock = true, random = Random(seed)
            )
            assertTrue("Dock 应保持不变", kept.moves.none { it.id == 3L || it.id == 4L })

            val released = ShuffleEngine.plan(
                snapshot(items), ShuffleScope.ALL_PAGES, keepDock = false, random = Random(seed)
            )
            assertTrue("放开后 Dock 也应参与", released.moves.any { it.id == 3L || it.id == 4L })
        }
    }

    @Test
    fun `single icon page produces no moves`() {
        val items = listOf(item(1, 0, 0, 0))
        val result = ShuffleEngine.plan(snapshot(items), ShuffleScope.ALL_PAGES, random = Random(1))
        assertEquals(0, result.moves.size)
    }

    @Test
    fun `legacy PER_PAGE preference maps to current page`() {
        assertEquals(ShuffleScope.CURRENT_PAGE, ShuffleScope.fromName("PER_PAGE"))
        assertEquals(ShuffleScope.CURRENT_PAGE, ShuffleScope.fromName("CURRENT_PAGE"))
        assertEquals(ShuffleScope.ALL_PAGES, ShuffleScope.fromName("ALL_PAGES"))
        assertEquals(ShuffleScope.ALL_PAGES, ShuffleScope.fromName(null))
        assertEquals(ShuffleScope.ALL_PAGES, ShuffleScope.fromName("garbage"))
    }
}
