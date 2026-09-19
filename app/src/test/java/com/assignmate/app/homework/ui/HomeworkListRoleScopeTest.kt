package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单页角色口径 / 分组 / 行每日维度单测（纯函数，[HomeworkListRoleScope]）：
 *
 * - 学生：只看当天（当天作业 + 今天落在阶段范围内的阶段作业）；阶段开始前不显示、结束后移出；
 * - 家长：看全部；全部天完成 → 已完成历史（折叠）；阶段结束仍有未完成天 → 已结束 + 未完成天数；
 * - 行：今日状态文案、阶段进度文案、今天已完成时不再重复完成（改开「撤销完成」）、
 *   进行区不渲染未完成天数文案。**「今天缺卡」不是可达状态**（当天到点后仍可完成，跨入次日才判未完成），
 *   故不存在针对它的行级分支与提示文案。
 */
class HomeworkListRoleScopeTest {

    // ---- 角色口径 ----

    @Test
    fun `学生只看当天作业与阶段覆盖中的阶段作业`() {
        val items = listOf(
            todayItem(id = 1L),
            stage(id = 2L, startEpochDay = TODAY - 1),
            stage(id = 3L, startEpochDay = TODAY + 1),
            stage(id = 4L, startEpochDay = TODAY - 8),
        )

        val visible = HomeworkListRoleScope.visibleForDay(
            items = items,
            role = Role.STUDENT,
            todayEpochDay = TODAY,
            zoneId = ZONE,
        )

        assertEquals(listOf(1L, 2L), visible.map { it.id })
    }

    @Test
    fun `学生看不到非当天创建的当天作业`() {
        val visible = HomeworkListRoleScope.visibleForDay(
            items = listOf(
                todayItem(id = 9L, createdEpochDay = TODAY - 1),
                todayItem(id = 10L, createdEpochDay = TODAY),
            ),
            role = Role.STUDENT,
            todayEpochDay = TODAY,
            zoneId = ZONE,
        )

        assertEquals(listOf(10L), visible.map { it.id })
    }

    @Test
    fun `家长看到全部作业含阶段前与已结束`() {
        val items = listOf(
            todayItem(id = 1L),
            stage(id = 2L, startEpochDay = TODAY - 8),
            stage(id = 3L, startEpochDay = TODAY + 1),
        )

        val visible = HomeworkListRoleScope.visibleForDay(
            items = items,
            role = Role.PARENT,
            todayEpochDay = TODAY,
            zoneId = ZONE,
        )

        assertEquals(items, visible)
    }

    @Test
    fun `无会话时可见范围为空`() {
        assertTrue(
            HomeworkListRoleScope.visibleForDay(
                items = listOf(todayItem()),
                role = null,
                todayEpochDay = TODAY,
                zoneId = ZONE,
            ).isEmpty(),
        )
    }

    // ---- 家长分组 ----

    @Test
    fun `全部天完成归入已完成历史`() {
        val item = stage(id = 1L, startEpochDay = TODAY - 6)
        val records = (0L until 7L).map { offset ->
            record(TODAY - 6 + offset, HomeworkDayStatus.COMPLETED)
        }

        val groups = HomeworkListRoleScope.group(listOf(item), { records }, TODAY, ZONE)

        assertTrue(groups.main.isEmpty())
        assertEquals(listOf(1L), groups.completedHistory.map { it.id })
        assertTrue(groups.ended.isEmpty())
    }

    @Test
    fun `阶段结束有未完成天归入已结束并标注未完成天数`() {
        val item = stage(id = 7L, startEpochDay = TODAY - 8)
        val records = (0L until 5L).map { offset ->
            record(TODAY - 8 + offset, HomeworkDayStatus.COMPLETED)
        }

        val groups = HomeworkListRoleScope.group(listOf(item), { records }, TODAY, ZONE)

        assertTrue(groups.main.isEmpty())
        assertEquals(listOf(7L), groups.ended.map { it.id })
        assertEquals(2, groups.endedMissedDayCounts[7L])
        assertTrue(groups.completedHistory.isEmpty())
        assertEquals(
            2,
            HomeworkListRoleScope.missedDayCount(item, records, TODAY, ZONE),
        )
    }

    @Test
    fun `进行中的阶段与当天作业留在进行区`() {
        val items = listOf(
            todayItem(id = 1L),
            stage(id = 2L, startEpochDay = TODAY - 2),
        )
        val records = listOf(record(TODAY - 2, HomeworkDayStatus.COMPLETED))

        val groups = HomeworkListRoleScope.group(items, { records }, TODAY, ZONE)

        assertEquals(listOf(1L, 2L), groups.main.map { it.id })
        assertTrue(groups.completedHistory.isEmpty())
        assertTrue(groups.ended.isEmpty())
    }

    @Test
    fun `当天作业完成归入已完成历史`() {
        val done = todayItem(id = 5L, status = HomeworkStatus.COMPLETED)
        val groups = HomeworkListRoleScope.group(listOf(done), { emptyList() }, TODAY, ZONE)

        assertEquals(listOf(5L), groups.completedHistory.map { it.id })
    }

    @Test
    fun `阶段最后一天尚未完成时不归入已完成历史`() {
        val item = stage(id = 3L, startEpochDay = TODAY - 6)
        // 前 6 天已完成，最后一天（今天）尚未完成
        val records = (0L until 6L).map { offset ->
            record(TODAY - 6 + offset, HomeworkDayStatus.COMPLETED)
        }

        val groups = HomeworkListRoleScope.group(listOf(item), { records }, TODAY, ZONE)

        assertEquals(listOf(3L), groups.main.map { it.id })
        assertTrue(groups.completedHistory.isEmpty())
    }

    // ---- 行每日维度 ----

    @Test
    fun `阶段行给出今日状态与进度文案`() {
        val item = stage(startEpochDay = TODAY - 2, deadlineTime = LocalTime.of(21, 0))
        val records = listOf(
            record(TODAY - 2, HomeworkDayStatus.COMPLETED),
            record(TODAY - 1, HomeworkDayStatus.COMPLETED),
        )

        val timeline = HomeworkListRoleScope.rowTimeline(item, records, TODAY, ZONE)

        assertEquals(HomeworkDayState.PENDING, timeline.todayState)
        assertEquals("今日：待完成", timeline.todayStateText)
        assertEquals("阶段进度：已打卡 2/7 天", timeline.progressText)
        assertEquals("每天 21:00 截止", timeline.dailyDeadlineText)
        assertTrue(timeline.coverageText!!.contains("每天到点截止"))
        assertTrue(timeline.coverageText!!.contains(LocalDate.ofEpochDay(TODAY - 2).toString()))
        assertTrue(timeline.coverageText!!.contains(LocalDate.ofEpochDay(TODAY + 4).toString()))
    }

    @Test
    fun `阶段行今日已完成时文案为已完成`() {
        val item = stage(startEpochDay = TODAY - 1)
        val records = listOf(record(TODAY, HomeworkDayStatus.COMPLETED))

        val timeline = HomeworkListRoleScope.rowTimeline(item, records, TODAY, ZONE)

        assertEquals("今日：已完成", timeline.todayStateText)
        assertEquals("阶段进度：已打卡 1/7 天", timeline.progressText)
    }

    @Test
    fun `阶段行今日未完成时可完成与排定且昨日缺失只计入进度`() {
        val item = stage(startEpochDay = TODAY - 2, deadlineTime = LocalTime.of(8, 0))
        assertEquals("阶段起始日应从 deadline 编码还原", TODAY - 2, item.stageStartEpochDay)

        // 今天（仍在阶段内）未完成 → 待完成且可完成（到点后仍可完成）
        val timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY, ZONE)
        assertEquals("今日：待完成", timeline.todayStateText)
        assertEquals("阶段进度：已打卡 0/7 天", timeline.progressText)
        assertEquals("已过去且未完成的天计为缺卡", 2, timeline.stageProgress!!.missedDays)

        val todayRow = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = timeline,
        )
        assertTrue("今天在阶段窗口内且未完成 → 可完成今天", todayRow.canComplete)
        assertTrue("今天在阶段窗口内 → 可排定时间", todayRow.canSchedule)
        assertFalse("今天尚未完成 → 不开放撤销完成", todayRow.canReopen)

        // 昨日视角：该自然日已过去且未完成 → 由每天详情推导为缺卡（只体现在阶段进度里，
        // 「今天」永远不会是缺卡态——当天到点后仍可完成，跨入次日才判未完成）
        val yesterdayOutcome = StageDayRecords.outcomeFor(
            item = item,
            records = emptyList(),
            epochDay = TODAY - 1,
            todayEpochDay = TODAY,
            zoneId = ZONE,
        )!!
        assertEquals("已过去的未完成天判为缺卡", HomeworkDayState.MISSED, yesterdayOutcome.state)
        assertEquals(2, StageDayRecords.progressOf(item, emptyList(), TODAY, ZONE)!!.missedDays)
    }

    @Test
    fun `阶段行今日已完成时不再开放完成改为开放撤销完成`() {
        val item = stage(startEpochDay = TODAY - 2)
        val records = listOf(record(TODAY, HomeworkDayStatus.COMPLETED))

        val row = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, records, TODAY, ZONE),
        )

        assertEquals("今日：已完成", row.todayStateText)
        assertEquals("阶段进度：已打卡 1/7 天", row.stageProgressText)
        assertFalse("今天已完成 → 不再重复开放「标记完成」", row.canComplete)
        assertFalse("今天已完成 → 不再开放「开始作业」", row.canStart)
        assertTrue("今天已完成 → 开放「撤销完成」（撤销今天的完成记录）", row.canReopen)
    }

    @Test
    fun `阶段窗口外的时间排定入口被关闭`() {
        val item = stage(startEpochDay = TODAY - 2)

        // 今天在阶段内 → 可排定时间
        val inWindow = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY, ZONE),
        )
        assertEquals(HomeworkDayState.PENDING, inWindow.timeline.todayState)
        assertTrue("当天仍在阶段内，应可排定时间", inWindow.canSchedule)
        assertTrue("当天仍在阶段内，今天可完成", inWindow.canComplete)

        // 阶段尚未开始的将来日（今天不在阶段覆盖范围内）→ 不开放排定与完成
        val notArrived = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY - 3, ZONE),
        )
        assertEquals(HomeworkDayState.NOT_ARRIVED, notArrived.timeline.todayState)
        assertFalse("阶段开始前不应开放时间排定", notArrived.canSchedule)
        assertFalse("阶段开始前没有「今天的作业」可完成", notArrived.canComplete)

        // 阶段已结束（今天已越过覆盖末日）→ 同样不开放（该口子在早先版本里是「缺卡过去天」视角，
        // 而「今天缺卡」按缺卡规则不可达，故统一按窗口外表达）
        val ended = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY + 5, ZONE),
        )
        assertEquals(HomeworkDayState.NOT_ARRIVED, ended.timeline.todayState)
        assertFalse("阶段结束后不应开放时间排定", ended.canSchedule)
        assertFalse("阶段结束后没有「今天的作业」可完成", ended.canComplete)
    }

    @Test
    fun `当天作业的时间排定不受阶段窗口逻辑影响`() {
        val today = todayItem(status = HomeworkStatus.PENDING)
        // 每日数据缺失（默认空投影）时也不误伤：当天作业仍可排定
        val withoutTimeline = today.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
        )
        assertTrue(withoutTimeline.canSchedule)

        val withTimeline = today.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(today, emptyList(), TODAY, ZONE),
        )
        assertTrue(withTimeline.canSchedule)
        assertEquals("今日：待完成", withTimeline.todayStateText)
    }

    @Test
    fun `阶段行今日可完成时对学生放行标记完成`() {
        val item = stage(startEpochDay = TODAY - 1)
        val row = item.toRowUiState(
            role = Role.STUDENT,
            sessionStudentId = STUDENT_ID,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY, ZONE),
        )

        assertTrue(row.canComplete)
    }

    @Test
    fun `当天行今日状态由状态列映射且无阶段进度`() {
        val pendingTimeline =
            HomeworkListRoleScope.rowTimeline(todayItem(status = HomeworkStatus.PENDING), emptyList(), TODAY, ZONE)
        assertEquals("今日：待完成", pendingTimeline.todayStateText)
        assertNull(pendingTimeline.progressText)
        assertNull(pendingTimeline.dailyDeadlineText)

        assertEquals(
            "今日：已完成",
            HomeworkListRoleScope.rowTimeline(todayItem(status = HomeworkStatus.COMPLETED), emptyList(), TODAY, ZONE)
                .todayStateText,
        )
        assertEquals(
            "今日：未开始",
            HomeworkListRoleScope.rowTimeline(todayItem(), emptyList(), TODAY, ZONE).todayStateText,
        )
    }

    @Test
    fun `已结束分组带出未完成天数文案`() {
        val item = stage(id = 7L, startEpochDay = TODAY - 8)
        val row = item.toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = false,
            canMoveDown = false,
            timeline = HomeworkListRoleScope.rowTimeline(item, emptyList(), TODAY, ZONE),
            section = HomeworkListSection.ENDED,
            missedDayCount = 7,
        )

        assertEquals(HomeworkListSection.ENDED, row.section)
        assertEquals("阶段已结束，有 7 天未完成", row.missedDaysText)
        assertNull(
            "进行区不渲染未完成天数文案",
            row.copy(section = HomeworkListSection.MAIN).missedDaysText,
        )
    }

    @Test
    fun `默认行每日维度为空不影响既有可操作性口径`() {
        val row = todayItem(status = HomeworkStatus.PENDING).toRowUiState(
            role = Role.PARENT,
            sessionStudentId = null,
            canMoveUp = true,
            canMoveDown = true,
        )

        assertNull(row.todayStateText)
        assertNull(row.stageProgressText)
        assertEquals(HomeworkListSection.MAIN, row.section)
        assertTrue(row.canComplete)
    }

    // ---- 测试工具 ----

    private fun stage(
        id: Long = 1L,
        startEpochDay: Long,
        deadlineTime: LocalTime = LocalTime.of(21, 0),
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, deadlineTime),
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.PENDING,
        createdByRole = CreatorRole.PARENT,
        createdAt = Instant.ofEpochMilli(
            HomeworkDailyDeadlineCodec.instantAt(startEpochDay, LocalTime.of(12, 0), ZONE).toEpochMilli(),
        ),
    )

    private fun todayItem(
        id: Long = 1L,
        status: HomeworkStatus = HomeworkStatus.RECORDED,
        createdEpochDay: Long = TODAY,
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "数学练习册",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = null,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = status,
        createdByRole = CreatorRole.PARENT,
        createdAt = Instant.ofEpochMilli(
            HomeworkDailyDeadlineCodec.instantAt(createdEpochDay, LocalTime.of(9, 0), ZONE).toEpochMilli(),
        ),
    )

    private fun record(epochDay: Long, status: HomeworkDayStatus): HomeworkDailyRecord =
        HomeworkDailyRecord(
            id = epochDay,
            homeworkId = 1L,
            studentId = STUDENT_ID,
            epochDay = epochDay,
            status = status,
            createdAtMillis = CLOCK_MILLIS,
        )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 10L

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        const val CLOCK_MILLIS: Long = 1_700_000_000_000L

        /** 与仓库测试固定时钟一致：2023-11-15（业务时区口径） */
        val TODAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}
