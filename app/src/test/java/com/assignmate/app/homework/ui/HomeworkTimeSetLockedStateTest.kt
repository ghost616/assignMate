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
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 时间设定页「不可编辑」状态投影单测（深链进入进行中 / 越权作业时的兜底口径）：
 *
 * 1. 进行中锁定：读出作业后必须保留 [HomeworkTimeSetUiState.item] 并置
 *    [HomeworkTimeSetUiState.lockedWorkInProgress]，页面据此渲染作业概要 + 就地锁定提示
 *    （不再只依赖一次性 Snackbar），且表单不可编辑、提交不触达仓库；
 * 2. 执行权不足：保留 item 并置 [HomeworkTimeSetUiState.permissionDenied]（区别于会话失效的
 *    [HomeworkTimeSetUiState.missingSession]），同样渲染概要 + 就地提示；
 * 3. 可编辑基线：待完成作业 editable/canSubmit 为 true，保证既有排定流程不回归。
 *
 * 说明：本仓库无 Compose 渲染测试（无设备/未引入 Robolectric），故这里验证的是
 * 「状态投影 + 提交链路」这一渲染分支的输入契约，实际观感需真机验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkTimeSetLockedStateTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `进行中作业深链进入时保留作业概要并置就地锁定提示`() = runTest {
        val locked = homework(id = 1L, status = HomeworkStatus.IN_PROGRESS)
        val repository = FakeTimeSetRepository(locked)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )

        viewModel.start(studentId = STUDENT_ID, homeworkId = locked.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("锁定分支必须保留已读到的作业，避免渲染残缺空表单", locked, state.item)
        assertTrue("进行中应置就地锁定标记", state.lockedWorkInProgress)
        assertFalse("锁定时表单不可编辑", state.editable)
        assertFalse("锁定时不可提交", state.canSubmit)
        assertFalse("锁定与会话失效是两类状态", state.missingSession)
        assertFalse(state.permissionDenied)
    }

    @Test
    fun `进行中作业提交被拦下且不触达仓库并给出可读提示`() = runTest {
        val locked = homework(id = 2L, status = HomeworkStatus.IN_PROGRESS)
        val repository = FakeTimeSetRepository(locked)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )
        val messages = mutableListOf<String>()
        val collector = collectMessages(viewModel, messages)

        viewModel.start(studentId = STUDENT_ID, homeworkId = locked.id)
        advanceUntilIdle()
        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue("锁定态不得调用仓库排定时间", repository.scheduleCalls.isEmpty())
        assertTrue(
            "锁定态应给出可读提示，实际：$messages",
            messages.any { it == WORK_IN_PROGRESS_LOCKED_HINT },
        )
        assertEquals(locked, viewModel.uiState.value.item)
        collector.cancel()
    }

    @Test
    fun `无权排定他人名下作业时保留概要并置无权限标记而非会话失效`() = runTest {
        val otherHomework = homework(id = 3L, status = HomeworkStatus.PENDING, studentId = OTHER_STUDENT_ID)
        val repository = FakeTimeSetRepository(otherHomework)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )
        val messages = mutableListOf<String>()
        val collector = collectMessages(viewModel, messages)

        viewModel.start(studentId = OTHER_STUDENT_ID, homeworkId = otherHomework.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(otherHomework, state.item)
        assertTrue("越权应是独立状态（区别于会话失效）", state.permissionDenied)
        assertFalse(state.missingSession)
        assertFalse(state.lockedWorkInProgress)
        assertFalse(state.editable)
        assertFalse(state.canSubmit)
        assertTrue(
            "越权应给出执行权提示，实际：$messages",
            messages.any { it == EXECUTE_PERMISSION_DENIED_HINT },
        )
        collector.cancel()
    }

    @Test
    fun `待完成作业仍可正常编辑与提交`() = runTest {
        val pending = homework(id = 4L, status = HomeworkStatus.PENDING)
        val repository = FakeTimeSetRepository(pending)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )

        viewModel.start(studentId = STUDENT_ID, homeworkId = pending.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("待完成作业应可编辑（既有流程不回归）", state.editable)
        assertTrue(state.canSubmit)
        assertFalse(state.lockedWorkInProgress)
        assertFalse(state.permissionDenied)
        assertNull(state.timeError)
    }

    @Test
    fun `作业不存在时走会话失效分支且不保留空表单`() = runTest {
        val repository = FakeTimeSetRepository(homework(id = 5L, status = HomeworkStatus.PENDING))
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )
        val messages = mutableListOf<String>()
        val collector = collectMessages(viewModel, messages)

        // 深链指向不存在的作业：repository.getHomework 返回 null
        viewModel.start(studentId = STUDENT_ID, homeworkId = 999L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("作业不存在应整体提示（Content 第一分支）", state.missingSession)
        assertNull("无作业可展示时不保留 item", state.item)
        assertFalse(state.lockedWorkInProgress)
        assertFalse(state.permissionDenied)
        assertFalse(state.editable)
        assertTrue(
            "应给出作业不存在提示，实际：$messages",
            messages.any { it == "作业不存在，可能已被删除" },
        )
        collector.cancel()
    }

    @Test
    fun `锁定态表单编辑入口不改动状态且保持不可提交`() = runTest {
        val locked = homework(id = 6L, status = HomeworkStatus.IN_PROGRESS)
        val viewModel = HomeworkTimeSetViewModel(
            FakeTimeSetRepository(locked),
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )

        viewModel.start(studentId = STUDENT_ID, homeworkId = locked.id)
        advanceUntilIdle()

        // 锁定态下页面不渲染表单；即使误触表单回调也不得让状态变得可提交
        viewModel.onStartDateChange("2023-11-16")
        viewModel.onStartTimeChange("09:00")
        viewModel.onEstimatedMinutesChange("30")

        val state = viewModel.uiState.value
        assertTrue(state.lockedWorkInProgress)
        assertFalse("锁定态始终不可提交", state.canSubmit)
        assertEquals("锁定态应始终保留已读到的作业", locked, state.item)
    }

    @Test
    fun `可编辑态提交一次只调用一次仓库且成功后发出保存事件`() = runTest {
        val pending = homework(id = 7L, status = HomeworkStatus.PENDING)
        val repository = FakeTimeSetRepository(pending)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(studentSession()),
            FixedClock(),
            zone,
        )
        val messages = mutableListOf<String>()
        val collector = collectMessages(viewModel, messages)

        viewModel.start(studentId = STUDENT_ID, homeworkId = pending.id)
        advanceUntilIdle()
        viewModel.onSubmit()
        advanceUntilIdle()
        // 再次提交：submitting 已复位，属正常的重复保存（既有行为），此处只验证不重复并发投递
        assertEquals(1, repository.scheduleCalls.size)
        assertTrue("成功应发出保存事件，实际：$messages", messages.isNotEmpty())
        collector.cancel()
    }

    @Test
    fun `家长会话进入进行中作业同样走锁定分支且不误判为无权限`() = runTest {
        val locked = homework(id = 8L, status = HomeworkStatus.IN_PROGRESS)
        val repository = FakeTimeSetRepository(locked)
        val viewModel = HomeworkTimeSetViewModel(
            repository,
            FakeTimeSetAuthRepository(parentSession()),
            FixedClock(),
            zone,
        )

        viewModel.start(studentId = STUDENT_ID, homeworkId = locked.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // 家长有执行权，故绝不能落到 permissionDenied；应命中进行中锁定
        assertFalse("家长有执行权，不应判为无权限", state.permissionDenied)
        assertTrue("进行中作业对家长同样锁定", state.lockedWorkInProgress)
        assertFalse(state.missingSession)
        assertFalse(state.editable)
        assertFalse(state.canSubmit)
        assertEquals(locked, state.item)
    }

    // ---- 测试工具 ----

    private fun CoroutineScope.collectMessages(
        viewModel: HomeworkTimeSetViewModel,
        sink: MutableList<String>,
    ) = launch {
        viewModel.events.collect { event ->
            when (event) {
                is TimeSetEvent.ShowMessage -> sink += event.message
                is TimeSetEvent.Saved -> sink += event.message
            }
        }
    }

    private fun studentSession(): SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
    )

    /** 家长会话：studentId 为 null（家长维度不绑定单一学生） */
    private fun parentSession(): SessionState = SessionState(
        role = Role.PARENT,
        parentId = PARENT_ID,
        studentId = null,
    )

    private fun homework(
        id: Long,
        status: HomeworkStatus = HomeworkStatus.PENDING,
        studentId: Long = STUDENT_ID,
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = studentId,
        content = "作业$id",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = null,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = status,
        createdByRole = CreatorRole.PARENT,
        createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val OTHER_STUDENT_ID = 9L
    }

    /** 固定时钟（业务时区 2023-11-15） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = 1_700_000_000_000L
    }

    /** 时间设定页所需能力的仓库替身：只实现 getHomework 与 updateSchedule（其余能力误用即失败） */
    private class FakeTimeSetRepository(private val item: HomeworkItem) : HomeworkRepository {

        /** 记录 updateSchedule 调用，用于断言锁定/越权态不触达仓库 */
        val scheduleCalls = mutableListOf<Triple<Long, Instant, Int>>()

        override suspend fun getHomework(homeworkId: Long): HomeworkItem? =
            item.takeIf { it.id == homeworkId }

        override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> = flowOf(emptyList())

        override suspend fun listHomework(studentId: Long): List<HomeworkItem> = emptyList()

        override suspend fun updateSchedule(
            homeworkId: Long,
            startTime: Instant,
            estimatedMinutes: Int,
            sessionRole: Role,
        ): ScheduleUpdateResult {
            scheduleCalls += Triple(homeworkId, startTime, estimatedMinutes)
            return ScheduleUpdateResult.Success(item)
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
    private class FakeTimeSetAuthRepository(private val session: SessionState) : AuthRepository {

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
