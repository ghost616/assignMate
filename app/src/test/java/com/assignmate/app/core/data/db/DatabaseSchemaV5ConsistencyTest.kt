package com.assignmate.app.core.data.db

import com.assignmate.app.core.domain.util.CoreConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core 数据库 schema v4->v5 静态一致性单测（纯 JVM，不需要设备/模拟器）。
 *
 * 被测对象有两个来源，本测试保证两者逐项一致：
 * 1. 迁移 DDL：从 `core/di/DatabaseModule.kt` 源码中抽取 MIGRATION_4_5 内真实执行的
 *    CREATE TABLE / ALTER TABLE / CREATE INDEX 语句（避免手抄导致与实现脱节）；
 * 2. 导出 schema：`app/schemas/com.assignmate.app.core.data.db.AppDatabase/{1..5}.json`
 *    （由 KSP + Room 在编译期导出，反映 @Entity/@Database 的最终定义）。
 *
 * 覆盖点：v5 表集合、homework_daily_record 的列/可空性/默认值/自增主键/外键/唯一索引、
 * timer_session 与 pause_record 的业务自然日 epoch_day 维度、迁移语句与 v4 库的增量关系、
 * 版本号三处口径统一、迁移链 1→2→3→4→5 连续与幂等。
 *
 * 注意（identityHash 口径）：导出 schema 的 identityHash 由 Room 编译器在编译期生成，
 * 本测试无法在单测里重算（它依赖编译器内部的对象图），因此只校验其**格式**与
 * setupQueries 的一致性；「结构是否真的变了」由本测试的 `legacyIdentityHash`
 * （与 Room 编译期同为 MD5 拼接的**同口径不同输入**指纹）在 v4/v5 之间比对保证。
 */
class DatabaseSchemaV5ConsistencyTest {

    // ---- 正向：版本、表集合与文件保留 ----

    @Test
    fun `v5 schema 版本号与 DATABASE_VERSION 一致`() {
        val raw = schemaRaw(5)
        assertEquals(5, raw.intAfter("\"version\""))
        assertEquals(CoreConstants.DATABASE_VERSION, raw.intAfter("\"version\""))
    }

    @Test
    fun `v5 schema 包含 7 张表且新表位于末尾`() {
        assertEquals(
            listOf(
                "ocr_retry_task",
                "parent_account",
                "student",
                "homework_item",
                "timer_session",
                "pause_record",
                "homework_daily_record",
            ),
            schemaRaw(5).tableNames,
        )
    }

    @Test
    fun `历史 schema 文件 1 到 4 全部保留且版本自洽`() {
        val dir = schemaDir()
        assertTrue("schema 目录不存在: $dir", dir.isDirectory)
        assertEquals(
            listOf("1.json", "2.json", "3.json", "4.json", "5.json"),
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.name }?.sorted(),
        )
        assertEquals(1, schemaRaw(1).intAfter("\"version\""))
        assertEquals(2, schemaRaw(2).intAfter("\"version\""))
        assertEquals(3, schemaRaw(3).intAfter("\"version\""))
        assertEquals(4, schemaRaw(4).intAfter("\"version\""))
    }

    // ---- 正向：新表结构逐项核对 ----

    @Test
    fun `homework_daily_record 表结构与说明逐列一致`() {
        val sql = schemaRaw(5).sqlFor(DAILY_TABLE)
        assertEquals(DAILY_COLUMNS, columnDefinitions(sql.createSql))
        assertEquals(
            listOf("index_homework_daily_record_homework_id_epoch_day",
                "index_homework_daily_record_student_id_epoch_day",
                "index_homework_daily_record_homework_id"),
            sql.indexSql.map { Regex("INDEX IF NOT EXISTS `(\\w+)`").find(it)!!.groupValues[1] },
        )
    }

    @Test
    fun `homework_daily_record 可空列与默认值语义正确`() {
        val sql = schemaRaw(5).sqlFor(DAILY_TABLE)
        for (nullable in listOf("started_at", "estimated_minutes", "actual_minutes", "finished_at")) {
            assertFalse("$nullable 必须可空（未开始/未完成时为空）", sql.column(nullable).notNull)
        }
        for (required in listOf("homework_id", "student_id", "epoch_day", "status", "created_at")) {
            assertTrue("$required 必须非空", sql.column(required).notNull)
        }
        assertEquals("0", sql.column("pause_count").defaultValue)
        assertEquals("0", sql.column("paused_total_minutes").defaultValue)
        assertEquals("INTEGER", sql.column("epoch_day").affinity)
    }

    @Test
    fun `homework_daily_record 主键自增且唯一约束与级联删除齐备`() {
        val sql = schemaRaw(5).sqlFor(DAILY_TABLE)
        assertTrue(
            "主键必须自增",
            sql.createSql.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"),
        )
        val unique = sql.indexSql.first { it.contains("index_homework_daily_record_homework_id_epoch_day") }
        assertTrue("同一作业同一天必须唯一", unique.contains("CREATE UNIQUE INDEX"))
        assertTrue(unique.contains("(`homework_id`, `epoch_day`)"))

        assertEquals(1, Regex("FOREIGN KEY", RegexOption.IGNORE_CASE).findAll(sql.createSql).count())
        assertEquals(
            normalizeSql("FOREIGN KEY(homework_id) REFERENCES homework_item(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE"),
            normalizeSql(foreignKeyClause(sql.createSql)),
        )
        assertFalse(
            "student_id 为冗余维度，不得建外键（避免与 homework_item 级联路径重复）",
            Regex("FOREIGN KEY\\(`?student_id`?\\)", RegexOption.IGNORE_CASE).containsMatchIn(sql.createSql),
        )
    }

    // ---- 正向：计时/暂停的业务自然日维度 ----

    @Test
    fun `timer_session 与 pause_record 均含业务自然日列且默认值为 0`() {
        for (table in listOf("timer_session", "pause_record")) {
            val sql = schemaRaw(5).sqlFor(table)
            val column = sql.column("epoch_day")
            assertTrue("$table.epoch_day 必须非空", column.notNull)
            assertEquals("INTEGER", column.affinity)
            assertEquals("$table.epoch_day 列默认值用于旧库 ALTER TABLE 加列", "0", column.defaultValue)
            assertTrue(
                "$table 需有 (homework_id, epoch_day) 索引以支撑逐日聚合",
                sql.indexSql.any {
                    it.replace("\${TABLE_NAME}", table).contains("ON `$table` (`homework_id`, `epoch_day`)")
                },
            )
        }
    }

    @Test
    fun `v5 相对 v4 仅新增一表并在两张表上增列`() {
        val before = schemaRaw(4)
        val after = schemaRaw(5)
        assertEquals(before.tableNames + DAILY_TABLE, after.tableNames)

        // v4 已有的全部表在 v5 中必须保持列结构，仅在 v5 改动的两张表中出现新增列
        for (table in listOf("ocr_retry_task", "parent_account", "student", "homework_item")) {
            assertEquals(
                "v5 不得改动 $table 的列结构",
                columnDefinitions(before.sqlFor(table).createSql),
                columnDefinitions(after.sqlFor(table).createSql),
            )
        }
        for (table in listOf("timer_session", "pause_record")) {
            val added = columnDefinitions(after.sqlFor(table).createSql)
                .filterNot { it in columnDefinitions(before.sqlFor(table).createSql) }
            assertEquals(listOf("epoch_day INTEGER NOT NULL DEFAULT 0"), added)
        }
    }

    // ---- 一致性：迁移 DDL vs 导出 schema（Room 打开旧库时逐字比对，归一化后必须相等） ----

    @Test
    fun `MIGRATION_4_5 建表语句与 v5 schema 完全一致`() {
        assertEquals(
            normalizeSql(schemaRaw(5).sqlFor(DAILY_TABLE).createSql.replace("\${TABLE_NAME}", DAILY_TABLE)),
            normalizeSql(migrationStatement("CREATE TABLE IF NOT EXISTS `$DAILY_TABLE`")),
        )
    }

    @Test
    fun `MIGRATION_4_5 索引语句与 v5 schema 完全一致`() {
        for (index in DAILY_INDICES + TIMER_DAY_INDEX + PAUSE_DAY_INDEX) {
            val table = when (index) {
                in DAILY_INDICES -> DAILY_TABLE
                TIMER_DAY_INDEX -> "timer_session"
                else -> "pause_record"
            }
            val exported = schemaRaw(5).sqlFor(table).indexSql.firstOrNull { it.contains("`$index`") }
            assertNotNull("schema 缺少索引 $index", exported)
            // 唯一索引在迁移中写作 CREATE UNIQUE INDEX，普通索引为 CREATE INDEX，前缀必须按导出 schema 选择
            val prefix = if (exported!!.contains("CREATE UNIQUE INDEX")) {
                "CREATE UNIQUE INDEX IF NOT EXISTS `$index`"
            } else {
                "CREATE INDEX IF NOT EXISTS `$index`"
            }
            assertEquals(
                "索引 $index 的迁移语句与导出 schema 不一致",
                normalizeSql(exported.replace("\${TABLE_NAME}", table)),
                normalizeSql(migrationStatement(prefix)),
            )
        }
    }

    @Test
    fun `MIGRATION_4_5 对已有表使用 ALTER TABLE 增列（增量而非重建）`() {
        for (table in listOf("timer_session", "pause_record")) {
            assertEquals(
                "ALTER TABLE `$table` ADD COLUMN `epoch_day` INTEGER NOT NULL DEFAULT 0",
                migrationStatement("ALTER TABLE `$table`"),
            )
        }
    }

    @Test
    fun `MIGRATION_4_5 恰好新增一表五索引两增列且无多余语句`() {
        val statements = migrationStatements()
        assertEquals(8, statements.size)
        assertEquals(1, statements.count { it.startsWith("CREATE TABLE") })
        // 索引共 5 条：每日详情表 3 条（含 1 条唯一索引）+ timer_session / pause_record 逐日索引各 1 条
        assertEquals(5, statements.count { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") })
        assertEquals(2, statements.count { it.startsWith("ALTER TABLE") })
        assertTrue(
            "迁移不得出现 DROP TABLE 等破坏性语句",
            statements.none { it.startsWith("DROP") || it.startsWith("DELETE") },
        )
    }

    // ---- 反向：迁移可重复执行、版本口径统一、升级链连续 ----

    @Test
    fun `迁移建表与索引语句均为幂等写法`() {
        for (statement in migrationStatements().filter { it.startsWith("CREATE") }) {
            assertTrue(
                "迁移语句必须使用 IF NOT EXISTS 以保证重复执行安全: $statement",
                statement.contains("IF NOT EXISTS"),
            )
        }
        assertTrue(
            "MIGRATION_4_5 必须以 Migration(4, 5) 声明",
            sourceText().contains("Migration(4, 5)"),
        )
    }

    @Test
    fun `三处版本号口径统一且未启用破坏性降级`() {
        val code = codeText()
        assertTrue(code.contains("addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)"))
        assertEquals(5, CoreConstants.DATABASE_VERSION)
        assertEquals(CoreConstants.DATABASE_VERSION, schemaRaw(5).intAfter("\"version\""))
        assertFalse(
            "禁止破坏性降级：实现代码中不得出现 fallbackToDestructiveMigration",
            code.contains("fallbackToDestructiveMigration"),
        )
    }

    @Test
    fun `迁移链覆盖 1 到 5 的连续升级路径`() {
        val src = sourceText()
        for (version in 1..4) {
            assertTrue(
                "缺少 Migration($version, ${version + 1})",
                src.contains("Migration($version, ${version + 1})"),
            )
        }
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            schemaDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.nameWithoutExtension.toInt() }?.sorted(),
        )
    }

    // ---- 结构指纹：v5 必须与 v4 不同（新表与新增列确实进入导出 schema） ----

    @Test
    fun `v5 schema 结构指纹与 v4 不同且 identityHash 已写入 setupQueries`() {
        assertNotEquals(
            "v5 新增表/列后结构指纹必须变化",
            legacyIdentityHash(schemaRaw(4)),
            legacyIdentityHash(schemaRaw(5)),
        )
        val raw = schemaRaw(5)
        val identityHash = raw.stringAfter("\"identityHash\"")
        assertNotNull("v5 schema 必须写入 identityHash", identityHash)
        assertTrue(
            "identityHash 必须为 32 位十六进制（Room 编译期生成）",
            Regex("^[0-9a-f]{32}$").matches(identityHash!!),
        )
        assertTrue(
            "setupQueries 必须写入 identityHash 供 Room 打开库时比对",
            raw.setupQueries.any { it.contains(identityHash) },
        )
    }

    // ---- 测试辅助 ----

    private data class ColumnSpec(
        val affinity: String,
        val notNull: Boolean,
        val defaultValue: String?,
    )

    /**
     * 够用的 Room schema JSON 读取器：只按稳定文本标记抽取本测试所需字段，
     * 不引入额外 JSON 依赖（单测 classpath 仅有 JUnit4/mockk/coroutines-test）。
     */
    private class SchemaRaw(val text: String) {
        val tableNames: List<String> =
            Regex("\"tableName\":\\s*\"(\\w+)\"").findAll(text).map { it.groupValues[1] }.toList()

        val setupQueries: List<String>
            get() = Regex("\"setupQueries\":\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                .find(text)!!.groupValues[1]
                .let { Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(it).map { m -> m.groupValues[1] } }
                .toList()

        fun intAfter(key: String): Int =
            Regex(Regex.escape(key) + "\\s*:\\s*(\\d+)").find(text)!!.groupValues[1].toInt()

        fun stringAfter(key: String): String? =
            Regex(Regex.escape(key) + "\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)

        /** 取某表的 createSql 与各索引的建表语句。 */
        fun sqlFor(table: String): TableSql {
            val body = entityBody(table)
            val allCreateSql = Regex("\"createSql\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(body).map { it.groupValues[1] }.toList()
            return TableSql(
                createSql = allCreateSql.first(),
                indexSql = allCreateSql.drop(1),
                fieldBlock = Regex("\"fields\":\\s*\\[(.*?)\\n\\s*\\],", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: "",
            )
        }

        private fun entityBody(table: String): String {
            val start = text.indexOf("\"tableName\": \"$table\"")
            assertTrue("导出 schema 缺少表 $table", start >= 0)
            val next = text.indexOf("\"tableName\":", start + 1)
            return text.substring(start, if (next < 0) text.length else next)
        }
    }

    private class TableSql(
        val createSql: String,
        val indexSql: List<String>,
        private val fieldBlock: String,
    ) {
        fun column(name: String): ColumnSpec {
            val entry = Regex(
                "\\{[^{}]*\"columnName\":\\s*\"$name\"[^{}]*}",
                RegexOption.DOT_MATCHES_ALL,
            ).find(fieldBlock)
            assertNotNull("schema 缺少列 $name", entry)
            val body = entry!!.value
            return ColumnSpec(
                affinity = Regex("\"affinity\":\\s*\"(\\w+)\"").find(body)!!.groupValues[1],
                notNull = Regex("\"notNull\":\\s*(true|false)").find(body)!!.groupValues[1].toBoolean(),
                defaultValue = Regex("\"defaultValue\":\\s*\"([^\"]*)\"")
                    .find(body)?.groupValues?.get(1),
            )
        }
    }

    /** 取出建表语句中的外键子句，用于与期望口径逐字比对。 */
    private fun foreignKeyClause(createSql: String): String {
        val clause = tableBodyParts(createSql).lastOrNull { it.startsWith("FOREIGN KEY") }
        assertNotNull("建表语句未声明外键: $createSql", clause)
        return clause!!
    }

    /** 从 CREATE TABLE 语句中取出“列定义”部分（去掉外键子句），按顶层逗号切分。 */
    private fun columnDefinitions(createSql: String): List<String> =
        tableBodyParts(createSql).filter { !it.startsWith("FOREIGN KEY") }

    /** 建表括号内的顶层片段（按顶层逗号切分；外键子句末尾的收尾右括号在此去掉）。 */
    private fun tableBodyParts(createSql: String): List<String> {
        val open = createSql.indexOf('(')
        var depth = 0
        var close = createSql.length - 1
        for (i in open until createSql.length) {
            when (createSql[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
        }
        val parts = splitTopLevel(createSql.substring(open + 1, close))
            .map { it.trim().replace("`", "").replace(Regex("\\s+"), " ") }
        return parts.mapIndexed { index, part ->
            if (index == parts.lastIndex) part.removeSuffix(")").trim() else part
        }.filter { it.isNotEmpty() }
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in body) {
            when (ch) {
                '(' -> {
                    depth++
                    current.append(ch)
                }
                ')' -> {
                    depth--
                    current.append(ch)
                }
                ',' -> if (depth == 0) {
                    parts.add(current.toString())
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }

    /** 把 SQL 归一化为可逐字比较的形式（去引号、统一空白与大小写）。 */
    private fun normalizeSql(sql: String): String =
        sql.replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    /**
     * Room 编译期同款拼接口径的**结构指纹**：把所有建表语句与索引语句拼接后取 MD5。
     *
     * 为什么要用它：导出 schema 的 identityHash 由编译期生成（本仓库无法在单测中重算），
     * 但「结构是否变化」必须可回归——本指纹在 v4/v5 之间必须不同，v5 内部必须自洽。
     */
    private fun legacyIdentityHash(raw: SchemaRaw): String {
        val items = mutableListOf<String>()
        for (table in raw.tableNames) {
            val sql = raw.sqlFor(table)
            items += sql.createSql.replace("\${TABLE_NAME}", table)
        }
        for (table in raw.tableNames) {
            for (indexSql in raw.sqlFor(table).indexSql) {
                val unique = indexSql.contains("CREATE UNIQUE INDEX")
                val head = if (unique) "CREATE UNIQUE INDEX" else "CREATE  INDEX"
                items += head + " " + indexSql.replace("\${TABLE_NAME}", table).split("IF NOT EXISTS", limit = 2)[1]
            }
        }
        return md5Hex(items.joinToString(SEPARATOR))
    }

    private fun md5Hex(value: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun repoFile(relativePath: String): File {
        val name = relativePath.substringAfterLast('/')
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            if (File(dir, name).isFile) return File(dir, name)
            dir = dir.parentFile
        }
        throw AssertionError("在 ${File("").absolutePath} 向上未找到文件 $relativePath")
    }

    private fun schemaDir(): File =
        repoFile("app/schemas/com.assignmate.app.core.data.db.AppDatabase/5.json").parentFile!!

    private val schemaCache = mutableMapOf<Int, SchemaRaw>()

    private fun schemaRaw(version: Int): SchemaRaw = schemaCache.getOrPut(version) {
        val file = File(schemaDir(), "$version.json")
        assertTrue("schema 文件缺失: ${file.absolutePath}", file.isFile)
        SchemaRaw(file.readText())
    }

    private fun sourceText(): String = repoFile(MODULE_SOURCE).readText()

    /**
     * 去掉注释后的代码文本：注释（含 KDoc 块注释）里可能出现 `fallbackToDestructiveMigration`
     * 等说明性字样，校验只能针对真实代码，避免把注释误判为实现。
     */
    private fun codeText(): String =
        sourceText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .map { line -> line.substringBefore("//") }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /** 从 DatabaseModule.kt 中抽取 MIGRATION_4_5 内真实执行的全部 DDL 语句。 */
    private fun migrationStatements(): List<String> {
        val text = sourceText()
        val start = text.indexOf("val MIGRATION_4_5")
        assertTrue("DatabaseModule.kt 未声明 MIGRATION_4_5", start >= 0)
        val nextMigration = text.indexOf("val MIGRATION", start + 1)
        val block = text.substring(start, if (nextMigration < 0) text.length else nextMigration)
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        return Regex("db\\.execSQL\\(([\\s\\S]*?)\\)\\s*(?=\\n\\s*db\\.execSQL|\\n\\s*})")
            .findAll(block)
            .map { it.groupValues[1] }
            .map { literal ->
                Regex("\"([\\s\\S]*?)\"", RegexOption.DOT_MATCHES_ALL)
                    .findAll(literal)
                    .joinToString("") { it.groupValues[1] }
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .toList()
    }

    /** 按前缀取迁移中的唯一一条 DDL（前缀含反引号包裹的标识符，保证精确匹配）。 */
    private fun migrationStatement(prefix: String): String {
        val matched = migrationStatements().filter { it.startsWith(prefix) }
        assertEquals("迁移中前缀为 [$prefix] 的 DDL 应恰好一条", 1, matched.size)
        return matched.first()
    }

    private companion object {
        const val MODULE_SOURCE = "app/src/main/java/com/assignmate/app/core/di/DatabaseModule.kt"
        const val DAILY_TABLE = "homework_daily_record"
        const val SEPARATOR = "\u00af\\_(\u30c4)_/\u00af"

        val DAILY_COLUMNS = listOf(
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
            "homework_id INTEGER NOT NULL",
            "student_id INTEGER NOT NULL",
            "epoch_day INTEGER NOT NULL",
            "status TEXT NOT NULL",
            "started_at INTEGER",
            "estimated_minutes INTEGER",
            "actual_minutes INTEGER",
            "pause_count INTEGER NOT NULL DEFAULT 0",
            "paused_total_minutes INTEGER NOT NULL DEFAULT 0",
            "finished_at INTEGER",
            "created_at INTEGER NOT NULL",
        )

        val DAILY_INDICES = listOf(
            "index_homework_daily_record_homework_id_epoch_day",
            "index_homework_daily_record_student_id_epoch_day",
            "index_homework_daily_record_homework_id",
        )

        const val TIMER_DAY_INDEX = "index_timer_session_homework_id_epoch_day"
        const val PAUSE_DAY_INDEX = "index_pause_record_homework_id_epoch_day"
    }
}
