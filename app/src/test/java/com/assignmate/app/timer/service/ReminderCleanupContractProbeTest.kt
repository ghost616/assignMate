package com.assignmate.app.timer.service

import android.content.Context
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱独立探针（本轮「清理失败可观测」修复）：锁定既有用例未覆盖的**结构性契约**与边界。
 *
 * 与 [ReminderCleanupObservabilityTest]（源码锚点）和 [StageDailyReminderDeliveryTest]（投递出口）互补：
 * 1. `ReminderCleanup.NO_OP` 必须恒报「已清理」（不能反过来把正常投递污染成失败）；
 * 2. 生产 android 实现必须是返回 `Boolean` 的 `ReminderCleanup`（可被断言的结构化出口，
 *    而不是 `Unit` 形态的「无痕」实现）——以签名层面的硬约束替代纯文本锚点；
 * 3. Intent 哨兵口径：epochDay `0` 是「未携带」哨兵，不得被当作合法自然日透传给清理参数；
 * 4. 注释变更（timer 业务时区口径）不得被顺手写成真实 Hilt 绑定；
 *    以及对 TimerModule 的 `@Binds` 做**存在性 + 无重复**断言（**刻意不设条数上限**：
 *    早年「@Binds 恒为 10 条」的写法过宽，任何合法新增绑定都会误报，已按真实意图收紧）；
 * 5. 回归敏感性：核心出口若被改回「直接返回 true（静默吞掉）」，本条断言必须失败——
 *    即该出口确实被测试保护，而不是只靠人工评审。
 *
 * ## 环境边界（探针实测，属已知限制而非缺陷）
 * 本仓库未开启 `testOptions.unitTests.isReturnDefaultValues`，纯 JVM 下 android.jar 桩会抛异常：
 * - `android.util.Log.d` → `RuntimeException: Method d in android.util.Log not mocked`，
 *   故 `ReminderCleanup.android(...)` 的**失败分支体**（先 `Log.d` 留痕再返回 false）无法在纯 JVM 跑到底，
 *   其返回值与留痕只能以源码锚点核验（真机/仪器测试范围，与测试说明一致）；
 * - `android.content.Intent` 的 `setAction` / `putExtra` / `getLongExtra` 同样未桩实现，
 *   故 Intent 编解码只能做源码锚点核验，**不能**在纯 JVM 做往返断言；
 * - `NotificationManagerCompat` / `PendingIntent` 的真实调用亦不可达。
 * 因此本类不做任何依赖 Android 系统服务真跑的断言，避免把环境限制误判为功能缺陷。
 */
class ReminderCleanupContractProbeTest {

    // ---- 1. 空实现的结构化契约 ----

    @Test
    fun `空清理实现恒报已清理`() {
        assertTrue("NO_OP 不得反过来把正常投递报成失败", ReminderCleanup.NO_OP.cancel(HOMEWORK_ID, TODAY))
        assertTrue("单次提醒口径（epochDay=null）同样恒为 true", ReminderCleanup.NO_OP.cancel(HOMEWORK_ID, null))
        assertTrue("极端作业 id 与自然日也不得改变契约", ReminderCleanup.NO_OP.cancel(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    // ---- 2. 生产实现的结构化出口（签名契约） ----

    @Test
    fun `生产清理实现是可断言的布尔出口而非无痕实现`() {
        val cleanup = ReminderCleanup.android(mockk<Context>(relaxed = true))

        assertTrue(
            "android 实现必须实现 ReminderCleanup 接口（清理结果经接口返回，不再是 Unit 无痕形态）",
            cleanup is ReminderCleanup,
        )
        val declared = ReminderCleanup::class.java.methods.single { it.name == "cancel" }
        assertEquals(
            "cancel 的返回类型必须是 Boolean——false 才能表达「清理未完成」（本轮修复的核心出口）",
            java.lang.Boolean.TYPE,
            declared.returnType,
        )
        assertEquals(
            "cancel 参数必须是 (long, Long) 口径（作业 + 自然日，null 表示单次提醒）",
            listOf(java.lang.Long.TYPE, java.lang.Long::class.java),
            declared.parameterTypes.toList(),
        )
    }

    // ---- 3. Intent 哨兵口径（源码锚点，见环境边界） ----

    @Test
    fun `epochDay 哨兵口径保持未携带即单次提醒`() {
        val code = codeOnly(
            moduleSource("src/main/java/com/assignmate/app/timer/service/HomeworkAlarmReceiver.kt"),
        )

        assertTrue(
            "未携带 epochDay 的哨兵必须是 0（旧版本已设闹钟向后兼容）",
            Regex("NO_EPOCH_DAY\\s*=\\s*0L").containsMatchIn(code),
        )
        assertTrue(
            "解析必须把哨兵折叠为 null（0 不得被当作合法自然日传给清理）",
            Regex(
                "getLongExtra\\(EXTRA_EPOCH_DAY,\\s*NO_EPOCH_DAY\\)\\.takeIf\\s*\\{\\s*it\\s*!=\\s*NO_EPOCH_DAY\\s*\\}",
            ).containsMatchIn(code),
        )
        assertTrue(
            "仅非空 epochDay 才写入 extra（单次提醒不得带该字段，否则会走逐日清理口径）",
            Regex(
                "if\\s*\\(epochDay\\s*!=\\s*null\\)\\s*\\{\\s*intent\\.putExtra\\(EXTRA_EPOCH_DAY,\\s*epochDay\\)",
            ).containsMatchIn(code),
        )
    }

    // ---- 3b. 回归敏感性：核心出口若被改回「静默吞掉」必须可被察觉 ----

    @Test
    fun `清理结果出口对静默吞掉的回归敏感`() {
        val source = moduleSource("src/main/java/com/assignmate/app/timer/service/HomeworkAlarmReceiver.kt")
        val live = codeOnly(source)
        val exitPattern = Regex("requiresCleanup\\(delivery\\)\\)\\s*\\{\\s*cleanup\\.cancel\\(")
        val swallowed = live.replace("cleanup.cancel(homeworkId, epochDay)", "true")

        assertTrue(
            "现网口径：失效分支必须把清理结果原样返回（清理失败不再无痕）",
            exitPattern.containsMatchIn(live),
        )
        assertFalse(
            "回归口径：若核心出口退回「直接返回 true」，本条断言即失败——该出口已处于测试保护之下",
            exitPattern.containsMatchIn(swallowed),
        )
        assertFalse(
            "生产源码不得出现「失效分支直接返回 true」的静默形态",
            Regex("requiresCleanup\\(delivery\\)\\)\\s*\\{\\s*(true|return true)").containsMatchIn(live),
        )
    }

    // ---- 4. 注释变更不得被写成真实绑定 ----

    @Test
    fun `时区注释口径核验 timer 模块没有第二条业务时区绑定`() {
        val module = moduleSource("src/main/java/com/assignmate/app/timer/di/TimerModule.kt")

        assertTrue("KDoc 必须指向 core 的唯一提供者", module.contains("provideBusinessZoneId"))
        assertTrue("KDoc 必须显式禁止业务模块自建时区绑定", module.contains("不得自建业务时区"))
        assertFalse(
            "TimerModule 不得出现携带 ZoneId 的 @Provides（注释改动不得顺手加第二条时区绑定）",
            Regex("@Provides[\\s\\S]{0,120}ZoneId").containsMatchIn(codeOnly(module)),
        )
        // 关键绑定的**存在性**断言（刻意不设条数上限）：未来新增合法绑定不应误报，
        // 但「绑定被整体删除」「绑定接口被改错」必须立刻失败。
        // 实现方式：先按 `abstract fun` 切出每个绑定声明块（到下一个 `abstract fun` 为止），
        // 再在**块内**核验 `impl: <实现>` 与 `): <绑定接口>` 两段——与换行/缩进/参数尾随逗号
        // 等格式细节无关（多行签名天然覆盖），且不解析 `@Binds` 的分组结构（分组序号错位会静默失守）。
        val code = codeOnly(module)
        EXPECTED_BINDINGS.forEach { (bound, impl) ->
            assertEquals(
                "TimerModule 缺少关键绑定（应恰好声明一次）：$impl -> $bound",
                1,
                bindingDeclarationsOf(code, impl, bound),
            )
        }
        // 不变量：同一绑定接口不得被两条绑定同时声明，且同一实现不得被绑到两个接口
        //（重复即 Hilt 的 DuplicateBindings，应在测试期先被拦下）
        assertEquals(
            "关键绑定清单不得出现重复接口",
            EXPECTED_BINDINGS.size,
            EXPECTED_BINDINGS.keys.toSet().size,
        )
        assertEquals(
            "关键绑定清单不得出现重复实现",
            EXPECTED_BINDINGS.size,
            EXPECTED_BINDINGS.values.toSet().size,
        )
        assertTrue(
            "关键绑定清单不得为空（清单为空等于根本没守）",
            EXPECTED_BINDINGS.isNotEmpty(),
        )
    }

    /**
     * 统计源码中「`impl: <implType>` 且紧接着 `): <boundType>`」的绑定声明块数量（期望恰好为 1）。
     *
     * 按 `abstract fun` 切块：一个绑定块从 `abstract fun` 到下一个 `abstract fun` 之间，
     * 因此块内出现 `: <implType>` 与 `): <boundType>` 即视为该绑定声明命中。
     * 该口径不依赖换行、缩进、参数行尾随逗号等格式细节；块内出现两处相同片段时会计数为 2（异常，可被断言发现）。
     */
    private fun bindingDeclarationsOf(code: String, implType: String, boundType: String): Int {
        val needle = "): $boundType"
        return code.split("abstract fun ").count { chunk ->
            val colon = chunk.indexOf(": $implType")
            colon >= 0 && chunk.substring(colon + 1).contains(needle)
        }
    }

    private fun moduleSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("未找到生产源文件：" + relative)
    }

    private fun codeOnly(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("(?m)^\\s*//.*$"), "")

    private companion object {

        const val HOMEWORK_ID = 7L

        const val TODAY = 20_000L

        /**
         * TimerModule 的**关键绑定存在性清单**（绑定接口 -> 期望实现类型，后者用于失败诊断）。
         *
         * 刻意**只做存在性**、不设条数上限：未来任何合法的新增绑定都不应让本用例误报，
         * 而「绑定被删除 / 绑定接口被改错 / 同一接口被两条绑定重复声明」必须立刻失败。
         * 存在性只比**绑定接口**（`abstract fun bindXxx(impl: Impl): Bound` 的 `Bound`），
         * 因此替换实现类属合法重构、不会误报；实现类型名仅用于断言失败时指出期望。
         */
        val EXPECTED_BINDINGS: Map<String, String> = mapOf(
            "TimerRepository" to "TimerRepositoryImpl",
            "TimerTickerController" to "AndroidTimerTickerController",
            "HomeworkAlarmScheduler" to "AndroidHomeworkAlarmScheduler",
            "TimerVoiceGuide" to "TtsTimerVoiceGuide",
            "TimerVoiceSettings" to "DataStoreTimerVoiceSettings",
            "TimerOverduePromptStore" to "DataStoreTimerOverduePromptStore",
            "TimerPermissionChecker" to "AndroidTimerPermissionChecker",
            "TimerTransactionRunner" to "RoomTimerTransactionRunner",
            "TimerRestStartStore" to "DataStoreTimerRestStartStore",
            "TimerReminderScheduleStore" to "DataStoreTimerReminderScheduleStore",
        )
    }
}
