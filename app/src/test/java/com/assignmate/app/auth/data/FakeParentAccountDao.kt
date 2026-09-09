package com.assignmate.app.auth.data

import com.assignmate.app.core.data.db.dao.ParentAccountDao
import com.assignmate.app.core.data.db.entity.ParentAccountEntity

/**
 * 内存版家长账号 DAO（单元测试替身）：以 MutableList 模拟 parent_account 表，
 * 自增主键行为与 Room 一致；并发场景由单线程 runTest 保证。
 */
class FakeParentAccountDao : ParentAccountDao {

    private val rows = mutableListOf<ParentAccountEntity>()
    private var nextId = 1L

    override suspend fun insert(item: ParentAccountEntity): Long {
        val id = nextId++
        rows += item.copy(id = id)
        return id
    }

    override suspend fun insertAll(items: List<ParentAccountEntity>): List<Long> =
        items.map { insert(it) }

    override suspend fun update(item: ParentAccountEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
        }
    }

    override suspend fun delete(item: ParentAccountEntity) {
        rows.removeAll { it.id == item.id }
    }

    override suspend fun findByAccount(account: String): ParentAccountEntity? =
        rows.firstOrNull { it.account == account }

    override suspend fun existsByAccount(account: String): Boolean =
        rows.any { it.account == account }

    /** 测试断言辅助：返回全部行快照 */
    fun all(): List<ParentAccountEntity> = rows.toList()
}