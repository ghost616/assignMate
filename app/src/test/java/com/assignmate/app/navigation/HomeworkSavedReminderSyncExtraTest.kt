package com.assignmate.app.navigation

import com.assignmate.app.auth.data.FakeKeyValueStore
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.timer.data.AlarmScheduleResult
import com.assignmate.app.timer.data.DataStoreTimerReminderScheduleStore
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.HomeworkAlarmScheduler
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
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
 * 离朱（测试智能体）对本轮「新建作业按 id 精确同步逐日提醒」的独立补充核查。
 *
 * 独立核查的动因：HomeworkSavedReminderSyncTest 的「录入页保存回调…」用例曾因**测试自身定位口径写错**而失败
 * （其 entryRouteBlock() 用 Regex.escape(HomeworkDestination.ENTRY) 去匹配常量**值**，而宿主是用常量
 * **表达式** route = HomeworkDestination.ENTRY 注册的），失败点在 block == null，并不代表生产接线有缺陷。
 * 本文件用与既有契约测试（SettingsNavigationContractTest.blockForRoute）同一口径「常量表达式 → 常量值」
 * 独立重新定位 ENTRY 注册块并逐条核验接线语义，从而把「测试口径缺陷」与「生产接线缺陷」区分开，
 * 也避免只依赖实现者自写的断言来确认修复有效。
 *
 * 覆盖维度（本轮变更面 + 既有测试未覆盖处）：
 * A. 接线（源码级，修正后的定位口径）：录入页保存回调透传事件回抛 id、不出现整份纠正、不引用路由
 *    homeworkId；模板页「事件 id 优先、路由 id 兜底」；接线层三分支的结构互斥与调用次数唯一。
 * B. 行为边界：回抛 id = 0（非正数下界）走兜底；id 与学生 id 同时非正数 → 不发起同步；回抛 id 不存在 →
 *    只取消该 id；回抛 id = Long.MAX_VALUE（极限值）→ 静默降级不误伤；有 id 时同学生的阶段作业不产生
 *    逐日闹钟（互斥的行为级反证）；重复保存幂等；调度器异常不外抛。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSavedReminderSyncExtraTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val dispatcherSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
    )

    // ---- A. 接线（源码级）：录入页保存按回抛 id 精确同步 ----

    @Test
    fun `录入页注册块按常量表达式定位成功且保存回调透传事件回抛的作业 id`() {
        val block = entryRouteBlock()
        assertNotNull(
            "宿主以常量表达式 route = HomeworkDestination.ENTRY 注册录入页；按常量值字面量匹配会定位失败",
            block,
        )
        assertTrue(
            "前置事实：宿主确实用常量表达式注册 ENTRY（这是既有用例定位失败的根因）",
            navHostSource.contains("route = HomeworkDestination.ENTRY"),
        )
        assertFalse(
            "宿主不使用字符串字面量注册 ENTRY（故按路由模板字面量切块的定位口径必然失效）",
            Regex("route\\s*=\\s*\"homework/entry/").containsMatchIn(navHostSource),
        )

        val entryBlock = block!!
        assertTrue(
            "录入页应声明 onSaved 收尾回调并接收保存成功事件回抛的新作业 id",
            entryBlock.contains("onSaved = { savedHomeworkId ->"),
        )
        assertTrue(
            "onSaved 应接到提醒同步桥 reminderSync.syncHomeworkSavedReminder",
            entryBlock.contains("reminderSync.syncHomeworkSavedReminder("),
        )
        assertTrue(
            "应原样透传事件回抛的作业 id（不做二次解析，避免 id 口径漂移）",
            Regex("savedHomeworkId\\s*=\\s*savedHomeworkId\\s*[,)]").containsMatchIn(entryBlock),
        )
        assertTrue(
            "应透传路由上的学生 id 作为「拿不到 id 时」的兜底依据",
            entryBlock.contains("studentId = entry.studentIdArg()"),
        )
        assertFalse(
            "录入页不应直接发起「按学生整份清单纠正」（优先按 id 精确同步）",
            entryBlock.contains("syncStudentReminders"),
        )
        assertFalse(
            "录入页路由不携带 homeworkId，不应出现路由 id 兜底解析",
            entryBlock.contains("homeworkIdArg()"),
        )
        assertTrue(
            "保存成功后仍回上一级清单页（二级页 popBackStack）",
            entryBlock.contains("navController.popBackStack()"),
        )
    }

    @Test
    fun `模板页注册块保存优先透传事件回抛 id 且路由 id 仅作非正数兜底`() {
        val block = templateRouteBlock()
        assertNotNull("未找到模板页路由注册块", block)
        val templateBlock = block!!

        assertTrue(
            "应优先透传事件回抛的作业 id，事件拿不到（非正数）时才回落路由 homeworkId",
            templateBlock.contains(
                "savedHomeworkId = savedHomeworkId.takeIf { it > 0L } ?: entry.homeworkIdArg()",
            ),
        )
        assertTrue(
            "模板页应透传路由学生 id 作为整份纠正兜底依据",
            templateBlock.contains("studentId = entry.studentIdArg()"),
        )
        assertTrue("保存成功后仍回上一级清单页", templateBlock.contains("navController.popBackStack()"))
    }

    /**
     * 接线层三分支互斥与「每条协调器入口方法内只出现一次」的结构核查。
     *
     * 本轮口径对齐（可观测性债修复）：原先「拿不到 id + 学生端未指定学生」是一条**无条件 else 落空分支**
     * 的静默 no-op；现改为「可执行分支各自提前 return → 末尾仅剩 no-op 留痕」，
     * 末尾分支会经注入的日志出口留痕（[ReminderSyncDispatcher] 的 logSink），因此：
     * - 不再存在 `} else if (studentId > 0L) {` 写法，改为独立 `if (studentId > 0L) { … return }`；
     * - 「无条件 else 落空分支」这条禁令仍然成立，反向核验同样增强为「不存在任何 else 块」；
     * - 三条分支仍互斥（提前 return 保证），两条协调器入口在方法内仍各只出现一次。
     */
    @Test
    fun `接线层三分支结构互斥且每条协调器入口在方法内各只出现一次`() {
        val body = dispatcherMethodBody("syncHomeworkSavedReminder")
        assertNotNull("未找到 syncHomeworkSavedReminder 方法体", body)
        val methodBody = body!!

        assertTrue("分派条件应是「事件回抛 id 为正数优先」", methodBody.contains("if (savedHomeworkId > 0L) {"))
        assertTrue(
            "兜底分支条件应是「学生 id 为正数」（与按 id 分支各自提前 return，保持三情形互斥）",
            methodBody.contains("if (studentId > 0L) {"),
        )
        assertTrue(
            "按 id 分支只调用 coordinator 的单条精确同步入口",
            methodBody.contains("syncHomeworkReminder(savedHomeworkId)"),
        )
        assertTrue(
            "兜底分支只调用 coordinator 的学生级纠正入口",
            methodBody.contains("syncStudentReminders(studentId)"),
        )
        assertEquals(
            "方法内按 id 同步入口只应出现一次（不存在重复调度）",
            1,
            Regex("syncHomeworkReminder\\(").findAll(methodBody).count(),
        )
        assertEquals(
            "方法内学生级纠正入口只应出现一次（不存在重复调度）",
            1,
            Regex("syncStudentReminders\\(").findAll(methodBody).count(),
        )
        assertEquals(
            "两个可执行分支各自提前 return，避免落入后续分支（三情形互斥的结构保证）",
            2,
            Regex("\\breturn\\b").findAll(methodBody).count(),
        )
        assertFalse("方法体内不得存在无条件 else 落空分支调度", Regex("else\\s*\\{").containsMatchIn(methodBody))
        assertFalse(
            "按 id 分支内不得再整份纠正（三情形互斥）",
            Regex("syncStudentReminders\\(")
                .containsMatchIn(methodBody.substringAfter("if (savedHomeworkId > 0L) {").substringBefore("if (studentId > 0L) {")),
        )
    }

    // ---- B. 行为：分支边界 ----

    @Test
    fun `回抛 id 为非正数下界 0 时按家长会话兜底整份纠正`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 2 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 0L)
        advanceUntilIdle()

        assertEquals(
            "0 属于「拿不到 id」（非正数），家长会话应退化为按学生整份清单纠正",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(7L)?.triggerAtMillis,
        )
        assertEquals(
            "整份纠正应覆盖该学生清单中的全部应提醒作业",
            BASE + 2 * MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(8L)?.triggerAtMillis,
        )
    }

    @Test
    fun `回抛 id 与学生 id 同时非正数时不发起任何同步`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 0L)
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = -1L)
        fixture.dispatcher.syncHomeworkSavedReminder(studentId = -7L, savedHomeworkId = 0L)
        advanceUntilIdle()

        assertTrue(
            "拿不到作业 id 又定位不到学生时不得设置任何闹钟（不猜测、不误伤他人提醒）",
            fixture.scheduler.scheduled.isEmpty(),
        )
        assertTrue("同样不得取消任何闹钟", fixture.scheduler.cancelled.isEmpty())
        assertTrue("不得产生逐日闹钟", fixture.scheduler.scheduledDaily.isEmpty())
        assertTrue("不得逐日取消", fixture.scheduler.cancelledDaily.isEmpty())
    }

    @Test
    fun `回抛 id 非正数且学生端未指定学生（哨兵 0）时不做整份纠正`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        // 若误走「按学生整份纠正」，该阶段作业会产生逐日闹钟——用可观察副作用反证分支未走
        fixture.put(stageHomework(id = 51L, range = StageRange.ONE_WEEK, startEpochDay = today))

        fixture.dispatcher.syncHomeworkSavedReminder(
            studentId = AppDestination.UNSPECIFIED_STUDENT_ID,
            savedHomeworkId = HomeworkDestination.ARG_HOMEWORK_ID_NONE,
        )
        advanceUntilIdle()

        assertTrue(
            "学生端未指定学生（studentId = 0）时不得按「某个学生」整份纠正",
            fixture.scheduler.scheduledDaily.isEmpty(),
        )
        assertEquals("未指定学生哨兵必须是非正数，否则会落到兜底分支", 0L, AppDestination.UNSPECIFIED_STUDENT_ID)
    }

    // ---- B. 行为：按 id 精确性（互斥的行为级反证）----

    @Test
    fun `有回抛 id 时不整份纠正：同学生名下阶段作业不产生逐日闹钟`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        // 同一学生名下的阶段作业：若走整份纠正，它必定被排上逐日闹钟
        fixture.put(stageHomework(id = 8L, range = StageRange.ONE_WEEK, startEpochDay = today))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 7L)
        advanceUntilIdle()

        assertEquals(
            "被保存作业应按 id 精确同步（与时间设定页保存同口径）",
            BASE + MINUTE - LEAD,
            fixture.scheduler.lastScheduledFor(7L)?.triggerAtMillis,
        )
        assertTrue(
            "有回抛 id 时不得顺带整份纠正（阶段作业不应被排上任何逐日闹钟）",
            fixture.scheduler.scheduledDaily.isEmpty(),
        )
        assertNull("未被保存的作业不应被设置提醒", fixture.scheduler.lastScheduledFor(8L))
    }

    @Test
    fun `回抛 id 对应作业不存在时只取消该 id 且不误伤其它作业`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 999L)
        advanceUntilIdle()

        assertTrue(
            "作业已不存在时按 id 取消其既有闹钟（幂等，无闹钟时为无操作）",
            fixture.scheduler.cancelled.contains(999L),
        )
        assertTrue("不得为该 id 设置任何闹钟", fixture.scheduler.scheduled.isEmpty())
        assertNull("同学生名下其它作业不得被顺带同步", fixture.scheduler.lastScheduledFor(7L))
    }

    @Test
    fun `回抛 id 为极限值 Long_MAX_VALUE 时静默降级且不误伤其它作业`() = runTest {
        val fixture = fixture()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        val failure = runCatching {
            fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = Long.MAX_VALUE)
        }.exceptionOrNull()
        advanceUntilIdle()

        assertNull("极限 id 也不得向调用方抛异常（保存主流程不受影响）", failure)
        assertTrue(
            "极限 id 走「作业不存在 → 取消」路径，取消仍精确命中该 id",
            fixture.scheduler.cancelled.contains(Long.MAX_VALUE),
        )
        assertNull("其它作业不得被顺带同步", fixture.scheduler.lastScheduledFor(7L))
    }

    @Test
    fun `同一作业重复保存同步结果一致且不影响其它作业`() = runTest {
        val fixture = fixture()
        val today = todayEpochDay()
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(stageHomework(id = 8L, range = StageRange.ONE_WEEK, startEpochDay = today))

        repeat(2) {
            fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 7L)
            advanceUntilIdle()
        }

        val triggers = fixture.scheduler.scheduled.filter { it.homeworkId == 7L }.map { it.triggerAtMillis }.distinct()
        assertEquals(
            "重复保存同步应重设同一请求码（结果一致，不产生第二种触发时刻）",
            listOf(BASE + MINUTE - LEAD),
            triggers,
        )
        assertTrue("重复同步不得把同学生名下其它作业（阶段作业）卷进来", fixture.scheduler.scheduledDaily.isEmpty())
    }

    @Test
    fun `保存同步链路的调度器异常被静默降级且不外抛`() = runTest {
        val repository = FakeHomeworkRepository()
        repository.put(
            timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )
        val scheduler = ThrowingAlarmScheduler()
        val coordinator = HomeworkReminderCoordinator(
            homeworkRepository = repository,
            alarmScheduler = scheduler,
            clock = MutableClock(BASE),
            zoneId = TimerTestEnv.ZONE,
            scheduleStore = DataStoreTimerReminderScheduleStore(FakeKeyValueStore()),
        )
        val dispatcher = ReminderSyncDispatcher(coordinator, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        val syncFailure = runCatching {
            dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 7L)
        }.exceptionOrNull()
        val cancelFailure = runCatching {
            dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 999L)
        }.exceptionOrNull()
        advanceUntilIdle()

        assertNull("调度器抛异常不得外抛给调用方（保存主流程静默降级）", syncFailure)
        assertNull("取消链路抛异常同样不得外抛", cancelFailure)
        assertTrue("同步调用确实透传到调度器（记录写于抛异常之前）", scheduler.scheduled.contains(7L))
        assertTrue("取消调用确实透传到调度器", scheduler.cancelled.contains(999L))
    }

    // ---- 夹具与源码解析工具（与既有契约测试同一口径）----

    private class Fixture(
        val repository: FakeHomeworkRepository,
        val scheduler: FakeAlarmScheduler,
        val dispatcher: ReminderSyncDispatcher,
    ) {
        fun put(item: HomeworkItem) {
            repository.put(item)
        }
    }

    /** 真实协调器 + 替身闹钟 + 内存仓库 + 内存登记表 + 可注入作用域：链路与生产一致 */
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
     * 录入页注册块：按「常量表达式 → 常量值」解析定位。
     * （这正是 HomeworkSavedReminderSyncTest 原先写错的定位口径——它按常量**值**字面量匹配，必然失败。）
     */
    private fun entryRouteBlock(): String? = blockForRoute(HomeworkDestination.ENTRY)

    /** 模板页注册块（同名常量表达式注册） */
    private fun templateRouteBlock(): String? = blockForRoute(HomeworkDestination.TEMPLATE)

    /** 按常量值定位注册块：解析 route = XxxDestination.YYY 表达式后与目标模板比较 */
    private fun blockForRoute(template: String): String? =
        splitComposableBlocks(navHostSource).firstOrNull { block ->
            val expression = Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]")
                .find(block)
                ?.groupValues
                ?.get(1)
                ?: return@firstOrNull false
            routeConstantValue(expression) == template
        }

    /** 把注册的 route 常量表达式解析为其常量值（仅列出本文件需要比对的路由） */
    private fun routeConstantValue(expression: String): String? = when (expression) {
        "HomeworkDestination.ENTRY" -> HomeworkDestination.ENTRY
        "HomeworkDestination.TEMPLATE" -> HomeworkDestination.TEMPLATE
        else -> null
    }

    /** 取接线层某方法的完整方法体（按花括号配平切片） */
    private fun dispatcherMethodBody(name: String): String? {
        val match = Regex("fun\\s+" + name + "\\s*\\(").find(dispatcherSource) ?: return null
        val open = dispatcherSource.indexOf('{', match.range.first)
        if (open < 0) return null
        val end = balancedEnd(dispatcherSource, open, '{', '}') ?: return null
        return dispatcherSource.substring(open, end + 1)
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 " + relative, file)
        return file!!.readText()
    }

    /** 切出每个 composable(...) { ... } 注册块（括号/花括号配平，跳过字符串字面量与注释） */
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

    /** 先记录再抛异常的调度器：验证保存同步链路失败静默降级（接口无默认实现，须逐个覆写） */
    private class ThrowingAlarmScheduler : HomeworkAlarmScheduler {

        val scheduled = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun schedule(
            homeworkId: Long,
            content: String,
            triggerAtMillis: Long,
        ): AlarmScheduleResult {
            scheduled += homeworkId
            throw IllegalStateException("调度器不可用")
        }

        override fun cancel(homeworkId: Long) {
            cancelled += homeworkId
            throw IllegalStateException("调度器不可用")
        }

        override fun scheduleDaily(
            homeworkId: Long,
            epochDay: Long,
            content: String,
            triggerAtMillis: Long,
        ): AlarmScheduleResult {
            scheduled += homeworkId
            throw IllegalStateException("调度器不可用")
        }

        override fun cancelDaily(homeworkId: Long, epochDay: Long) {
            cancelled += homeworkId
            throw IllegalStateException("调度器不可用")
        }
    }

    private companion object {

        /** 固定时钟（与 timer/homework 测试同一基准） */
        const val BASE = TimerTestEnv.FIXED_MILLIS

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE

        /** 提醒提前量（与生产规则同源，不写死数字） */
        const val LEAD = TimerConstants.REMINDER_LEAD_MILLIS

        /** 作业归属学生（timer 测试环境固定的学生 id = 2，为正数，属家长会话兜底分支） */
        const val STUDENT_ID = TimerTestEnv.STUDENT_ID

        /** 阶段作业的每日截止时刻（到点提醒的钟面） */
        val DAILY_TIME: LocalTime = LocalTime.of(21, 0)
    }
}