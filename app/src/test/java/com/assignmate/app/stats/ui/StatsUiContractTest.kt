package com.assignmate.app.stats.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.DifficultyLevel
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.StatsCalculations
import com.assignmate.app.stats.domain.StatsConstants
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stats UI 层契约单测：路由拼装/参数解析、目标学生解析（权限口径）、失败文案映射、
 * 页面状态投影（跨页面文案一致性、进度条取值收敛），以及「仓库接口只读」这一模块契约。
 *
 * 这些断言不依赖 Compose 运行时，直接验证页面前置契约，属纯函数/纯投影测试。
 */
class StatsUiContractTest {

    // ---- 路由与参数解析 ----

    @Test
    fun `当日盘点路由按学生与日期拼装`() {
        // 缺省日期 = 「今天」哨兵，保持既有行为（与 framework 的 navArgument 缺省值一致）
        assertEquals(
            "stats/day/2?epochDay=${StatsDestination.ARG_EPOCH_DAY_TODAY}",
            StatsDestination.daySummaryRoute(2L),
        )
        assertEquals("stats/day/2?epochDay=19675", StatsDestination.daySummaryRoute(2L, DAY_EPOCH))
    }

    @Test
    fun `当日盘点路由模板含 epochDay 可选查询参数`() {
        assertTrue(StatsDestination.DAY_SUMMARY.contains("{studentId}"))
        assertTrue(StatsDestination.DAY_SUMMARY.contains("${StatsDestination.ARG_EPOCH_DAY}="))
        assertEquals("epochDay", StatsDestination.ARG_EPOCH_DAY)
    }

    @Test
    fun `当日盘点路由的日期参数可被 epochDayOf 反向解析`() {
        val route = StatsDestination.daySummaryRoute(2L, DAY_EPOCH)
        val raw = route.substringAfter("${StatsDestination.ARG_EPOCH_DAY}=")
        assertEquals(DAY_EPOCH, StatsDestination.epochDayOf(raw))
        // 哨兵经路由往返后仍解析为「今天」语义
        val sentinel = StatsDestination.daySummaryRoute(2L).substringAfter("${StatsDestination.ARG_EPOCH_DAY}=")
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf(sentinel))
    }

    @Test
    fun `单项详情路由包含学生与作业`() {
        assertEquals("stats/item/2/7", StatsDestination.itemDetailRoute(2L, 7L))
    }

    @Test
    fun `历史查询路由默认与起始日相同的单日查询`() {
        assertEquals(
            "stats/history/2?fromEpochDay=19675&toEpochDay=19675",
            StatsDestination.historyRoute(2L, DAY_EPOCH),
        )
        assertEquals(
            "stats/history/2?fromEpochDay=19674&toEpochDay=19675",
            StatsDestination.historyRoute(2L, DAY_EPOCH - 1, DAY_EPOCH),
        )
    }

    @Test
    fun `非法或缺失的参数回退到哨兵值`() {
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf(null))
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf("abc"))
        assertEquals(StatsDestination.ARG_HOMEWORK_ID_NONE, StatsDestination.homeworkIdOf(null))
        assertEquals(StatsDestination.ARG_HOMEWORK_ID_NONE, StatsDestination.homeworkIdOf(""))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf(null))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf("x"))
    }

    @Test
    fun `合法参数按原值解析`() {
        assertEquals(2L, StatsDestination.studentIdOf("2"))
        assertEquals(7L, StatsDestination.homeworkIdOf("7"))
        assertEquals(DAY_EPOCH, StatsDestination.epochDayOf("19675"))
    }

    @Test
    fun `哨兵值语义与页面分支一致`() {
        // 0 表示学生未指定（学生端取本人、家长端提示未选定）；-1 表示作业未指定，均非合法业务 id
        assertEquals(0L, StatsDestination.ARG_STUDENT_ID_NONE)
        assertEquals(-1L, StatsDestination.ARG_HOMEWORK_ID_NONE)
        assertEquals(-1L, StatsDestination.ARG_EPOCH_DAY_TODAY)
    }

    // ---- 目标学生解析（权限口径） ----

    @Test
    fun `学生会话固定取本人忽略路由`() {
        val session = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)
        assertEquals(2L, resolveStatsStudentId(session, routeStudentId = 3L))
        assertEquals(2L, resolveStatsStudentId(session, routeStudentId = 0L))
    }

    @Test
    fun `家长会话取路由指定的正数学生`() {
        val session = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
        assertEquals(3L, resolveStatsStudentId(session, routeStudentId = 3L))
        assertNull(resolveStatsStudentId(session, routeStudentId = 0L))
        assertNull(resolveStatsStudentId(session, routeStudentId = -5L))
    }

    @Test
    fun `无会话一律返回空`() {
        assertNull(resolveStatsStudentId(SessionState.NONE, routeStudentId = 3L))
        assertNull(resolveStatsStudentId(SessionState.NONE, routeStudentId = 0L))
    }

    @Test
    fun `学生会话缺少 studentId 时返回空而不放行`() {
        val session = SessionState(role = Role.STUDENT, parentId = 1L, studentId = null)
        assertNull(resolveStatsStudentId(session, routeStudentId = 3L))
    }

    // ---- 失败文案映射 ----

    @Test
    fun `每个失败原因都有面向用户的文案`() {
        StatsFailure.entries.forEach { failure ->
            val message = StatsErrorMessages.messageOf(failure)
            assertTrue("失败原因 $failure 的文案不应为空", message.isNotBlank())
        }
    }

    @Test
    fun `关键失败文案与约定一致`() {
        assertEquals("登录状态已失效，请重新进入", StatsErrorMessages.messageOf(StatsFailure.NO_ACTIVE_SESSION))
        assertEquals(
            "看不到这位同学的统计，请确认选择的学生",
            StatsErrorMessages.messageOf(StatsFailure.ACCESS_DENIED),
        )
        assertEquals("日期范围不正确，请重新选择", StatsErrorMessages.messageOf(StatsFailure.INVALID_QUERY))
        assertEquals("统计数据读取失败，请稍后重试", StatsErrorMessages.messageOf(StatsFailure.READ_FAILED))
    }

    @Test
    fun `空态与尚未开始属正常态文案而非错误`() {
        assertEquals("今天还没有作业记录哦", StatsErrorMessages.NO_DATA_TODAY)
        assertEquals("这一天还没有作业记录哦", StatsErrorMessages.NO_DATA)
        assertEquals("这段时间还没有作业记录哦", StatsErrorMessages.NO_HISTORY)
        assertEquals("还没有开始计时，暂时没有耗时数据", StatsErrorMessages.NOT_STARTED)
        assertEquals("未设定", StatsErrorMessages.ESTIMATED_UNSET)
    }

    @Test
    fun `空态文案按盘点日期口径选择`() {
        // 当日盘点页可展示任意历史日，历史日视图不应出现「今天」措辞
        assertEquals(StatsErrorMessages.NO_DATA_TODAY, StatsErrorMessages.noDataText(isToday = true))
        assertEquals(StatsErrorMessages.NO_DATA, StatsErrorMessages.noDataText(isToday = false))
        assertTrue(StatsErrorMessages.NO_DATA_TODAY.startsWith("今天"))
        assertTrue(StatsErrorMessages.NO_DATA.startsWith("这一天"))
    }

    @Test
    fun `失败结果不携带数据且原因可读`() {
        val failure: StatsResult<Any> = StatsResult.Failure(StatsFailure.ACCESS_DENIED)
        assertTrue(failure is StatsResult.Failure)
        assertEquals(StatsFailure.ACCESS_DENIED, (failure as StatsResult.Failure).reason)
    }

    // ---- 页面状态投影 ----

    @Test
    fun `完成率进度收敛到 0 到 1`() {
        val emptySummary = DaySummary(epochDay = DAY_EPOCH, totalCount = 0, completedCount = 0)
        val zero = DaySummaryUiState(summary = emptySummary)
        assertEquals(0f, zero.progress, 0f)
        assertEquals("0.0%", zero.percentText)

        val full = DaySummaryUiState(summary = daySummary(totalCount = 2, completedCount = 2))
        assertEquals(1f, full.progress, 1e-6f)
        assertEquals("100.0%", full.percentText)

        val empty = DaySummaryUiState()
        assertEquals(0f, empty.progress, 0f)
        assertEquals("暂停 0 次", empty.pauseCountText)
        assertEquals("不到 1 分钟", empty.pausedTotalText)
        assertNull(empty.mostPausedText)
        assertFalse(empty.isEmpty)
    }

    @Test
    fun `历史条目日期文案与查询范围文案同源`() {
        val single = HistoryUiState(query = HistoryQuery(DAY_EPOCH))
        assertEquals(StatsCalculations.dateText(DAY_EPOCH), single.rangeText)
        assertEquals("2023-11-14", single.rangeText)

        val range = HistoryUiState(query = HistoryQuery(DAY_EPOCH - 1, DAY_EPOCH))
        assertEquals("2023-11-13 ~ 2023-11-14", range.rangeText)

        val rows = HistoryUiState(
            query = HistoryQuery(DAY_EPOCH),
            summaries = listOf(daySummary(epochDay = DAY_EPOCH)),
        ).rows
        assertEquals(StatsCalculations.dateText(DAY_EPOCH), rows.single().dateText)
    }

    @Test
    fun `历史条目与合计文案随盘点结果一致`() {
        val state = HistoryUiState(
            query = HistoryQuery(DAY_EPOCH),
            summaries = listOf(daySummary()),
            total = daySummary(pauseCount = 3, pausedTotalMillis = 7 * MINUTE),
        )
        assertEquals("已完成 1 / 2 项（50.0%）", state.rows.single().progressText)
        assertEquals("暂停 1 次 · 2 分钟", state.rows.single().pauseText)
        assertTrue(state.totalText!!.startsWith("合计完成 1 / 2 项 · 暂停 3 次 · "))
        assertTrue(state.totalText!!.endsWith("7 分钟"))
    }

    @Test
    fun `盘点页文案随今天与历史日收敛`() {
        val today = DaySummaryUiState(
            epochDay = DAY_EPOCH,
            todayEpochDay = DAY_EPOCH,
            summary = daySummary(epochDay = DAY_EPOCH),
        )
        assertTrue(today.isToday)
        assertEquals("正在盘点今天的作业…", today.loadingText)
        assertEquals("今天的作业盘点", today.titleText)
        assertEquals("今天动过的作业（2 项）", today.itemListTitle)
        assertEquals(StatsErrorMessages.NO_DATA_TODAY, today.emptyText)

        val history = DaySummaryUiState(
            epochDay = DAY_EPOCH - 1,
            todayEpochDay = DAY_EPOCH,
            summary = daySummary(epochDay = DAY_EPOCH - 1),
        )
        assertFalse(history.isToday)
        assertEquals("2023-11-13", history.dateText)
        // 加载态、页面标题、清单卡标题、空态四处共用同一 isToday 分支，不出现「今天 / 这一天」混用
        assertEquals("正在盘点 2023-11-13 的作业…", history.loadingText)
        assertEquals("2023-11-13 的作业盘点", history.titleText)
        assertEquals("这一天动过的作业（2 项）", history.itemListTitle)
        assertEquals(StatsErrorMessages.NO_DATA, history.emptyText)
    }

    @Test
    fun `单项详情无数据时全部展示占位文案`() {
        val state = ItemDetailUiState()

        assertEquals("作业详情", state.contentText)
        assertEquals("", state.statusText)
        assertEquals(StatsErrorMessages.ESTIMATED_UNSET, state.estimatedText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.elapsedText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.pausedText)
        assertEquals("0 次", state.sessionCountText)
        assertEquals("", state.difficultyLabel)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.assessmentHint)
        assertFalse(state.hasExecution)
    }

    @Test
    fun `困难度等级标签与提示口径一致`() {
        val slow = ItemDetailUiState(detail = itemDetail(difficulty = DifficultyLevel.SLOW))
        assertEquals("偏慢", slow.difficultyLabel)
        assertTrue(DifficultyLevel.SLOW.needsAttention)

        val smooth = ItemDetailUiState(detail = itemDetail(difficulty = DifficultyLevel.SMOOTH))
        assertEquals("很顺利", smooth.difficultyLabel)
        assertFalse(DifficultyLevel.SMOOTH.needsAttention)
    }

    // ---- 模块契约：统计仓库只读 ----

    @Test
    fun `统计仓库接口只暴露读取聚合方法`() {
        val forbidden = listOf(
            "insert", "update", "delete", "save", "add", "remove", "create",
            "start", "pause", "resume", "complete", "set", "reorder", "write",
        )
        val methods = StatsRepository::class.java.declaredMethods
            .filter { Modifier.isAbstract(it.modifiers) }
            .map { it.name }

        assertEquals(3, methods.size)
        methods.forEach { name ->
            assertFalse(
                "统计仓库不应暴露写能力（不得新增数据库表）: $name",
                forbidden.any { prefix -> name.startsWith(prefix, ignoreCase = true) },
            )
            assertTrue(
                "统计仓库方法应以查询语义命名: $name",
                name.startsWith("summarize") || name.startsWith("item") || name.startsWith("history"),
            )
        }
    }

    // ---- 模块契约：历史页不保留未接线的单项详情入口 ----

    @Test
    fun `历史页不提供直达单项详情的接口与事件`() {
        // 本期不提供「历史条目直达单项详情」入口（页面从未接线）：接口与事件一并删除，
        // 防止「无人调用的事件分支」作为死代码回流（若后续要提供，需连同页面接线一起加回）。
        assertFalse(
            "历史页不应保留未接线的 openItemDetail 接口",
            HistoryViewModel::class.java.declaredMethods.any { it.name == "openItemDetail" },
        )
        assertEquals(
            "历史页一次性事件只保留「进入某日盘点」",
            listOf("OpenDaySummary"),
            HistoryEvent::class.java.declaredClasses.map { it.simpleName }.sorted(),
        )
    }

    @Test
    fun `跨度上限常量为 92 天且单日查询合法`() {
        assertEquals(92L, StatsConstants.MAX_HISTORY_DAYS)
        assertTrue(HistoryQuery(DAY_EPOCH).isValid)
        assertEquals(1L, HistoryQuery(DAY_EPOCH).spanDays)
    }

    companion object {

        /** 2023-11-14 的 UTC 纪元日 */
        const val DAY_EPOCH = 19_675L

        /** 一分钟（毫秒） */
        const val MINUTE = 60_000L
    }
}