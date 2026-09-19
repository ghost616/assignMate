package com.assignmate.app.timer.service

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.HomeworkAlarmScheduler
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerReminderRules
import io.mockk.mockk
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提醒不得残留」的**投递侧**核对用例（皐陶审查 #5：用户可感知缺陷）。
 *
 * 与 [com.assignmate.app.timer.data.StageDailyReminderTest]（调度侧：范围变更时精确取消旧闹钟）互补，
 * 本类锁定**投递侧**这一层：
 * - 即便同步没跑到、旧闹钟仍然到点触发，投递时也要按库内事实判断「该天是否仍在阶段覆盖区间内」，
 *   超出区间一律不展示（Stale → Silent）；
 * - 失效时投递路径还要清理该次（作业 + 自然日）的调度状态，不留下还能再次触发的闹钟；
 * - 核对不可用（Unknown → Neutral）不清理，避免在无法确认时做破坏性动作。
 *
 * 另含 #8-2 的守卫：按天调度方法是抽象方法（无默认实现），杜绝「实现忘记覆写 → 每日提醒静默退化为单次」。
 */
class StageDailyReminderDeliveryTest {

    // ---- 投递侧区间核对（纯规则） ----

    @Test
    fun `超出阶段覆盖区间的一天不确认提醒`() {
        val item = stageItem(stageStartEpochDay = TODAY, stageRange = StageRange.ONE_WEEK)
        val lastDay = TODAY + StageRange.ONE_WEEK.days - 1L

        assertTrue("覆盖首日应在区间内", TimerReminderRules.isWithinStageRange(item, TODAY))
        assertTrue("覆盖末日（起始日 + 天数 - 1）应在区间内", TimerReminderRules.isWithinStageRange(item, lastDay))
        assertFalse(
            "范围缩短后残留的「原第 8 天」已不在覆盖区间内",
            TimerReminderRules.isWithinStageRange(item, lastDay + 1L),
        )
        assertFalse(
            "范围之前的一天同样不在区间内",
            TimerReminderRules.isWithinStageRange(item, TODAY - 1L),
        )
    }

    @Test
    fun `阶段元数据缺失或非阶段作业时保守视为在区间内`() {
        assertTrue(
            "当天作业没有「哪一天」的概念，不参与区间判定",
            TimerReminderRules.isWithinStageRange(todayItem(), TODAY + 100L),
        )
        assertTrue(
            "缺少每日截止时刻（脏数据）时宁可提醒，不静默丢掉提醒",
            TimerReminderRules.isWithinStageRange(
                stageItem(stageStartEpochDay = TODAY, dailyTime = null),
                TODAY + 100L,
            ),
        )
        assertTrue(
            "阶段范围无法从编码还原（历史脏值）时同样保守提醒",
            TimerReminderRules.isWithinStageRange(
                stageItem(stageStartEpochDay = TODAY, stageRange = null),
                TODAY + 100L,
            ),
        )
    }

    @Test
    fun `投递核对规则对该天超区间一律静默`() {
        listOf(HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS).forEach { status ->
            assertEquals(
                "$status 的阶段作业，只要该天超出覆盖区间即静默",
                ReminderVerification.Stale,
                HomeworkReminderVerificationRules.verify(
                    content = "背古诗",
                    status = status,
                    isStage = true,
                    epochDay = TODAY,
                    dayStatus = null,
                    withinStageRange = false,
                ),
            )
        }
        assertEquals(
            "超区间的判定优先于「当天详情是否完成」（对用户而言结果都是不打扰）",
            ReminderVerification.Stale,
            HomeworkReminderVerificationRules.verify(
                content = "背古诗",
                status = HomeworkStatus.PENDING,
                isStage = true,
                epochDay = TODAY,
                dayStatus = HomeworkDayStatus.NOT_STARTED,
                withinStageRange = false,
            ),
        )
    }

    @Test
    fun `投递核对规则在区间内保持既有口径`() {
        assertEquals(
            "区间内 + 未完成 → 照常点名提醒",
            ReminderVerification.ConfirmedPending("背古诗"),
            HomeworkReminderVerificationRules.verify(
                content = "背古诗",
                status = HomeworkStatus.PENDING,
                isStage = true,
                epochDay = TODAY,
                dayStatus = null,
                withinStageRange = true,
            ),
        )
        assertEquals(
            "当天已打卡完成 → 静默（与既有口径一致）",
            ReminderVerification.Stale,
            HomeworkReminderVerificationRules.verify(
                content = "背古诗",
                status = HomeworkStatus.PENDING,
                isStage = true,
                epochDay = TODAY,
                dayStatus = HomeworkDayStatus.COMPLETED,
                withinStageRange = true,
            ),
        )
    }

    // ---- 投递路径：失效即静默 + 清理调度状态 ----

    @Test
    fun `投递路径在失效时静默并按作业与自然日清理调度状态`() = runTest {
        val cancelled = mutableListOf<Pair<Long, Long?>>()
        val delivery = deliverWith(
            check = ReminderCheck { _, _ -> ReminderVerification.Stale },
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                cancelled += homeworkId to epochDay
                true
            },
        )

        assertEquals(
            "失效的逐日提醒不得展示（Stale → Silent）",
            ReminderDelivery.Silent,
            delivery,
        )
        assertEquals(
            "必须精确清理「该作业 + 该自然日」那一个闹钟",
            listOf(HOMEWORK_ID to TODAY),
            cancelled,
        )
    }

    @Test
    fun `投递路径在核对不可用时不清理`() = runTest {
        val cancelled = mutableListOf<Pair<Long, Long?>>()

        val delivery = deliverWith(
            check = ReminderCheck { _, _ -> ReminderVerification.Unknown },
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                cancelled += homeworkId to epochDay
                true
            },
        )

        assertEquals(
            "无法核对时退化为中性文案（不点名可能失效的作业）",
            ReminderDelivery.Neutral,
            delivery,
        )
        assertTrue(
            "无法确认失效时不得做破坏性动作（不清理）",
            cancelled.isEmpty(),
        )
    }

    @Test
    fun `核对抛异常时异常安全外壳不外抛并清理该次闹钟`() = runTest {
        val cancelled = mutableListOf<Pair<Long, Long?>>()
        val cleanup = ReminderCleanup { homeworkId, epochDay ->
            cancelled += homeworkId to epochDay
            true
        }

        val delivered = HomeworkAlarmReceiver().deliverSafely(
            context = mockk<android.content.Context>(relaxed = true),
            homeworkId = HOMEWORK_ID,
            epochDay = TODAY,
            cleanup = cleanup,
            check = ReminderCheck { _, _ -> throw IllegalStateException("注入式核对失败") },
        )

        assertFalse("异常必须被外壳吞掉，不得冒泡打断广播收尾", delivered)
        assertEquals(
            "核对失败也要清掉这次（作业 + 自然日）的闹钟，否则残留闹钟会在后续日子继续触发",
            listOf(HOMEWORK_ID to TODAY),
            cancelled,
        )
    }

    @Test
    fun `失效决策与清理开关一致`() {        assertTrue(
            "明确失效（Stale → Silent）才清理，避免旧闹钟在后续日子继续触发",
            HomeworkReminderDeliveryRules.requiresCleanup(
                HomeworkReminderDeliveryRules.decide(ReminderVerification.Stale),
            ),
        )
        assertFalse(
            "核对不可用（Unknown → Neutral）不做破坏性动作",
            HomeworkReminderDeliveryRules.requiresCleanup(
                HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown),
            ),
        )
        assertFalse(
            "正常投递（Named）不清理",
            HomeworkReminderDeliveryRules.requiresCleanup(
                HomeworkReminderDeliveryRules.decide(ReminderVerification.ConfirmedPending("语文生字")),
            ),
        )
    }

    // ---- #8-2：按天方法必须强制实现（防静默退化） ----

    @Test
    fun `按天调度方法没有默认实现以避免静默退化为单次`() {
        val dailyMethods = listOf("scheduleDaily", "cancelDaily").map { name ->
            HomeworkAlarmScheduler::class.java.declaredMethods.toList().single { it.name == name }
        }

        dailyMethods.forEach { method ->
            assertTrue(
                "${method.name} 必须是抽象方法（无默认实现）：默认退化为单次提醒会让阶段作业静默少提醒",
                Modifier.isAbstract(method.modifiers),
            )
        }
        assertEquals(
            "按天方法必须在接口上声明（实现方漏覆写即编译失败）",
            listOf("cancelDaily", "scheduleDaily"),
            dailyMethods.map { it.name }.sorted(),
        )
    }

    @Test
    fun `替身实现按天调度时记录到自然日而不落到单次槽位`() {
        val scheduler = FakeAlarmScheduler()

        scheduler.scheduleDaily(HOMEWORK_ID, TODAY + 1L, "背古诗", 1_000L)

        assertEquals(
            "按天调度必须落到「作业 + 自然日」槽位",
            listOf(TODAY + 1L),
            scheduler.scheduledDaysOf(HOMEWORK_ID),
        )
        assertTrue("不得退化为单次提醒", scheduler.scheduled.isEmpty())
    }

    // ---- 测试辅助 ----

    /**
     * 经内部投递入口跑一遍「核对 → 决策 → 投递 → 清理」，返回该次**实际决策**的投递动作。
     *
     * 只对 [ReminderDelivery.Silent]（静默）执行真实投递入口——静默路径只做 `cleanup.cancel`，
     * 可在纯 JVM 验证副作用；[ReminderDelivery.Neutral] / [ReminderDelivery.Named] 会走到
     * 通知渠道与震动（Android 系统服务，mockk 替身下会类转换失败），故只断言纯规则决策，
     * 通知投递本身属真机/仪器测试范围。
     */
    private suspend fun deliverWith(
        check: ReminderCheck,
        cleanup: ReminderCleanup,
    ): ReminderDelivery {
        val delivery = HomeworkReminderDeliveryRules.decide(check.verify(HOMEWORK_ID, TODAY))
        if (delivery is ReminderDelivery.Silent) {
            HomeworkAlarmReceiver().deliver(
                context = mockk<android.content.Context>(relaxed = true),
                homeworkId = HOMEWORK_ID,
                epochDay = TODAY,
                cleanup = cleanup,
                check = check,
            )
        }
        return delivery
    }

    private fun stageItem(
        stageStartEpochDay: Long,
        stageRange: StageRange? = StageRange.ONE_WEEK,
        dailyTime: LocalTime? = LocalTime.of(21, 0),
    ) = timerTestHomework(
        id = HOMEWORK_ID,
        type = HomeworkType.STAGE,
        stageRange = stageRange,
        stageStartEpochDay = stageStartEpochDay,
        stageDailyTime = dailyTime,
    )

    private fun todayItem() = timerTestHomework(id = HOMEWORK_ID)

    private companion object {

        const val HOMEWORK_ID = 7L

        /** 与 [TimerTestEnv] 同一固定时钟口径下的业务自然日 */
        val TODAY: Long = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS)
            .atZone(TimerTestEnv.ZONE)
            .toLocalDate()
            .toEpochDay()
    }
}
