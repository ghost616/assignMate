package com.assignmate.app.stats.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.domain.HistoryQuery
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stats 归属校验统一到 auth 口径的看护用例——家长分支经
 * [AuthRepository.isStudentOwnedBy] / [AuthRepository.ownedStudentIds]（与 homework/timer 同一能力），
 * 本模块不再自建等价实现。覆盖四类口径：
 *
 * 1. 家长会话的越权判定与 auth 统一归属能力**逐一交叉验证**，不通过一律收敛为 [StatsFailure.ACCESS_DENIED]；
 * 2. 学生会话仍严格「仅限本人」，不因 auth 侧家长归属成立而放行（兄妹同家长场景）；
 * 3. `itemDetail` 的「作业归属 == 会话唯一可见学生」校验在统一口径下仍成立（多学生家长不追加该校验）；
 * 4. 归属链路不再用 runCatching 吞掉协程取消：取消信号向上传播，不降级为 ACCESS_DENIED。
 *
 * 时钟/时区口径与 [StatsRepositoryTestEnv] 一致（2023-11-14 00:09 +08:00 / Asia/Shanghai）。
 */
class StatsOwnershipUnificationTest {

    private val dayEpoch = StatsRepositoryTestEnv.DAY_EPOCH

    @Test
    fun `家长会话越权判定与 auth 统一归属口径逐一一致`() = runTest {
        val env = StatsRepositoryTestEnv(parentSession())
        val targets = listOf(
            StatsRepositoryTestEnv.STUDENT_ID,
            StatsRepositoryTestEnv.OTHER_STUDENT_ID,
            NOT_REGISTERED_STUDENT_ID,
        )

        targets.forEach { target ->
            val ownedByAuth = env.authRepository.isStudentOwnedBy(StatsRepositoryTestEnv.PARENT_ID, target)
            val result = env.repository.summarizeDay(target, dayEpoch)
            if (ownedByAuth) {
                assertTrue("名下学生 $target 应可查询", result is StatsResult.Success)
            } else {
                assertEquals(
                    "非名下学生 $target 应越权拒绝",
                    StatsFailure.ACCESS_DENIED,
                    (result as StatsResult.Failure).reason,
                )
            }
        }
        // 批量口径与逐条口径同源：家长名下仅 STUDENT_ID 一名学生
        assertEquals(
            setOf(StatsRepositoryTestEnv.STUDENT_ID),
            env.authRepository.ownedStudentIds(StatsRepositoryTestEnv.PARENT_ID),
        )
    }

    @Test
    fun `三个查询入口的越权判定同源并收敛为越权拒绝`() = runTest {
        val env = StatsRepositoryTestEnv(parentSession())
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )

        val denied = listOf(
            env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch),
            env.repository.history(StatsRepositoryTestEnv.OTHER_STUDENT_ID, HistoryQuery(dayEpoch)),
            env.repository.itemDetail(1L),
        )

        denied.forEach { assertEquals(StatsFailure.ACCESS_DENIED, (it as StatsResult.Failure).reason) }
        assertFalse(
            env.authRepository.isStudentOwnedBy(
                StatsRepositoryTestEnv.PARENT_ID,
                StatsRepositoryTestEnv.OTHER_STUDENT_ID,
            ),
        )
    }

    @Test
    fun `学生会话仅限本人不因同家长名下学生而放行`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.addStudentProfile(
            id = SIBLING_STUDENT_ID,
            parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
            name = "小刚",
        )
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = SIBLING_STUDENT_ID, status = HomeworkStatus.COMPLETED),
        )

        // auth 侧「同家长名下」成立，但学生会话只能看本人（归属能力不改变学生会话语义）
        assertTrue(
            env.authRepository.isStudentOwnedBy(StatsRepositoryTestEnv.PARENT_ID, SIBLING_STUDENT_ID),
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.summarizeDay(SIBLING_STUDENT_ID, dayEpoch) as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.history(SIBLING_STUDENT_ID, HistoryQuery(dayEpoch)) as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.itemDetail(1L) as StatsResult.Failure).reason,
        )
    }

    @Test
    fun `无会话时直接返回无会话失败且不触发归属查询`() = runTest {
        // 归属查询被替换为「必抛取消异常」：若实现先查归属，用例会以异常失败而非返回失败结果
        val repository = repositoryWith(
            authRepository = CancellingAuthRepository(FakeStatsAuthRepository(SessionState.NONE)),
        )

        val result = repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertEquals(StatsFailure.NO_ACTIVE_SESSION, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `家长名下多名学生时详情按作业归属取数且不追加唯一可见学生校验`() = runTest {
        val env = StatsRepositoryTestEnv(parentSession())
        env.authRepository.addStudentProfile(
            id = SIBLING_STUDENT_ID,
            parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
            name = "小刚",
        )
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = SIBLING_STUDENT_ID, status = HomeworkStatus.COMPLETED),
        )
        env.timerRepository.putSession(
            statsTestSession(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                finishedAtMillis = StatsRepositoryTestEnv.at(10),
                studentId = SIBLING_STUDENT_ID,
            ),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals(2, env.authRepository.ownedStudentIds(StatsRepositoryTestEnv.PARENT_ID).size)
        assertEquals(SIBLING_STUDENT_ID, detail.studentId)
        assertTrue(detail.hasExecution)
    }

    @Test
    fun `归属查询遇协程取消时汇总不降级为越权拒绝`() {
        val repository = repositoryWith(
            authRepository = CancellingAuthRepository(
                delegate = FakeStatsAuthRepository(parentSession()),
                cancelOwnedStudentIds = false,
            ),
        )

        // 家长分支归属查询（isStudentOwnedBy）：取消信号必须原样传播，不得被降级成 ACCESS_DENIED
        assertEquals(
            CancellingAuthRepository.IS_STUDENT_OWNED_BY_MESSAGE,
            runAndCaptureCancellation { repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch) },
        )
    }

    @Test
    fun `会话可见学生解析遇协程取消时详情不降级为越权拒绝`() {
        val homeworkRepository = FakeStatsHomeworkRepository().apply {
            put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        }
        // 直接构造替身（不经测试环境）时需自行登记档案：家长对作业归属学生的归属须先成立，才走到可见学生解析
        val delegate = FakeStatsAuthRepository(parentSession()).apply {
            addStudentProfile(
                id = StatsRepositoryTestEnv.STUDENT_ID,
                parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
                name = "小明",
            )
        }
        val repository = repositoryWith(
            authRepository = CancellingAuthRepository(
                delegate = delegate,
                cancelIsStudentOwnedBy = false,
            ),
            homeworkRepository = homeworkRepository,
        )

        // 归属校验经 isStudentOwnedBy 通过后，唯一可见学生解析（ownedStudentIds）同样不得吞掉取消
        assertEquals(
            CancellingAuthRepository.OWNED_STUDENT_IDS_MESSAGE,
            runAndCaptureCancellation { repository.itemDetail(1L) },
        )
    }

    /** 用指定 auth 替身构造被测仓库（其余替身固定，避免与测试环境耦合） */
    private fun repositoryWith(
        authRepository: AuthRepository,
        homeworkRepository: FakeStatsHomeworkRepository = FakeStatsHomeworkRepository(),
        timerRepository: FakeStatsTimerRepository = FakeStatsTimerRepository(),
    ): StatsRepository = StatsRepositoryImpl(
        homeworkRepository = homeworkRepository,
        timerRepository = timerRepository,
        authRepository = authRepository,
        clock = StatsMutableClock(StatsRepositoryTestEnv.FIXED_MILLIS),
        zoneId = StatsRepositoryTestEnv.ZONE,
    )

    /** 执行 suspend 查询并捕获其抛出的 [CancellationException]（未抛出则本用例失败） */
    private fun runAndCaptureCancellation(block: suspend () -> Unit): String? {
        var caught: CancellationException? = null
        runTest {
            try {
                block()
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertNotNull("查询吞掉了协程取消异常，破坏了结构化并发", caught)
        return caught?.message
    }

    private companion object {

        /** 未登记档案的学生 id（auth 归属查询应收敛为 false） */
        const val NOT_REGISTERED_STUDENT_ID = 999L

        /** 与当前学生会话同家长、但非本人的学生 id（验证「仅限本人」） */
        const val SIBLING_STUDENT_ID = 5L
    }
}

/** 家长会话（不携带学生维度，与生产家长登录后的会话形态一致） */
private fun parentSession(): SessionState = SessionState(
    role = Role.PARENT,
    parentId = StatsRepositoryTestEnv.PARENT_ID,
    studentId = null,
)

/**
 * 归属查询必定抛出 [CancellationException] 的 auth 替身：stats 归属链路不再用 runCatching 包裹
 * （auth 契约已把数据层异常收敛为 false/空集并显式重抛取消），故取消信号必须原样向上传播，
 * 不得被降级成 [StatsFailure.ACCESS_DENIED]。
 *
 * 说明：接口委托（`by delegate`）会为接口成员生成转发，故两个归属方法必须显式覆写才能模拟取消，
 * 未覆写时调用会直接转发到 [delegate]（其内部 getStudent/listStudents 不会被本类拦截）。
 */
private class CancellingAuthRepository(
    private val delegate: AuthRepository,
    private val cancelIsStudentOwnedBy: Boolean = true,
    private val cancelOwnedStudentIds: Boolean = true,
) : AuthRepository by delegate {

    override suspend fun isStudentOwnedBy(parentId: Long, studentId: Long): Boolean =
        if (cancelIsStudentOwnedBy) {
            throw CancellationException(IS_STUDENT_OWNED_BY_MESSAGE)
        } else {
            delegate.isStudentOwnedBy(parentId, studentId)
        }

    override suspend fun ownedStudentIds(parentId: Long): Set<Long> =
        if (cancelOwnedStudentIds) {
            throw CancellationException(OWNED_STUDENT_IDS_MESSAGE)
        } else {
            delegate.ownedStudentIds(parentId)
        }

    companion object {

        /** 逐条归属查询（isStudentOwnedBy）触发的取消文案 */
        const val IS_STUDENT_OWNED_BY_MESSAGE = "coroutine cancelled: isStudentOwnedBy"

        /** 批量归属查询（ownedStudentIds）触发的取消文案 */
        const val OWNED_STUDENT_IDS_MESSAGE = "coroutine cancelled: ownedStudentIds"
    }
}
