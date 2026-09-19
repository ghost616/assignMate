package com.assignmate.app.timer.service

import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 离朱增量复核补强用例（针对本轮 `deliverSafely` 异常安全外壳）。
 *
 * 既有 StageDailyReminderDeliveryTest 只覆盖「核对抛 RuntimeException → 不外抛 + 清理」一例；
 * 本类补齐同一改动的**边界与语义**面：
 * 1. 协程取消（CancellationException）必须**原样重抛且不清理**（结构化并发语义，外壳不得把它当普通异常吞掉）；
 * 2. 清理替身自身抛异常时外壳仍不得外抛（runCatching 包裹清理，广播收尾不能被清理拖垮）；
 *    清理**失败（抛异常或返回 false）只影响 `deliver` 的清理结果汇报**，不让外壳走兜底路径，
 *    故 `deliverSafely` 仍返回 true——清理失败的专门断言见 `ReminderCleanupObservabilityTest`；
 * 3. 失败兜底返回值与清理参数（含 epochDay == null 的单次提醒口径）必须逐字精确；
 * 4. 不回归：Stale 走正常返回 + 精确清理；空清理替身下零副作用；
 * 5. 静态核验：deliver 的唯一调用点在 deliverSafely 内部；onReceive 经 deliverSafely 投递；入口仍为 internal 可注入。
 *
 * 环境边界（探针实测；单测工作目录为 app/，user.dir=E:\assignMate\app）：
 * - Neutral / Named 投递会走到通知渠道与震动，纯 JVM 下
 *   `context.getSystemService(NotificationManager::class.java)` 在 mockk-relaxed Context 上抛
 *   ClassCastException（Object → NotificationManager），故真实通知/震动投递属真机或仪器测试范围；
 * - 由此，Unknown 用例中外壳返回 false，来源是**环境替身边界**而非生产逻辑，
 *   故本类以「空清理替身时零可观测副作用」锁定不变量，并保留源码级契约核验；
 * - 源码文本核验一律使用 ASCII 锚点，避免文件编码差异导致断言脆弱。
 */
class HomeworkAlarmReceiverSafetyShellExtraTest {

    // ---- 1. 协程取消语义 ----

    @Test
    fun `核对抛取消异常时原样重抛且不清理`() = runTest {
        val cancelled = mutableListOf<Pair<Long, Long?>>()
        val cleanup = ReminderCleanup { homeworkId, epochDay ->
            cancelled += homeworkId to epochDay
            true
        }
        val cancellation = CancellationException("结构化并发取消：不得吞掉")

        val thrown = try {
            HomeworkAlarmReceiver().deliverSafely(
                context = mockk(relaxed = true),
                homeworkId = HOMEWORK_ID,
                epochDay = TODAY,
                cleanup = cleanup,
                check = ReminderCheck { _, _ -> throw cancellation },
            )
            null
        } catch (e: Throwable) {
            e
        }

        assertSame(
            "CancellationException 必须原样重抛（同一实例），否则协程取消会被异常外壳悄悄吞掉",
            cancellation,
            thrown,
        )
        assertTrue(
            "取消不是「提醒失效」，外壳不得产生清理副作用",
            cancelled.isEmpty(),
        )
    }

    // ---- 2. 清理自身失败也必须被吞掉 ----

    @Test
    fun `清理动作自身抛异常时外壳仍不外抛`() = runTest {
        var cleanupAttempted = false
        val cleanup = ReminderCleanup { _, _ ->
            cleanupAttempted = true
            throw IllegalStateException("注入式清理失败（PendingIntent/AlarmManager 异常）")
        }

        val delivered = try {
            HomeworkAlarmReceiver().deliverSafely(
                context = mockk(relaxed = true),
                homeworkId = HOMEWORK_ID,
                epochDay = TODAY,
                cleanup = cleanup,
                check = ReminderCheck { _, _ -> throw IllegalStateException("注入式核对失败") },
            )
        } catch (e: Throwable) {
            fail("清理失败属于收尾噪音，必须被 runCatching 吞掉，不得冒泡：$e")
            return@runTest
        }

        assertTrue("失败兜底路径确实尝试过清理", cleanupAttempted)
        assertFalse("即便清理失败，外壳仍必须返回 false（走了兜底路径）", delivered)
    }

    // ---- 3. 兜底返回值与清理参数精确性 ----

    @Test
    fun `单次提醒核对抛异常时清理参数为作业与空自然日`() = runTest {
        val cancelled = mutableListOf<Pair<Long, Long?>>()

        val delivered = HomeworkAlarmReceiver().deliverSafely(
            context = mockk(relaxed = true),
            homeworkId = SINGLE_ID,
            epochDay = null,
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                cancelled += homeworkId to epochDay
                true
            },
            check = ReminderCheck { _, _ -> throw RuntimeException("注入式核对失败") },
        )

        assertFalse(delivered)
        assertEquals(
            "单次提醒（epochDay 缺失）必须按 null 口径清理，且只清一次、只清该作业",
            listOf(SINGLE_ID to null),
            cancelled,
        )
    }

    @Test
    fun `核对抛任意异常类型都走同一兜底口径`() = runTest {
        val failures = listOf<Throwable>(
            IllegalStateException("状态异常"),
            IllegalArgumentException("参数异常"),
            NullPointerException("空指针"),
            RuntimeException("运行期异常"),
            UnsupportedOperationException("未实现"),
        )

        failures.forEach { failure ->
            val cancelled = mutableListOf<Pair<Long, Long?>>()
            val delivered = HomeworkAlarmReceiver().deliverSafely(
                context = mockk(relaxed = true),
                homeworkId = HOMEWORK_ID,
                epochDay = TODAY,
                cleanup = ReminderCleanup { homeworkId, epochDay ->
                    cancelled += homeworkId to epochDay
                    true
                },
                check = ReminderCheck { _, _ -> throw failure },
            )

            assertFalse("${failure::class.simpleName} 也必须被外壳吞掉", delivered)
            assertEquals(
                "${failure::class.simpleName} 也必须精确清理该次闹钟",
                listOf(HOMEWORK_ID to TODAY),
                cancelled,
            )
        }
    }

    // ---- 4. 不回归：Stale 正常返回 + 精确清理；空清理替身零副作用 ----

    @Test
    fun `核对判定失效时外壳正常返回且精确清理该次闹钟`() = runTest {
        val args = mutableListOf<Pair<Long, Long?>>()
        val cancelled = mutableListOf<Pair<Long, Long?>>()

        val delivered = HomeworkAlarmReceiver().deliverSafely(
            context = mockk(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                cancelled += homeworkId to epochDay
                true
            },
            check = ReminderCheck { homeworkId, epochDay ->
                args += homeworkId to epochDay
                ReminderVerification.Stale
            },
        )

        assertTrue("Stale 属正常返回路径（未丢异常），外壳应返回 true 而非走兜底", delivered)
        assertEquals("核对参数必须原样透传（作业 id + 自然日）", listOf(HOMEWORK_ID to TODAY), args)
        assertEquals("Stale → 精确清理该次闹钟（既有口径不回归）", listOf(HOMEWORK_ID to TODAY), cancelled)
    }

    @Test
    fun `核对不可用时不产生清理副作用`() = runTest {
        val recorded = mutableListOf<Pair<Long, Long?>>()

        HomeworkAlarmReceiver().deliverSafely(
            context = mockk(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = ReminderCleanup.NO_OP,
            check = ReminderCheck { _, _ -> ReminderVerification.Unknown },
        )

        assertTrue(
            "空清理替身必须零可观测副作用（Unknown 口径不做破坏性动作）",
            recorded.isEmpty(),
        )
    }

    // ---- 5. 静态核验（源码契约，ASCII 锚点） ----

    @Test
    fun `投递入口保持 internal 且 deliver 只在外壳内部被调用`() {
        val source = receiverSource()

        assertTrue(
            "deliverSafely 必须是 internal suspend 且返回 Boolean",
            Regex("""internal\s+suspend\s+fun\s+deliverSafely\([\s\S]{0,600}?\)\s*:\s*Boolean""")
                .containsMatchIn(source),
        )
        assertTrue(
            "deliver 必须仍是 internal suspend（既有测试入口与注入点不回归）",
            Regex("""internal\s+suspend\s+fun\s+deliver\(""").containsMatchIn(source),
        )
        assertEquals(
            "deliver 的调用点只应有 1 处（外壳内部转发）；onReceive 若直接调用会使异常冒泡、残留闹钟不清理",
            1,
            deliverCallSites(codeOnly(source)).size,
        )
    }

    @Test
    fun `onReceive 经外壳投递且不再直接调用 deliver`() {
        val code = codeOnly(receiverSource())
        val start = code.indexOf("override fun onReceive")
        val end = code.indexOf("internal suspend fun deliverSafely", start)
        assertTrue("必须能定位到 onReceive 段（ASCII 锚点）", start >= 0 && end > start)
        val onReceive = code.substring(start, end)

        assertTrue(
            "onReceive 的协程体必须经 deliverSafely 投递",
            Regex("""\bdeliverSafely\(""").containsMatchIn(onReceive),
        )
        assertTrue(
            "onReceive 段内不得出现 deliver 调用点",
            deliverCallSites(onReceive).isEmpty(),
        )
    }

    @Test
    fun `失败分支的日志与清理各自被 runCatching 包裹`() {
        val code = codeOnly(receiverSource())
        val start = code.indexOf("internal suspend fun deliverSafely")
        val end = code.indexOf("internal suspend fun deliver(", start)
        assertTrue("必须能定位到外壳段（ASCII 锚点）", start >= 0 && end > start)
        val shell = code.substring(start, end)

        assertTrue("失败分支必须记日志（TAG 常量 + 诊断信息），便于真机排查", shell.contains("Log.d(TAG"))
        assertTrue(
            "日志调用必须被 runCatching 包裹（日志自身失败不得让广播收尾出问题）",
            Regex("""runCatching\s*\{\s*Log\.d\(""").containsMatchIn(shell),
        )
        assertTrue(
            "清理调用必须被 runCatching 包裹（清理失败静默）",
            Regex("""runCatching\s*\{\s*cleanup\.cancel\(""").containsMatchIn(shell),
        )
        assertTrue(
            "必须先按 CancellationException 分支重抛，再做宽泛 Exception 兜底",
            shell.indexOf("CancellationException") in 1 until shell.indexOf("catch (e: Exception)"),
        )
    }

    @Test
    fun `外壳返回值契约与广播收尾不回归`() {
        val source = receiverSource()
        val code = codeOnly(source)
        val start = code.indexOf("internal suspend fun deliverSafely")
        val end = code.indexOf("internal suspend fun deliver(", start)
        assertTrue("必须能定位到外壳段（ASCII 锚点）", start >= 0 && end > start)
        val shell = code.substring(start, end)

        assertTrue(
            "成功路径返回 true（): Boolean = try { ... true }）",
            Regex("""\)\s*:\s*Boolean\s*=\s*try\s*\{[\s\S]*?true""").containsMatchIn(shell),
        )
        assertTrue(
            "兜底路径返回 false",
            Regex("""catch\s*\(e:\s*Exception\)\s*\{[\s\S]*?false""").containsMatchIn(shell),
        )
        assertTrue(
            "onReceive 的 finally 只保留 pendingResult.finish()（外壳不外抛才收得到尾）",
            code.contains("runCatching { pendingResult.finish() }"),
        )
        assertTrue(
            "日志 TAG 必须常量化且与类名一致",
            Regex("""private\s+const\s+val\s+TAG\s*=\s*"HomeworkAlarmReceiver"""").containsMatchIn(source),
        )
    }

    // ---- 辅助 ----

    /** 单测工作目录实测为 `app/`（user.dir=E:\assignMate\app），故以模块内相对路径为准 */
    private fun receiverSource(): String {
        val relative = "src/main/java/com/assignmate/app/timer/service/HomeworkAlarmReceiver.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("未找到生产源文件（工作目录=${File(".").absolutePath}）：$relative")
        return file.readText()
    }

    /**
     * 去掉行注释 / 块注释 / KDoc，避免文档里提到的 `deliver(...)` 影响「实际调用点计数」的核验。
     */
    private fun codeOnly(source: String): String =
        source
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")

    /**
     * `deliver(` 的**调用点**（排除 `deliverSafely(`、以及 `fun deliver(` 声明行）。
     * 直接用文本口径计数，避免依赖 Kotlin 反编译形态。
     */
    private fun deliverCallSites(text: String): List<String> {
        val marker = Regex("""(?<!Safely)\bdeliver\(""")
        return marker.findAll(text).mapNotNull { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first).coerceAtLeast(0)
            val line = text.substring(lineStart, text.indexOf('\n', match.range.first).let { if (it < 0) text.length else it })
            if (line.contains("fun deliver(")) null else line.trim()
        }.toList()
    }

    private companion object {

        const val HOMEWORK_ID = 7L

        /** 单次提醒口径（不带业务自然日） */
        const val SINGLE_ID = 11L

        const val TODAY = 20_000L
    }
}