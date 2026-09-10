package com.assignmate.app.core.data.db

import com.assignmate.app.core.domain.util.CoreConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core 数据库 schema v3->v4 静态一致性单测（纯 JVM，不需要设备/模拟器）。
 *
 * 被测对象有两个来源，本测试保证两者逐项一致：
 * 1. 迁移 DDL：从 `core/di/DatabaseModule.kt` 源码中抽取 MIGRATION_3_4 内真实执行的
 *    CREATE TABLE / CREATE INDEX 语句（避免手抄导致与实现脱节）；
 * 2. 导出 schema：`app/schemas/com.assignmate.app.core.data.db.AppDatabase/{1..4}.json`
 *    （由 KSP + Room 在编译期导出，反映 @Entity/@Database 的最终定义）。
 *
 * 覆盖点：建表列与顺序、PK/AUTOINCREMENT、NOT NULL 与默认值、外键与级联动作、
 * 索引名与列、旧 schema 文件保留、版本号与 DATABASE_VERSION 一致，以及 Room 运行期
 * 打开旧库时比对的完整性校验（DDL 归一化后必须完全相等）。
 */
class DatabaseSchemaV4ConsistencyTest {

    // ---- 期望口径（来源：待测试功能说明 v4 表结构定义） ----

    private val timerSession = ExpectedTable(
        table = "timer_session",
        columns = listOf(
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
            "homework_id INTEGER NOT NULL",
            "student_id INTEGER NOT NULL",
            "parent_account_id INTEGER NOT NULL",
            "started_at INTEGER NOT NULL",
            "finished_at INTEGER",
            "paused_total_millis INTEGER NOT NULL DEFAULT 0",
            "pause_count INTEGER NOT NULL DEFAULT 0",
            "status TEXT NOT NULL",
        ),
        foreignKey = "FOREIGN KEY(homework_id) REFERENCES homework_item(id) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE",
        indices = listOf(
            "index_timer_session_homework_id",
            "index_timer_session_student_id",
            "index_timer_session_student_id_status",
        ),
    )

    private val pauseRecord = ExpectedTable(
        table = "pause_record",
        columns = listOf(
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
            "session_id INTEGER NOT NULL",
            "homework_id INTEGER NOT NULL",
            "pause_start_at INTEGER NOT NULL",
            "pause_end_at INTEGER",
        ),
        foreignKey = "FOREIGN KEY(session_id) REFERENCES timer_session(id) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE",
        indices = listOf(
            "index_pause_record_session_id",
            "index_pause_record_homework_id",
        ),
    )

    private val migratedTables = listOf(timerSession, pauseRecord)

    // ---- 正向：导出 schema 的版本、identityHash、表集合 ----

    @Test
    fun `v4 schema 版本号与 identityHash 与说明一致`() {
        val raw = schemaRaw(4)
        assertEquals(4, raw.intAfter("\"version\""))
        val identityHash = raw.stringAfter("\"identityHash\"")!!
        assertEquals("ec71d42158f7a17a089c63602229ace8", identityHash)
        assertTrue(
            "setupQueries 必须写入 identityHash 供 Room 打开库时比对",
            raw.setupQueries.any { it.contains(identityHash) },
        )
        assertEquals(CoreConstants.DATABASE_VERSION, raw.intAfter("\"version\""))
    }

    @Test
    fun `v4 schema 包含 6 张表且两新表俱全`() {
        assertEquals(
            listOf(
                "ocr_retry_task",
                "parent_account",
                "student",
                "homework_item",
                "timer_session",
                "pause_record",
            ),
            schemaRaw(4).tableNames,
        )
    }

    @Test
    fun `历史 schema 文件 1 到 3 全部保留`() {
        val dir = schemaDir()
        assertTrue("schema 目录不存在: $dir", dir.isDirectory)
        assertEquals(
            listOf("1.json", "2.json", "3.json", "4.json"),
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.name }?.sorted(),
        )
        assertEquals(1, schemaRaw(1).intAfter("\"version\""))
        assertEquals(2, schemaRaw(2).intAfter("\"version\""))
        assertEquals(3, schemaRaw(3).intAfter("\"version\""))
    }

    // ---- 正向：导出 schema 的表结构逐项核对 ----

    @Test
    fun `timer_session 表结构与说明逐列一致`() {
        assertColumnsMatchSchema(timerSession)
        assertForeignKeyMatchesSchema(timerSession)
        assertIndicesMatchSchema(timerSession)
    }

    @Test
    fun `pause_record 表结构与说明逐列一致`() {
        assertColumnsMatchSchema(pauseRecord)
        assertForeignKeyMatchesSchema(pauseRecord)
        assertIndicesMatchSchema(pauseRecord)
    }

    @Test
    fun `两新表的可空列与默认值语义正确`() {
        val session = schemaRaw(4).sqlFor("timer_session")
        assertFalse("finished_at 必须可空（会话进行中为 null）", session.column("finished_at").notNull)
        assertTrue("started_at 必须非空", session.column("started_at").notNull)
        assertEquals("0", session.column("paused_total_millis").defaultValue)
        assertEquals("0", session.column("pause_count").defaultValue)
        assertEquals("INTEGER", session.column("paused_total_millis").affinity)

        val pause = schemaRaw(4).sqlFor("pause_record")
        assertFalse("pause_end_at 必须可空（仍在暂停中为 null）", pause.column("pause_end_at").notNull)
        assertTrue("pause_start_at 必须非空", pause.column("pause_start_at").notNull)
        assertTrue("session_id 必须非空", pause.column("session_id").notNull)
    }

    @Test
    fun `两新表主键均为自增 Long id`() {
        for (name in listOf("timer_session", "pause_record")) {
            val sql = schemaRaw(4).sqlFor(name)
            assertTrue(
                "$name 主键必须自增",
                sql.createSql.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"),
            )
            assertTrue("$name 主键列必须非空", sql.column("id").notNull)
            assertEquals("INTEGER", sql.column("id").affinity)
        }
    }

    // ---- 一致性：迁移 DDL vs 导出 schema（Room 打开旧库时会逐字比对，归一化后必须相等） ----

    @Test
    fun `MIGRATION_3_4 建表语句与 v4 schema 完全一致`() {
        for (expected in migratedTables) {
            assertEquals(
                "表 ${expected.table} 的迁移建表语句与导出 schema 不一致",
                normalizeSql(
                    schemaRaw(4).sqlFor(expected.table).createSql
                        .replace("\${TABLE_NAME}", expected.table),
                ),
                normalizeSql(migrationTableDdl(expected.table)),
            )
        }
    }

    @Test
    fun `MIGRATION_3_4 索引语句与 v4 schema 完全一致`() {
        for (expected in migratedTables) {
            val indexSql = schemaRaw(4).sqlFor(expected.table).indexSql
            for (indexName in expected.indices) {
                val exported = indexSql.firstOrNull {
                    it.replace("\${TABLE_NAME}", expected.table).contains("`$indexName`")
                }
                assertNotNull("schema 缺少索引 $indexName", exported)
                assertEquals(
                    "索引 $indexName 的迁移语句与导出 schema 不一致",
                    normalizeSql(exported!!.replace("\${TABLE_NAME}", expected.table)),
                    normalizeSql(migrationIndexDdl(expected.table, indexName)),
                )
            }
        }
    }

    @Test
    fun `MIGRATION_3_4 恰好新增两表五索引且无多余语句`() {
        val statements = migrationStatements()
        val tables = statements.filter { it.startsWith("CREATE TABLE") }
        val indices = statements.filter { it.startsWith("CREATE INDEX") }
        assertEquals(
            listOf("timer_session", "pause_record"),
            tables.map { Regex("CREATE TABLE IF NOT EXISTS `(\\w+)`").find(it)!!.groupValues[1] },
        )
        assertEquals(7, statements.size)
        assertEquals(
            timerSession.indices + pauseRecord.indices,
            indices.map { Regex("CREATE INDEX IF NOT EXISTS `(\\w+)`").find(it)!!.groupValues[1] },
        )
    }

    // ---- 反向：外键只落在声明的列上，冗余维度不得重复建外键 ----

    @Test
    fun `timer_session 外键仅 homework_id 且级联删除`() {
        val createSql = schemaRaw(4).sqlFor("timer_session").createSql
        assertEquals(1, Regex("FOREIGN KEY", RegexOption.IGNORE_CASE).findAll(createSql).count())
        assertEquals(
            normalizeSql(timerSession.foreignKey),
            normalizeSql(foreignKeyClause(createSql)),
        )
        assertFalse(
            "student_id/parent_account_id 为冗余维度，不得建外键（避免与 homework_item 级联路径重复）",
            Regex("FOREIGN KEY\\(`?(student_id|parent_account_id)`?\\)", RegexOption.IGNORE_CASE)
                .containsMatchIn(createSql),
        )
    }

    @Test
    fun `pause_record 外键仅 session_id 且级联删除`() {
        val createSql = schemaRaw(4).sqlFor("pause_record").createSql
        assertEquals(1, Regex("FOREIGN KEY", RegexOption.IGNORE_CASE).findAll(createSql).count())
        assertEquals(
            normalizeSql(pauseRecord.foreignKey),
            normalizeSql(foreignKeyClause(createSql)),
        )
        assertFalse(
            "homework_id 为冗余维度，不得建外键",
            Regex("FOREIGN KEY\\(`?homework_id`?\\)", RegexOption.IGNORE_CASE)
                .containsMatchIn(createSql),
        )
    }

    // ---- 反向：迁移必须可重复执行（幂等），版本口径统一 ----

    @Test
    fun `迁移建表与索引语句均为幂等写法`() {
        for (statement in migrationStatements()) {
            assertTrue(
                "迁移语句必须使用 IF NOT EXISTS 以保证重复执行安全: $statement",
                statement.contains("IF NOT EXISTS"),
            )
        }
        assertTrue(
            "MIGRATION_3_4 必须以 Migration(3, 4) 声明",
            sourceText().contains("Migration(3, 4)"),
        )
    }

    @Test
    fun `三处版本号口径统一且未启用破坏性降级`() {
        val code = codeText()
        assertTrue(code.contains("addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"))
        assertEquals(4, CoreConstants.DATABASE_VERSION)
        assertEquals(CoreConstants.DATABASE_VERSION, schemaRaw(4).intAfter("\"version\""))
        assertFalse(
            "禁止破坏性降级：实现代码中不得出现 fallbackToDestructiveMigration",
            code.contains("fallbackToDestructiveMigration"),
        )
    }

    // ---- 边界：旧库升级路径必须连续（1->2、2->3、3->4），不得跳版 ----

    @Test
    fun `迁移链覆盖 1 到 4 的连续升级路径`() {
        val src = sourceText()
        for (version in 1..3) {
            assertTrue(
                "缺少 Migration($version, ${version + 1})",
                src.contains("Migration($version, ${version + 1})"),
            )
        }
        assertEquals(
            listOf(1, 2, 3, 4),
            schemaDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.nameWithoutExtension.toInt() }?.sorted(),
        )
    }

    // ---- 测试辅助 ----

    private data class ExpectedTable(
        val table: String,
        val columns: List<String>,
        val foreignKey: String,
        val indices: List<String>,
    )

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

    private fun assertColumnsMatchSchema(expected: ExpectedTable) {
        val createSql = schemaRaw(4).sqlFor(expected.table).createSql
        assertEquals(
            "表 ${expected.table} 建表列/顺序不一致",
            expected.columns,
            columnDefinitions(createSql),
        )
    }

    private fun assertForeignKeyMatchesSchema(expected: ExpectedTable) {
        val createSql = schemaRaw(4).sqlFor(expected.table).createSql
        assertEquals(
            "表 ${expected.table} 外键不一致",
            normalizeSql(expected.foreignKey),
            normalizeSql(foreignKeyClause(createSql)),
        )
    }

    private fun assertIndicesMatchSchema(expected: ExpectedTable) {
        val actual = schemaRaw(4).sqlFor(expected.table).indexSql
            .map { Regex("INDEX IF NOT EXISTS `(\\w+)`").find(it)!!.groupValues[1] }
        assertEquals("表 ${expected.table} 索引不一致", expected.indices, actual)
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
        repoFile("app/schemas/com.assignmate.app.core.data.db.AppDatabase/4.json").parentFile!!

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

    /** 从 DatabaseModule.kt 中抽取 MIGRATION_3_4 内真实执行的全部 CREATE 语句。 */
    private fun migrationStatements(): List<String> {
        val text = sourceText()
        val start = text.indexOf("val MIGRATION_3_4")
        assertTrue("DatabaseModule.kt 未声明 MIGRATION_3_4", start >= 0)
        val nextMigration = text.indexOf("val MIGRATION", start + 1)
        val block = text.substring(start, if (nextMigration < 0) text.length else nextMigration)
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        val statements = Regex("db\\.execSQL\\(([\\s\\S]*?)\\)\\s*(?=\\n\\s*db\\.execSQL|\\n\\s*})")
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
        assertEquals("MIGRATION_3_4 应恰好包含 7 条 DDL（2 建表 + 5 索引）", 7, statements.size)
        return statements
    }

    private fun migrationTableDdl(table: String): String {
        val statement = migrationStatements()
            .firstOrNull { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") }
        assertNotNull("MIGRATION_3_4 未创建表 $table", statement)
        return statement!!
    }

    private fun migrationIndexDdl(table: String, indexName: String): String {
        val statement = migrationStatements()
            .firstOrNull { it.startsWith("CREATE INDEX IF NOT EXISTS `$indexName`") }
        assertNotNull("MIGRATION_3_4 未创建索引 $indexName", statement)
        val statementSql = requireNotNull(statement)
        assertTrue(
            "索引 $indexName 未指向表 $table: $statementSql",
            statementSql.contains("ON `$table`"),
        )
        return statementSql
    }

    private companion object {
        const val MODULE_SOURCE = "app/src/main/java/com/assignmate/app/core/di/DatabaseModule.kt"
    }
}