package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkTestEnv
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 离朱补充探测（本轮 homework 审查修复，error 修复的**跨层链路**）：
 * 「家长在模板页把作业改成阶段作业（trace 到 updateTemplate）」→「落库可还原」
 * →「学生进入时间设定页能提交」，整条链路上真实仓库 + 真实 auth 会话 + 真实 ViewModel。
 *
 * 与既有套件的分工：
 * - `HomeworkRepositoryImplTest` 只到仓库层（dailyDeadlineTime / stageStartEpochDay 可还原）；
 * - `HomeworkTimeSetStageDeadlineTest` 用仓库替身只到 ViewModel 层；
 * - 本类把两层接起来，并用**真实仓库**断言「越限不落库 / 合法即落库」，避免两层各自为真、合起来不成立。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkStageDeadlineIntegrationProbeTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `家长改阶段作业后落库可还原且学生能在时间设定页提交`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val today = HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), ZONE)

        // 家长先录入一条当天作业
        val created = env.repository.addHomework(
            HomeworkTemplate(
                content = "语文作业",
                type = HomeworkType.TODAY,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = today,
                zoneId = ZONE,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        val item = created.items.single()

        // 家长在模板页改为阶段作业（每日 20:30 截止）：模板页传入的是「每日时刻载体」
        val carrier = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(20, 30))
        assertTrue(
            "前置：载体是纪元日 0 附近的小值",
            carrier.toEpochMilli() < 100_000_000L,
        )
        val updated = env.repository.updateTemplate(
            item.id,
            HomeworkType.STAGE,
            StageRange.ONE_WEEK,
            carrier,
            Role.PARENT,
        )
        assertTrue(updated is HomeworkOperationResult.Success)

        // warning 1：落库值是「创建日 + 每日时刻」的编码（可唯一还原），不是载体本身
        val stored = env.repository.getHomework(item.id)!!
        assertNotEquals("落库不得保留时刻载体", carrier, stored.deadline)
        assertEquals(
            HomeworkDailyDeadlineCodec.encodeStageDaily(today, LocalTime.of(20, 30)),
            stored.deadline,
        )
        assertEquals(LocalTime.of(20, 30), stored.dailyDeadlineTime)
        assertEquals("起始日可还原为创建日（不依赖兜底）", today, stored.stageStartEpochDay)
        assertEquals(today + 6L, stored.stageLastEpochDay)

        // 学生进入：时间设定页（真实仓库 + 真实会话 + 真实 ViewModel）
        env.loginAsStudent(parentId, studentId)
        val viewModel = HomeworkTimeSetViewModel(env.repository, env.authRepository, env.clock, ZONE)
        viewModel.start(studentId, item.id)
        advanceUntilIdle()
        assertTrue("学生应为本人名下阶段作业排定时间", viewModel.uiState.value.editable)
        assertNull(viewModel.uiState.value.timeError)

        // 反向：20:00 开始 60 分钟 -> 21:00 越过 20:30 每日截止 -> 本地拦下且不落库
        form(viewModel, today, LocalTime.of(20, 0), 60)
        viewModel.onSubmit()
        advanceUntilIdle()
        assertNotNull("越过每日截止时刻必须给出可读提示", viewModel.uiState.value.timeError)
        assertNull("越限不得触达仓库", env.repository.getHomework(item.id)!!.startTime)

        // 正向：19:50 开始 40 分钟 -> 20:30 恰好等于每日截止 -> 提交成功
        form(viewModel, today + 2L, LocalTime.of(19, 50), 40)
        viewModel.onSubmit()
        advanceUntilIdle()
        assertNull("修复前：阶段作业在设时间页永远提交不了", viewModel.uiState.value.timeError)
        val scheduled = env.repository.getHomework(item.id)!!
        assertNotNull("合法安排必须落库", scheduled.startTime)
        assertEquals(40, scheduled.estimatedMinutes)
        assertEquals(
            "落库开始时刻 = 所选业务日 + 所选钟面时刻",
            HomeworkDailyDeadlineCodec.instantAt(today + 2L, LocalTime.of(19, 50), ZONE),
            scheduled.startTime,
        )
    }

    @Test
    fun `当天作业改类型回当天后仍按绝对截止时刻预校验`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val today = HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), ZONE)
        val absoluteDeadline = HomeworkDailyDeadlineCodec.instantAt(today, LocalTime.of(18, 0), ZONE)

        val created = env.repository.addHomework(
            HomeworkTemplate(
                content = "数学作业",
                type = HomeworkType.TODAY,
                deadline = absoluteDeadline,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = today,
                zoneId = ZONE,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        val item = created.items.single()
        assertEquals("当天作业的绝对 deadline 原样落库", absoluteDeadline, item.deadline)

        env.loginAsStudent(parentId, studentId)
        val viewModel = HomeworkTimeSetViewModel(env.repository, env.authRepository, env.clock, ZONE)
        viewModel.start(studentId, item.id)
        advanceUntilIdle()

        // 17:00 + 60min = 18:00 恰好等于绝对 deadline -> 通过
        form(viewModel, today, LocalTime.of(17, 0), 60)
        viewModel.onSubmit()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.timeError)
        assertNotNull(env.repository.getHomework(item.id)!!.startTime)
    }

    // ---- 测试工具 ----

    private fun form(
        viewModel: HomeworkTimeSetViewModel,
        epochDay: Long,
        time: LocalTime,
        minutes: Int,
    ) {
        viewModel.onStartDateChange(LocalDate.ofEpochDay(epochDay).toString())
        viewModel.onStartTimeChange("%02d:%02d".format(time.hour, time.minute))
        viewModel.onEstimatedMinutesChange(minutes.toString())
    }

    private companion object Constants {

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
