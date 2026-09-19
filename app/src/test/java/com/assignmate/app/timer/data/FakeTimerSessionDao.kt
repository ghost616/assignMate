package com.assignmate.app.timer.data

import com.assignmate.app.core.data.db.dao.TimerSessionDao
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版计时会话 DAO（单元测试替身）：以 MutableList 模拟 timer_session 表。
 *
 * 排序/查询口径与真实 SQL 保持一致（started_at ASC；按 student+status 过滤），
 * 便于确定性验证「恢复现场」「按学生取未结束会话」等仓库行为；
 * 变更后以 revision 触发 Flow 重新发射，模拟 Room 的观察语义。
 *
 * [writeCount] 累计写操作次数（insert/update/delete 及按 id 的字段更新各计一次），
 * 供「越权被拒绝时不得产生任何写入」的断言使用。
 */
class FakeTimerSessionDao : TimerSessionDao {

    private val rows = mutableListOf<TimerSessionEntity>()
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    /** 累计写操作次数（只增不减，供越权「零写入」断言比对前后快照） */
    var writeCount: Int = 0
        private set

    override suspend fun insert(item: TimerSessionEntity): Long {
        val id = nextId++
        rows += item.copy(id = id)
        writeCount++
        touch()
        return id
    }

    override suspend fun insertAll(items: List<TimerSessionEntity>): List<Long> = items.map { insert(it) }

    override suspend fun update(item: TimerSessionEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
            writeCount++
            touch()
        }
    }

    override suspend fun delete(item: TimerSessionEntity) {
        if (rows.removeAll { it.id == item.id }) {
            writeCount++
            touch()
        }
    }

    override fun observeByHomework(homeworkId: Long): Flow<List<TimerSessionEntity>> =
        revision.map { orderedByHomework(homeworkId) }

    override suspend fun loadByHomework(homeworkId: Long): List<TimerSessionEntity> =
        orderedByHomework(homeworkId)

    override suspend fun loadByStudent(studentId: Long): List<TimerSessionEntity> =
        rows.filter { it.studentId == studentId }.sortedBy { it.startedAt }

    override suspend fun loadByStudentAndStatus(
        studentId: Long,
        status: String,
    ): List<TimerSessionEntity> =
        rows.filter { it.studentId == studentId && it.status == status }.sortedBy { it.startedAt }

    /** 按作业 + 业务自然日查询（排序口径与库内 SQL 一致：开始时刻升序） */
    override suspend fun loadByHomeworkAndDay(
        homeworkId: Long,
        epochDay: Long,
    ): List<TimerSessionEntity> =
        rows.filter { it.homeworkId == homeworkId && it.epochDay == epochDay }
            .sortedBy { it.startedAt }

    override suspend fun findById(id: Long): TimerSessionEntity? = rows.firstOrNull { it.id == id }

    override suspend fun updateFinish(sessionId: Long, finishedAtMillis: Long, status: String) {
        mutate(sessionId) { it.copy(finishedAt = Instant.ofEpochMilli(finishedAtMillis), status = status) }
    }

    override suspend fun updatePauseSummary(
        sessionId: Long,
        pausedTotalMillis: Long,
        pauseCount: Int,
        status: String,
    ) {
        mutate(sessionId) {
            it.copy(pausedTotalMillis = pausedTotalMillis, pauseCount = pauseCount, status = status)
        }
    }

    override suspend fun updateStatus(sessionId: Long, status: String) {
        mutate(sessionId) { it.copy(status = status) }
    }

    /** 测试断言辅助：返回全部会话快照（按开始时刻升序） */
    fun all(): List<TimerSessionEntity> = rows.sortedBy { it.startedAt }

    private fun mutate(sessionId: Long, transform: (TimerSessionEntity) -> TimerSessionEntity) {
        val index = rows.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            rows[index] = transform(rows[index])
            writeCount++
            touch()
        }
    }

    private fun touch() {
        revision.value += 1
    }

    private fun orderedByHomework(homeworkId: Long): List<TimerSessionEntity> =
        rows.filter { it.homeworkId == homeworkId }.sortedBy { it.startedAt }
}
