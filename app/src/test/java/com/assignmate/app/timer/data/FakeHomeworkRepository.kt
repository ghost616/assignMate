package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 内存版作业仓库（单元测试替身）：只实现 timer 仓库真正依赖的读作业与状态流转能力，
 * 并可注入「流转失败」的返回值，用于覆盖 [TimerRepositoryImpl] 的错误分支
 * （作业状态同步失败时不得创建/收尾会话）。
 *
 * 其它作业管理能力（录入/调序/排定/改删模板）不属于 timer 模块测试范围，
 * 一律抛 [UnsupportedOperationException]，一旦被测代码误用会立刻暴露，避免假绿灯。
 */
class FakeHomeworkRepository : HomeworkRepository {

    private val items = linkedMapOf<Long, HomeworkItem>()

    /** 覆盖 startProgress 的返回值；为 null 时按内存数据自然流转（→ 进行中） */
    var startProgressOverride: HomeworkStatusResult? = null

    /** 覆盖 complete 的返回值；为 null 时按内存数据自然流转（→ 已完成） */
    var completeOverride: HomeworkStatusResult? = null

    /** 调用计数（验证同步次数，如幂等场景不重复同步） */
    var startProgressCalls: Int = 0
        private set
    var completeCalls: Int = 0
        private set

    /** 登记作业项测试数据 */
    fun put(item: HomeworkItem) {
        items[item.id] = item
    }

    override suspend fun getHomework(homeworkId: Long): HomeworkItem? = items[homeworkId]

    override suspend fun listHomework(studentId: Long): List<HomeworkItem> =
        items.values.filter { it.studentId == studentId }
            .sortedWith(compareBy<HomeworkItem> { it.priority }.thenBy { it.createdAt })

    override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> =
        flowOf(items.values.filter { it.studentId == studentId })

    override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult {
        startProgressCalls++
        startProgressOverride?.let { return it }
        return transition(homeworkId, HomeworkStatus.IN_PROGRESS)
    }

    override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult {
        completeCalls++
        completeOverride?.let { return it }
        return transition(homeworkId, HomeworkStatus.COMPLETED)
    }

    private fun transition(homeworkId: Long, target: HomeworkStatus): HomeworkStatusResult {
        val item = items[homeworkId] ?: return HomeworkStatusResult.NotFound
        if (!item.status.canTransitionTo(target)) {
            return HomeworkStatusResult.IllegalTransition(item.status, target)
        }
        val updated = item.copy(status = target)
        items[homeworkId] = updated
        return HomeworkStatusResult.Success(updated)
    }

    // ---- 与 timer 无关的能力：误用即失败 ----

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
        throw UnsupportedOperationException("timer 模块单测不涉及该 homework 能力")
}
