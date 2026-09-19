package com.assignmate.app.homework.ui

import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.DeleteStudentResult
import com.assignmate.app.auth.data.ParentLoginResult
import com.assignmate.app.auth.data.ParentRegisterResult
import com.assignmate.app.auth.data.RenameStudentResult
import com.assignmate.app.auth.data.StudentEnterResult
import com.assignmate.app.auth.data.UpdateVerificationCodeResult
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 时间设定页「阶段作业截止时间口径」回归单测（皋陶审查 error 修复的守护用例）。
 *
 * 缺陷背景（修复前会失败，故本类具备反向验证能力）：
 * 阶段作业（STAGE）的 deadline 已改为承载「每日时刻」的编码值，但时间设定页的本地预校验
 * 曾对**所有类型**统一按绝对时刻调用 validateDeadline(item.deadline)：于是
 * - 家长在模板页保存过阶段作业后（submitEdit 对 PARENT 必走 updateTemplate，把 deadline 落成
 *   timeOfDayCarrier ≈ 75_600_000 ms ≈ 1970-01-01T21:00Z），任何 2025/2026 的开始时间都大于它，
 *   该阶段作业在「设时间/改时间」页**永远提交不了**（误报「开始时间加预估时长超过了截止时间」）；
 * - 未编辑过的阶段作业（编码值 ≈ 起始日+1 的 UTC 当日时刻）在阶段第 3 天起也被误拦。
 * 修复后 UI 与仓库同源：STAGE 走「该天每日截止时刻」口径、TODAY 保持绝对 deadline 口径。
 *
 * 覆盖：阶段第 N 天（含起始日+2 / +6）仍能提交；家长编辑落 timeOfDayCarrier 后仍能提交；
 * 超过每日截止时刻仍被本地拦下；当天作业的绝对 deadline 语义不回归。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkTimeSetStageDeadlineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 修复前会失败的用例（反向验证能力） ----

    @Test
    fun `阶段作业在起始日加两天仍能提交设置时间`() = runTest {
        // 编码值口径：stageStartEpochDay = 起始日，dailyDeadlineTime = 21:00
        val item = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        val day = EPOCH_DAY + 2L
        assertEquals(EPOCH_DAY, item.stageStartEpochDay)
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)

        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, day, "16:00", "30")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals("阶段第 3 天的常规安排应可提交", 1, repositoryOf(viewModel).scheduleCalls.size)
        assertNull(viewModel.uiState.value.timeError)
    }

    @Test
    fun `阶段作业在起始日加六天仍能提交设置时间`() = runTest {
        val item = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, EPOCH_DAY + 6L, "17:00", "60")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repositoryOf(viewModel).scheduleCalls.size)
        assertNull(viewModel.uiState.value.timeError)
    }

    @Test
    fun `家长编辑过阶段作业后每日时刻载体仍能提交`() = runTest {
        // 模拟「家长在模板页保存过」：deadline 落为 timeOfDayCarrier（约 1970-01-01T21:00Z）
        val carrier = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0))
        assertTrue("载体应远小于 2025 年的开始时刻", carrier.toEpochMilli() < 100_000_000L)
        val item = stageWithDeadline(carrier)

        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, EPOCH_DAY + 3L, "16:00", "30")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            "修复前：任何 2026 年开始时刻都大于载体，阶段作业永远提交不了",
            1,
            repositoryOf(viewModel).scheduleCalls.size,
        )
        assertNull(viewModel.uiState.value.timeError)
    }

    // ---- 仍须被拦下的边界（修复不得放松每日时刻约束） ----

    @Test
    fun `阶段作业跨过每日截止时刻仍被本地拦下且不触达仓库`() = runTest {
        val item = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        // 20:30 开始 60 分钟 = 21:30，跨过 21:00 每日截止时刻（当天仍是同一天，不涉及跨日）
        setForm(viewModel, EPOCH_DAY + 1L, "20:30", "60")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue("越限必须给出可读提示", viewModel.uiState.value.timeError != null)
        assertEquals("越限不得触达仓库", 0, repositoryOf(viewModel).scheduleCalls.size)
    }

    @Test
    fun `阶段作业跨日时长按绝对瞬时判定被拦下`() = runTest {
        // 每日 21:00 截止；23:00 开始 120 分钟会跨到次日——钟面比较会误判通过，绝对瞬时比较必须拦下
        val item = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, EPOCH_DAY + 2L, "23:00", "120")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.timeError != null)
        assertEquals(0, repositoryOf(viewModel).scheduleCalls.size)
    }

    @Test
    fun `阶段作业正好在每日截止时刻结束视为通过`() = runTest {
        val item = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, EPOCH_DAY + 4L, "20:00", "60")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repositoryOf(viewModel).scheduleCalls.size)
    }

    // ---- 当天作业（绝对 deadline 语义）不回归 ----

    @Test
    fun `当天作业仍按绝对截止时刻预校验`() = runTest {
        val deadline = HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY, LocalTime.of(18, 0), zone)
        val item = todayItem(deadline)

        val okViewModel = viewModel(item)
        okViewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(okViewModel, EPOCH_DAY, "17:00", "60")
        okViewModel.onSubmit()
        advanceUntilIdle()
        assertEquals("17:00 + 60min = 18:00 正好等于绝对 deadline，应通过", 1, repositoryOf(okViewModel).scheduleCalls.size)

        val badViewModel = viewModel(item)
        badViewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(badViewModel, EPOCH_DAY, "17:30", "60")
        badViewModel.onSubmit()
        advanceUntilIdle()
        assertTrue("超过绝对 deadline 必须被拦下", badViewModel.uiState.value.timeError != null)
        assertEquals(0, repositoryOf(badViewModel).scheduleCalls.size)
    }

    @Test
    fun `当天作业无截止时间时不受约束`() = runTest {
        val item = todayItem(deadline = null)
        val viewModel = viewModel(item)
        viewModel.start(STUDENT_ID, item.id)
        advanceUntilIdle()
        setForm(viewModel, EPOCH_DAY, "23:00", "120")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(1, repositoryOf(viewModel).scheduleCalls.size)
    }

    // ---- 领域契约（供各消费方统一解释 deadline 一列） ----

    @Test
    fun `absoluteDeadlineAt 按类型返回当日绝对截止时刻`() {
        val stageItem = stage(deadlineTime = LocalTime.of(21, 0), startEpochDay = EPOCH_DAY)
        assertEquals(
            "阶段作业：任一天都取该天的每日截止时刻",
            HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY + 2L, LocalTime.of(21, 0), zone),
            stageItem.absoluteDeadlineAt(EPOCH_DAY + 2L, zone),
        )

        val deadline = HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY, LocalTime.of(18, 0), zone)
        assertEquals(
            "当天作业：任何一天都取同一个绝对时刻",
            deadline,
            todayItem(deadline).absoluteDeadlineAt(EPOCH_DAY + 5L, zone),
        )
    }

    @Test
    fun `未设每日截止时刻的阶段作业不受当日时刻约束`() {
        val item = stageWithDeadline(deadline = null)
        assertNull(item.dailyDeadlineTime)
        assertEquals(
            "未设每日时刻 = 不产生额外约束（校验入口直接放行，跨日时长同样不拦）",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(
                item = item,
                startMillis = HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY, LocalTime.of(23, 0), zone)
                    .toEpochMilli(),
                estimatedMinutes = 30,
                zoneId = zone,
            ),
        )
        assertNull(
            "取数口径统一为 null（缺每日时刻即该天无截止约束，与计时侧 TimerCalculations 同口径）",
            item.absoluteDeadlineAt(EPOCH_DAY, zone),
        )
    }

    @Test
    fun `阶段起始日兜底使用注入的业务时区而非 systemDefault`() {
        // 创建时刻是业务时区（Asia/Shanghai）的当天 09:00：编码无法还原时回退创建日
        val item = stageWithDeadline(deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)))
        assertNull("载体不承载起始日，stageStartEpochDay 应为 null", item.stageStartEpochDay)
        assertEquals(
            "兜底取业务时区口径的创建日",
            EPOCH_DAY,
            item.stageStartEpochDayOr(zone),
        )
    }

    // ---- 测试工具 ----

    /** 每次构造都记录仓库替身，供断言「是否触达仓库」 */
    private var lastRepository: FakeScheduleRepository = FakeScheduleRepository(null)

    private fun viewModel(item: HomeworkItem): HomeworkTimeSetViewModel {
        val repository = FakeScheduleRepository(item)
        lastRepository = repository
        return HomeworkTimeSetViewModel(repository, FakeAuthRepository(studentSession()), FixedClock(), zone)
    }

    private fun repositoryOf(@Suppress("UNUSED_PARAMETER") viewModel: HomeworkTimeSetViewModel): FakeScheduleRepository =
        lastRepository
    private fun setForm(
        viewModel: HomeworkTimeSetViewModel,
        epochDay: Long,
        time: String,
        minutes: String,
    ) {
        viewModel.onStartDateChange(LocalDate.ofEpochDay(epochDay).toString())
        viewModel.onStartTimeChange(time)
        viewModel.onEstimatedMinutesChange(minutes)
    }

    private fun stage(
        deadlineTime: LocalTime,
        startEpochDay: Long,
    ): HomeworkItem = stageWithDeadline(
        HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, deadlineTime),
    )

    private fun stageWithDeadline(deadline: Instant?): HomeworkItem = HomeworkItem(
        id = STAGE_ID,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = deadline,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY, LocalTime.of(9, 0), zone),
    )

    private fun todayItem(deadline: Instant?): HomeworkItem = HomeworkItem(
        id = TODAY_ID,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "数学练习册",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = deadline,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = HomeworkDailyDeadlineCodec.instantAt(EPOCH_DAY, LocalTime.of(9, 0), zone),
    )

    private fun studentSession(): SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val STAGE_ID = 11L
        const val TODAY_ID = 12L

        /** 业务自然日基准：2023-11-15（与既有时间设定页用例一致） */
        val EPOCH_DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }

    /** 固定时钟（业务时区 2023-11-15） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = 1_700_000_000_000L
    }

    /** 时间设定页所需能力的仓库替身：只实现 getHomework 与 updateSchedule（其余能力误用即失败） */
    private class FakeScheduleRepository(private val item: HomeworkItem?) : HomeworkRepository {

        val scheduleCalls = mutableListOf<Triple<Long, Instant, Int>>()

        override suspend fun getHomework(homeworkId: Long): HomeworkItem? = item?.takeIf { it.id == homeworkId }

        override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> = flowOf(emptyList())

        override suspend fun listHomework(studentId: Long): List<HomeworkItem> = emptyList()

        override suspend fun updateSchedule(
            homeworkId: Long,
            startTime: Instant,
            estimatedMinutes: Int,
            sessionRole: Role,
        ): ScheduleUpdateResult {
            scheduleCalls += Triple(homeworkId, startTime, estimatedMinutes)
            return ScheduleUpdateResult.Success(item!!)
        }

        override suspend fun addHomework(
            template: HomeworkTemplate,
            studentId: Long,
        ): AddHomeworkResult = unsupported()

        override suspend fun reorderHomework(
            homeworkId: Long,
            direction: ReorderDirection,
            sessionRole: Role,
        ): HomeworkOrderResult = unsupported()

        override suspend fun moveHomeworkTo(
            homeworkId: Long,
            targetIndex: Int,
            sessionRole: Role,
        ): HomeworkOrderResult = unsupported()

        override suspend fun clearSchedule(
            homeworkId: Long,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun updateContent(
            homeworkId: Long,
            content: String,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun updateTemplate(
            homeworkId: Long,
            type: HomeworkType,
            stageRange: StageRange?,
            deadline: Instant?,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun deleteHomework(
            homeworkId: Long,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("本用例不涉及该仓库能力")
    }

    /** auth 仓库替身：仅提供会话读取，其余能力误用即失败 */
    private class FakeAuthRepository(private val session: SessionState) : AuthRepository {

        private val sessionFlow = MutableStateFlow(session)

        override fun observeSession(): Flow<SessionState> = sessionFlow

        override suspend fun currentSession(): SessionState = sessionFlow.value

        override suspend fun logout() {
            sessionFlow.value = SessionState.NONE
        }

        override suspend fun listStudents(parentId: Long): List<Student> = emptyList()

        override suspend fun getStudent(studentId: Long): Student? = Student(
            id = studentId,
            parentAccountId = PARENT_ID,
            name = "小明",
            verificationCode = "123456",
            createdAt = Instant.ofEpochMilli(0L),
        )

        override suspend fun registerParent(account: String, password: String): ParentRegisterResult =
            unsupported()

        override suspend fun loginParent(account: String, password: String): ParentLoginResult =
            unsupported()

        override suspend fun addStudent(parentId: Long, name: String): AddStudentResult = unsupported()

        override suspend fun renameStudent(studentId: Long, newName: String): RenameStudentResult =
            unsupported()

        override suspend fun deleteStudent(studentId: Long): DeleteStudentResult = unsupported()

        override suspend fun resetStudentVerificationCode(
            studentId: Long,
        ): UpdateVerificationCodeResult = unsupported()

        override suspend fun updateStudentVerificationCode(
            studentId: Long,
            newCode: String,
        ): UpdateVerificationCodeResult = unsupported()

        override suspend fun enterAsStudent(
            parentAccount: String,
            verificationCode: String,
        ): StudentEnterResult = unsupported()

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("本用例不涉及该 auth 能力")
    }
}
