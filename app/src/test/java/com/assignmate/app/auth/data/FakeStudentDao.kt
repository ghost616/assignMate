package com.assignmate.app.auth.data

import com.assignmate.app.core.data.db.dao.StudentDao
import com.assignmate.app.core.data.db.entity.StudentEntity

/**
 * 内存版学生档案 DAO（单元测试替身）：以 MutableList 模拟 student 表，
 * 查询排序与真实 SQL（created_at ASC, id ASC）保持一致，保证测试确定性。
 */
class FakeStudentDao : StudentDao {

    private val rows = mutableListOf<StudentEntity>()
    private var nextId = 1L

    override suspend fun insert(item: StudentEntity): Long {
        val id = nextId++
        rows += item.copy(id = id)
        return id
    }

    override suspend fun insertAll(items: List<StudentEntity>): List<Long> =
        items.map { insert(it) }

    override suspend fun update(item: StudentEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
        }
    }

    override suspend fun delete(item: StudentEntity) {
        rows.removeAll { it.id == item.id }
    }

    override suspend fun findByParentAccountId(parentAccountId: Long): List<StudentEntity> =
        rows.filter { it.parentAccountId == parentAccountId }
            .sortedWith(compareBy<StudentEntity> { it.createdAt }.thenBy { it.id })

    override suspend fun findByParentAccountIdAndVerificationCode(
        parentAccountId: Long,
        verificationCode: String,
    ): StudentEntity? =
        rows.firstOrNull {
            it.parentAccountId == parentAccountId && it.verificationCode == verificationCode
        }

    override suspend fun findById(id: Long): StudentEntity? =
        rows.firstOrNull { it.id == id }

    /** 测试断言辅助：返回全部行快照 */
    fun all(): List<StudentEntity> = rows.toList()
}