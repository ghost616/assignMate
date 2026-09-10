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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「开始作业」入口（需求 C）单测：覆盖文案映射契约、清单路由回调的最低兼容接线，
 * 以及「开始作业 → 先落库推进状态 → 再外抛导航意图」的时序契约。
 *
 * 说明：清单页文档注释明确 —— [HomeworkListViewModel.onStartHomeworkClick] 只负责状态推进与可读提示，
 * 导航意图由 [HomeworkListRoute] 的 `onStartHomework` 回调外抛。本测试据此分别验证两侧：
 * 1. 导航回调（[HomeworkListCallbacks.onStartHomework]）可单独构造且默认空实现；
 * 2. 落库推进（[HomeworkListViewModel.onStartHomeworkClick]）确实调用仓库 startProgress 并给出可读提示，
 *    且进行中项与越权项被拦截时不得触达仓库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkListStartFlowTest {

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

    // ---- 文案映射（纯函数） ----

    @Test
    fun `三个新增锁定结果均映射为进行中锁定提示`() {
        assertEquals(WORK_IN_PROGRESS_LOCKED_HINT, HomeworkOrderResult.LockedWorkInProgress.toUserMessage())
        assertEquals(WORK_IN_PROGRESS_LOCKED_HINT, ScheduleUpdateResult.LockedWorkInProgress.toUserMessage())
        assertEquals(
            WORK_IN_PROGRESS_LOCKED_HINT,
            HomeworkOperationResult.LockedWorkInProgress.toUserMessage("已撤销时间安排"),
        )
        assertEquals("作业已开始，不能再调整顺序或时间", WORK_IN_PROGRESS_LOCKED_HINT)
    }

    @Test
    fun `锁定提示与既有权限提示文案互不串味`() {
        assertEquals(PERMISSION_DENIED_HINT, HomeworkOrderResult.PermissionDenied.toUserMessage())
        assertEquals(
            PERMISSION_DENIED_HINT,
            HomeworkOperationResult.PermissionDenied.toUserMessage("已删除该作业"),
        )
        assertEquals(
            EXECUTE_PERMISSION_DENIED_HINT,
            ScheduleUpdateResult.PermissionDenied.toUserMessage(),
        )
        assertTrue(WORK_IN_PROGRESS_LOCKED_HINT != PERMISSION_DENIED_HINT)
        assertTrue(WORK_IN_PROGRESS_LOCKED_HINT != EXECUTE_PERMISSION_DENIED_HINT)
    }

    // ---- 路由回调兼容性（framework 现有调用无需改动即可编译） ----

    @Test
    fun `清单路由回调集合可仅用既有参数构造且导航意图默认空实现`() {
        val callbacks = HomeworkListCallbacks(
            onBack = {},
            onAddHomework = {},
            onEditTime = {},
            onEditTemplate = {},
            onMoveUp = {},
            onMoveDown = {},
            onDeleteClick = {},
            onDeleteConfirm = {},
            onDeleteDismiss = {},
            onComplete = {},
            onReopen = {},
        )

        // 未接线时点击「开始作业」不应抛异常（默认空实现）
        callbacks.onStartHomework(42L)
        callbacks.onStart(42L)
    }

    @Test
    fun `清单路由回调可注入开始作业导航意图`() {
        val navigated = mutableListOf<Long>()
        val callbacks = HomeworkListCallbacks(
            onBack = {},
            onAddHomework = {},
            onEditTime = {},
            onEditTemplate = {},
            onMoveUp = {},
            onMoveDown = {},
            onDeleteClick = {},
            onDeleteConfirm = {},
            onDeleteDismiss = {},
            onComplete = {},
            onReopen = {},
            onStartHomework = { id -> navigated += id },
        )

        callbacks.onStartHomework(7L)

        assertEquals(listOf(7L), navigated)
    }

    // ---- 开始作业：先落库推进，再提示 ----

    @Test
    fun `待完成作业点击开始作业先落库推进为进行中并提示已开始`() = runTest {
        val pending = homework(id = 1L, status = HomeworkStatus.PENDING)
        val repository = FakeHomeworkRepository(mutableListOf(pending))
        val auth = FakeAuthRepository(studentSession())
        val viewModel = HomeworkListViewModel(repository, auth, FixedClock(), zone)
        val events = mutableListOf<String>()
        val collector = collectEvents(viewModel, events)

        viewModel.start(0L)
        advanceUntilIdle()
        viewModel.onStartHomeworkClick(pending.id)
        advanceUntilIdle()

        assertEquals("开始作业必须先经仓库落库推进状态", listOf(1L to Role.STUDENT), repository.startCalls)
        assertTrue(
            "应给出可读的开始提示，实际：$events",
            events.any { it == "已开始该作业" },
        )
        collector.cancel()
    }

    @Test
    fun `进行中作业点击继续计时再次调用开始入口并提示`() = runTest {
        val inProgress = homework(id = 2L, status = HomeworkStatus.IN_PROGRESS)
        val repository = FakeHomeworkRepository(mutableListOf(inProgress))
        val auth = FakeAuthRepository(studentSession())
        val viewModel = HomeworkListViewModel(repository, auth, FixedClock(), zone)
        val events = mutableListOf<String>()
        val collector = collectEvents(viewModel, events)

        viewModel.start(0L)
        advanceUntilIdle()
        viewModel.onStartHomeworkClick(inProgress.id)
        advanceUntilIdle()

        assertEquals(listOf(2L to Role.STUDENT), repository.startCalls)
        assertTrue(events.any { it == "已开始该作业" })
        collector.cancel()
    }

    @Test
    fun `已记录作业不展示开始入口故点击不触达仓库`() = runTest {
        val recorded = homework(id = 3L, status = HomeworkStatus.RECORDED)
        val repository = FakeHomeworkRepository(mutableListOf(recorded))
        val auth = FakeAuthRepository(studentSession())
        val viewModel = HomeworkListViewModel(repository, auth, FixedClock(), zone)
        val events = mutableListOf<String>()
        val collector = collectEvents(viewModel, events)

        viewModel.start(0L)
        advanceUntilIdle()
        viewModel.onStartHomeworkClick(recorded.id)
        advanceUntilIdle()

        assertTrue("已记录需先排定时间，不应触达仓库", repository.startCalls.isEmpty())
        assertTrue(events.any { it == EXECUTE_PERMISSION_DENIED_HINT })
        collector.cancel()
    }

    @Test
    fun `他人名下作业点击开始作业被拦截且不触达仓库`() = runTest {
        val otherStudentHomework = homework(id = 4L, studentId = OTHER_STUDENT_ID)
        val repository = FakeHomeworkRepository(mutableListOf(otherStudentHomework))
        val auth = FakeAuthRepository(studentSession())
        val viewModel = HomeworkListViewModel(repository, auth, FixedClock(), zone)
        val events = mutableListOf<String>()
        val collector = collectEvents(viewModel, events)

        viewModel.start(0L)
        advanceUntilIdle()
        viewModel.onStartHomeworkClick(otherStudentHomework.id)
        advanceUntilIdle()

        assertTrue("越权操作不得触达仓库", repository.startCalls.isEmpty())
        assertTrue(events.any { it == EXECUTE_PERMISSION_DENIED_HINT })
        collector.cancel()
    }

    // ---- 测试工具 ----

    private fun CoroutineScope.collectEvents(
        viewModel: HomeworkListViewModel,
        sink: MutableList<String>,
    ) = launch {
        viewModel.events.collect { event ->
            when (event) {
                is HomeworkListEvent.ShowMessage -> sink += event.message
                // 删除成功事件由 framework 接线消费（取消到点提醒），与本用例关注的提示文案无关
                else -> Unit
            }
        }
    }

    private fun studentSession(): SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
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

    /**
     * 作业仓库替身：只实现本用例涉及的能力（observeHomework / startProgress），
     * 其余能力抛 [UnsupportedOperationException]，避免被测代码误用而静默通过。
     */
    private class FakeHomeworkRepository(
        initialItems: List<HomeworkItem>,
    ) : HomeworkRepository {

        private val items = MutableStateFlow(initialItems)

        /** 记录 startProgress 调用（id + 会话角色） */
        val startCalls = mutableListOf<Pair<Long, Role>>()

        /** 记录其它状态流转调用（用于断言不越界触达） */
        val completeCalls = mutableListOf<Long>()

        override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> = items

        override suspend fun listHomework(studentId: Long): List<HomeworkItem> =
            items.value.filter { it.studentId == studentId }

        override suspend fun getHomework(homeworkId: Long): HomeworkItem? =
            items.value.firstOrNull { it.id == homeworkId }

        override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult {
            startCalls += homeworkId to sessionRole
            val current = items.value.firstOrNull { it.id == homeworkId }
                ?: return HomeworkStatusResult.NotFound
            val updated = current.copy(status = HomeworkStatus.IN_PROGRESS)
            items.value = items.value.map { if (it.id == homeworkId) updated else it }
            return HomeworkStatusResult.Success(updated)
        }

        override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult {
            completeCalls += homeworkId
            return unsupported()
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

        override suspend fun updateSchedule(
            homeworkId: Long,
            startTime: Instant,
            estimatedMinutes: Int,
            sessionRole: Role,
        ): ScheduleUpdateResult = unsupported()

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