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
 * 离朱补充探测（本轮 homework 审查修复，warning 2 / warning 3）：
 * 清单页角色口径的**时区注入**与行投影的 `canSchedule` / `canComplete` 矩阵。
 *
 * 1. [HomeworkListRoleScope.visibleForDay] 四参数签名（recordsOf 死参数已删除）下的可见性：
 *    阶段起始日可还原时用编码值、不可还原时用**注入时区**回退创建日——
 *    同一创建瞬时的北京时间创建日与 UTC 创建日不同，故「阶段开始前/结束后」边界会整体平移一天；
 * 2. `toRowUiState` 的 `canSchedule`（阶段窗口约束）与 `canComplete`/`canStart`（今天是否可动手）矩阵，
 *    含「每日数据缺失（默认空投影）不误伤」；**「今天缺卡」按缺卡规则不可达**
 *    （当天到点后仍可完成，跨入次日才判未完成），故矩阵中不再有该取值，
 *    缺卡只体现在阶段进度的未完成天数上。
 * 3. [HomeworkListRoleScope.group] / [HomeworkListRoleScope.rowTimeline] 的 recordsOf 正常口径不回归。
 */
class HomeworkListScheduleWindowProbeTest {

    // ---- 1. 可见性：注释中的「区域口径透传业务时区」 ----

    @Test
    fun `不可还原编码的阶段起始日按注入时区回退创建日并整体平移一天`() {
        // 创建瞬时 2023-11-15 00:30（+08:00）= 2023-11-14T16:30Z；deadline 为时刻载体（不可还原）
        val item = stageWithCarrier(createdAt = instantAt(DAY, LocalTime.of(0, 30), SHANGHAI))

        assertNull("载体不承载起始日", item.stageStartEpochDay)
        assertEquals("UTC+8 口径：阶段自 DAY 起算", DAY, item.stageStartEpochDayOr(SHANGHAI))
        assertEquals("UTC 口径：阶段自 DAY-1 起算", DAY - 1L, item.stageStartEpochDayOr(UTC))

        // UTC+8：覆盖 DAY..DAY+6 -> DAY 可见、DAY-1 不可见、DAY+6 可见
        assertEquals(listOf(1L), visibleIds(item, DAY, SHANGHAI))
        assertTrue(visibleIds(item, DAY - 1L, SHANGHAI).isEmpty())
        assertEquals(listOf(1L), visibleIds(item, DAY + 6L, SHANGHAI))

        // UTC：覆盖 DAY-1..DAY+5 -> DAY-1 可见、DAY+6 不可见（整体平移一天）
        assertEquals(listOf(1L), visibleIds(item, DAY - 1L, UTC))
        assertTrue(visibleIds(item, DAY + 6L, UTC).isEmpty())
    }

    @Test
    fun `当天作业可见性按注入时区的创建日`() {
        // 同一创建瞬时：+08:00 口径的创建日是 DAY，UTC 口径是 DAY-1
        val item = todayItem(createdAt = instantAt(DAY, LocalTime.of(0, 30), SHANGHAI))

        assertEquals(listOf(1L), visibleIds(item, DAY, SHANGHAI))
        assertEquals(listOf(1L), visibleIds(item, DAY - 1L, UTC))
        assertTrue("UTC+8 口径下前一天不显示", visibleIds(item, DAY - 1L, SHANGHAI).isEmpty())
        assertTrue("UTC 口径下 DAY 不是归属日", visibleIds(item, DAY, UTC).isEmpty())
    }

    @Test
    fun `四参数签名保持角色口径`() {
        val items = listOf(todayItem(), stage(startEpochDay = DAY + 1))

        assertEquals(2, visibleIdsForRole(items, Role.PARENT, DAY).size)
        assertEquals("学生看不到阶段未开始的阶段作业", 1, visibleIdsForRole(items, Role.STUDENT, DAY).size)
        assertTrue(visibleIdsForRole(items, null, DAY).isEmpty())
    }

    // ---- 2. canSchedule / canComplete 矩阵 ----

    @Test
    fun `canSchedule 随今日状态矩阵变化`() {
        val stage = stage(startEpochDay = DAY - 2, status = HomeworkStatus.PENDING)

        assertTrue("今天在阶段窗口内", row(stage, HomeworkDayState.PENDING).canSchedule)
        assertTrue("今日已完成（状态列未完成）仍可改明天的时间", row(stage, HomeworkDayState.COMPLETED).canSchedule)
        assertFalse("阶段尚未开始的将来日不开放排定", row(stage, HomeworkDayState.NOT_ARRIVED).canSchedule)
        assertTrue("每日数据缺失（默认空投影）不误伤", rowWithoutTimeline(stage).canSchedule)
    }

    @Test
    fun `canSchedule 的状态与锁定口径不回归`() {
        val recorded = stage(startEpochDay = DAY, status = HomeworkStatus.RECORDED)
        val inProgress = stage(startEpochDay = DAY, status = HomeworkStatus.IN_PROGRESS)
        val completed = stage(startEpochDay = DAY, status = HomeworkStatus.COMPLETED)

        assertTrue(row(recorded, HomeworkDayState.PENDING).canSchedule)
        assertFalse("进行中锁定改时间", row(inProgress, HomeworkDayState.PENDING).canSchedule)
        assertFalse("已完成不可再排定", row(completed, HomeworkDayState.PENDING).canSchedule)
        assertFalse(
            "学生不可排定他人名下作业",
            stage(startEpochDay = DAY, status = HomeworkStatus.PENDING).toRowUiState(
                role = Role.STUDENT,
                sessionStudentId = STUDENT_ID + 1L,
                canMoveUp = false,
                canMoveDown = false,
                timeline = timeline(HomeworkDayState.PENDING),
            ).canSchedule,
        )
    }

    @Test
    fun `canComplete 与 canStart 只在今天位于阶段窗口内且尚未完成时开放`() {
        val stage = stage(startEpochDay = DAY - 2, status = HomeworkStatus.PENDING)

        assertTrue("今天在阶段窗口内且未完成 → 可完成今天", row(stage, HomeworkDayState.PENDING).canComplete)
        assertTrue("今天在阶段窗口内且未完成 → 可开始今天", row(stage, HomeworkDayState.PENDING).canStart)
        assertFalse(
            "今天已完成 → 不再重复开放「标记完成」",
            row(stage, HomeworkDayState.COMPLETED).canComplete,
        )
        assertFalse("今天已完成 → 不再开放「开始作业」", row(stage, HomeworkDayState.COMPLETED).canStart)
        assertTrue(
            "今天已完成 → 开放「撤销完成」（撤销今天的完成记录）",
            row(stage, HomeworkDayState.COMPLETED).canReopen,
        )
        assertFalse(
            "阶段尚未开始时没有「今天的作业」可完成（窗口外）",
            row(stage, HomeworkDayState.NOT_ARRIVED).canComplete,
        )
        assertFalse(
            "阶段已结束时没有「今天的作业」可开始（窗口外）",
            row(stage, HomeworkDayState.NOT_ARRIVED).canStart,
        )
        assertFalse(
            "已记录需先排定时间",
            row(stage.copy(status = HomeworkStatus.RECORDED), HomeworkDayState.PENDING).canComplete,
        )
        assertTrue("每日数据缺失时不误伤既有口径", rowWithoutTimeline(stage).canComplete)
    }

    // ---- 3. group / rowTimeline 的 recordsOf 口径不回归 ----

    @Test
    fun `group 与 rowTimeline 在死参数清理后按每天详情正常分组`() {
        val stage = stage(id = 1L, startEpochDay = DAY - 6)
        val done = todayItem(id = 2L, status = HomeworkStatus.COMPLETED)
        val records = (0L until 7L).map { offset -> record(DAY - 6 + offset, HomeworkDayStatus.COMPLETED) }

        val groups = HomeworkListRoleScope.group(listOf(stage, done), { records }, DAY, SHANGHAI)

        assertEquals(listOf(1L, 2L), groups.completedHistory.map { it.id })
        assertTrue(groups.main.isEmpty())
        assertTrue(groups.ended.isEmpty())
        assertEquals(0, groups.endedMissedDayCounts[1L])

        val timeline = HomeworkListRoleScope.rowTimeline(stage, records, DAY, SHANGHAI)
        assertEquals(HomeworkDayState.COMPLETED, timeline.todayState)
        assertEquals("阶段进度：已打卡 7/7 天", timeline.progressText)
        assertTrue(timeline.coverageText!!.contains(LocalDate.ofEpochDay(DAY - 6).toString()))
        assertTrue(timeline.coverageText!!.contains(LocalDate.ofEpochDay(DAY).toString()))
        assertFalse("阶段最后一天已完成 -> 今天无需再完成", timeline.isTodayActionable)
        assertNull("当天作业没有阶段进度", HomeworkListRoleScope.rowTimeline(done, emptyList(), DAY, SHANGHAI).progressText)
    }

    // ---- 测试工具 ----

    private fun visibleIds(item: HomeworkItem, todayEpochDay: Long, zone: ZoneId): List<Long> =
        HomeworkListRoleScope.visibleForDay(listOf(item), Role.STUDENT, todayEpochDay, zone).map { it.id }

    private fun visibleIdsForRole(items: List<HomeworkItem>, role: Role?, todayEpochDay: Long): List<Long> =
        HomeworkListRoleScope.visibleForDay(items, role, todayEpochDay, SHANGHAI).map { it.id }

    private fun timeline(state: HomeworkDayState): HomeworkRowTimeline = HomeworkRowTimeline(
        todayState = state,
        stageProgress = null,
        isTodayActionable = state.isActionable,
    )

    private fun row(item: HomeworkItem, state: HomeworkDayState): HomeworkRowUiState = item.toRowUiState(
        role = Role.PARENT,
        sessionStudentId = null,
        canMoveUp = false,
        canMoveDown = false,
        timeline = timeline(state),
    )

    private fun rowWithoutTimeline(item: HomeworkItem): HomeworkRowUiState = item.toRowUiState(
        role = Role.PARENT,
        sessionStudentId = null,
        canMoveUp = false,
        canMoveDown = false,
    )

    private fun instantAt(epochDay: Long, time: LocalTime, zone: ZoneId): Instant =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, zone)

    /** 编码可还原起始日的阶段作业 */
    private fun stage(
        id: Long = 1L,
        startEpochDay: Long,
        status: HomeworkStatus = HomeworkStatus.PENDING,
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, LocalTime.of(21, 0)),
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = status,
        createdByRole = CreatorRole.PARENT,
        createdAt = instantAt(startEpochDay, LocalTime.of(9, 0), SHANGHAI),
    )

    /** 编码不可还原（每日时刻载体）的阶段作业 */
    private fun stageWithCarrier(createdAt: Instant): HomeworkItem = HomeworkItem(
        id = 1L,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.PENDING,
        createdByRole = CreatorRole.PARENT,
        createdAt = createdAt,
    )

    private fun todayItem(
        id: Long = 1L,
        status: HomeworkStatus = HomeworkStatus.RECORDED,
        createdAt: Instant = instantAt(DAY, LocalTime.of(9, 0), SHANGHAI),
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
        createdAt = createdAt,
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
        const val CLOCK_MILLIS: Long = 1_700_000_000_000L

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 与既有套件一致：业务自然日 2023-11-15 */
        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}
