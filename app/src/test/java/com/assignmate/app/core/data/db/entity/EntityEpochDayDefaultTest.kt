package com.assignmate.app.core.data.db.entity

import java.io.File
import java.lang.reflect.Constructor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `epoch_day` 默认值口径的单测（纯 JVM，无需设备/模拟器；不依赖 kotlin-reflect）。
 *
 * 背景（皐陶审查遗留 #3）：`@ColumnInfo(defaultValue = "0")` 只是 Room 建表/旧库
 * `ALTER TABLE ADD COLUMN` 的**列默认值**（SQLite 语义），而 Kotlin 数据类的
 * `val epochDay: Long = 0L` 会让调用方漏传时**静默**写出 `epoch_day = 0` 的脏行——
 * 该值不属于任何真实自然日（1970-01-01），按（作业 + 自然日）查不到、统计里也归不了日。
 *
 * 因此两条要求必须同时成立，本测试逐项锁定：
 * 1. Kotlin 侧**无默认值**：漏传在编译期即失败——源码断言 `val epochDay: Long` 不带 `= 默认值`，
 *    并按**实体源码的字段顺序**定位 epochDay 在构造参数中的下标，再用反射核对该下标确为 Long
 *    （下标不写死：TimerSessionEntity 该位是第 5 个参数、PauseRecordEntity 是第 4 个，
 *    写死下标会指到同类型的相邻字段而失去鉴别力——该缺陷由离朱测试复核指出后修正）；
 * 2. Room 侧**列默认值仍为 "0"**：`@ColumnInfo` 的保留级别是 CLASS（运行期不可见，
 *    故用源码扫描验证），不改变 v4 -> v5 的迁移 DDL 与导出 schema（旧库加列兼容）。
 *
 * 配套：既有「v4 旧库遗留 epoch_day = 0 的行按开始时刻自愈折算」路径见 timer 模块的
 * TimerDayAttributionTest（`旧库未落归属日的会话按开始时刻补折算并写入当天详情`）；
 * 本次只改写入侧默认值，不动该自愈口径。
 */
class EntityEpochDayDefaultTest {

    // ---- 正向：Kotlin 无默认值（漏传在编译期暴露） ----

    @Test
    fun `两实体的 epochDay 都是必填构造参数（源码不声明默认值）`() {
        for (fileName in listOf("TimerSessionEntity.kt", "PauseRecordEntity.kt")) {
            val code = codeTextOf(fileName)
            assertFalse(
                "$fileName 不得出现 `val epochDay: Long = ...`（带默认值会让漏传静默写出 epoch_day = 0）",
                EPOCH_DAY_WITH_DEFAULT.containsMatchIn(code),
            )
            assertTrue(
                "$fileName 必须保留 `val epochDay: Long` 的必填声明",
                EPOCH_DAY_REQUIRED.containsMatchIn(code),
            )
        }
    }

    /**
     * 下标/类型契约：epochDay 在**主构造参数序列**中的位置与类型必须与源码字段声明一致。
     *
     * 「无默认值」由上面的源码断言（[ENTITY_SOURCE_FILES] 首个用例）与 Room 侧列默认值用例共同保证；
     * 本用例补的是**位置正确性**——若把这个下标写死，会指向同类型的相邻字段（TimerSessionEntity 的
     * parentAccountId 也是 Long），断言恒成立且失去鉴别力（离朱测试复核已实证该缺陷）。
     */
    @Test
    fun `epochDay 在主构造参数序列中的下标与类型由源码字段顺序确定`() {
        for (fileName in ENTITY_SOURCE_FILES) {
            val parameters = parametersOf(fileName)
            val epochDayIndex = parameters.indexOfFirst { it.first == "epochDay" }
            assertTrue("$fileName 必须声明 val epochDay", epochDayIndex >= 0)
            assertEquals(
                "$fileName 的 epochDay 类型必须是 Long",
                "Long",
                parameters[epochDayIndex].second,
            )

            val entityClass = entityClassOf(fileName)
            val constructorParameters = primaryConstructorOf(entityClass).parameterTypes
            assertEquals(
                "$fileName 主构造参数个数必须与源码字段个数一致（构造顺序与字段声明顺序相同）",
                parameters.size,
                constructorParameters.size,
            )

            // 运行期对照面（摆脱「仅依赖源码文本解析」的单点依赖）：
            // Kotlin 属性的 backing field 运行期可见、顺序与主构造参数一致，类型按 JVM 描述符给出
            // （Long -> long）；编译器为数据类额外生成的 `$stable` 字段不计入属性字段。
            val propertyFields = runtimePropertyFieldsOf(entityClass)
            val runtimeEpochDayIndex = propertyFields.indexOfFirst { it.name == "epochDay" }
            assertEquals(
                "$fileName 运行期属性字段中必须存在 epochDay（与源码解析交叉核对）",
                epochDayIndex,
                runtimeEpochDayIndex,
            )
            assertEquals(
                "$fileName 运行期属性字段 epochDay 的类型必须是 long（Kotlin 类型为 Long）",
                Long::class.javaPrimitiveType,
                entityClass.getDeclaredField("epochDay").type,
            )
            assertEquals(
                "$fileName 运行期属性字段个数必须与主构造参数个数一致（每个构造参数对应一个属性）",
                constructorParameters.size,
                propertyFields.size,
            )

            assertEquals(
                "$fileName 位于下标 $epochDayIndex 的主构造参数必须是 Long（即 epochDay 本身）；" +
                    "若此断言指向了同类型的相邻字段（如 parentAccountId），说明下标定位失效",
                Long::class.javaPrimitiveType,
                constructorParameters[epochDayIndex],
            )
            // 追加断言：下标两侧的字段类型必须与源码一致，避免「下标恰好落在另一个 Long 字段」的假通过
            val parentAccountIndex = parameters.indexOfFirst { it.first == "parentAccountId" }
            if (parentAccountIndex >= 0) {
                assertTrue(
                    "parentAccountId 必须位于 epochDay 之前（两字段不得相互错位）",
                    parentAccountIndex < epochDayIndex,
                )
                assertEquals(
                    "parentAccountId 同为 Long 字段，因此必须靠「下标 + 两侧字段」共同定位",
                    Long::class.javaPrimitiveType,
                    constructorParameters[parentAccountIndex],
                )
            }
        }
    }

    /**
     * 定位口径自证：把一份**字段顺序被交换**的合成实体源码交给解析器，epochDay 的下标必须随之变化。
     *
     * 为什么需要它：真实实体里 epochDay 与 parentAccountId 同为 Long，若把下标写死（历史缺陷），
     * 交换字段顺序后断言会指向 parentAccountId 而**假通过**。本用例不依赖任何生产代码改动即可
     * 证明「下标确实由源码顺序推导」，并与上一条用例的「parentAccountId 必须位于 epochDay 之前」
     * 构成完整闭环。
     */
    @Test
    fun `定位口径自证：字段顺序被交换时 epochDay 下标随之变化`() {
        val canonical = parametersFromText(
            """
            data class SyntheticEntity(
                @ColumnInfo(name = "parent_account_id")
                val parentAccountId: Long,
                @ColumnInfo(name = "epoch_day")
                val epochDay: Long,
            )
            """.trimIndent(),
        )
        assertEquals("标准顺序下 epochDay 应为第 2 个参数", 1, canonical.indexOfFirst { it.first == "epochDay" })

        val swapped = parametersFromText(
            """
            data class SyntheticEntity(
                @ColumnInfo(name = "epoch_day")
                val epochDay: Long,
                @ColumnInfo(name = "parent_account_id")
                val parentAccountId: Long,
            )
            """.trimIndent(),
        )
        val swappedEpochDayIndex = swapped.indexOfFirst { it.first == "epochDay" }
        val swappedParentIndex = swapped.indexOfFirst { it.first == "parentAccountId" }
        assertEquals("交换后 epochDay 必须落到下标 0（写死下标会停在旧位置）", 0, swappedEpochDayIndex)
        assertTrue(
            "交换后 parentAccountId 落在 epochDay 之后——真实实体的「parentAccountId 在前」断言" +
                "必须能识别该类错位（否则定位失效）",
            swappedParentIndex > swappedEpochDayIndex,
        )
        // 真实实体同理：不写死下标是唯一能识别「字段顺序变化」的口径
        for (fileName in ENTITY_SOURCE_FILES) {
            val realParameters = parametersOf(fileName)
            val index = realParameters.indexOfFirst { it.first == "epochDay" }
            assertTrue("$fileName 的 epochDay 必须能被源码顺序定位", index >= 0)
            assertEquals(
                "$fileName 定位结果必须与合成源码同口径（按声明顺序计数）",
                realParameters[index].second,
                "Long",
            )
        }
    }

    @Test
    fun `其余可选参数仍保留各自默认值（说明本次只收敛了 epochDay）`() {
        // 运行时行为自证：id / finishedAt / pausedTotalMillis / pauseCount 的默认值仍生效
        val session = TimerSessionEntity(
            homeworkId = 1L,
            studentId = 2L,
            parentAccountId = 3L,
            epochDay = 20_089L,
            startedAt = Instant.ofEpochMilli(0L),
            status = "RUNNING",
        )
        assertEquals("id 默认 0（交给数据库自增）", 0L, session.id)
        assertNull("finishedAt 默认 null（会话未结束）", session.finishedAt)
        assertEquals("pausedTotalMillis 默认 0", 0L, session.pausedTotalMillis)
        assertEquals("pauseCount 默认 0", 0, session.pauseCount)

        val pause = PauseRecordEntity(
            sessionId = 1L,
            homeworkId = 1L,
            epochDay = 20_089L,
            pauseStartAt = Instant.ofEpochMilli(0L),
        )
        assertEquals("id 默认 0", 0L, pause.id)
        assertNull("pauseEndAt 默认 null（暂停中）", pause.pauseEndAt)
    }

    // ---- 反向：列默认值仍保留（仅服务旧库加列 / 建表兼容） ----

    @Test
    fun `两实体的 epoch_day 列默认值仍为 0 以保证旧库加列兼容`() {
        for (fileName in listOf("TimerSessionEntity.kt", "PauseRecordEntity.kt")) {
            val annotation = columnInfoOfEpochDay(fileName)
            assertEquals(
                "$fileName 的 epoch_day 列名必须保持不变",
                "@ColumnInfo(name = \"epoch_day\"",
                annotation.substringBefore(",").trim(),
            )
            assertTrue(
                "$fileName 的 epoch_day 必须保留列默认值 \"0\"（仅服务旧库 ALTER TABLE 加列兼容）",
                annotation.contains("defaultValue = \"0\""),
            )
        }
    }

    @Test
    fun `实体源码中不再出现 epochDay 的 Kotlin 默认值写法`() {
        for (fileName in listOf("TimerSessionEntity.kt", "PauseRecordEntity.kt")) {
            val code = codeTextOf(fileName)
            assertFalse(
                "$fileName 不得出现 `val epochDay: Long = 0L`（漏传必须编译期暴露，而非静默写 0）",
                Regex("val\\s+epochDay\\s*:\\s*Long\\s*=").containsMatchIn(code),
            )
            assertTrue(
                "$fileName 必须保留 `val epochDay: Long` 的非默认声明",
                Regex("val\\s+epochDay\\s*:\\s*Long\\s*(,|\\))").containsMatchIn(code),
            )
        }
    }

    // ---- 语义：显式传入的值按原样保留，0 只是「未折算」哨兵 ----

    @Test
    fun `显式传入的业务自然日按原样落在实体上`() {
        val day = LocalDate.of(2025, 1, 5).toEpochDay()
        val session = TimerSessionEntity(
            homeworkId = 1L,
            studentId = 2L,
            parentAccountId = 3L,
            epochDay = day,
            startedAt = Instant.ofEpochMilli(0L),
            status = "RUNNING",
        )
        val pause = PauseRecordEntity(
            sessionId = 1L,
            homeworkId = 1L,
            epochDay = day,
            pauseStartAt = Instant.ofEpochMilli(0L),
        )

        assertEquals(day, session.epochDay)
        assertEquals(day, pause.epochDay)
        assertEquals(
            "0 是「未折算」哨兵（1970-01-01），不属于任何真实业务自然日",
            1970,
            LocalDate.ofEpochDay(UNSPECIFIED_EPOCH_DAY).year,
        )
        assertTrue("真实业务自然日必然不等于哨兵值", day != UNSPECIFIED_EPOCH_DAY)
    }

    @Test
    fun `哨兵值与真实业务自然日可区分且业务时区口径与 UTC 口径相差一天`() {
        val realDay = LocalDate.of(2024, 12, 31).toEpochDay()
        val gap = realDay - UNSPECIFIED_EPOCH_DAY
        assertTrue("真实自然日与哨兵的差距必然远大于 1 天", gap > 365L * 50L)

        // 跨零点时刻：业务时区（上海）已进入次日，UTC 口径仍在当天——禁止用 UTC 毫秒折算做归属日
        val boundaryMillis = 1_735_678_400_000L // 2024-12-31T22:13:20Z = 上海 2025-01-01 06:13:20
        val shanghaiDay = Instant.ofEpochMilli(boundaryMillis)
            .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toEpochDay()
        val utcDay = Instant.ofEpochMilli(boundaryMillis)
            .atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay()
        assertEquals("同一时刻在两地口径下相差 1 天", 1L, shanghaiDay - utcDay)
    }

    // ---- 测试辅助 ----

    /**
     * 从实体源码按声明顺序取出**主构造**字段（属性名 -> 类型文本）。
     *
     * 两个要点：
     * 1. 作用域限定在 `data class XxxEntity(` 到与之配对的右括号之间（括号配对扫描），
     *    不整文件扫描——否则将来在实体类体内新增带类型的属性或 `val` 局部声明会造成参数计数误报；
     * 2. 主构造顺序 = 字段声明顺序（Room 实体均为单一主构造的数据类），因此源码顺序即可
     *    权威定位 epochDay 的构造参数下标；不写死下标的理由见类注释。
     */
    private fun parametersOf(entityFileName: String): List<Pair<String, String>> =
        parametersFromText(codeTextOf(entityFileName)).also { parameters ->
            assertTrue("$entityFileName 未解析到任何主构造参数", parameters.size >= 3)
        }

    /** 从任意 Kotlin 源码文本解析「data class 主构造」的字段（属性名 -> 类型文本，按声明顺序） */
    private fun parametersFromText(code: String): List<Pair<String, String>> {
        val header = CONSTRUCTOR_HEADER.find(code)
        assertTrue("源码必须声明 data class 主构造", header != null)

        val parametersStart = code.indexOf('(', header!!.range.last)
        assertTrue("data class 主构造缺少左括号", parametersStart > 0)
        val parametersEnd = matchingParenthesis(code, parametersStart)
        val constructorBody = code.substring(parametersStart + 1, parametersEnd)

        return CONSTRUCTOR_PARAMETER.findAll(constructorBody)
            .map { match -> match.groupValues[1] to match.groupValues[2].removeSuffix("?") }
            .toList()
    }

    /** 返回与 [openIndex] 处左括号配对的右括号下标（同一段文本内），未配对即断言失败 */
    private fun matchingParenthesis(code: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw AssertionError("主构造括号未闭合（起始下标 $openIndex）")
    }

    /** 实体文件名 -> 实体类（反射侧核对构造签名用） */
    private fun entityClassOf(entityFileName: String): Class<*> = when (entityFileName) {
        "TimerSessionEntity.kt" -> TimerSessionEntity::class.java
        "PauseRecordEntity.kt" -> PauseRecordEntity::class.java
        else -> throw AssertionError("未登记的实体文件: $entityFileName")
    }

    /**
     * 运行期属性字段（按声明顺序）：主构造的每个 `val` 参数对应一个 backing field。
     *
     * 需剔除编译器为数据类生成的 `$stable` 标记字段（Kotlin 2.x 起出现，命名以 `$` 开头），
     * 它不是构造参数对应的属性，若计入会造成参数计数误报。
     */
    private fun runtimePropertyFieldsOf(entityClass: Class<*>): List<java.lang.reflect.Field> =
        entityClass.declaredFields.filterNot { it.name.startsWith("\$") }

    /** 主构造函数 = 唯一「非 synthetic 的 public 构造」，其参数个数即数据类的参数个数 */
    private fun primaryConstructorOf(entityClass: Class<*>): Constructor<*> {
        val candidates = entityClass.declaredConstructors.filter {
            !it.isSynthetic && java.lang.reflect.Modifier.isPublic(it.modifiers)
        }
        assertEquals("${entityClass.simpleName} 必须恰好有一个主构造函数", 1, candidates.size)
        return candidates.single()
    }

    /**
     * 取 `epoch_day` 字段上 `@ColumnInfo(...)` 的源码文本。
     *
     * 为什么用源码扫描：`@ColumnInfo` 的保留级别是 CLASS（见 androidx.room.ColumnInfo 的
     * `@Retention(RetentionPolicy.CLASS)`），运行期反射读不到；而列名与列默认值直接决定
     * Room 的建表/迁移 DDL 与导出 schema（已由 DatabaseSchemaV5ConsistencyTest 逐字校验），
     * 此处只需保证实体声明不漂移。
     */
    private fun columnInfoOfEpochDay(entityFileName: String): String {
        val code = codeTextOf(entityFileName)
        val declarationIndex = code.indexOf("val epochDay")
        assertTrue("$entityFileName 必须声明 val epochDay", declarationIndex > 0)
        val before = code.substring(0, declarationIndex)
        val annotation = before.lastIndexOf("@ColumnInfo(")
        assertTrue("$entityFileName 的 epochDay 必须标注 @ColumnInfo", annotation > 0)
        val close = before.indexOf(')', annotation)
        assertTrue("$entityFileName 的 @ColumnInfo(...) 必须闭合", close > annotation)
        return before.substring(annotation, close + 1)
    }

    /** 实体源码文本（去掉块注释与行注释，避免说明性文字参与结构校验） */
    private fun codeTextOf(entityFileName: String): String =
        entitySourceFile(entityFileName).readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun entitySourceFile(entityFileName: String): File {
        val relative = "$MAIN_SOURCE_ROOT/$entityFileName"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            if (File(dir, entityFileName).isFile) return File(dir, entityFileName)
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $relative")
    }

    private companion object {

        const val MAIN_SOURCE_ROOT =
            "app/src/main/java/com/assignmate/app/core/data/db/entity"

        /** 待校验实体源码文件（同时用于下标推导与列默认值扫描） */
        val ENTITY_SOURCE_FILES = listOf("TimerSessionEntity.kt", "PauseRecordEntity.kt")

        /** 主构造起点：`data class XxxEntity(`（据此把字段解析限定在主构造括号内） */
        val CONSTRUCTOR_HEADER = Regex("data\\s+class\\s+\\w+\\s*\\(")

        /**
         * 主构造字段匹配：`val 名称: 类型[?]`（类型可带泛型/限定名；可空标记单独成组，判定时去掉）。
         * 调用方已把扫描范围限定在主构造括号内（见 [parametersOf]），故不会误匹配类体内的声明。
         */
        val CONSTRUCTOR_PARAMETER = Regex(
            "val\\s+(\\w+)\\s*:\\s*([\\w.<>]+)\\s*\\??\\s*(?=[,)=\\n])",
        )

        /** 带默认值的写法（本次收敛的目标：实体中不得再出现） */
        val EPOCH_DAY_WITH_DEFAULT = Regex("val\\s+epochDay\\s*:\\s*Long\\s*=")

        /** 必填写法：`val epochDay: Long,` 或参数列表末尾 `val epochDay: Long)` */
        val EPOCH_DAY_REQUIRED = Regex("val\\s+epochDay\\s*:\\s*Long\\s*[,)]")

        /** `epoch_day` 的「未折算」哨兵值（列默认值，仅历史行可能出现） */
        const val UNSPECIFIED_EPOCH_DAY = 0L
    }
}