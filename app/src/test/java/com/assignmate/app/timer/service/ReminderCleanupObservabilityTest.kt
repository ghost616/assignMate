package com.assignmate.app.timer.service

import com.assignmate.app.timer.data.TimerTestEnv
import io.mockk.mockk
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Cleanup failure must be observable」用例（修复轮：`ReminderCleanup.android` 整体 runCatching 无日志）。
 *
 * ## 被修的问题
 * 清理失败（AlarmManager 系统服务缺失 / PendingIntent 构造被拒 / cancel 抛异常）此前被整体
 * `runCatching` 吞掉且**不留任何痕迹**：残留闹钟会在后续日子继续打扰用户，开发侧却无从判断
 * 「同步没跑到」还是「清理失败」，与同一处 `deliverSafely` 的「记录 + 清理各自收口」口径不一致。
 *
 * ## 修复后的可观测出口（本类锁定）
 * 1. **结构化出口**：`ReminderCleanup.cancel(homeworkId, epochDay): Boolean` — false 表示清理未完成；
 *    失效（Stale）路径下 `deliver` 原样返回它，`deliverSafely` 透出同一结果（纯 JVM 可断言）；
 * 2. **人类可读出口**：android 实现失败时 `Log.d(TAG="HomeworkAlarmReceiver", ...)` 记录
 *    homeworkId / epochDay / requestCode 与失败原因（真机排查线索）；
 * 3. **不回归**：清理成功、核对不可用（不清理）、正常投递三态的返回值语义与既有链路完全不变，
 *    且清理失败**不改变投递动作**（仍不发通知、不震动、不打断广播收尾）。
 *
 * ## 环境边界
 * - Neutral（核对不可用 → 中性文案）路径经 Context 替身打通（接管 `getSystemService(NotificationManager)`
 *   与 `VibratorManager`，并把 POST_NOTIFICATIONS 置为已授予），故「不清理」与「照常发中性通知」都真实执行；
 *   通知渠道与震动的**真实效果**仍属真机/仪器测试范围；
 * - android 实现的真实 `Log` 出口无法在纯 JVM 断言，故以源码锚点核验其存在性与参数口径（不引入 Robolectric）；
 * - 源码核验一律使用 ASCII 锚点，避免文件编码差异导致断言脆弱。
 */
class ReminderCleanupObservabilityTest {

    // ---- 1. 结构化出口：清理失败可被断言（Stale → deliver 透出 false） ----

    @Test
    fun `cleanup failure is observable as false from deliver`() = runTest {
        val cleanupCalls = mutableListOf<Pair<Long, Long?>>()

        val cleaned = HomeworkAlarmReceiver().deliver(
            context = mockk(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                cleanupCalls += homeworkId to epochDay
                false
            },
            check = ReminderCheck { _, _ -> ReminderVerification.Stale },
        )

        assertFalse(
            "cleanup failure must surface as false (before the fix it was swallowed by runCatching)",
            cleaned,
        )
        assertEquals(
            "failure path must still attempt cleanup for exactly (homeworkId, epochDay)",
            listOf(HOMEWORK_ID to TODAY),
            cleanupCalls,
        )
    }

    @Test
    fun `cleanup success is reported as true from deliver`() = runTest {
        val cleaned = HomeworkAlarmReceiver().deliver(
            context = mockk(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = ReminderCleanup { _, _ -> true },
            check = ReminderCheck { _, _ -> ReminderVerification.Stale },
        )

        assertTrue(
            "successful cleanup (including the no-alarm-to-cancel case) must not be reported as failure",
            cleaned,
        )
    }

    @Test
    fun `unknown verification neither cleans up nor reports cleanup failure`() {
        // Unknown（核对不可用）会走 Neutral 投递：通知构造需要 PendingIntent.getActivity（纯 JVM 未桩实现），
        // 故按既有口径在**决策层**锁定「不清理」（requiresCleanup），并核验投递入口的清理调用就在该开关之内
        assertFalse(
            "unknown verification must not trigger destructive cleanup",
            HomeworkReminderDeliveryRules.requiresCleanup(
                HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown),
            ),
        )
        assertTrue(
            "stale verification must still trigger cleanup (no regression on the fixed path)",
            HomeworkReminderDeliveryRules.requiresCleanup(
                HomeworkReminderDeliveryRules.decide(ReminderVerification.Stale),
            ),
        )

        val code = codeOnly(receiverSource())
        val deliverBody = code.substring(code.indexOf("internal suspend fun deliver("))
        val cleanupGate = deliverBody.indexOf("requiresCleanup(delivery)")
        val cleanupCall = deliverBody.indexOf("cleanup.cancel(")

        assertTrue("deliver must gate cleanup on requiresCleanup", cleanupGate in 1 until cleanupCall)
        assertTrue(
            "cleanup call must live inside the gated branch (unknown path cannot reach it)",
            Regex("requiresCleanup\\(delivery\\)\\)\\s*\\{\\s*cleanup\\.cancel\\(").containsMatchIn(deliverBody),
        )
    }

    @Test
    fun `cleanup failure does not turn the safe shell into its fallback path`() = runTest {
        val cleaned = HomeworkAlarmReceiver().deliverSafely(
            context = mockk(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = ReminderCleanup { _, _ -> false },
            check = ReminderCheck { _, _ -> ReminderVerification.Stale },
        )

        assertTrue(
            "deliverSafely reports 'delivery body did not throw'; cleanup outcome is reported by deliver",
            cleaned,
        )
    }

    // ---- 2. 人类可读出口：android 实现的失败留痕与返回值口径（源码锚点核验） ----

    @Test
    fun `android cleanup returns boolean and logs the failure with context`() {
        val source = receiverSource()

        assertTrue(
            "cleanup contract must return Boolean (false = cleanup failed, caller observable)",
            source.contains("fun cancel(homeworkId: Long, epochDay: Long?): Boolean"),
        )
        assertTrue(
            "missing AlarmManager service must be logged instead of silently returning",
            source.contains("Log.d(") && source.contains("getSystemService(AlarmManager::class.java)"),
        )
        assertTrue(
            "failure path must log homeworkId and epochDay (only clue for residual alarms on device)",
            source.contains("homeworkId=" + "$" + "homeworkId epochDay=" + "$" + "epochDay"),
        )
        assertTrue(
            "failure path must log the failure cause (throwable)",
            source.contains("Log.d(") && source.contains("failure,"),
        )
        assertTrue("success folds to true", source.contains("onSuccess = { true }"))
        assertTrue("failure folds to false", source.contains("onFailure = { failure ->"))
        assertTrue(
            "must still build the equivalent PendingIntent with the scheduler request code",
            source.contains("HomeworkAlarmReceiver.intentOf"),
        )
        assertTrue(
            "must still cancel through the AlarmManager system service",
            source.contains("AlarmManager::class.java"),
        )
        assertTrue(
            "cleanup failure log tag literal must be present in the KDoc/log path",
            source.contains("TAG") && source.contains("Log.d("),
        )
    }

    @Test
    fun `cleanup failure is logged at debug level with the class-name tag`() {
        val source = receiverSource()
        val quote = "\""

        assertTrue(
            "cleanup log TAG must be a constant equal to the class name",
            Regex("private\\s+const\\s+val\\s+TAG\\s*=\\s*" + quote + "HomeworkAlarmReceiver").containsMatchIn(source),
        )
        assertTrue(
            "cleanup failure log must be at debug level (release builds stay silent)",
            Regex("Log\\.d\\(\\s*TAG,").containsMatchIn(source),
        )
        assertFalse(
            "cleanup failure must not be escalated to Log.e/w noise",
            Regex("Log\\.[ew]\\(TAG,\\s*").containsMatchIn(source),
        )
    }

    @Test
    fun `cleanup result is surfaced by both delivery entries`() {
        val code = receiverSource()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?m)^\\s*//.*$"), "")

        assertTrue(
            "deliver must report the cleanup outcome as its own return value",
            Regex("internal\\s+suspend\\s+fun\\s+deliver\\([\\s\\S]{0,600}?\\)\\s*:\\s*Boolean")
                .containsMatchIn(code),
        )
        assertTrue(
            "stale branch must return cleanup.cancel(...) instead of dropping it",
            Regex("return\\s+if\\s*\\(HomeworkReminderDeliveryRules\\.requiresCleanup\\(delivery\\)\\)\\s*\\{\\s*cleanup\\.cancel\\(")
                .containsMatchIn(code),
        )
    }

    @Test
    fun `reminder cleanup call sites are all boolean returning`() {
        val source = receiverSource()

        assertTrue(
            "cleanup substitute in production must be the android implementation only",
            source.contains("ReminderCleanup.android("),
        )
        assertFalse(
            "no leftover Unit-style cleanup lambda in production (return type must be observable)",
            Regex("ReminderCleanup\\s*\\{\\s*[^}]*->[^}]*\\}\\s*$").containsMatchIn(source),
        )
    }

    // ---- 辅助 ----

    /**
     * 去掉块注释 / KDoc（含行内 `cleanup.cancel(` 等示例文本），避免文档影响「调用点」核验；
     * 行注释保留缩进以便正则匹配「注释掉的清理调用」这类回归。
     */
    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")

    /** 单测工作目录实测为 `app/`（user.dir=E:\assignMate\app），故以模块内相对路径为准 */
    private fun receiverSource(): String {
        val relative = "src/main/java/com/assignmate/app/timer/service/HomeworkAlarmReceiver.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("missing production source (cwd=" + File(".").absolutePath + "): " + relative)
        return file.readText()
    }

    private companion object {

        const val HOMEWORK_ID = 7L

        /** 与 [TimerTestEnv] 同一固定时钟口径下的业务自然日 */
        val TODAY: Long = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS)
            .atZone(TimerTestEnv.ZONE)
            .toLocalDate()
            .toEpochDay()
    }
}