package com.iconshuffle.core

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * 直接读写 launcher.db。
 *
 * ⚠️ 前提：调用方必须先把数据库文件复制到「本 App 有写权限」的位置。
 * Shizuku 特权身份负责搬运，本类只做解析与改写，这样职责清晰、失败点可枚举。
 *
 * 兼容性策略（这是能否在 HyperOS 上跑通的关键）：
 *  - 不硬编码列名集合，而是先 `PRAGMA table_info(favorites)` 读真实 schema，
 *    再按「实际存在的列」拼 SQL。MIUI 的 favorites 表列与 AOSP 不完全一致。
 *  - 不硬编码 `itemType` 取值语义，只按「是否 1x1 + 是否有合法 grid 坐标」判断可交换性。
 *  - 写回时只 UPDATE cellX / cellY 两列，其余列一个字节都不碰。
 */
class LauncherDb(private val file: File) {

    data class Snapshot(
        val items: List<LauncherItem>,
        val pageCount: Int,
        val columns: Set<String>,
        val itemCount: Int,
    )

    class UnsupportedDbException(message: String) : Exception(message)

    private fun open(writable: Boolean): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        )

    /** 嗅探 SQLite 魔数，避免把 WAL / 加密库当成正常文件。 */
    fun hasSqliteHeader(): Boolean {
        if (!file.exists() || file.length() < 100) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(16)
                if (input.read(head) < 16) return false
                String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 读取 SQLite 头里的页数与页大小，用于报告数据库体量。 */
    fun headerInfo(): Triple<Int, Int, Int>? {
        if (!hasSqliteHeader()) return null
        val head = ByteArray(100)
        file.inputStream().use { it.read(head) }
        fun be16(off: Int) = ((head[off].toInt() and 0xFF) shl 8) or (head[off + 1].toInt() and 0xFF)
        fun be32(off: Int) = (head[off].toInt() and 0xFF shl 24) or
            (head[off + 1].toInt() and 0xFF shl 16) or
            (head[off + 2].toInt() and 0xFF shl 8) or
            (head[off + 3].toInt() and 0xFF)
        val pageSize = when (val v = be16(16)) { 1 -> 65536 else -> v }
        val pageCount = be32(28)
        val schemaCookie = be32(40)
        return Triple(pageSize, pageCount, schemaCookie)
    }

    /** 真实列名集合。 */
    private fun columnsOf(db: SQLiteDatabase): Set<String> {
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(favorites)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) cols += c.getString(nameIdx)
            }
        }
        return cols
    }

    private fun Cursor.str(name: String, fallback: String? = null): String? {
        val i = getColumnIndex(name)
        return if (i >= 0 && !isNull(i)) getString(i) else fallback
    }

    private fun Cursor.int(name: String, fallback: Int): Int {
        val i = getColumnIndex(name)
        return if (i >= 0 && !isNull(i)) getInt(i) else fallback
    }

    private fun Cursor.long(name: String, fallback: Long): Long {
        val i = getColumnIndex(name)
        return if (i >= 0 && !isNull(i)) getLong(i) else fallback
    }

    // ---------------------------------------------------------------- 读

    fun read(): Snapshot {
        if (!hasSqliteHeader()) {
            throw UnsupportedDbException(
                "文件不是标准 SQLite 库（可能是 WAL 未合并或被 ROM 加密）：${file.absolutePath}"
            )
        }
        open(false).use { db ->
            val columns = columnsOf(db)
            if ("favorites" !in tableNames(db)) {
                throw UnsupportedDbException(
                    "库里没有 favorites 表，实际表为：${tableNames(db).joinToString(",")}"
                )
            }
            val need = listOf("_id", "container", "cellX", "cellY")
            val missing = need.filterNot { it in columns }
            if (missing.isNotEmpty()) {
                throw UnsupportedDbException("favorites 表缺少必需列：$missing；实际列=$columns")
            }

            val select = buildList {
                add("*") // 先全取，再用 getColumnIndex 安全读取，避免拼错列名导致整体失败
            }.joinToString(",")
            val items = mutableListOf<LauncherItem>()
            var total = 0
            db.rawQuery("SELECT $select FROM favorites", null).use { c ->
                while (c.moveToNext()) {
                    total++
                    items += LauncherItem(
                        id = c.long("_id", -1L),
                        itemType = c.int("itemType", LauncherItem.TYPE_APPLICATION),
                        container = c.int("container", -1),
                        screen = c.int("screen", -1),
                        cellX = c.int("cellX", -1),
                        cellY = c.int("cellY", -1),
                        spanX = c.int("spanX", 1),
                        spanY = c.int("spanY", 1),
                        rank = c.int("rank", 0),
                        title = c.str("title"),
                        profileId = c.long("profileId", 0L),
                        hidden = c.int("hidden", 0) == 1,
                        modified = c.long("modified", 0L),
                    )
                }
            }
            val maxScreen = items.filter { it.isOnWorkspace && it.screen >= 0 }
                .maxOfOrNull { it.screen } ?: -1
            return Snapshot(items, maxScreen + 1, columns, total)
        }
    }

    private fun tableNames(db: SQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'",
            null
        ).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { names += it }
        }
        return names
    }

    /** 每页的图标数量（只统计桌面页，不含 Dock / 文件夹内部）。 */
    fun itemsPerScreen(snapshot: Snapshot): Map<Int, Int> =
        snapshot.items
            .filter { it.isOnWorkspace && it.screen >= 0 }
            .groupingBy { it.screen }
            .eachCount()

    /**
     * 推测桌面的「当前页」。
     *
     * 没有公开 API 能问出桌面停在哪一页，所以用启发式：
     * 取 `modified` 时间戳最晚的那一页 —— 用户刚才操作过的页面。
     * 对「仅当前页面」模式，这是最合理的逼近；推测不出来时返回 0。
     *
     * 之所以不做「读取桌面截图/无障碍」之类的方案：为了一个默认值引入
     * 无障碍权限不划算，而且界面上会让用户确认。
     */
    fun guessCurrentScreen(snapshot: Snapshot): Int {
        val perScreen = snapshot.items.filter { it.isOnWorkspace && it.screen >= 0 }
        if (perScreen.isEmpty()) return 0
        return perScreen
            .groupBy { it.screen }
            .mapValues { (_, items) -> items.maxOfOrNull { it.modified } ?: 0L }
            .maxByOrNull { it.value }
            ?.key
            ?: 0
    }

    // ---------------------------------------------------------------- 写

    /**
     * 只更新 cellX / cellY，事务内批量提交。
     * @return 实际更新的行数
     */
    fun applyMoves(moves: List<CellMove>): Int {
        if (moves.isEmpty()) return 0
        open(true).use { db ->
            db.beginTransaction()
            try {
                // 用预编译语句，避免 100+ 条 SQL 的解析开销
                val stmt = db.compileStatement("UPDATE favorites SET cellX=?, cellY=? WHERE _id=?")
                var changed = 0
                moves.forEach { m ->
                    stmt.clearBindings()
                    stmt.bindLong(1, m.toX.toLong())
                    stmt.bindLong(2, m.toY.toLong())
                    stmt.bindLong(3, m.id)
                    changed += stmt.executeUpdateDelete()
                }
                db.setTransactionSuccessful()
                return changed
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 写回后校验：确认目标坐标确实落库。 */
    fun verifyMoves(moves: List<CellMove>): List<String> {
        val actual = read().items.associateBy { it.id }
        val problems = mutableListOf<String>()
        moves.forEach { m ->
            val item = actual[m.id]
            when {
                item == null -> problems += "id=${m.id} 写入后消失"
                item.cellX != m.toX || item.cellY != m.toY ->
                    problems += "id=${m.id} 期望(${m.toX},${m.toY}) 实际(${item.cellX},${item.cellY})"
            }
        }
        return problems
    }

    /** 供 sqlite3 回落方案使用的 SQL 脚本。 */
    fun buildSqlScript(moves: List<CellMove>): String = buildString {
        appendLine("PRAGMA foreign_keys=OFF;")
        appendLine("BEGIN TRANSACTION;")
        moves.forEach { m ->
            appendLine("UPDATE favorites SET cellX=${m.toX}, cellY=${m.toY} WHERE _id=${m.id};")
        }
        appendLine("COMMIT;")
    }

    companion object {
        private const val TAG = "LauncherDb"

        fun logRead(snapshot: Snapshot) {
            Log.i(TAG, "favorites=${snapshot.itemCount} pages=${snapshot.pageCount} cols=${snapshot.columns}")
        }
    }
}
