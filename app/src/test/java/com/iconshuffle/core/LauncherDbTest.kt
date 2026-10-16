package com.iconshuffle.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 数据库读写测试。用 Robolectric 提供真实的 android.database.sqlite 实现，
 * 因此能验证「读 schema → 事务 UPDATE → 回读校验」这条链路。
 *
 * 这里刻意造一张「列比 AOSP 少」的表，模拟 MIUI 系 favorites 表结构差异。
 */
@RunWith(RobolectricTestRunner::class)
class LauncherDbTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createDb(file: File): File {
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """
            CREATE TABLE favorites (
                _id INTEGER PRIMARY KEY,
                itemType INTEGER,
                container INTEGER,
                screen INTEGER,
                cellX INTEGER,
                cellY INTEGER,
                spanX INTEGER,
                spanY INTEGER,
                rank INTEGER,
                title TEXT,
                profileId INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO favorites VALUES (1,0,-100,0,0,0,1,1,0,'相机',0)")
        db.execSQL("INSERT INTO favorites VALUES (2,0,-100,0,1,0,1,1,1,'微信',0)")
        db.execSQL("INSERT INTO favorites VALUES (3,1,-100,0,2,0,2,1,2,'时钟',0)")
        db.execSQL("INSERT INTO favorites VALUES (4,0,-101,-1,0,0,1,1,0,'电话',0)")
        db.close()
        return file
    }

    @Test
    fun `reads rows and reports real columns`() {
        val f = createDb(File(tmp.root, "launcher.db"))
        val db = LauncherDb(f)
        assertTrue("SQLite 头识别失败", db.hasSqliteHeader())

        val snapshot = db.read()
        assertEquals(4, snapshot.itemCount)
        assertEquals(1, snapshot.pageCount)
        assertTrue(snapshot.columns.contains("cellX"))

        val camera = snapshot.items.first { it.id == 1L }
        assertEquals("相机", camera.title)
        assertTrue(camera.isSwappable)
        assertTrue(camera.isOnWorkspace)

        val widget = snapshot.items.first { it.id == 3L }
        assertTrue("2x1 widget 不应被视为可交换", !widget.isSwappable)

        val dock = snapshot.items.first { it.id == 4L }
        assertTrue(dock.isDock)
    }

    @Test
    fun `applyMoves writes coordinates and verify passes`() {
        val f = createDb(File(tmp.root, "launcher.db"))
        val db = LauncherDb(f)
        val moves = listOf(
            CellMove(1L, 0, 0, 1, 0),
            CellMove(2L, 1, 0, 0, 0),
        )
        val changed = db.applyMoves(moves)
        assertEquals(2, changed)
        assertTrue("回读校验失败：${db.verifyMoves(moves)}", db.verifyMoves(moves).isEmpty())

        val after = db.read().items.associateBy { it.id }
        assertEquals(1, after.getValue(1L).cellX)
        assertEquals(0, after.getValue(2L).cellX)
        // 未参与的行必须原样
        assertEquals(2, after.getValue(3L).cellX)
    }

    @Test
    fun `sql script fallback is generated with only cell updates`() {
        val f = createDb(File(tmp.root, "launcher.db"))
        val script = LauncherDb(f).buildSqlScript(listOf(CellMove(1L, 0, 0, 3, 4)))
        assertTrue(script.contains("UPDATE favorites SET cellX=3, cellY=4 WHERE _id=1;"))
        assertTrue(script.contains("BEGIN TRANSACTION;"))
        assertTrue(script.contains("COMMIT;"))
    }

    @Test
    fun `end to end shuffle changes positions but loses nothing`() {
        // 造一页 6 个图标，打乱后 id 集合与占格集合都不变
        val f = File(tmp.root, "launcher.db")
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        raw.execSQL("CREATE TABLE favorites (_id INTEGER PRIMARY KEY, itemType INTEGER, container INTEGER, screen INTEGER, cellX INTEGER, cellY INTEGER, spanX INTEGER, spanY INTEGER, rank INTEGER, title TEXT, profileId INTEGER)")
        var id = 1
        for (y in 0 until 2) for (x in 0 until 3) {
            raw.execSQL("INSERT INTO favorites VALUES ($id,0,-100,0,$x,$y,1,1,$id,'app$id',0)")
            id++
        }
        raw.close()

        val db = LauncherDb(f)
        val before = db.read()
        val beforeSlots = before.items.map { Triple(it.screen, it.cellX, it.cellY) }.toSet()

        val result = ShuffleEngine.plan(before, ShuffleScope.ALL_PAGES, random = kotlin.random.Random(42))
        assertTrue("6 个图标应该产生换位", result.moves.isNotEmpty())
        db.applyMoves(result.moves)

        val afterSnap = db.read()
        assertEquals("行数发生变化", before.itemCount, afterSnap.itemCount)
        assertEquals(
            "占格集合发生变化",
            beforeSlots,
            afterSnap.items.map { Triple(it.screen, it.cellX, it.cellY) }.toSet()
        )
        assertEquals(
            "id 集合发生变化",
            before.items.map { it.id }.toSet(),
            afterSnap.items.map { it.id }.toSet()
        )
    }
}
