package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 作业项 DAO：所有查询以学生维度收敛（student_id），不提供跨学生全局查询；
 * 状态机、优先级规则、时间段冲突判定等业务逻辑在 homework 层，DAO 只负责数据访问。
 */
@Dao
interface HomeworkItemDao : BaseDao<HomeworkItemEntity> {

    /** 观察某学生的作业清单：默认按优先级升序（同优先级按创建时间升序，保证顺序稳定） */
    @Query(
        "SELECT * FROM homework_item WHERE student_id = :studentId " +
            "ORDER BY priority ASC, created_at ASC",
    )
    fun observeByStudent(studentId: Long): Flow<List<HomeworkItemEntity>>

    /** 读取某学生作业清单快照（按优先级升序，排序口径与 [observeByStudent] 一致），供优先级重排使用 */
    @Query(
        "SELECT * FROM homework_item WHERE student_id = :studentId " +
            "ORDER BY priority ASC, created_at ASC",
    )
    suspend fun loadByStudent(studentId: Long): List<HomeworkItemEntity>

    /** 按学生 + 状态查询（按优先级升序），如"待完成清单""已完成清单" */
    @Query(
        "SELECT * FROM homework_item WHERE student_id = :studentId AND status = :status " +
            "ORDER BY priority ASC, created_at ASC",
    )
    suspend fun loadByStudentAndStatus(studentId: Long, status: String): List<HomeworkItemEntity>

    /** 按主键查询（返回 null 表示不存在） */
    @Query("SELECT * FROM homework_item WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): HomeworkItemEntity?

    /** 查询某学生当前最大优先级（无作业时返回 null），供新增作业时递增/追加排序 */
    @Query("SELECT MAX(priority) FROM homework_item WHERE student_id = :studentId")
    suspend fun maxPriority(studentId: Long): Int?

    /**
     * 查询某学生全部"已排定时间段"的作业（start_time 非空，按开始时间升序），
     * 供防冲突校验（新增/调整作业时比对时间段重叠）使用。
     */
    @Query(
        "SELECT * FROM homework_item WHERE student_id = :studentId AND start_time IS NOT NULL " +
            "ORDER BY start_time ASC",
    )
    suspend fun loadScheduledByStudent(studentId: Long): List<HomeworkItemEntity>

    /**
     * 事务化批量插入（阶段作业逐日展开最多 30 条时使用），避免中途失败留下「半套作业」。
     *
     * 注意：Room 会为 @Transaction 注解的默认方法生成「在单事务内执行」的实现，
     * 因此这里保持为接口默认方法而不是抽象方法（继承自 BaseDao 的 insertAll 无事务语义）。
     */
    @Transaction
    suspend fun insertAllInTransaction(items: List<HomeworkItemEntity>): List<Long> {
        val ids = mutableListOf<Long>()
        items.forEach { ids += insert(it) }
        return ids
    }
}
