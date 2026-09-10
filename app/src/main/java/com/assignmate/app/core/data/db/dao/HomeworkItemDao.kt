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

    /**
     * 事务化的「落位重排」：把 [homeworkId] 移动到 [targetIndex]（越界收敛），
     * 其余项顺延并按 MIN_PRIORITY 起等步长整体重排优先级。
     *
     * 为什么放在 DAO：仓库层需要「读全清单 → 计算新顺序 → 批量写优先级」三段原子完成，
     * 否则读与写之间作业可能被并发置为「进行中」或被删除，导致完成一次已失效的重排
     * （进行中锁定的判定依据在写入前已过期）。@Transaction 默认方法由 Room 生成单事务实现。
     *
     * 事务内会重读清单并在写入前复查 [LOCKED_STATUS]（进行中锁定），命中则不改动任何数据。
     * 业务规则（谁能调序、优先级步长）仍在 homework 层，本方法只承载锁定这一与写入原子性强绑定的守卫。
     */
    @Transaction
    suspend fun moveToPositionInTransaction(homeworkId: Long, targetIndex: Int): MovePositionOutcome {
        val item = findById(homeworkId) ?: return MovePositionOutcome.NOT_FOUND
        if (item.status == LOCKED_STATUS) {
            return MovePositionOutcome.LOCKED
        }
        val ordered = loadByStudent(item.studentId)
        val from = ordered.indexOfFirst { it.id == homeworkId }
        if (from < 0) {
            return MovePositionOutcome.NOT_FOUND
        }
        val to = targetIndex.coerceIn(0, ordered.lastIndex)
        if (from == to) {
            return MovePositionOutcome.SUCCESS
        }
        val reordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        reordered.forEachIndexed { index, entity ->
            val expected = MIN_PRIORITY + index * PRIORITY_STEP
            if (entity.priority != expected) {
                update(entity.copy(priority = expected))
            }
        }
        return MovePositionOutcome.SUCCESS
    }
}

/**
 * 进行中锁定状态列取值（与 homework 的 HomeworkStatus.IN_PROGRESS.name 同值）；
 * DAO 侧只做该单一状态的守卫比较，不依赖业务枚举，避免 core → homework 的反向依赖。
 *
 * 未放到接口 companion 内是因为该常量被 [HomeworkItemDao.moveToPositionInTransaction]
 * 这一接口默认方法直接引用，而接口默认方法内不能引用本接口 companion 的成员。
 */
private const val LOCKED_STATUS: String = "IN_PROGRESS"

/** 重排后的首个优先级与步长（与 homework 的 HomeworkConstants 口径一致） */
private const val MIN_PRIORITY = 0
private const val PRIORITY_STEP = 1

/** [HomeworkItemDao.moveToPositionInTransaction] 的结果（不改动数据的分支同样以此回报） */
enum class MovePositionOutcome {

    /** 重排成功（含目标位置与当前位置相同、或已在目标位置的幂等场景） */
    SUCCESS,

    /** 作业不存在（可能已被删除） */
    NOT_FOUND,

    /** 作业已进入「进行中」：拒绝重排且不改动任何数据 */
    LOCKED,
}
