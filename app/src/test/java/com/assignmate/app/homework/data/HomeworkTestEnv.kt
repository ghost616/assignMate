package com.assignmate.app.homework.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.AuthRepositoryImpl
import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.FakeKeyValueStore
import com.assignmate.app.auth.data.FakeParentAccountDao
import com.assignmate.app.auth.data.FakeStudentDao
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.time.Clock
import java.time.ZoneId

/**
 * homework 测试环境：聚合内存版 DAO / KeyValueStore 与固定时钟，
 * 复用 auth 的真实仓库实现来建立家长/学生会话（避免手写会话替身导致与生产语义漂移）。
 */
class HomeworkTestEnv {

    val homeworkDao = FakeHomeworkItemDao()
    val parentDao = FakeParentAccountDao()
    val studentDao = FakeStudentDao()
    val store = FakeKeyValueStore()
    /** 可推进时钟：默认固定在 FIXED_MILLIS，便于构造创建时间先后与时段校验场景 */
    val clock = MutableClock(FIXED_MILLIS)
    val authRepository: AuthRepository = AuthRepositoryImpl(parentDao, studentDao, store, clock)
    val repository = HomeworkRepositoryImpl(homeworkDao, authRepository, clock, ZoneId.of("Asia/Shanghai"))

    /** 推进时钟（毫秒），用于让后创建项的 created_at 更大（排序确定） */
    fun advance(millis: Long) = clock.advance(millis)

    /** 建立家长会话，返回家长 id */
    suspend fun loginAsParent(account: String = ACCOUNT, password: String = PASSWORD): Long {
        authRepository.registerParent(account, password)
        return authRepository.currentSession().parentId!!
    }

    /** 新增学生并返回学生 id */
    suspend fun addStudent(parentId: Long, name: String = "小明"): Long =
        (authRepository.addStudent(parentId, name) as AddStudentResult.Success).student.id

    /** 切换到学生会话（学生进入） */
    suspend fun loginAsStudent(parentId: Long, studentId: Long, account: String = ACCOUNT) {
        val student = studentDao.findById(studentId)!!
        authRepository.enterAsStudent(account, student.verificationCode)
    }

    /** 退出登录（构造无会话场景） */
    suspend fun logout() = authRepository.logout()

    suspend fun currentSession(): SessionState = authRepository.currentSession()

    companion object {

        const val ACCOUNT = "parent001"
        const val PASSWORD = "pwd-123456"

        /** 固定时钟：2023-11-14T22:13:20Z（与 auth 测试一致，便于跨模块心智一致） */
        const val FIXED_MILLIS = 1_700_000_000_000L
    }
}