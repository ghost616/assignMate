package com.assignmate.app.core.data.di

import com.assignmate.app.auth.data.AuthRepositoryImpl
import com.assignmate.app.auth.data.FakeKeyValueStore
import com.assignmate.app.auth.data.FakeStudentDao
import com.assignmate.app.core.data.db.FakeHomeworkDailyRecordDao
import com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl
import com.assignmate.app.core.data.db.dao.ParentAccountDao
import com.assignmate.app.core.data.db.entity.ParentAccountEntity
import com.assignmate.app.core.di.DailyRecordModule
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.FakeHomeworkItemDao
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.di.HomeworkModule
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import java.io.File
import java.time.ZoneId
import javax.inject.Named
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 业务时区「唯一来源」契约测试（纯 JVM，无需设备/模拟器）。
 *
 * 背景（皐陶审查遗留 #2）：此前业务时区有**两个独立 DI 入口**——core 的
 * @Named(DAILY_RECORD_ZONE_ID) 与 homework 的裸 ZoneId（各自 default systemDefault()）。
 * 覆写其一即造成「今天」口径漂移：跨零点时作业归属日（homework）与每天详情折算（core）
 * 可能落到不同自然日。
 *
 * 本轮收敛为：core.di.DailyRecordModule 提供**全应用唯一的无限定 ZoneId**，
 * homework / timer / stats 一律消费该绑定、不得自建。本测试从结构（静态扫描 + 反射）
 * 与行为（覆写一处即两处消费方同步切换）两侧锁定。
 *
 * ## 关于 systemDefault 的两档口径（重要）
 * 1. **业务时区绑定**：全应用只允许 core 一处（本测试断言唯一）——这是「今天」口径的唯一来源；
 * 2. **业务时区默认值**：除该提供方内部外**不得再出现**。homework 侧已在
 *    「阶段作业完成语义修正轮」收敛完毕（Repository / ViewModel / Compose Content / 角色口径 /
 *    模板 / 空实现均改为显式传注入时区，见 [LEGACY_SYSTEM_DEFAULT_FALLBACKS] 的消减记录）；
 *    当前仅剩 [LEGACY_SYSTEM_DEFAULT_FALLBACKS] 清单内的欠账（展示格式化默认值、
 *    StageDayRecords 中供 stats 生产调用点省略参数的默认值、timer 侧默认值/兼容构造），
 *    由对应模块的计划承接，故此处以**精确清单**锁定：清单外的任何新增 systemDefault 兜底都会让
 *    本测试失败，同时清单中每项都带整改说明。
 */
class BusinessTimeZoneSingleSourceTest {

    // ---- 结构：唯一来源存在且可被覆写 ----

    @Test
    fun `唯一业务时区绑定由 core 提供且方法签名稳定`() {
        val provider = DailyRecordModule::class.java.declaredMethods
            .firstOrNull { it.name == "provideBusinessZoneId" }
        assertNotNull(
            "core.di.DailyRecordModule 必须提供唯一的业务时区 @Provides（方法名 provideBusinessZoneId）",
            provider,
        )
        assertEquals("业务时区提供方的返回类型必须是 ZoneId", ZoneId::class.java, provider!!.returnType)
        assertTrue(
            "业务时区提供方必须标注 @Provides，否则不进入 Hilt 图",
            provider.isAnnotationPresent(dagger.Provides::class.java),
        )
        assertTrue(
            "业务时区提供方必须是单例（全应用同一实例，避免不同消费方拿到不同时区对象）",
            provider.isAnnotationPresent(javax.inject.Singleton::class.java),
        )
    }

    @Test
    fun `生产代码中只有 core 一处业务时区绑定`() {
        val sources = mainKotlinSources()
        assertTrue("未扫描到足够的 Kotlin 源文件，测试路径解析可能失效", sources.size > 50)

        // 全应用只有 core 的 DailyRecordModule 声明业务时区 provide 方法
        val zoneProviders = sources.filter { source ->
            Regex("fun\\s+\\w*[Zz]one\\w*\\s*\\(").containsMatchIn(codeText(source))
        }
        assertEquals(
            "业务时区绑定必须唯一（只允许 core 的 DailyRecordModule 声明 provide 方法）",
            listOf(DAILY_RECORD_MODULE),
            zoneProviders.map { it.name }.sorted(),
        )

        // homework / timer / stats 的 DI 模块不得再声明业务时区绑定：唯一允许的 ZoneId 使用形态是
        // 「仓库装配函数的参数」（消费注入），不得存在返回 ZoneId 的提供函数
        for (module in listOf("HomeworkModule.kt", "TimerModule.kt", "StatsModule.kt")) {
            val file = sources.firstOrNull { it.name == module } ?: continue
            val zoneReturningFunctions = zoneReturningFunctions(file)
            if (module == "HomeworkModule.kt") {
                assertEquals(
                    "$module 只允许「仓库装配函数的 zoneId 参数」这一处 ZoneId 使用；" +
                        "返回 ZoneId 的函数必须不存在（应消费 core 的唯一来源）",
                    listOf(REPOSITORY_PROVIDER_FUNCTION),
                    zoneReturningFunctions.keys.toList(),
                )
                assertEquals(
                    "$module 装配函数不得以 ZoneId 为返回类型",
                    "HomeworkRepository",
                    zoneReturningFunctions.values.single(),
                )
            } else {
                assertTrue(
                    "$module 不得出现任何 ZoneId 用法（其消费方经构造注入 core 绑定）",
                    zoneReturningFunctions.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `业务时区默认值只允许出现在 core 提供方内部（既有欠账按精确清单锁定）`() {
        val sources = mainKotlinSources()

        // 1. 业务时区默认值：只允许唯一提供方内部以 systemDefault() 兜底
        val systemDefaultSites = sources.filter { codeText(it).contains("ZoneId.systemDefault()") }
            .map { it.name }
            .sorted()
        assertEquals(
            "任何模块不得自行以 systemDefault() 作为业务时区默认值；既有欠账清单只允许收敛、不允许新增",
            (listOf(DAILY_RECORD_MODULE) + LEGACY_SYSTEM_DEFAULT_FALLBACKS).sorted(),
            systemDefaultSites,
        )

        // 2. 既有欠账明细必须仍是「可显式传参覆盖的默认值」形态（参数默认值 / 兼容构造实参），
        //    而不是新的 DI 绑定或字段级兜底——保证它们不会成为第二个口径入口
        val defaultParameterPattern = Regex("zoneId\\s*:\\s*ZoneId\\s*=\\s*ZoneId\\.systemDefault\\(\\)")
        val compatConstructorPattern = Regex("zoneId\\s*=\\s*ZoneId\\.systemDefault\\(\\)")
        for (fileName in LEGACY_SYSTEM_DEFAULT_FALLBACKS) {
            val file = sources.first { it.name == fileName }
            val code = codeText(file)
            assertTrue(
                "$fileName 的 systemDefault 欠账必须仍是可覆写的参数默认值/兼容构造实参形态",
                defaultParameterPattern.containsMatchIn(code) || compatConstructorPattern.containsMatchIn(code),
            )
        }
    }

    @Test
    fun `每天详情仓库的时区注入参数不带限定符`() {
        val constructor = HomeworkDailyRecordRepositoryImpl::class.java.declaredConstructors.single()
        val zoneParameter = constructor.parameters.single { it.type == ZoneId::class.java }

        assertFalse(
            "消费方不得再使用 @Named 限定的第二绑定入口（应与全应用唯一绑定同关键字面）",
            zoneParameter.isAnnotationPresent(Named::class.java),
        )
    }

    // ---- 行为：覆写一处即全局生效（作业归属日 + 每天详情折算） ----

    @Test
    fun `覆写唯一来源后作业归属日与每天详情折算同时切换为上海口径`() = runTest {
        val env = envOf(ZoneId.of("Asia/Shanghai"))
        val item = env.addTodayHomework()

        // 作业归属日（homework 侧）与每天详情折算（core 侧）必须同源同值
        assertEquals(
            "固定时刻在上海口径下已跨入 2025-01-01",
            EPOCH_DAY_2025_01_01,
            item.createdEpochDay(env.zoneId),
        )
        assertEquals(
            "core 每天详情折算必须与作业归属日一致（同一业务时区）",
            item.createdEpochDay(env.zoneId),
            env.dailyRecordRepository.epochDayOf(item.createdAt.toEpochMilli()),
        )
    }

    @Test
    fun `覆写唯一来源为 UTC 后两处消费者同步切换且与上海口径相差一天`() = runTest {
        val shanghai = envOf(ZoneId.of("Asia/Shanghai"))
        val utc = envOf(ZoneId.of("UTC"))
        val inShanghai = shanghai.addTodayHomework()
        val inUtc = utc.addTodayHomework()

        assertEquals(
            "同一时刻在 UTC 口径下仍属 2024-12-31",
            EPOCH_DAY_2024_12_31,
            inUtc.createdEpochDay(utc.zoneId),
        )
        assertEquals(
            "覆写时区必须同时改变作业归属日（若存在第二个时区入口，此处会与每天详情不一致）",
            1L,
            inShanghai.createdEpochDay(shanghai.zoneId) - inUtc.createdEpochDay(utc.zoneId),
        )
        // 每天详情（core）与作业归属日（homework）在两套时区下都保持一致
        val pairs = listOf(shanghai to inShanghai, utc to inUtc)
        for (pair in pairs) {
            val env = pair.first
            val item = pair.second
            assertEquals(
                "core 折算与 homework 归属日必须同源",
                item.createdEpochDay(env.zoneId),
                env.dailyRecordRepository.epochDayOf(item.createdAt.toEpochMilli()),
            )
        }
    }

    // ---- 测试夹具：按生产 Hilt 装配方式接线（唯一来源 -> 两个消费方） ----

    private class Env(
        val zoneId: ZoneId,
        val dailyRecordRepository: HomeworkDailyRecordRepositoryImpl,
        val homeworkRepository: HomeworkRepository,
        val clock: MutableClock,
    ) {
        /** 以固定时刻录入一条当天作业，返回落库作业项（其 createdAt 即固定时钟时刻） */
        suspend fun addTodayHomework(): HomeworkItem = run {
            val result = homeworkRepository.addHomework(
                template = HomeworkTemplate(
                    content = "语文朗读",
                    type = HomeworkType.TODAY,
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = dailyRecordRepository.epochDayOf(clock.currentTimeMillis()),
                    zoneId = zoneId,
                ),
                studentId = STUDENT_ID,
            )
            assertTrue("录入作业必须成功，实际：$result", result is AddHomeworkResult.Success)
            (result as AddHomeworkResult.Success).items.single()
        }
    }

    private suspend fun envOf(zoneId: ZoneId): Env {
        val clock = MutableClock(FIXED_MILLIS)
        val authRepository = AuthRepositoryImpl(
            parentAccountDao = FakeParentAccountDaoStub(),
            studentDao = FakeStudentDao(),
            keyValueStore = FakeKeyValueStore(),
            clock = clock,
        )
        authRepository.registerParent(ACCOUNT, PASSWORD)
        authRepository.addStudent(PARENT_ID, "小明")

        val dailyRecordRepository = HomeworkDailyRecordRepositoryImpl(
            FakeHomeworkDailyRecordDao(),
            zoneId,
        )
        // 与生产一致：经 HomeworkModule 的装配方法接线，时区由唯一来源传入两个消费方
        val homeworkRepository = HomeworkModule.provideHomeworkRepository(
            homeworkItemDao = FakeHomeworkItemDao(),
            authRepository = authRepository,
            clock = clock,
            zoneId = zoneId,
            dailyRecordRepository = dailyRecordRepository,
        )
        return Env(zoneId, dailyRecordRepository, homeworkRepository, clock)
    }

    /** 只填注册流程所需行为的家长 DAO 替身：账号不存在 + 分配固定 id */
    private class FakeParentAccountDaoStub : ParentAccountDao {

        override suspend fun insert(item: ParentAccountEntity): Long = PARENT_ID

        override suspend fun insertAll(items: List<ParentAccountEntity>): List<Long> = items.map { insert(it) }

        override suspend fun update(item: ParentAccountEntity) = Unit

        override suspend fun delete(item: ParentAccountEntity) = Unit

        override suspend fun findByAccount(account: String): ParentAccountEntity? = null

        override suspend fun existsByAccount(account: String): Boolean = false
    }

    // ---- 源码扫描辅助 ----

    private fun mainKotlinSources(): List<File> {
        val dir = File(repoRoot(), MAIN_SOURCE_ROOT)
        assertTrue("主源码目录不存在: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /** 去掉块注释与行注释后的代码文本：注释里的说明字样不得参与结构校验 */
    private fun codeText(file: File): String =
        file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** 声明区文本（在 [codeText] 基础上去掉 import 行）：消费方 import ZoneId 属正常，不算绑定 */
    private fun declarationText(file: File): String =
        codeText(file)
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")

    /**
     * 取某个源文件里**声明了 ZoneId 的函数**：函数名 -> 返回类型文本。
     *
     * 判定只需覆盖两类形态：返回 ZoneId 的提供函数（`fun x(): ZoneId = ...`）与
     * 「以 ZoneId 为参数」的消费函数（参数列表里出现 ZoneId）。返回类型为 null 表示表达式体/空返回。
     */
    private fun zoneReturningFunctions(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (match in FUNCTION_SIGNATURE.findAll(declarationText(file))) {
            val signature = match.value
            if (!signature.contains("ZoneId")) continue
            val name = match.groupValues[1]
            val returnType = match.groupValues[2].trim().ifEmpty { "Unit" }
            result[name] = returnType
        }
        return result
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, MAIN_SOURCE_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $MAIN_SOURCE_ROOT")
    }

    private companion object {

        const val MAIN_SOURCE_ROOT = "app/src/main/java/com/assignmate/app"
        const val DAILY_RECORD_MODULE = "DailyRecordModule.kt"

        /** homework 模块中唯一允许出现 ZoneId 的函数（仓库装配：zoneId 为注入参数） */
        const val REPOSITORY_PROVIDER_FUNCTION = "provideHomeworkRepository"

        /** 函数签名匹配：函数名 + 参数列表 + 可选返回类型（含表达式体赋值与空返回） */
        val FUNCTION_SIGNATURE = Regex(
            "fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_<>,. ?]*))?\\s*[{=]",
        )

        /**
         * 既有 systemDefault() 兜底欠账（非 Hilt 绑定，均可被显式传参覆盖）——只允许逐项消减：
         * - TimeFormatters.kt：展示格式化函数的默认时区（homework 的 OCR 文本仍按默认调用，
         *   待其模块适配后改为显式传注入时区）。
         *
         * **StageDayRecords.kt 的欠账已清零**（homework 计划：五个推导入口的 `zoneId` 去默认值改必填）：
         * homework 侧调用点（仓库 / 清单角色口径 / 各 ViewModel）全部显式传注入时区，本模块生产代码
         * 已无 `ZoneId.systemDefault()`。跨模块消费方（stats 的 `StatsCalculations.shouldDoOn` /
         * `stageProgressOf`）若仍省参，将由**编译错误**暴露（不再静默回退到系统时区），
         * 由 stats 计划透传其注入时区后收口。
         *
         * timer 侧欠账**已清零**（本轮 timer 计划承接）：`TimerExecutionViewModel` /
         * `TimerCalculations.isHomeworkOverdue` 不再提供 `ZoneId.systemDefault()` 默认值、
         * `HomeworkReminderCoordinator` 的兼容构造改为显式要求 `zoneId`，timer 内业务时区
         * 只剩「注入 core 唯一绑定」一条入口。
         */
        val LEGACY_SYSTEM_DEFAULT_FALLBACKS = listOf(
            "TimeFormatters.kt",
        )

        /** 固定时钟：2024-12-31T20:53:20Z（上海口径已跨入 2025-01-01，UTC 口径仍是 12-31） */
        const val FIXED_MILLIS = 1_735_678_400_000L

        const val EPOCH_DAY_2024_12_31 = 20_088L
        const val EPOCH_DAY_2025_01_01 = 20_089L

        const val PARENT_ID = 1L
        const val STUDENT_ID = 1L
        const val ACCOUNT = "parent001"
        const val PASSWORD = "pwd-123456"
    }
}