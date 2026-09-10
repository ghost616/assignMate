package com.assignmate.app.homework.data

import com.assignmate.app.core.data.db.dao.HomeworkItemDao
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