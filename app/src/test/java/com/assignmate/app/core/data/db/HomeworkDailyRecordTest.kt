package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.entity.HomeworkDailyRecordEntity
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「作业每天详情」表与仓库的单测（纯 JVM，无需设备/模拟器）。
 *
 * 覆盖口径：
 * - 新建表与 DAO 读写：按（作业 + 自然日）读/写/更新、按（学生 + 自然日）批量查询、按作业查询全部天；
 * - 唯一约束（homework_id + epoch_day）：同一天重复插入不产生两条；
 * - 级联删除：作业删除时其每天详情一并清理、删某作业全部天返回条数；
 * - 自然日维度：timer_session / pause_record 的 epoch_day 读写（实体无 Kotlin 默认值，见独立用例）；
 * - 仓库层：entity/domain 映射（含未知状态兜底）、业务时区 epochDay 折算（禁止 UTC 折算）
 *   与 [HomeworkDailyRecordRepository.updateExecution] 的整体覆盖（传 null 清空）语义。
 */
class HomeworkDailyRecordTest {

    private val dao = FakeHomeworkDailyRecordDao()
    private val repository = HomeworkDailyRecordRepositoryImpl(dao, ZoneId.of("Asia/Shanghai"))

    // ---- 建表与 DAO 读写 ----

    @Test
    fun `按作业与自然日写入后可精确读回且字段完整`() = runTest {
        val id = repository.upsert(
            record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.IN_PROGRESS)
                .copy(
                    startedAtMillis = DAY_1_START,
                    estimatedMinutes = 30,
                    actualMinutes = 12,
                    pauseCount = 2,
                    pausedTotalMinutes = 5,
                ),
        )
        assertTrue("自增主键必须由数据库分配", id > 0L)

        val loaded = repository.find(7L, DAY_1)
        assertNotNull(loaded)
        requireNotNull(loaded)
        assertEquals(id, loaded.id)
        assertEquals(7L, loaded.homeworkId)
        assertEquals(3L, loaded.studentId)
        assertEquals(DAY_1, loaded.epochDay)
        assertEquals(HomeworkDayStatus.IN_PROGRESS, loaded.status)
        assertEquals(DAY_1_START, loaded.startedAtMillis)
        assertEquals(30, loaded.estimatedMinutes)
        assertEquals(12, loaded.actualMinutes)
        assertEquals(2, loaded.pauseCount)
        assertEquals(5, loaded.pausedTotalMinutes)
        assertNull("未完成时完成时刻必须为 null", loaded.finishedAtMillis)
    }

    @Test
    fun `同一天重复写入不产生两条记录（唯一约束）`() = runTest {
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        val secondId = repository.upsert(
            record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.IN_PROGRESS)
                .copy(pauseCount = 1),
        )

        assertEquals("同一作业同一天只允许一条详情", 1, dao.snapshot().size)
        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertEquals(secondId, loaded.id)
        assertEquals("重复写入应原地更新状态", HomeworkDayStatus.IN_PROGRESS, loaded.status)
        assertEquals(1, loaded.pauseCount)
    }

    @Test
    fun `同一作业不同自然日各自成条且按升序返回`() = runTest {
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_2, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.COMPLETED))

        val all = repository.loadByHomework(7L)
        assertEquals(listOf(DAY_1, DAY_2), all.map { it.epochDay })
        assertEquals(listOf(HomeworkDayStatus.COMPLETED, HomeworkDayStatus.NOT_STARTED), all.map { it.status })
    }

    @Test
    fun `按学生与自然日可批量查询当天全部作业且不串天不串学生`() = runTest {
        repository.upsert(record(homeworkId = 1L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 2L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.COMPLETED))
        // 干扰数据：同学生前一天、同一天其他学生（必须换作业 id——
        // (homework_id, epoch_day) 唯一，同一作业同一天不可能属于两个学生）
        repository.upsert(record(homeworkId = 1L, studentId = 3L, epochDay = DAY_2, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 3L, studentId = 9L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))

        val snapshot = repository.loadByStudentAndDay(3L, DAY_1)
        assertEquals(listOf(1L, 2L), snapshot.map { it.homeworkId })
        assertTrue(snapshot.all { it.studentId == 3L && it.epochDay == DAY_1 })

        val observed = repository.observeByStudentAndDay(3L, DAY_1).first()
        assertEquals(listOf(1L, 2L), observed.map { it.homeworkId })
    }

    @Test
    fun `按作业观察全部天详情可随写入刷新且只含该作业`() = runTest {
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        assertEquals(1, repository.observeByHomework(7L).first().size)

        repository.upsert(record(homeworkId = 8L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_2, status = HomeworkDayStatus.MISSED))

        val days = repository.observeByHomework(7L).first()
        assertEquals(listOf(DAY_1, DAY_2), days.map { it.epochDay })
        assertEquals("未完成（缺卡）状态需原样往返", HomeworkDayStatus.MISSED, days.last().status)
    }

    @Test
    fun `更新状态与更新执行数据互不覆盖`() = runTest {
        val id = repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        repository.updateExecution(
            id = id,
            startedAtMillis = DAY_1_START,
            estimatedMinutes = 25,
            actualMinutes = 20,
            pauseCount = 3,
            pausedTotalMinutes = 4,
            finishedAtMillis = DAY_1_START + 20 * 60_000L,
        )
        repository.updateStatus(id, HomeworkDayStatus.COMPLETED)

        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertEquals(HomeworkDayStatus.COMPLETED, loaded.status)
        assertEquals(25, loaded.estimatedMinutes)
        assertEquals(20, loaded.actualMinutes)
        assertEquals(3, loaded.pauseCount)
        assertEquals(4, loaded.pausedTotalMinutes)
        assertEquals(DAY_1_START + 20 * 60_000L, loaded.finishedAtMillis)
    }

    @Test
    fun `更新执行数据为整体覆盖 传 null 即清空该字段`() = runTest {
        val id = repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.IN_PROGRESS))
        repository.updateExecution(
            id = id,
            startedAtMillis = DAY_1_START,
            estimatedMinutes = 30,
            actualMinutes = 12,
            pauseCount = 2,
            pausedTotalMinutes = 5,
            finishedAtMillis = DAY_1_START + 12 * 60_000L,
        )

        // 整体覆盖：startedAt / estimatedMinutes / finishedAt 传 null -> 三列被清空
        repository.updateExecution(
            id = id,
            startedAtMillis = null,
            estimatedMinutes = null,
            actualMinutes = 12,
            pauseCount = 0,
            pausedTotalMinutes = 0,
            finishedAtMillis = null,
        )

        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertNull("started_at 传 null 即清空（不是保留原值）", loaded.startedAtMillis)
        assertNull("estimated_minutes 传 null 即清空", loaded.estimatedMinutes)
        assertNull("finished_at 传 null 即清空", loaded.finishedAtMillis)
        assertEquals("原样传入的字段按新值写入", 12, loaded.actualMinutes)
        assertEquals(0, loaded.pauseCount)
        assertEquals(0, loaded.pausedTotalMinutes)
    }

    @Test
    fun `更新执行数据传 null 清空后仍可重新写回执行数据`() = runTest {
        val id = repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.IN_PROGRESS))
        repository.updateExecution(
            id = id,
            startedAtMillis = null,
            estimatedMinutes = null,
            actualMinutes = null,
            pauseCount = 0,
            pausedTotalMinutes = 0,
            finishedAtMillis = null,
        )

        val cleared = repository.find(7L, DAY_1)
        requireNotNull(cleared)
        assertNull(cleared.startedAtMillis)
        assertNull(cleared.actualMinutes)

        // 清空后不残留「不可再写」的中间态：重新写入完整快照仍可读回
        repository.updateExecution(
            id = id,
            startedAtMillis = DAY_1_START,
            estimatedMinutes = 25,
            actualMinutes = 25,
            pauseCount = 1,
            pausedTotalMinutes = 3,
            finishedAtMillis = DAY_1_START + 25 * 60_000L,
        )
        val rewritten = repository.find(7L, DAY_1)
        requireNotNull(rewritten)
        assertEquals(DAY_1_START, rewritten.startedAtMillis)
        assertEquals(25, rewritten.actualMinutes)
        assertEquals(1, rewritten.pauseCount)
        assertEquals(DAY_1_START + 25 * 60_000L, rewritten.finishedAtMillis)
        assertEquals("状态字段不因执行数据写入而改变", HomeworkDayStatus.IN_PROGRESS, rewritten.status)
    }

    @Test
    fun `按状态便捷写入在已存在时保留创建时刻与执行数据`() = runTest {
        val firstId = repository.upsertStatus(
            homeworkId = 7L,
            studentId = 3L,
            epochDay = DAY_1,
            status = HomeworkDayStatus.NOT_STARTED,
            nowMillis = DAY_1_CREATED,
        )
        repository.updateExecution(
            id = firstId,
            startedAtMillis = DAY_1_START,
            estimatedMinutes = 10,
            actualMinutes = 8,
            pauseCount = 1,
            pausedTotalMinutes = 2,
            finishedAtMillis = null,
        )

        val secondId = repository.upsertStatus(
            homeworkId = 7L,
            studentId = 3L,
            epochDay = DAY_1,
            status = HomeworkDayStatus.IN_PROGRESS,
            nowMillis = DAY_1_CREATED + 3_600_000L,
        )

        assertEquals(1, dao.snapshot().size)
        assertEquals(firstId, secondId)
        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertEquals(HomeworkDayStatus.IN_PROGRESS, loaded.status)
        assertEquals("重复写入不得改写创建时刻", DAY_1_CREATED, loaded.createdAtMillis)
        assertEquals("状态更新不得抹掉执行数据", 10, loaded.estimatedMinutes)
        assertEquals(1, loaded.pauseCount)
    }

    @Test
    fun `便捷写入在当天无记录时按传入时刻新建`() = runTest {
        val id = repository.upsertStatus(
            homeworkId = 7L,
            studentId = 3L,
            epochDay = DAY_1,
            status = HomeworkDayStatus.MISSED,
            nowMillis = DAY_1_CREATED,
        )
        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertEquals(id, loaded.id)
        assertEquals(HomeworkDayStatus.MISSED, loaded.status)
        assertEquals(DAY_1_CREATED, loaded.createdAtMillis)
        assertEquals(0, loaded.pauseCount)
        assertEquals(0, loaded.pausedTotalMinutes)
    }

    @Test
    fun `唯一约束在绕过仓库直接重复插入时同样拦截`() = runTest {
        dao.insert(entity(homeworkId = 7L, epochDay = DAY_1))
        try {
            dao.insert(entity(homeworkId = 7L, epochDay = DAY_1))
            fail("同（作业 + 自然日）重复插入必须被唯一约束拦截")
        } catch (expected: IllegalArgumentException) {
            assertEquals(1, dao.snapshot().size)
        }
    }

    // ---- 级联清理 ----

    @Test
    fun `删除作业时级联清理其每天详情`() = runTest {
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_2, status = HomeworkDayStatus.COMPLETED))
        repository.upsert(record(homeworkId = 8L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))

        val removed = dao.cascadeDeleteByHomework(7L)

        assertEquals(2, removed)
        assertEquals(emptyList<Long>(), repository.loadByHomework(7L))
        assertEquals("其他作业的每日详情不受影响", 1, repository.loadByHomework(8L).size)
    }

    @Test
    fun `删除某作业全部天详情返回条数且可空跑`() = runTest {
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_1, status = HomeworkDayStatus.NOT_STARTED))
        repository.upsert(record(homeworkId = 7L, studentId = 3L, epochDay = DAY_2, status = HomeworkDayStatus.COMPLETED))

        assertEquals(2, repository.deleteByHomework(7L))
        assertEquals(0, repository.deleteByHomework(7L))
        assertNull(repository.find(7L, DAY_1))
    }

    // ---- 自然日维度：timer_session / pause_record ----

    @Test
    fun `计时会话与暂停明细携带业务自然日`() = runTest {
        val session = TimerSessionEntity(
            homeworkId = 7L,
            studentId = 3L,
            parentAccountId = 1L,
            epochDay = DAY_1,
            startedAt = Instant.ofEpochMilli(DAY_1_START),
            status = "RUNNING",
        )
        // 归属日按显式入参原样落在实体上（Kotlin 侧无默认值，漏传在编译期即失败，
        // 见 EntityEpochDayDefaultTest；列默认值 0 只服务于 v4 旧库 ALTER TABLE 加列）
        assertEquals(DAY_1, session.epochDay)

        val pause = PauseRecordEntity(
            sessionId = 1L,
            homeworkId = 7L,
            epochDay = DAY_1,
            pauseStartAt = Instant.ofEpochMilli(DAY_1_START),
        )
        assertEquals(DAY_1, pause.epochDay)
        assertEquals("暂停中时结束时刻为 null", null, pause.pauseEndAt)
    }

    // ---- 仓库层：业务时区折算与未知状态兜底 ----

    @Test
    fun `epochDay 按业务时区折算 凌晨时刻不得落到前一天`() {
        // 2025-01-05 00:30（Asia/Shanghai, UTC+8）== 2025-01-04T16:30Z
        val millis = 1_736_008_200_000L
        val expected = 20_093L
        assertEquals(expected, repository.epochDayOf(millis))
        // 回归保护：同一时刻若按 UTC 折算会落到前一天——禁止使用「毫秒 / 86400000」的口径
        assertEquals(expected - 1, Math.floorDiv(millis, 86_400_000L))
    }

    @Test
    fun `未知状态落盘值读回时兜底未开始`() = runTest {
        dao.insert(entity(homeworkId = 7L, epochDay = DAY_1, status = "LEGACY_UNKNOWN"))

        val loaded = repository.find(7L, DAY_1)
        requireNotNull(loaded)
        assertEquals(HomeworkDayStatus.NOT_STARTED, loaded.status)
    }

    // ---- 测试夹具 ----

    private fun record(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
    ) = HomeworkDailyRecord(
        homeworkId = homeworkId,
        studentId = studentId,
        epochDay = epochDay,
        status = status,
        createdAtMillis = DAY_1_CREATED,
    )

    private fun entity(
        homeworkId: Long,
        epochDay: Long,
        status: String = "NOT_STARTED",
    ) = HomeworkDailyRecordEntity(
        homeworkId = homeworkId,
        studentId = 3L,
        epochDay = epochDay,
        status = status,
        createdAt = Instant.ofEpochMilli(DAY_1_CREATED),
    )

    private companion object {
        /** 2025-01-05（Asia/Shanghai）等自然日的 epochDay 口径 */
        const val DAY_1 = 20_093L
        const val DAY_2 = 20_094L

        /** 2025-01-05 08:00（Asia/Shanghai）的 epoch 毫秒 */
        const val DAY_1_START = 1_736_035_200_000L
        const val DAY_1_CREATED = 1_736_035_100_000L
    }
}
