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
 *
 * @param zone 注入的业务时区（默认上海）：三处消费点（仓库归属日折算、每天详情折算、
 *   [setEpochDay] 的钟面构造）共用同一值，测试可据此证明「覆写唯一业务时区来源后
 *   阶段起止日与归属日同步切换」，而不是各自落到系统时区。
 */
class HomeworkTestEnv(val zone: ZoneId = ZONE) {

    val homeworkDao = FakeHomeworkItemDao()
    val parentDao = FakeParentAccountDao()
    val studentDao = FakeStudentDao()
    val store = FakeKeyValueStore()
    /** 可推进时钟：默认固定在 FIXED_MILLIS，便于构造创建时间先后与时段校验场景 */
    val clock = MutableClock(FIXED_MILLIS)
    val authRepository: AuthRepository = AuthRepositoryImpl(parentDao, studentDao, store, clock)

    /** 内存版「作业每天详情」仓库（core 契约的替身）：状态流转与阶段进度推导的真实链路 */
    val dailyRecordRepository = FakeHomeworkDailyRecordRepository(zone)
    val repository = HomeworkRepositoryImpl(
        homeworkDao,
        authRepository,
        clock,
        zone,
        dailyRecordRepository,
    )

    /** 把时钟直接设到某个 epochDay 的 09:13:20（业务时区），用于构造「阶段已多天前开始」等场景 */
    fun setEpochDay(epochDay: Long, hour: Int = 9, minute: Int = 13) {
        clock.set(
            java.time.LocalDate.ofEpochDay(epochDay)
                .atTime(hour, minute)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
        )
    }

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

        /** 业务时区（与仓库/每天详情仓库的注入口径一致） */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 固定时钟：2023-11-14T22:13:20Z（与 auth 测试一致，便于跨模块心智一致） */
        const val FIXED_MILLIS = 1_700_000_000_000L
    }
}