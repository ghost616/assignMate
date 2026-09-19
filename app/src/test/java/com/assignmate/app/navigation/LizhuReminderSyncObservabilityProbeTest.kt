package com.assignmate.app.navigation

import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱（测试智能体）对本轮变更的独立行为核查（不复用实现者自写断言）。
 *
 * 覆盖本轮三条交付面：
 * 1. 静默 no-op 分支可观测：id 全不可用时恰好留痕一次、内容只含两个 id、不产生任何设置或取消
 *    （单次与逐日都不得出现）；两个可执行分支成功时不得留痕。
 * 2. 留痕失败（日志出口抛异常）不得外抛、不得改变「不发起同步」语义，也不得污染后续调用。
 * 3. KDoc 入口计数（四条）= 四个对外方法，逐条落到既有协调器入口；三条情形互斥结构；
 *    生产出口为 android.util.Log.d 且有 Hilt 生产绑定。
 *
 * 环境边界：本仓库未开启 unitTests.isReturnDefaultValues，纯 JVM 下 android.util.Log 未桩实现，
 * 故「留痕」以可注入出口做行为级断言；生产出口以源码锚点核验（真机范围）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LizhuReminderSyncObservabilityProbeTest {

    private val dispatcherSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
    )

    // ---- 1. 行为：静默分支恰好留痕一次且不做任何调度 ----

    @Test
    fun `id 全不可用时恰好留痕一次且不产生任何设置或取消`() = runTest {
        val recorder = RecordingLogSink()
        val fixture = fixture(recorder)
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 0L)

        assertEquals("静默分支必须恰好留痕一次", 1, recorder.entries.size)
        val logged = recorder.entries.single()
        assertEquals("TAG 应为类名口径", "ReminderSyncDispatcher", logged.tag)
        assertNull("正常降级留痕不应带异常", logged.cause)
        assertTrue("内容应含 savedHomeworkId，实际：${logged.message}", logged.message.contains("savedHomeworkId=0"))
        assertTrue("内容应含 studentId，实际：${logged.message}", logged.message.contains("studentId=0"))

        advanceUntilIdle()
        assertTrue("不得设置单次闹钟", fixture.scheduler.scheduled.isEmpty())
        assertTrue("不得取消单次闹钟", fixture.scheduler.cancelled.isEmpty())
        assertTrue("不得设置逐日闹钟", fixture.scheduler.scheduledDaily.isEmpty())
        assertTrue("不得取消逐日闹钟", fixture.scheduler.cancelledDaily.isEmpty())
    }

    @Test
    fun `全部非正数 id 组合均恰好留痕一次且零调度`() = runTest {
        val combinations = listOf(
            0L to 0L,
            0L to -1L,
            -1L to 0L,
            -1L to -1L,
            Long.MIN_VALUE to 0L,
            0L to Long.MIN_VALUE,
            Long.MIN_VALUE to Long.MIN_VALUE,
        )

        combinations.forEach { (studentId, savedHomeworkId) ->
            val recorder = RecordingLogSink()
            val fixture = fixture(recorder)
            fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

            fixture.dispatcher.syncHomeworkSavedReminder(studentId = studentId, savedHomeworkId = savedHomeworkId)
            advanceUntilIdle()

            assertEquals(
                "组合（studentId=$studentId, savedHomeworkId=$savedHomeworkId）应恰好留痕一次",
                1,
                recorder.entries.size,
            )
            assertEquals(
                "留痕应带上该组合的 savedHomeworkId",
                savedHomeworkId,
                recorder.entries.single().message
                    .substringAfter("savedHomeworkId=")
                    .substringBefore(" ")
                    .toLong(),
            )
            assertEquals(
                "留痕应带上该组合的 studentId",
                studentId,
                recorder.entries.single().message
                    .substringAfter("studentId=")
                    .substringBefore(" ")
                    .toLong(),
            )
            assertTrue(
                "组合（studentId=$studentId, savedHomeworkId=$savedHomeworkId）不得产生任何闹钟调用",
                fixture.scheduler.scheduled.isEmpty() &&
                    fixture.scheduler.cancelled.isEmpty() &&
                    fixture.scheduler.scheduledDaily.isEmpty() &&
                    fixture.scheduler.cancelledDaily.isEmpty(),
            )
        }
    }

    @Test
    fun `两个可执行分支成功时不产生任何日志`() = runTest {
        val recorder = RecordingLogSink()
        val fixture = fixture(recorder)
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 2 * MINUTE))

        // 可执行分支一：按 id 精确同步
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 7L)
        advanceUntilIdle()
        assertEquals("按 id 同步分支不得留痕", 0, recorder.entries.size)
        assertEquals(
            "按 id 同步确实落地（前置事实）",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(7L)?.triggerAtMillis,
        )

        // 可执行分支二：家长会话整份清单纠正
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 0L)
        advanceUntilIdle()
        assertEquals("家长兜底分支不得留痕", 0, recorder.entries.size)
        assertNotNull("整份纠正确实落地（前置事实）", fixture.scheduler.lastScheduledFor(8L))
    }

    // ---- 2. 反向：留痕失败不外抛、不改变语义、不污染后续调用 ----

    @Test
    fun `留痕出口抛异常时不外抛也不产生同步且后续调用仍可留痕`() = runTest {
        val throwing = ThrowingLogSink()
        val fixture = fixture(throwing)
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        val failure = runCatching {
            fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 0L)
        }.exceptionOrNull()
        advanceUntilIdle()

        assertNull("留痕失败不得抛给调用方（保存主流程不受影响）", failure)
        assertEquals("留痕出口确实被调用（前置事实）", 1, throwing.attempts)
        assertTrue("留痕失败后仍不得发起任何同步", fixture.scheduler.scheduled.isEmpty())
        assertTrue("留痕失败后仍不得取消任何闹钟", fixture.scheduler.cancelled.isEmpty())
        assertTrue("留痕失败后仍不得产生逐日闹钟", fixture.scheduler.scheduledDaily.isEmpty())

        val healthy = RecordingLogSink()
        val healthyFixture = fixture(healthy)
        healthyFixture.put(
            timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )
        healthyFixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 0L)
        advanceUntilIdle()
        assertEquals("替身出口仍可正常留痕（失败未被吞成新的静默缺口）", 1, healthy.entries.size)
    }

    // ---- 3. 结构：KDoc 计数、入口与实现一致、生产出口与绑定 ----

    @Test
    fun `KDoc 所述入口计数为四条且与四个对外方法一一对应`() {
        val numbered = Regex("^\\s*\\* (\\d)\\. ", RegexOption.MULTILINE)
            .findAll(dispatcherSource)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals("KDoc 应逐条列出四个入口（1..4）", listOf(1, 2, 3, 4), numbered)
        assertTrue("KDoc 应以「四个」声明入口数", dispatcherSource.contains("覆盖四个提醒同步入口"))
        assertTrue(
            "KDoc 应显式说明录入页与模板页共用同一入口",
            dispatcherSource.contains("共用同一入口"),
        )

        val publicMethods = Regex("fun\\s+(\\w+)\\s*\\(")
            .findAll(dispatcherSource)
            .map { it.groupValues[1] }
            .filter { it != "debug" && it != "launchSafely" && it != "logFailure" }
            .toList()
        assertEquals(
            "四个入口应逐条对应四个对外方法",
            listOf(
                "cancelHomeworkReminderSync",
                "syncHomeworkReminder",
                "syncStudentReminders",
                "syncHomeworkSavedReminder",
            ),
            publicMethods,
        )
        assertTrue(
            "launchSafely / logFailure 必须保持 private（否则成为未登记入口）",
            Regex("private\\s+fun\\s+launchSafely\\s*\\(").containsMatchIn(dispatcherSource) &&
                Regex("private\\s+fun\\s+logFailure\\s*\\(").containsMatchIn(dispatcherSource),
        )

        listOf(
            "cancelHomeworkReminderSync" to "coordinator.cancelHomeworkReminder(",
            "syncHomeworkReminder" to "coordinator.syncHomeworkReminder(",
            "syncStudentReminders" to "coordinator.syncStudentReminders(",
            "syncHomeworkSavedReminder" to "syncHomeworkReminder(savedHomeworkId)",
        ).forEach { (method, landing) ->
            val body = methodBody(method)
            assertNotNull("KDoc 登记的入口 $method 未在接线层实现", body)
            assertTrue("入口 $method 应经既有协调器入口 $landing 落地", body!!.contains(landing))
        }
    }

    @Test
    fun `静默分支结构为可执行分支各自提前 return 且末尾留痕`() {
        val body = methodBody("syncHomeworkSavedReminder")
        assertNotNull("未找到 syncHomeworkSavedReminder 方法体", body)
        val methodBody = body!!

        assertEquals(
            "方法内只应有一次「回抛 id 判正」",
            1,
            Regex("savedHomeworkId\\s*>\\s*0L").findAll(methodBody).count(),
        )
        assertEquals(
            "两个可执行分支各自提前 return（共 2 个 return）",
            2,
            Regex("\\breturn\\b").findAll(methodBody).count(),
        )
        assertFalse("不得存在无条件 else 落空分支", Regex("else\\s*\\{").containsMatchIn(methodBody))
        assertTrue(
            "末尾应为 runCatching 包裹的显式留痕（防止被改回静默空实现）",
            Regex("runCatching\\s*\\{\\s*logSink\\.debug\\(\\s*TAG,[\\s\\S]*?null,")
                .containsMatchIn(methodBody),
        )
        val tail = methodBody.substringAfter("if (studentId > 0L) {").substringAfter("return")
        assertTrue("末尾分支应调用 logSink.debug", tail.contains("logSink.debug("))
        assertTrue(
            "末尾分支不得调用任何协调器入口（保持「不发起同步」语义）",
            !tail.contains("coordinator.") &&
                !tail.contains("syncHomeworkReminder(") &&
                !tail.contains("syncStudentReminders("),
        )
    }

    @Test
    fun `生产日志出口走 Log_d 且有 Hilt 绑定且接线层不直接调 Log`() {
        val sinkSource = readSource("src/main/java/com/assignmate/app/navigation/ReminderSyncLogSink.kt")
        assertTrue(
            "日志出口应为可注入 fun interface + 生产实现",
            sinkSource.contains("fun interface ReminderSyncLogSink") &&
                sinkSource.contains("object AndroidReminderSyncLogSink : ReminderSyncLogSink"),
        )
        assertTrue("生产出口应走 android.util.Log.d", sinkSource.contains("Log.d(tag, message"))
        assertFalse(
            "fun interface 抽象方法不得带默认值",
            Regex("fun\\s+debug\\([^)]*=\\s*null\\s*\\)").containsMatchIn(sinkSource),
        )
        assertTrue(
            "须有 Hilt 生产绑定（否则契约会被装配绕过）",
            readSource("src/main/java/com/assignmate/app/di/ApplicationScopeModule.kt")
                .contains("fun provideReminderSyncLogSink(): ReminderSyncLogSink = AndroidReminderSyncLogSink"),
        )
        assertTrue("TAG 常量应为类名口径", dispatcherSource.contains("const val TAG = \"ReminderSyncDispatcher\""))
        assertTrue("接线层应经 logSink 留痕", dispatcherSource.contains("logSink.debug("))
        assertFalse("接线层不得直接调用 android.util.Log", dispatcherSource.contains("Log.d("))
    }

    // ---- 夹具与源码工具 ----

    private class RecordingLogSink : ReminderSyncLogSink {

        val entries = mutableListOf<Entry>()

        override fun debug(tag: String, message: String, cause: Throwable?) {
            entries += Entry(tag, message, cause)
        }
    }

    private data class Entry(val tag: String, val message: String, val cause: Throwable?)

    private class ThrowingLogSink : ReminderSyncLogSink {

        var attempts: Int = 0

        override fun debug(tag: String, message: String, cause: Throwable?) {
            attempts++
            throw IllegalStateException("日志出口不可用")
        }
    }

    private class Fixture(
        val repository: FakeHomeworkRepository,
        val scheduler: FakeAlarmScheduler,
        val dispatcher: ReminderSyncDispatcher,
    ) {
        fun put(item: HomeworkItem) = repository.put(item)
    }

    /** 真实协调器 + 替身闹钟 + 内存仓库 + 可注入作用域（链路与生产一致，日志出口可断言） */
    private fun TestScope.fixture(logSink: ReminderSyncLogSink): Fixture {
        val repository = FakeHomeworkRepository()
        val scheduler = FakeAlarmScheduler()
        val coordinator = HomeworkReminderCoordinator(
            homeworkRepository = repository,
            alarmScheduler = scheduler,
            clock = MutableClock(BASE),
            zoneId = TimerTestEnv.ZONE,
            scheduleStore = null,
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            scheduler = scheduler,
            dispatcher = ReminderSyncDispatcher(coordinator, scope, logSink),
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    private fun methodBody(name: String): String? {
        val match = Regex("fun\\s+" + name + "\\s*\\(").find(dispatcherSource) ?: return null
        val open = dispatcherSource.indexOf('{', match.range.first)
        if (open < 0) return null
        var depth = 0
        var i = open
        var inString = false
        while (i < dispatcherSource.length) {
            val ch = dispatcherSource[i]
            when {
                inString -> when {
                    ch == '\\' -> i++
                    ch == '"' -> inString = false
                }

                ch == '"' -> inString = true
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return dispatcherSource.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE

        const val LEAD = TimerConstants.REMINDER_LEAD_MILLIS

        const val STUDENT_ID = TimerTestEnv.STUDENT_ID
    }
}