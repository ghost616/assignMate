package com.assignmate.app.auth.data

import com.assignmate.app.auth.domain.PasswordHasher
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.dao.StudentDao
import com.assignmate.app.core.domain.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthRepositoryImpl 仓库关键流程单测：注册唯一性/密码哈希、登录成败、
 * 学生上限拦截、验证码管理与冲突、学生进入校验、会话持久化与观察流。
 * DAO 使用内存 fake（FakeParentAccountDao/FakeStudentDao/FakeKeyValueStore）+ 固定时钟。
 */
class AuthRepositoryImplTest {

    private val account = "parent001"
    private val password = "pwd-123456"

    private class Env {
        val parentDao = FakeParentAccountDao()
        val studentDao = FakeStudentDao()
        val store = FakeKeyValueStore()
        val clock: Clock = FIXED_CLOCK
        val repository = AuthRepositoryImpl(parentDao, studentDao, store, clock)
    }

    private companion object {
        const val FIXED_MILLIS = 1_700_000_000_000L

        /** 固定时钟：测试不依赖真实时间，保证验证码生成、创建时间等断言确定 */
        val FIXED_CLOCK: Clock = Clock { FIXED_MILLIS }
    }

    private fun newEnv(): Env = Env()

    // ---- 注册 ----

    @Test
    fun `注册成功 密码仅存哈希且建立家长会话`() = runTest {
        val env = newEnv()
        val result = env.repository.registerParent(account, password)

        assertEquals(ParentRegisterResult.Success, result)
        val rows = env.parentDao.all()
        assertEquals(1, rows.size)
        val savedHash = rows[0].passwordHash
        assertNotEquals(password, savedHash)
        assertTrue(PasswordHasher.verify(password, savedHash))

        val session = env.repository.currentSession()
        assertTrue(session.isParent)
        assertEquals(rows[0].id, session.parentId)
        assertNull(session.studentId)
    }

    @Test
    fun `账号重复注册被唯一性拦截且仅保留一条记录`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val second = env.repository.registerParent(account, "another-pass1")

        assertTrue(second is ParentRegisterResult.Failure)
        assertEquals(
            ParentRegisterFailure.ACCOUNT_ALREADY_EXISTS,
            (second as ParentRegisterResult.Failure).reason,
        )
        assertEquals(1, env.parentDao.all().size)
    }

    @Test
    fun `注册账号自动去除首尾空白`() = runTest {
        val env = newEnv()
        val result = env.repository.registerParent("  $account  ", password)

        assertEquals(ParentRegisterResult.Success, result)
        assertEquals(account, env.parentDao.all().single().account)
    }

    @Test
    fun `注册账号统一转小写后入库`() = runTest {
        val env = newEnv()
        val result = env.repository.registerParent("  Parent001  ", password)

        assertEquals(ParentRegisterResult.Success, result)
        assertEquals("parent001", env.parentDao.all().single().account)
    }

    @Test
    fun `账号大小写不同形式重复注册被唯一性拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent("Parent001", password)
        val second = env.repository.registerParent("PARENT001", "another-pass1")

        assertTrue(second is ParentRegisterResult.Failure)
        assertEquals(
            ParentRegisterFailure.ACCOUNT_ALREADY_EXISTS,
            (second as ParentRegisterResult.Failure).reason,
        )
        assertEquals(1, env.parentDao.all().size)
    }

    @Test
    fun `注册非法账号或密码被格式拦截且不入库`() = runTest {
        val env = newEnv()
        val badAccount = env.repository.registerParent("ab", password)
        val badPassword = env.repository.registerParent(account, "123")

        assertTrue(badAccount is ParentRegisterResult.Failure)
        assertEquals(
            ParentRegisterFailure.INVALID_ACCOUNT,
            (badAccount as ParentRegisterResult.Failure).reason,
        )
        assertTrue(badPassword is ParentRegisterResult.Failure)
        assertEquals(
            ParentRegisterFailure.INVALID_PASSWORD,
            (badPassword as ParentRegisterResult.Failure).reason,
        )
        assertEquals(0, env.parentDao.all().size)
    }

    // ---- 登录 ----

    @Test
    fun `登录成功建立家长会话`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)

        val result = env.repository.loginParent(account, password)
        assertEquals(ParentLoginResult.Success, result)
        val session = env.repository.currentSession()
        assertTrue(session.isParent)
        assertEquals(env.parentDao.all().single().id, session.parentId)
    }

    @Test
    fun `注册后以大小写不同形式登录成功`() = runTest {
        val env = newEnv()
        env.repository.registerParent("Parent001", password)

        val result = env.repository.loginParent("parent001", password)
        assertEquals(ParentLoginResult.Success, result)
        val session = env.repository.currentSession()
        assertTrue(session.isParent)
        assertEquals(env.parentDao.all().single().id, session.parentId)
    }

    @Test
    fun `登录密码错误被拦截且原家长会话保持`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val expectedParentId = env.parentDao.all().single().id

        val result = env.repository.loginParent(account, "wrong-pass")
        assertTrue(result is ParentLoginResult.Failure)
        assertEquals(
            ParentLoginFailure.WRONG_PASSWORD,
            (result as ParentLoginResult.Failure).reason,
        )
        // 失败登录不破坏已建立的家长会话
        val session = env.repository.currentSession()
        assertTrue(session.isParent)
        assertEquals(expectedParentId, session.parentId)
    }

    @Test
    fun `登录不存在的账号被拦截`() = runTest {
        val env = newEnv()
        val result = env.repository.loginParent("nobody001", password)

        assertTrue(result is ParentLoginResult.Failure)
        assertEquals(
            ParentLoginFailure.ACCOUNT_NOT_FOUND,
            (result as ParentLoginResult.Failure).reason,
        )
    }

    // ---- 学生档案与上限 ----

    @Test
    fun `新增学生自动生成六位进入验证码`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!

        val result = env.repository.addStudent(parentId, "小明")
        assertTrue(result is AddStudentResult.Success)
        val student = (result as AddStudentResult.Success).student
        assertEquals("小明", student.name)
        assertEquals(6, student.verificationCode.length)
        assertTrue(student.verificationCode.all { it.isDigit() })
        assertEquals(1, env.studentDao.all().size)
    }

    @Test
    fun `新增学生超过五名被拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        repeat(5) { index ->
            val ok = env.repository.addStudent(parentId, "学生$index")
            assertTrue(ok is AddStudentResult.Success)
        }

        val sixth = env.repository.addStudent(parentId, "第六人")
        assertTrue(sixth is AddStudentResult.Failure)
        assertEquals(
            AddStudentFailure.STUDENT_LIMIT_REACHED,
            (sixth as AddStudentResult.Failure).reason,
        )
        assertEquals(5, env.studentDao.all().size)
    }

    @Test
    fun `自动生成的学生验证码在家长内互不重复`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val codes = mutableSetOf<String>()
        repeat(5) { index ->
            val result = env.repository.addStudent(parentId, "学生$index")
            val student = (result as AddStudentResult.Success).student
            codes += student.verificationCode
        }
        assertEquals(5, codes.size)
    }

    @Test
    fun `新增空白姓名学生被拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!

        val result = env.repository.addStudent(parentId, "   ")
        assertTrue(result is AddStudentResult.Failure)
        assertEquals(
            AddStudentFailure.INVALID_NAME,
            (result as AddStudentResult.Failure).reason,
        )
    }

    @Test
    fun `按家长列出学生按创建顺序返回`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        env.repository.addStudent(parentId, "小美")
        env.repository.addStudent(parentId, "小刚")

        val list = env.repository.listStudents(parentId)
        assertEquals(listOf("小美", "小刚"), list.map { it.name })
    }

    @Test
    fun `重命名学生成功且修剪姓名`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.renameStudent(added.id, "  大明  ")
        assertEquals(RenameStudentResult.Success, result)
        assertEquals("大明", env.repository.getStudent(added.id)?.name)
    }

    @Test
    fun `重命名不存在学生被拦截`() = runTest {
        val env = newEnv()
        val result = env.repository.renameStudent(999L, "小明")
        assertTrue(result is RenameStudentResult.Failure)
        assertEquals(
            RenameStudentFailure.STUDENT_NOT_FOUND,
            (result as RenameStudentResult.Failure).reason,
        )
    }

    @Test
    fun `删除学生成功且列表同步`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.deleteStudent(added.id)
        assertEquals(DeleteStudentResult.Success, result)
        assertTrue(env.repository.listStudents(parentId).isEmpty())
    }

    // ---- 家长-学生归属校验（统一口径，供 homework/timer/stats 复用） ----

    @Test
    fun `名下学生归属校验为真`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        assertTrue(env.repository.isStudentOwnedBy(parentId, added.id))
    }

    @Test
    fun `他人学生归属校验为假`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val firstParentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(firstParentId, "小明") as AddStudentResult.Success).student
        env.repository.registerParent("parent002", password)
        val secondParentId = env.repository.currentSession().parentId!!

        assertNotEquals(firstParentId, secondParentId)
        assertTrue(env.repository.isStudentOwnedBy(firstParentId, added.id))
        assertFalse(env.repository.isStudentOwnedBy(secondParentId, added.id))
    }

    @Test
    fun `学生不存在时归属校验为假`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!

        assertFalse(env.repository.isStudentOwnedBy(parentId, 999L))
        // 家长不存在同样收敛为假
        assertFalse(env.repository.isStudentOwnedBy(999L, 999L))
    }

    @Test
    fun `非法家长或学生 id 归属校验为假`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        assertFalse(env.repository.isStudentOwnedBy(0L, added.id))
        assertFalse(env.repository.isStudentOwnedBy(-1L, added.id))
        assertFalse(env.repository.isStudentOwnedBy(parentId, 0L))
        assertFalse(env.repository.isStudentOwnedBy(parentId, -1L))
    }

    @Test
    fun `非法 id 不触达数据层直接返回`() = runTest {
        val dao = mockk<StudentDao>()
        val repository = AuthRepositoryImpl(FakeParentAccountDao(), dao, FakeKeyValueStore(), FIXED_CLOCK)

        assertFalse(repository.isStudentOwnedBy(0L, 1L))
        assertTrue(repository.ownedStudentIds(0L).isEmpty())
        coVerify(exactly = 0) { dao.findById(any()) }
        coVerify(exactly = 0) { dao.findByParentAccountId(any()) }
    }

    @Test
    fun `ownedStudentIds 返回名下全部学生 id`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val first = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student
        val second = (env.repository.addStudent(parentId, "小美") as AddStudentResult.Success).student

        assertEquals(setOf(first.id, second.id), env.repository.ownedStudentIds(parentId))
    }

    @Test
    fun `ownedStudentIds 无学生或家长不存在返回空集`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        env.repository.registerParent("parent002", password)
        val otherParentId = env.repository.currentSession().parentId!!
        env.repository.addStudent(otherParentId, "小刚")

        assertTrue(env.repository.ownedStudentIds(parentId).isEmpty())
        assertTrue(env.repository.ownedStudentIds(999L).isEmpty())
    }

    @Test
    fun `ownedStudentIds 非法家长 id 返回空集`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)

        assertTrue(env.repository.ownedStudentIds(0L).isEmpty())
        assertTrue(env.repository.ownedStudentIds(-1L).isEmpty())
    }

    @Test
    fun `归属查询数据层异常时收敛为假与空集`() = runTest {
        val dao = mockk<StudentDao>()
        coEvery { dao.findById(any()) } throws IllegalStateException("db down")
        coEvery { dao.findByParentAccountId(any()) } throws IllegalStateException("db down")
        val repository = AuthRepositoryImpl(FakeParentAccountDao(), dao, FakeKeyValueStore(), FIXED_CLOCK)

        assertFalse(repository.isStudentOwnedBy(1L, 2L))
        assertTrue(repository.ownedStudentIds(1L).isEmpty())
    }

    @Test
    fun `归属查询遇协程取消异常时继续向上传播`() {
        val dao = mockk<StudentDao>()
        coEvery { dao.findById(any()) } throws CancellationException("coroutine cancelled")
        coEvery { dao.findByParentAccountId(any()) } throws CancellationException("coroutine cancelled")
        val repository = AuthRepositoryImpl(FakeParentAccountDao(), dao, FakeKeyValueStore(), FIXED_CLOCK)

        // 取消信号不得被"异常收敛为 false/空集"吞掉，否则破坏结构化并发
        assertEquals("coroutine cancelled", runAndCaptureCancellation { repository.isStudentOwnedBy(1L, 2L) })
        assertEquals("coroutine cancelled", runAndCaptureCancellation { repository.ownedStudentIds(1L) })
    }

    /** 执行 suspend 查询并捕获其抛出的 [CancellationException]（未抛出则本用例失败） */
    private fun runAndCaptureCancellation(block: suspend () -> Unit): String? {
        var caught: CancellationException? = null
        runTest {
            try {
                block()
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertNotNull("查询吞掉了协程取消异常，破坏了结构化并发", caught)
        return caught?.message
    }

    // ---- 验证码管理 ----

    @Test
    fun `重置验证码生成新码且与旧码不同`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.resetStudentVerificationCode(added.id)
        assertTrue(result is UpdateVerificationCodeResult.Success)
        val newCode = (result as UpdateVerificationCodeResult.Success).newCode
        assertEquals(6, newCode.length)
        assertNotEquals(added.verificationCode, newCode)
        assertEquals(newCode, env.repository.getStudent(added.id)?.verificationCode)
    }

    @Test
    fun `自定义验证码成功生效`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.updateStudentVerificationCode(added.id, "8888")
        assertTrue(result is UpdateVerificationCodeResult.Success)
        assertEquals("8888", env.repository.getStudent(added.id)?.verificationCode)
    }

    @Test
    fun `自定义验证码格式非法被拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.updateStudentVerificationCode(added.id, "12ab")
        assertTrue(result is UpdateVerificationCodeResult.Failure)
        assertEquals(
            UpdateVerificationCodeFailure.INVALID_CODE,
            (result as UpdateVerificationCodeResult.Failure).reason,
        )
    }

    @Test
    fun `自定义验证码与同家长学生冲突被拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val first = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student
        val second = (env.repository.addStudent(parentId, "小美") as AddStudentResult.Success).student

        val result = env.repository.updateStudentVerificationCode(second.id, first.verificationCode)
        assertTrue(result is UpdateVerificationCodeResult.Failure)
        assertEquals(
            UpdateVerificationCodeFailure.CODE_ALREADY_USED,
            (result as UpdateVerificationCodeResult.Failure).reason,
        )
    }

    // ---- 学生进入校验 ----

    @Test
    fun `学生凭家长账号与验证码进入成功建立学生会话`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.enterAsStudent(account, added.verificationCode)
        assertTrue(result is StudentEnterResult.Success)
        assertEquals("小明", (result as StudentEnterResult.Success).student.name)

        val session = env.repository.currentSession()
        assertEquals(Role.STUDENT, session.role)
        assertEquals(parentId, session.parentId)
        assertEquals(added.id, session.studentId)
    }

    @Test
    fun `学生进入时家长账号大小写不同仍可匹配`() = runTest {
        val env = newEnv()
        env.repository.registerParent("Parent001", password)
        val parentId = env.repository.currentSession().parentId!!
        val added = (env.repository.addStudent(parentId, "小明") as AddStudentResult.Success).student

        val result = env.repository.enterAsStudent("PARENT001", added.verificationCode)
        assertTrue(result is StudentEnterResult.Success)
        assertEquals("小明", (result as StudentEnterResult.Success).student.name)

        val session = env.repository.currentSession()
        assertEquals(Role.STUDENT, session.role)
        assertEquals(parentId, session.parentId)
        assertEquals(added.id, session.studentId)
    }

    @Test
    fun `验证码错误被拦截且不改变会话`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        val parentId = env.repository.currentSession().parentId!!

        val result = env.repository.enterAsStudent(account, "999999")
        assertTrue(result is StudentEnterResult.Failure)
        assertEquals(
            StudentEnterFailure.CODE_MISMATCH,
            (result as StudentEnterResult.Failure).reason,
        )
        val session = env.repository.currentSession()
        assertEquals(Role.PARENT, session.role)
        assertEquals(parentId, session.parentId)
    }

    @Test
    fun `验证码格式非法被拦截`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)

        val result = env.repository.enterAsStudent(account, "12ab")
        assertTrue(result is StudentEnterResult.Failure)
        assertEquals(
            StudentEnterFailure.INVALID_VERIFICATION_CODE,
            (result as StudentEnterResult.Failure).reason,
        )
    }

    @Test
    fun `家长账号不存在时学生进入被拦截`() = runTest {
        val env = newEnv()
        val result = env.repository.enterAsStudent("nobody001", "123456")
        assertTrue(result is StudentEnterResult.Failure)
        assertEquals(
            StudentEnterFailure.PARENT_ACCOUNT_NOT_FOUND,
            (result as StudentEnterResult.Failure).reason,
        )
    }

    // ---- 会话 ----

    @Test
    fun `退出登录清除全部会话键`() = runTest {
        val env = newEnv()
        env.repository.registerParent(account, password)
        assertTrue(env.repository.currentSession().isActive)

        env.repository.logout()
        assertEquals(SessionState.NONE, env.repository.currentSession())
    }

    @Test
    fun `观察会话流随登录与登出变化`() = runTest {
        val env = newEnv()
        assertEquals(SessionState.NONE, env.repository.observeSession().first())

        env.repository.registerParent(account, password)
        val afterRegister = env.repository.observeSession().first { it.parentId != null }
        assertNotNull(afterRegister.parentId)
        assertEquals(Role.PARENT, afterRegister.role)

        env.repository.logout()
        val afterLogout = env.repository.observeSession().first { !it.isActive }
        assertEquals(SessionState.NONE, afterLogout)
    }
}