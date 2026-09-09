package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.assignmate.app.core.data.db.entity.ParentAccountEntity

/**
 * 家长账号 DAO：账号唯一索引保证按 account 至多命中一条；密码校验/注册等业务在 auth 层。
 */
@Dao
interface ParentAccountDao : BaseDao<ParentAccountEntity> {

    /** 按登录账号查询唯一家长（不存在返回 null） */
    @Query("SELECT * FROM parent_account WHERE account = :account LIMIT 1")
    suspend fun findByAccount(account: String): ParentAccountEntity?

    /** 判断账号是否已被注册（存在即返回 true） */
    @Query("SELECT EXISTS(SELECT 1 FROM parent_account WHERE account = :account)")
    suspend fun existsByAccount(account: String): Boolean
}
