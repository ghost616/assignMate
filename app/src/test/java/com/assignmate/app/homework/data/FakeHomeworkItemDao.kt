package com.assignmate.app.homework.data

import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.data.db.dao.MovePositionOutcome
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版作业项 DAO（单元测试替身）：以 MutableList 模拟 homework_item 表，
 * 排序/查询口径与真实 SQL 保持一致（priority ASC, created_at ASC；MAX(priority)；start_time 非空过滤），
 * 保证仓库层优先级调整与时间段防冲突逻辑可被确定性验证。
 */
class FakeHomeworkItemDao : HomeworkItemDao {

    private val rows = mutableListOf<HomeworkItemEntity>()
    private var nextId = 1L

    /** Room 的 Flow 查询在数据变更后重新发射：以 StateFlow 模拟同一行为 */
    private val revision = MutableStateFlow(0)

    /**
     * 测试专用：在「进入事务」的瞬间执行的并发变更钩子（一次性）。
     *
     * 真实 Room 的 @Transaction 默认方法由单事务实现，事务内重读能看到并发写入的最新状态；
     * 单线程测试无法制造真实并发，因此用本钩子把变更注入到「事务内重读之前」，
     * 从而可确定性地验证事务内复查（如进行中锁定）能拦下已失效的重排。执行后自动清空。
     */
    var onNextTransactionEnter: ((MutableList<HomeworkItemEntity>) -> Unit)? = null

    /**
     * 复刻 [HomeworkItemDao.moveToPositionInTransaction] 的事务内语义：
     * 先执行并发钩子（若有），再重读状态与清单，命中锁定则不改动任何数据。
     */
    override suspend fun moveToPositionInTransaction(
        homeworkId: Long,
        targetIndex: Int,
    ): MovePositionOutcome {
        onNextTransactionEnter?.let { hook ->
            hook(rows)
            onNextTransactionEnter = null
            revision.value += 1
        }
        return super.moveToPositionInTransaction(homeworkId, targetIndex)
    }

    override suspend fun insert(item: HomeworkItemEntity): Long {
        val id = nextId++
        rows += item.copy(id = id)
        revision.value += 1
        return id
    }

    override suspend fun insertAll(items: List<HomeworkItemEntity>): List<Long> = items.map { insert(it) }



    override suspend fun update(item: HomeworkItemEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
            revision.value += 1
        }
    }

    override suspend fun delete(item: HomeworkItemEntity) {
        if (rows.removeAll { it.id == item.id }) {
            revision.value += 1
        }
    }

    override fun observeByStudent(studentId: Long): Flow<List<HomeworkItemEntity>> =
        revision.map { ordered(studentId) }

    override suspend fun loadByStudent(studentId: Long): List<HomeworkItemEntity> = ordered(studentId)

    override suspend fun loadByStudentAndStatus(
        studentId: Long,
        status: String,
    ): List<HomeworkItemEntity> = ordered(studentId).filter { it.status == status }

    override suspend fun findById(id: Long): HomeworkItemEntity? = rows.firstOrNull { it.id == id }

    override suspend fun maxPriority(studentId: Long): Int? =
        ordered(studentId).maxOfOrNull { it.priority }

    override suspend fun loadScheduledByStudent(studentId: Long): List<HomeworkItemEntity> =
        ordered(studentId).filter { it.startTime != null }

    /** 测试断言辅助：返回全部行快照（按优先级） */
    fun all(): List<HomeworkItemEntity> = rows.toList()

    private fun ordered(studentId: Long): List<HomeworkItemEntity> =
        rows.filter { it.studentId == studentId }
            .sortedWith(compareBy<HomeworkItemEntity> { it.priority }.thenBy { it.createdAt })
}