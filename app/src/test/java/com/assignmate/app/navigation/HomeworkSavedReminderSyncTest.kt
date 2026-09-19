package com.assignmate.app.navigation

import com.assignmate.app.auth.data.FakeKeyValueStore
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.timer.data.DataStoreTimerReminderScheduleStore
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerReminderRules
import java.io.File
import java.time.LocalTime
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
 * 新建作业「按 id 精确同步逐日提醒」的接线核查（framework）。
 *
 * 缺口背景（皋陶 info）：模板页保存后虽已能同步提醒，但**录入页新建作业**路径未回抛新 homeworkId，
 * 学生端「未指定学生」分支只能靠「按学生整份清单纠正」兜底，导致该新建作业在用户进入清单/计时页之前
 * 没有任何逐日闹钟。homework 侧已让保存成功事件回抛新 homeworkId 后，framework 的接线改为：
 *
 * - **优先按回抛的作业 id 精确同步**（[ReminderSyncDispatcher.syncHomeworkSavedReminder] →
 *   `coordinator.syncHomeworkReminder(id)`）：当天作业按排定时刻设单次提醒；阶段作业按阶段覆盖区间逐日排定，
 *   请求码沿用协调器既有的 `TimerReminderRules.requestCodeOf(homeworkId, epochDay)` 口径（不另造机制）；
 *   因按 id 同步不依赖学生 id，学生端会话（路由 studentId = 0 = 未指定学生）同样成立；
 * - 仅当事件与路由都拿不到作业 id 时，才回落到「按学生整份清单纠正」（兜底用例见 [TemplateReminderSyncWiringTest]）；
 * - 三条分支互斥，故不会出现「既按 id 同步又整份纠正」的重复调度。
 *
 * 覆盖维度：
 * 1. 接线（源码级）：录入页注册块的 `onSaved(savedHomeworkId)` 已接到提醒同步并透传该 id，且该块内不出现
 *    整份清单纠正（证明优先走按 id 精确同步）；接线层的分派条件为「id 为正数优先」；
 * 2. 行为（真实协调器 + 替身闹钟 + 内存仓库 + 内存登记表 + 可注入作用域）：
 *    新建阶段作业按 id 逐日同步且恰好覆盖阶段区间；新建当天作业按 id 设单次提醒；学生端未指定学生时
 *    两类作业都能设上闹钟（不再出现「无逐日闹钟」）；各场景均**不**顺带纠正同学生名下的其它作业；
 * 3. 请求码口径：逐日闹钟一天一码、与同作业单次闹钟的请求码错开（复用 timer 既有规则）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSavedReminderSyncTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val dispatcherSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
    )

    // ---- 1. 接线（源码级）：录入页保存 → 按回抛 id 精确同步 ----

    @Test
    fun `录入页保存回调按回抛的新作业 id 精确同步且不走整份清单纠正`() {
        val block = entryRouteBlock()
        assertNotNull("未找到录入页路由注册块", block)

        assertTrue(
            "录入页应声明 onSaved 收尾回调并接收保存成功事件回抛的新作业 id",
            block!!.contains("onSaved = { savedHomeworkId ->"),
        )
        assertTrue(
            "onSaved 应接到 reminderSync.syncHomeworkSavedReminder（提醒同步桥）",
            block.contains("reminderSync.syncHomeworkSavedReminder("),
        )
        assertTrue(
            "应原样透传事件回抛的作业 id（不做二次解析，避免 id 口径漂移）",
            block.contains("savedHomeworkId = savedHomeworkId"),
        )
        assertFalse(
            "录入页不应直接发起「按学生整份清单纠正」（优先按 id 精确同步）",
            block.contains("syncStudentReminders"),
        )
        assertTrue(
            "保存成功后仍回上一级清单页（二级页 popBackStack）",
            block.contains("navController.popBackStack()"),
        )

        assertTrue(
            "接线层应提供按 id 优先的分派入口（整份纠正仅在拿不到 id 时兜底）",
            dispatcherSource.contains("fun syncHomeworkSavedReminder(studentId: Long, savedHomeworkId: Long)") &&
                dispatcherSource.contains("if (savedHomeworkId > 0L)"),
        )
        assertTrue(
            "按 id 精确同步应走 timer 既有协调器入口 coordinator.syncHomeworkReminder",
            dispatcherSource.contains("coordinator.syncHomeworkReminder("),
        )
    }

    // ---- 2. 行为：新建作业（回抛新 id）→ 按 id 同步 ----

    @Test
    fun `新建阶段作业保存后按回抛 id 同步阶段覆盖区间内的逐日提醒`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        val newStageId = 21L
        fixture.put(stageHomework(id = newStageId, range = StageRange.ONE_WEEK, startEpochDay = today))
        // 同一学生名下另一条作业：按 id 精确同步不得顺带纠正它（反证「不是整份清单纠正」）
        fixture.put(timerTestHomework(id = 22L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 3 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = newStageId)
        advanceUntilIdle()

        assertEquals(
            "逐日提醒应恰好覆盖阶段区间 ∩ 今天起（阶段覆盖区间内的每一天各一个闹钟）",
            StageRange.ONE_WEEK.coveredEpochDays(today).toSet(),
            fixture.scheduler.scheduledDaysOf(newStageId).toSet(),
        )
        assertTrue(
            "逐日闹钟只能挂在回抛的那个作业 id 上（透传的 id 与作业一致）",
            fixture.scheduler.scheduledDaily.all { it.homeworkId == newStageId },
        )
        assertNull(
            "未被保存的其它作业不应被同步（说明走的是按 id 精确同步而非整份清单纠正）",
            fixture.scheduler.lastScheduledFor(22L),
        )
    }

    @Test
    fun `新建当天作业保存后按回抛 id 设置单次提醒`() = runTest {
        val fixture = fixture()
        val newTodayId = 31L
        fixture.put(timerTestHomework(id = newTodayId, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(timerTestHomework(id = 32L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 5 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = newTodayId)
        advanceUntilIdle()

        assertEquals(
            "新建当天作业应按其排定时刻设置单次提醒（与时间设定页保存同口径）",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(newTodayId)?.triggerAtMillis,
        )
        assertTrue(
            "当天作业不应残留逐日闹钟",
            fixture.scheduler.scheduledDaysOf(newTodayId).isEmpty(),
        )
        assertNull(
            "未被保存的其它作业不应被同步（按 id 精确同步，不整份纠正）",
            fixture.scheduler.lastScheduledFor(32L),
        )
    }

    // ---- 3. 行为：学生端「未指定学生」不再出现「无逐日闹钟」 ----

    @Test
    fun `学生端未指定学生时新建阶段作业仍有逐日闹钟`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        val newStageId = 41L
        fixture.put(stageHomework(id = newStageId, range = StageRange.ONE_WEEK, startEpochDay = today))

        // 学生端会话：清单/录入路由上的 studentId 为「未指定学生」哨兵
        fixture.dispatcher.syncHomeworkSavedReminder(
            studentId = AppDestination.UNSPECIFIED_STUDENT_ID,
            savedHomeworkId = newStageId,
        )
        advanceUntilIdle()

        val days = fixture.scheduler.scheduledDaysOf(newStageId)
        assertTrue(
            "学生端未指定学生的新建阶段作业也必须有逐日闹钟（修复「进清单/计时页之前没有逐日闹钟」的缺口）",
            days.isNotEmpty(),
        )
        assertEquals(
            "逐日闹钟应完整覆盖阶段区间（口径与家长端一致）",
            StageRange.ONE_WEEK.coveredEpochDays(today).toSet(),
            days.toSet(),
        )
        assertTrue(
            "路由未携带学生时不得退化为「按某个学生整份纠正」而误伤他人提醒",
            fixture.scheduler.scheduledDaily.all { it.homeworkId == newStageId },
        )
    }

    @Test
    fun `学生端未指定学生时新建当天作业也能设上单次提醒`() = runTest {
        val fixture = fixture()
        val newTodayId = 42L
        fixture.put(timerTestHomework(id = newTodayId, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(
            studentId = AppDestination.UNSPECIFIED_STUDENT_ID,
            savedHomeworkId = newTodayId,
        )
        advanceUntilIdle()

        assertEquals(
            "学生端未指定学生的新建当天作业同样应按 id 设上提醒",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(newTodayId)?.triggerAtMillis,
        )
    }

    // ---- 4. 请求码口径：复用既有 requestCodeOf(homeworkId, epochDay) ----

    @Test
    fun `阶段逐日提醒沿用既有按天请求码口径且与单次请求码错开`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        val newStageId = 51L
        fixture.put(stageHomework(id = newStageId, range = StageRange.ONE_WEEK, startEpochDay = today))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = newStageId)
        advanceUntilIdle()

        val codes = fixture.scheduler.scheduledDaily.map { alarm ->
            TimerReminderRules.requestCodeOf(alarm.homeworkId, alarm.epochDay)
        }
        assertEquals(
            "逐日提醒一天一码（同一作业的不同自然日请求码互不相同）",
            codes.size,
            codes.distinct().size,
        )
        assertTrue(
            "逐日请求码应与该作业的单次请求码错开（区间隔离，不撞码覆盖）",
            codes.none { it == TimerReminderRules.requestCodeOf(newStageId) },
        )
    }

    // ---- 测试夹具与源码解析工具 ----

    private class Fixture(
        val repository: FakeHomeworkRepository,
        val scheduler: FakeAlarmScheduler,
        val dispatcher: ReminderSyncDispatcher,
    ) {
        fun put(item: HomeworkItem) {
            repository.put(item)
        }
    }

    /**
     * 真实协调器 + 替身闹钟 + 内存仓库 + 内存登记表 + 可注入作用域：
     * 逐日闹钟的「登记 → 覆盖」链路与生产一致（否则「阶段区间逐日设置」无从观察）。
     */
    private fun TestScope.fixture(): Fixture {
        val repository = FakeHomeworkRepository()
        val scheduler = FakeAlarmScheduler()
        val coordinator = HomeworkReminderCoordinator(
            homeworkRepository = repository,
            alarmScheduler = scheduler,
            clock = MutableClock(BASE),
            zoneId = TimerTestEnv.ZONE,
            scheduleStore = DataStoreTimerReminderScheduleStore(FakeKeyValueStore()),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            scheduler = scheduler,
            dispatcher = ReminderSyncDispatcher(coordinator, scope),
        )
    }

    /** 阶段作业样本：约定时刻编码进 deadline（与生产 homework 编码口径一致） */
    private fun stageHomework(id: Long, range: StageRange, startEpochDay: Long): HomeworkItem =
        timerTestHomework(
            id = id,
            status = HomeworkStatus.PENDING,
            type = HomeworkType.STAGE,
            stageRange = range,
            stageStartEpochDay = startEpochDay,
            stageDailyTime = DAILY_TIME,
        )

    /** 业务自然日口径的「今天」（与 core / homework / timer 同一折算口径） */
    private fun todayEpochDay(): Long = HomeworkValidators.epochDayOf(BASE, TimerTestEnv.ZONE)

    /**
     * 录入页路由注册块：按**常量表达式 → 常量值**解析定位（与 [com.assignmate.app.navigation.SettingsNavigationContractTest]
     * 的 blockForRoute 同一口径）。
     *
     * 修复说明（离朱独立核查发现）：原实现用 `Regex.escape(HomeworkDestination.ENTRY)` 匹配的是该常量的**值**
     * （`homework/entry/{studentId}` 字面量），而宿主是以常量**表达式** `route = HomeworkDestination.ENTRY`
     * 注册的，故匹配恒为空、`block == null` 断言必然失败（假失败，与生产接线无关）。
     * 现改为解析注册块的 route 表达式再比对常量值；并保留字面量注册的兜底匹配以兼容未来改法。
     */
    private fun entryRouteBlock(): String? {
        val expressionPattern = Regex("route\\s*=\\s*HomeworkDestination\\.ENTRY\\s*[,)]")
        val literalPattern = Regex(
            "route\\s*=\\s*\"" + Regex.escape(HomeworkDestination.ENTRY) + "\"\\s*[,)]",
        )
        return splitComposableBlocks(navHostSource).firstOrNull { block ->
            expressionPattern.containsMatchIn(block) || literalPattern.containsMatchIn(block)
        }
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative", file)
        return file!!.readText()
    }

    /** 切出每个 `composable(...) { ... }` 注册块（括号/花括号配平，跳过字符串字面量与注释） */
    private fun splitComposableBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = source.indexOf("composable(")
        while (index >= 0) {
            val headerEnd = balancedEnd(source, index + "composable".length - 1, '(', ')') ?: break
            val bodyStart = source.indexOf('{', headerEnd)
            if (bodyStart < 0) break
            val bodyEnd = balancedEnd(source, bodyStart, '{', '}') ?: break
            blocks += source.substring(index, bodyEnd + 1)
            index = source.indexOf("composable(", bodyEnd)
        }
        return blocks
    }

    /** 自 [start] 处的 [open] 起配平，返回配对 [close] 的下标（跳过字符串字面量与注释） */
    private fun balancedEnd(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = start
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        while (i < source.length) {
            val ch = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLineComment -> if (ch == '\n') inLineComment = false
                inBlockComment -> if (ch == '*' && next == '/') {
                    inBlockComment = false
                    i++
                }

                inString -> when {
                    ch == '\\' -> i++
                    ch == '"' -> inString = false
                }

                ch == '/' && next == '/' -> inLineComment = true
                ch == '/' && next == '*' -> inBlockComment = true
                ch == '"' -> inString = true
                ch == open -> depth++
                ch == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private companion object {

        /** 固定时钟（与 timer/homework 测试同一基准） */
        const val BASE = TimerTestEnv.FIXED_MILLIS

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE

        /** 提醒提前量（与生产规则同源，不写死数字） */
        const val LEAD = TimerConstants.REMINDER_LEAD_MILLIS

        /** 作业归属学生（timer 测试环境固定的学生 id） */
        const val STUDENT_ID = TimerTestEnv.STUDENT_ID

        /** 阶段作业的每日截止时刻（到点提醒的钟面） */
        val DAILY_TIME: LocalTime = LocalTime.of(21, 0)
    }
}
