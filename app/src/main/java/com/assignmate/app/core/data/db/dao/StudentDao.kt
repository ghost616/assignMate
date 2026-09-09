package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.assignmate.app.core.data.db.entity.StudentEntity

/**
 * 学生档案 DAO：查询全部走家长维度（parent_account_id），不提供跨家长全局查询。
 */
@Dao
interface StudentDao : BaseDao<StudentEntity> {

    /** 按家长列出其全部学生（按创建时间升序） */
    @Query("SELECT * FROM student WHERE parent_account_id = :parentAccountId ORDER BY created_at ASC")
    suspend fun findByParentAccountId(parentAccountId: Long): List<StudentEntity>

    /** 按家长 + 进入验证码查询学生（验证码未命中返回 null） */
    @Query(
        "SELECT * FROM student " +
            "WHERE parent_account_id = :parentAccountId AND verification_code = :verificationCode " +
            "LIMIT 1",
    )
    suspend fun findByParentAccountIdAndVerificationCode(
        parentAccountId: Long,
        verificationCode: String,
    ): StudentEntity?

    /** 按主键查询（返回 null 表示不存在） */
    @Query("SELECT * FROM student WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StudentEntity?
}
