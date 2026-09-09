package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update

/**
 * 通用 DAO 基接口：提供统一增删改能力，实体类型由各业务 DAO 泛型实体化。
 *
 * 用法：@Dao interface XxxDao : BaseDao<XxxEntity> { /* 自定义 @Query */ }
 * Room 会合并父接口标注方法并生成实现。
 */
@Dao
interface BaseDao<T> {

    /** 插入单条，返回自增主键 */
    @Insert
    suspend fun insert(item: T): Long

    /** 批量插入，返回自增主键列表 */
    @Insert
    suspend fun insertAll(items: List<T>): List<Long>

    /** 按主键更新（不存在的记录会被忽略） */
    @Update
    suspend fun update(item: T)

    /** 按主键删除 */
    @Delete
    suspend fun delete(item: T)
}
