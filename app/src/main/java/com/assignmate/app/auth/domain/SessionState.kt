package com.assignmate.app.auth.domain

/**
 * 当前会话状态快照：当前角色 + 当前家长 id + 当前学生 id。
 *
 * 持久化：auth 仓库经 KeyValueStore 落盘并对外提供可观察 Flow；
 * homework/timer/stats 依据 [role]/[parentId]/[studentId] 圈定数据范围。
 * 约定：角色为 PARENT 时 [studentId] 为 null；角色为 STUDENT 时两者均非空
 * （学生必然归属于某位家长账号，便于按家长维度取数）。
 */
data class SessionState(
    val role: Role? = null,
    val parentId: Long? = null,
    val studentId: Long? = null,
) {

    /** 是否存在有效会话（未登出） */
    val isActive: Boolean get() = role != null

    /** 当前是否家长会话 */
    val isParent: Boolean get() = role == Role.PARENT

    /** 当前是否学生会话（且已关联家长与本人档案） */
    val isStudent: Boolean get() = role == Role.STUDENT && parentId != null && studentId != null

    companion object {

        /** 未登录/已登出的空会话 */
        val NONE = SessionState()
    }
}