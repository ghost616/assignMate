package com.assignmate.app.core.data.di

import com.assignmate.app.core.data.db.FakeHomeworkDailyRecordDao
import com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl
import java.io.File
import java.lang.reflect.Constructor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 独立复核探针（离朱）：**不复用**计划自带测试（BusinessTimeZoneSingleSourceTest /
 * EntityEpochDayDefaultTest）的夹具与判定启发式，从五个互相独立的角度复核
 * 「业务时区收敛为唯一来源 + epochDay 默认值清理」是否真的成立：
 *
 * A. DI 图（Hilt 生成代码）层：全图 ZoneId 是否真的只有一个 Provider，且被 core / homework /
 *    stats / timer 四个模块共同消费；
 * B. 行为层：以硬编码日历期望（非公式复算）复核 epochDayOf 的时区边界切换；
 * C. 编译期语义层：以「默认值掩码合成构造」在运行时证明两实体的 epochDay **确实没有** Kotlin
 *    默认值（计划测试只用源码正则，未做运行时证明）；
 * D. 注入点层：所有 ZoneId 消费点的构造参数都不带 @Named（第二入口必须以限定符形式出现）；
 * E. 源码层：以**返回类型**（而非函数名含 zone）判定，全应用只有 core 一处返回 ZoneId 的
 *    提供函数——计划测试的函数名启发式会被 `fun provideToday(): ZoneId` 这类命名绕过。
 */
class BusinessTimeZoneConvergenceProbeTest {

    // ---- A. 生成代码（DI 图）层 ----

    @Test
    fun `生成 Hilt 组件中 ZoneId 只有一个 Provider 且被四个模块共同消费`() {
        val component = generatedComponentFile()
        val code = component.readText()

        val providerFields = Regex("Provider<ZoneId>\\s+(\\w+)\\s*;").findAll(code)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "Hilt 图中必须恰好存在一个 ZoneId Provider（出现多个说明还有第二个绑定入口）",
            listOf("provideBusinessZoneIdProvider"),
            providerFields,
        )

        val zoneFactories = Regex("import\\s+[\\w.]*ZoneIdFactory\\s*;").findAll(code)
            .map { it.value.trim() }
            .toList()
        assertEquals(
            "只允许 core 的 provideBusinessZoneId 工厂进入 Hilt 图",
            listOf("import com.assignmate.app.core.di.DailyRecordModule_ProvideBusinessZoneIdFactory;"),
            zoneFactories,
        )

        val consumption = Regex("provideBusinessZoneIdProvider\\.get\\(\\)").findAll(code).count()
        assertTrue(
            "唯一绑定必须被各模块消费（期望 >= 10 处，实际 " + consumption + "）——" +
                "消费数过少说明部分模块仍在自建时区",
            consumption >= 10,
        )

        val representatives = listOf(
            "new HomeworkDailyRecordRepositoryImpl(",
            "new HomeworkListViewModel(",
            "new StatsRepositoryImpl(",
            "new TimerExecutionViewModel(",
        )
        for (callSite in representatives) {
            val start = code.indexOf(callSite)
            assertTrue("生成代码中必须存在消费点 " + callSite, start >= 0)
            val end = code.indexOf(");", start).let { if (it < 0) code.length else it }
            assertTrue(
                callSite + " 必须注入唯一业务时区绑定（provideBusinessZoneIdProvider）",
                code.substring(start, end).contains("provideBusinessZoneIdProvider.get()"),
            )
        }
    }

    // ---- B. 行为层：硬编码日历期望 ----

    @Test
    fun `epochDayOf 在业务时区零点按自然日切换且与 UTC 口径可相差一天`() {
        val shanghai = repositoryIn(ZoneId.of("Asia/Shanghai"))
        val utc = repositoryIn(ZoneId.of("UTC"))

        assertEquals(20_088L, LocalDate.of(2024, 12, 31).toEpochDay())
        assertEquals(20_089L, LocalDate.of(2025, 1, 1).toEpochDay())
        assertEquals(20_093L, LocalDate.of(2025, 1, 5).toEpochDay())

        assertEquals(20_088L, shanghai.epochDayOf(1_735_660_799_000L))
        assertEquals(20_088L, utc.epochDayOf(1_735_660_799_000L))

        assertEquals(20_089L, shanghai.epochDayOf(1_735_660_800_000L))
        assertEquals(20_088L, utc.epochDayOf(1_735_660_800_000L))

        assertEquals(20_093L, shanghai.epochDayOf(1_736_008_200_000L))
        assertEquals(20_092L, utc.epochDayOf(1_736_008_200_000L))

        assertEquals(0L, shanghai.epochDayOf(0L))
        assertEquals(0L, utc.epochDayOf(0L))

        assertEquals(
            "覆写注入时区必须整体改变折算口径（若仍存在 UTC 毫秒折算则恒为 0）",
            1L,
            shanghai.epochDayOf(1_735_660_800_000L) - utc.epochDayOf(1_735_660_800_000L),
        )
    }

    // ---- C. 编译期语义层：默认值掩码 ----

    @Test
    fun `掩码合成构造证明两实体的 epochDay 没有 Kotlin 默认值`() {
        assertEpochDayHasNoDefault(
            entityClass = Class.forName("com.assignmate.app.core.data.db.entity.TimerSessionEntity"),
            sentinel = 777_777L,
            optionalInstantFieldName = "finishedAt",
        )
        assertEpochDayHasNoDefault(
            entityClass = Class.forName("com.assignmate.app.core.data.db.entity.PauseRecordEntity"),
            sentinel = 888_888L,
            optionalInstantFieldName = "pauseEndAt",
        )
    }

    // ---- D. 注入点层 ----

    @Test
    fun `全部 ZoneId 注入点都不带 @Named 限定符`() {
        val consumers = listOf(
            "com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl",
            "com.assignmate.app.homework.data.HomeworkRepositoryImpl",
            "com.assignmate.app.homework.data.NoopDailyRecordRepository",
            "com.assignmate.app.homework.ui.HomeworkEntryViewModel",
            "com.assignmate.app.homework.ui.HomeworkListViewModel",
            "com.assignmate.app.homework.ui.HomeworkTemplateViewModel",
            "com.assignmate.app.homework.ui.HomeworkTimeSetViewModel",
            "com.assignmate.app.stats.data.StatsRepositoryImpl",
            "com.assignmate.app.stats.ui.HistoryViewModel",
            "com.assignmate.app.stats.ui.DaySummaryViewModel",
            "com.assignmate.app.stats.ui.ItemDetailViewModel",
            "com.assignmate.app.timer.data.HomeworkReminderCoordinator",
            "com.assignmate.app.timer.ui.TimerExecutionViewModel",
        )

        var zoneParameters = 0
        for (name in consumers) {
            val clazz = Class.forName(name)
            for (constructor in clazz.declaredConstructors) {
                for (parameter in constructor.parameters) {
                    if (parameter.type != ZoneId::class.java) continue
                    zoneParameters++
                    assertFalse(
                        name + " 的 ZoneId 注入参数不得带 @Named 限定符" +
                            "（限定符会构成第二个绑定入口，覆写 core 唯一绑定将不再全局生效）",
                        parameter.isAnnotationPresent(Named::class.java),
                    )
                }
            }
        }
        assertTrue("必须扫描到足够的 ZoneId 注入点（实际 " + zoneParameters + "）", zoneParameters >= 12)
    }

    // ---- E. 源码层：按返回类型判定 ----

    @Test
    fun `以返回类型判定的 ZoneId 提供函数只有 core 一处`() {
        val declarations = mutableListOf<String>()
        for (file in mainKotlinSources()) {
            val code = codeText(file)
            for (match in RETURN_TYPE_ZONE_ID.findAll(code)) {
                declarations += file.name + "::" + match.groupValues[1]
            }
        }
        assertEquals(
            "以返回类型判定：全应用只有 core 的 provideBusinessZoneId 返回 ZoneId",
            listOf("DailyRecordModule.kt::provideBusinessZoneId"),
            declarations.sorted(),
        )

        val businessDi = mainKotlinSources().filter {
            val path = it.path.replace('\\', '/')
            path.contains("/homework/di/") || path.contains("/timer/di/") || path.contains("/stats/di/")
        }
        assertTrue("必须扫描到三个业务模块的 DI 源文件", businessDi.size >= 3)
        for (file in businessDi) {
            assertFalse(
                file.name + " 不得声明返回 ZoneId 的提供函数（应消费 core 唯一绑定）",
                RETURN_TYPE_ZONE_ID.containsMatchIn(codeText(file)),
            )
        }
    }

    // ---- 辅助 ----

    private fun repositoryIn(zoneId: ZoneId) =
        HomeworkDailyRecordRepositoryImpl(FakeHomeworkDailyRecordDao(), zoneId)

    /**
     * 运行时证明 epochDay 无 Kotlin 默认值。
     *
     * 原理：数据类只要有默认参数，Kotlin 就会生成合成构造
     * (... 原参数 ..., int mask, DefaultConstructorMarker)；mask 第 i 位表示「第 i 个参数可省」。
     * 本探针把 mask 所有位都置位后调用，并：
     * - 对确实有默认值的参数（id / finishedAt 等）断言其回到默认值（方法自检，证明掩码真的生效）；
     * - 对 epochDay 传入哨兵值，断言其原样保留——若 epochDay 仍有 = 0L 默认值，
     *   置位掩码会让它变成 0，断言即失败。
     */
    private fun assertEpochDayHasNoDefault(
        entityClass: Class<*>,
        sentinel: Long,
        optionalInstantFieldName: String,
    ) {
        val constructor = maskedConstructorOf(entityClass)
        val types = constructor.parameterTypes
        val primaryCount = types.size - 2
        assertEquals(
            "掩码合成构造的倒数第二个参数必须是 int 默认值掩码",
            "int",
            types[primaryCount].name,
        )

        val firstInstant = (0 until primaryCount).first { types[it] == Instant::class.java }
        val epochDayIndex = firstInstant - 1
        assertEquals(
            entityClass.simpleName + ": epochDay 必须是紧邻首个 Instant 参数之前的 Long 参数",
            "long",
            types[epochDayIndex].name,
        )

        val args = arrayOfNulls<Any?>(types.size)
        for (i in 0 until primaryCount) {
            args[i] = sampleValueOf(types[i])
        }
        args[0] = 999L
        args[epochDayIndex] = sentinel
        args[firstInstant + 1] = Instant.ofEpochMilli(500L)
        args[primaryCount] = -1
        args[primaryCount + 1] = null

        val instance = constructor.newInstance(*args)

        assertEquals(
            entityClass.simpleName + ": 探针自检失败——置位掩码后 id 的默认值未生效，本探针结论不可信",
            0L,
            readLong(instance, "getId"),
        )
        assertNull(
            entityClass.simpleName + ": 探针自检失败——置位掩码后 " +
                optionalInstantFieldName + " 的默认值未生效",
            readField(instance, optionalInstantFieldName),
        )
        assertEquals(
            entityClass.simpleName + ": epochDay 必须没有 Kotlin 默认值" +
                "（掩码全位置位时仍须采用实参；若仍是 = 0L 会被静默改写为 0）",
            sentinel,
            readLong(instance, "getEpochDay"),
        )
    }

    private fun maskedConstructorOf(entityClass: Class<*>): Constructor<*> {
        val candidates = entityClass.declaredConstructors.filter {
            it.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        assertEquals(
            entityClass.simpleName + " 必须恰好有一个默认值掩码合成构造" +
                "（没有则说明该类不存在任何默认参数，与「仅 epochDay 收敛」的预期不符）",
            1,
            candidates.size,
        )
        return candidates.single()
    }

    private fun sampleValueOf(type: Class<*>): Any? = when {
        type.name == "long" -> 0L
        type.name == "int" -> 0
        type == Instant::class.java -> Instant.ofEpochMilli(0L)
        type == String::class.java -> "RUNNING"
        else -> null
    }

    private fun readLong(instance: Any, getter: String): Long =
        instance.javaClass.getMethod(getter).invoke(instance) as Long

    private fun readField(instance: Any, fieldName: String): Any? =
        instance.javaClass.getDeclaredField(fieldName).also { it.isAccessible = true }.get(instance)

    private fun generatedComponentFile(): File {
        val relative = "app/build/generated/hilt/component_sources/debug/com/assignmate/app/" +
            "DaggerAssignMateApplication_HiltComponents_SingletonC.java"
        val file = File(repoRoot(), relative)
        assertTrue(
            "未找到 Hilt 生成组件 " + relative + "（应先完成 :app:compileDebugKotlin 编译）",
            file.isFile,
        )
        return file
    }

    private fun mainKotlinSources(): List<File> {
        val dir = File(repoRoot(), MAIN_SOURCE_ROOT)
        assertTrue("主源码目录不存在: " + dir.absolutePath, dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun codeText(file: File): String =
        file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, MAIN_SOURCE_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 " + File("").absolutePath + " 向上未找到 " + MAIN_SOURCE_ROOT)
    }

    private companion object {
        const val MAIN_SOURCE_ROOT = "app/src/main/java/com/assignmate/app"

        val RETURN_TYPE_ZONE_ID = Regex(
            "fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*:\\s*ZoneId\\b",
        )
    }
}