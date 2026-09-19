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
import java.io.File
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业模板页（录入/编辑模板）保存后的到点提醒同步接线核查（framework）。
 *
 * 缺口背景：模板页保存成功后原先只 `popBackStack`，没有提醒同步回调，导致「阶段范围 / 每日截止时刻 /
 * 类型（阶段 ↔ 当天）」变更后闹钟未被重排——与列表删除作业、时间设定页保存的同步口径不一致。
 * 修复口径（复用既有桥接入口，timer 内部实现不改）：
 * - 保存成功事件回抛作业 id（编辑 = 被编辑作业；新建 = 新建作业项真实 id）→ **按该 id 精确同步**；
 * - 事件拿不到 id 时回落路由上的 homeworkId（编辑语义下仍是被编辑作业）；
 * - 两者都拿不到且家长会话（路由 studentId 为正数）→ 退化为该学生「整份清单纠正」
 *   （与进入计时页的既有纠正同一协调器入口）；
 * - 三条分支互斥，不存在「既按 id 同步又整份纠正」的重复调度。
 *
 * 覆盖维度：
 * 1. 接线（源码级）：TEMPLATE 注册块的 `onSaved` 已接到提醒同步并透传事件回抛的作业 id 与路由学生 id，且仍回清单；
 * 2. 行为（真实协调器 + 替身闹钟 + 内存仓库 + 可注入作用域）：精确同步只动被保存作业；阶段作业逐日重排与
 *    范围收窄后超范围闹钟被取消；类型切回当天时历史逐日闹钟被清掉；删除作业路径（既有口径）逐日闹钟全取消；
 *    拿不到 id 时按学生整份纠正；拿不到 id + 学生端未指定学生时不发起同步；
 * 3. 依赖方向：接线层只经 timer 既有协调器入口（不新造机制），且不 import 任何页面实现。
 *
 * 说明（因本轮语义升级而重写的用例）：本轮把「模板页按**路由** homeworkId 分派」升级为
 * 「按**保存事件回抛**的作业 id 分派」，故原断言 `savedHomeworkId = entry.homeworkIdArg()` 的用例改断言
 * 事件 id 优先、路由 id 兜底；原「新建作业（路由 id 为 -1）且家长会话」的兜底用例保留，改称为
 * 「事件与路由都拿不到 id 时退化为整份清单纠正」（分支语义未变，仅命名与注释与实现对齐）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateReminderSyncWiringTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val dispatcherSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
    )

    // ---- 1. 接线：模板页保存回调 → 提醒同步 ----

    @Test
    fun `模板页保存回调已接到提醒同步并透传事件回抛的作业 id`() {
        val block = templateRouteBlock()
        assertNotNull("未找到模板页路由注册块", block)

        assertTrue(
            "模板页应声明 onSaved 收尾回调并接收保存成功事件回抛的作业 id",
            block!!.contains("onSaved = { savedHomeworkId ->"),
        )
        assertTrue(
            "onSaved 应接到 reminderSync.syncHomeworkSavedReminder（既有提醒同步桥）",
            block.contains("reminderSync.syncHomeworkSavedReminder("),
        )
        assertTrue(
            "应透传模板页路由上的学生 id（家长 = 被选学生；学生端 = 未指定哨兵）",
            block.contains("studentId = entry.studentIdArg()"),
        )
        assertTrue(
            "应优先透传事件回抛的作业 id，事件拿不到时才回落路由 homeworkId",
            block.contains("savedHomeworkId = savedHomeworkId.takeIf { it > 0L } ?: entry.homeworkIdArg()"),
        )
        assertTrue("保存成功后仍回上一级清单页（二级页 popBackStack）", block.contains("navController.popBackStack()"))
    }

    @Test
    fun `接线层只经 timer 既有协调器入口且随页面销毁仍能跑完`() {
        assertTrue(
            "单条精确同步走既有入口 coordinator.syncHomeworkReminder",
            dispatcherSource.contains("coordinator.syncHomeworkReminder("),
        )
        assertTrue(
            "按学生整份纠正走既有入口 coordinator.syncStudentReminders",
            dispatcherSource.contains("coordinator.syncStudentReminders("),
        )
        assertTrue(
            "删除作业路径仍走既有取消入口 coordinator.cancelHomeworkReminder",
            dispatcherSource.contains("coordinator.cancelHomeworkReminder("),
        )
        assertTrue(
            "同步在注入的进程级作用域内启动（页面 popBackStack 后仍能跑完）",
            dispatcherSource.contains("scope.launch"),
        )
        assertTrue(
            "接线层不 import 任何页面实现（只依赖 Hilt 注入的协调器）",
            !dispatcherSource.contains("import com.assignmate.app.homework.ui") &&
                !dispatcherSource.contains("import com.assignmate.app.stats.ui"),
        )
    }

    // ---- 2. 行为：编辑既有作业 → 精确同步 ----

    @Test
    fun `编辑既有作业保存后只同步被保存的该条作业`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        // 同一学生名下另一条作业：精确同步不得顺带纠正它（证明透传的是被保存作业的 id）
        fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 2 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 7L)
        advanceUntilIdle()

        assertEquals(
            "被保存作业应按其当前时刻重设提醒（与时间设定页保存口径一致）",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(7L)?.triggerAtMillis,
        )
        assertNull("未被保存的作业不应被顺带同步", fixture.scheduler.lastScheduledFor(8L))
    }

    @Test
    fun `阶段作业保存后按每日截止时刻设置逐日提醒且范围收窄后超范围闹钟被取消`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        val stage = stageHomework(id = 11L, range = StageRange.TWO_WEEKS, startEpochDay = today)
        fixture.put(stage)

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 11L)
        advanceUntilIdle()

        val twoWeekDays = fixture.scheduler.scheduledDaysOf(11L)
        assertTrue("阶段作业保存后应按每日截止时刻设置逐日提醒", twoWeekDays.isNotEmpty())
        assertTrue(
            "逐日提醒应覆盖阶段区间 ∩ 今天起（两周共 ${StageRange.TWO_WEEKS.days} 天，$twoWeekDays）",
            twoWeekDays.containsAll(StageRange.TWO_WEEKS.coveredEpochDays(today)),
        )

        // 模板页把阶段范围改短（两周 → 一周）后保存：超出新范围的历史闹钟必须取消
        fixture.put(stage.copy(stageRange = StageRange.ONE_WEEK))
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 11L)
        advanceUntilIdle()

        val beyondNewRange = StageRange.TWO_WEEKS.coveredEpochDays(today) - StageRange.ONE_WEEK.coveredEpochDays(today).toSet()
        assertTrue(
            "超出新范围的旧逐日闹钟应被取消（范围变更后必须重排，$beyondNewRange）",
            fixture.scheduler.cancelledDaysOf(11L).containsAll(beyondNewRange),
        )
    }

    @Test
    fun `模板页把类型从阶段切回当天后历史逐日闹钟全部取消`() = runTest {
        val fixture = fixture()
        val stage = stageHomework(id = 11L, range = StageRange.ONE_WEEK, startEpochDay = todayEpochDay())
        fixture.put(stage)

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 11L)
        advanceUntilIdle()
        val stageDays = fixture.scheduler.scheduledDaysOf(11L)
        assertTrue("前置事实：阶段作业保存后确有逐日闹钟", stageDays.isNotEmpty())

        // 模板页把类型切回「当天」并保存（仓库里即被保存后的作业形态：无阶段范围、改回时刻提醒）
        fixture.put(
            stage.copy(
                type = HomeworkType.TODAY,
                stageRange = null,
                deadline = null,
                startTime = Instant.ofEpochMilli(BASE + MINUTE),
            ),
        )
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 11L)
        advanceUntilIdle()

        assertTrue(
            "类型切回当天后历史逐日闹钟必须全部取消（不遗留无效提醒）",
            fixture.scheduler.cancelledDaysOf(11L).containsAll(stageDays),
        )
        assertEquals(
            "当天作业按开始时刻设置单次提醒",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(11L)?.triggerAtMillis,
        )
    }

    @Test
    fun `删除作业路径（既有口径）取消该作业的全部含逐日闹钟`() = runTest {
        val fixture = fixture()
        val stage = stageHomework(id = 11L, range = StageRange.ONE_WEEK, startEpochDay = todayEpochDay())
        fixture.put(stage)

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 11L)
        advanceUntilIdle()
        val stageDays = fixture.scheduler.scheduledDaysOf(11L)
        assertTrue(stageDays.isNotEmpty())

        fixture.dispatcher.cancelHomeworkReminderSync(11L)
        advanceUntilIdle()

        assertTrue("删除作业后逐日闹钟应被逐日取消", fixture.scheduler.cancelledDaysOf(11L).containsAll(stageDays))
        assertTrue("删除作业后单次闹钟也应被取消", fixture.scheduler.cancelled.contains(11L))
    }

    // ---- 3. 行为：拿不到作业 id 时的兜底两条分支 ----

    @Test
    fun `事件与路由都拿不到作业 id 且家长会话时退化为按学生整份清单纠正`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 2 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(
            studentId = STUDENT_ID,
            savedHomeworkId = HomeworkDestination.ARG_HOMEWORK_ID_NONE,
        )
        advanceUntilIdle()

        assertEquals(
            "整份纠正应按清单把应设的提醒都设上（作业 id 未知时的兜底口径）",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(7L)?.triggerAtMillis,
        )
        assertEquals(BASE + 2 * MINUTE - LEAD, fixture.scheduler.lastScheduledFor(8L)?.triggerAtMillis)
    }

    @Test
    fun `拿不到作业 id 且学生端未指定学生时不发起同步`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(
            studentId = AppDestination.UNSPECIFIED_STUDENT_ID,
            savedHomeworkId = HomeworkDestination.ARG_HOMEWORK_ID_NONE,
        )
        advanceUntilIdle()

        assertTrue("定位不到作业也定位不到学生时不得发起同步（避免误伤他人提醒）", fixture.scheduler.scheduled.isEmpty())
        assertTrue(fixture.scheduler.cancelled.isEmpty())
        assertTrue(
            "缺省哨兵常量为 -1（新建语义），与路由默认值一致",
            HomeworkDestination.ARG_HOMEWORK_ID_NONE < 0L,
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
     * 逐日闹钟的「登记 → 取消」链路与生产一致（否则「范围收窄/切类型后的取消防」无从观察）。
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

    /** 模板页路由注册块（按注册 route 表达式定位） */
    private fun templateRouteBlock(): String? {
        val pattern = Regex("route\\s*=\\s*HomeworkDestination\\.TEMPLATE\\s*[,)]")
        return splitComposableBlocks(navHostSource).firstOrNull { pattern.containsMatchIn(it) }
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
