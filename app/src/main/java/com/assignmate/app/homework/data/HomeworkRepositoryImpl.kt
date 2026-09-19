package com.assignmate.app.homework.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.data.db.dao.MovePositionOutcome
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkTimeSlot
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.HomeworkValidationException
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [HomeworkRepository] 默认实现：基于 core 的 [HomeworkItemDao]（Room）、auth 会话与可注入 [Clock]。
 *
 * 关键规则（与 [HomeworkValidators] 同源，仓库层兜底防绕过 UI）：
 * - 写入的家长归属 id 取自 auth 当前会话（parentId），学生 id 由调用方指定并校验会话有效；
 * - 权限分两个维度（[HomeworkValidators]），并统一叠加「家长归属」围栏：
 *   1) 改删/调序（[canModify]/[canDelete]/[canReorder]）：学生仅可操作自己录入项，家长可操作全部；
 *      其中调序额外要求「本人名下」（学生不可调序他人名下、即使该作业由学生录入）；
 *      类型/阶段范围/截止时间（[updateTemplate]）为家长专属，学生会话直接拒绝；
 *   2) 执行权（[canOperate]）：时间排定与状态流转面向「作业的执行者」，
 *      学生可操作本人名下全部作业（含家长布置的），家长可操作名下学生全部作业；
 *   - 归属围栏：家长「作业所属学生必须在当前家长名下」（家长分支经 auth 的
 *     [AuthRepository.isStudentOwnedBy] 判定），学生会话沿用「仅本人名下」；全部按 id 入口
 *     共用 [readAuthorized] 做统一校验（会话有效性与权限维度在前、归属维度随后），
 *     失败一律返回各自密封结果的 PermissionDenied 且不改动任何数据；
 * - 时间：排定前先过 deadline 约束与同学生时间段防冲突（排除自身），失败返回可读原因；
 * - 进行中锁定：状态为 [HomeworkStatus.IN_PROGRESS] 的作业不得再调整优先级与时间排定
 *   （[reorderHomework]/[moveHomeworkTo]/[updateSchedule]/[clearSchedule] 一律拒绝，
 *   返回 LockedWorkInProgress 类结果且不改动数据）；「已完成」保持既有约束；
 * - 状态：新增为「已记录」；排定成功推进到「待完成」；其余流转按 [HomeworkStatus] 合法性校验；
 * - 时间来源统一取 [Clock]，便于测试注入固定时钟。
 *
 * ## 「完成」的两种语义（阶段作业：当天完成 ≠ 整条完成）
 *
 * 1. **当天作业（TODAY）**：完成即整条置 [HomeworkStatus.COMPLETED]，语义逐字不变。
 * 2. **阶段作业（STAGE）**：[complete] 完成的是**今天**这一天的作业（落到每天详情），
 *    **不**把整条作业项置为已完成；整条状态按每天进度收敛：
 *    - 阶段覆盖日**全部完成** → 整条 [HomeworkStatus.COMPLETED]；
 *    - 仍有未完成的天 → 回到 [HomeworkStatus.PENDING]（「整条还没走完」）——该状态在
 *      timer 的可开始集合内（`STARTABLE_STATUSES = {PENDING, IN_PROGRESS}`），
 *      因此**第二天可直接开始计时，不需要先「撤销完成」**，阶段范围内每一天都可开始；
 *    - 阶段已结束但仍有未完成天：同样不得置 COMPLETED，整条停留在 PENDING，
 *      由清单页按每天进度归入家长端「已结束」分组并标注未完成天数。
 * 3. **[reopen] 与 [complete] 对称**：阶段作业撤销的是**今天**的完成记录（今天回到「未开始」），
 *    整条随之回到 PENDING（今天可重新开始计时）。
 * 4. 阶段窗口外（今天不在覆盖区间内，如阶段尚未开始或已结束）拒绝「完成今天」，
 *    返回 [HomeworkStatusResult.IllegalTransition]，避免写入一条边界不明的每天详情。
 *
 * 之所以把「每天的完成」放在每天详情、整条状态只做进度收敛：用户需求是「阶段作业 1 条，
 * 范围内每天都做、每天到点截止」——若按整条状态表达，第一天完成后整条即为「已完成」，
 * 第二天既不能开始计时、还会被并行模块（提醒/统计）当成不需要再做的事。
 */
@Singleton
class HomeworkRepositoryImpl @Inject constructor(
    private val homeworkItemDao: HomeworkItemDao,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    /** 时区口径：与 [com.assignmate.app.homework.di.HomeworkModule] 绑定的业务时区一致，供日期换算使用 */
    private val zoneId: ZoneId,
    /**
     * core 的「作业每天详情」仓库：负责把「开始 / 完成 / 撤销完成」落到每天详情，
     * 并供清单页推导今日状态与阶段进度。默认 [NoopDailyRecordRepository] 为空实现
     * （构造时传入业务时区，避免空实现内写死系统时区），保证仅依赖清单快照的既有测试替身零改动——
     * 本模块**不直接访问 Room DAO**（core 持有表结构）。
     */
    private val dailyRecordRepository: HomeworkDailyRecordRepository = NoopDailyRecordRepository(zoneId),
) : HomeworkRepository {

    // ---- 查询 ----

    override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> =
        homeworkItemDao.observeByStudent(studentId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun listHomework(studentId: Long): List<HomeworkItem> =
        homeworkItemDao.loadByStudent(studentId).map { it.toDomain() }

    override suspend fun getHomework(homeworkId: Long): HomeworkItem? =
        homeworkItemDao.findById(homeworkId)?.toDomain()

    override suspend fun dailyRecordsOf(studentId: Long): Map<Long, List<HomeworkDailyRecord>> {
        // 学生维度的全部天详情按（作业 id）聚合；阶段进度与今日状态据此推导
        return homeworkItemDao.loadByStudent(studentId)
            .associate { entity -> entity.id to dailyRecordRepository.loadByHomework(entity.id) }
    }

    override suspend fun dailyRecords(homeworkId: Long): List<HomeworkDailyRecord> =
        dailyRecordRepository.loadByHomework(homeworkId)

    // ---- 录入 ----

    override suspend fun addHomework(
        template: HomeworkTemplate,
        studentId: Long,
    ): AddHomeworkResult {
        val session = authRepository.currentSession()
        val parentId = session.parentId ?: return AddHomeworkResult.NoActiveSession
        // 目标学生必须是会话可见的作业归属：学生会话仅限本人，家长会话仅限本人名下学生
        if (!isTargetStudentVisible(studentId)) {
            return AddHomeworkResult.NoActiveSession
        }
        return HomeworkValidators.validateTemplate(template).fold(
            onSuccess = { validated ->
                val firstPriority = nextPriority(studentId)
                val items = validated.template.toItems(
                    parentAccountId = parentId,
                    studentId = studentId,
                    createdAt = now(),
                    firstPriority = firstPriority,
                )
                // 作业项固定 1 条（阶段作业同样只 1 条）：事务化写入，避免中途失败留下半套作业
                val ids = homeworkItemDao.insertAllInTransaction(items.map { it.toEntity() })
                AddHomeworkResult.Success(items.mapIndexed { index, item -> item.copy(id = ids[index]) })
            },
            onFailure = { error ->
                AddHomeworkResult.TemplateInvalid(error.asValidationError())
            },
        )
    }

    // ---- 优先级 ----

    override suspend fun reorderHomework(
        homeworkId: Long,
        direction: ReorderDirection,
        sessionRole: Role,
    ): HomeworkOrderResult =
        // 统一判定：调序权（改删权 + 归属维度）+ 家长归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canReorder(item, sessionRole, currentStudentId())
        }.fold(
            onNotFound = { HomeworkOrderResult.NotFound },
            onDenied = { HomeworkOrderResult.PermissionDenied },
            onAllowed = { item ->
                if (item.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：优先级已在计时链路中被确认，禁止再调整（不改动数据）
                    HomeworkOrderResult.LockedWorkInProgress
                } else {
                    reorderSiblings(item.id, item.studentId, direction)
                }
            },
        )

    /** 相邻交换（边界幂等成功）：清单自身位置与同学生可见范围一致，故排序后按 id 定位被调项 */
    private suspend fun reorderSiblings(
        homeworkId: Long,
        studentId: Long,
        direction: ReorderDirection,
    ): HomeworkOrderResult {
        val ordered = homeworkItemDao.loadByStudent(studentId)
        val index = ordered.indexOfFirst { it.id == homeworkId }
        if (index < 0) {
            return HomeworkOrderResult.NotFound
        }
        val neighborIndex = when (direction) {
            ReorderDirection.UP -> index - 1
            ReorderDirection.DOWN -> index + 1
        }
        if (neighborIndex !in ordered.indices) {
            // 已在边界：幂等成功，不改动任何数据
            return HomeworkOrderResult.Success
        }
        swapPriority(ordered[index], ordered[neighborIndex])
        return HomeworkOrderResult.Success
    }

    override suspend fun moveHomeworkTo(
        homeworkId: Long,
        targetIndex: Int,
        sessionRole: Role,
    ): HomeworkOrderResult =
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canReorder(item, sessionRole, currentStudentId())
        }.fold(
            onNotFound = { HomeworkOrderResult.NotFound },
            onDenied = { HomeworkOrderResult.PermissionDenied },
            onAllowed = { item ->
                if (item.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：落位调整同样被拒绝（与 reorderHomework 同源）
                    HomeworkOrderResult.LockedWorkInProgress
                } else {
                    // 落位需要「先读全清单 → 校验 → 批量写优先级」三段原子完成：
                    // 统一交给 DAO 的 @Transaction 方法（事务内重读并在写入前复查进行中锁定），
                    // 避免读与写之间作业被并发置为进行中后仍完成一次已失效的重排。
                    when (homeworkItemDao.moveToPositionInTransaction(homeworkId, targetIndex)) {
                        MovePositionOutcome.SUCCESS -> HomeworkOrderResult.Success
                        MovePositionOutcome.NOT_FOUND -> HomeworkOrderResult.NotFound
                        MovePositionOutcome.LOCKED -> HomeworkOrderResult.LockedWorkInProgress
                    }
                }
            },
        )

    // ---- 时间排定 ----

    override suspend fun updateSchedule(
        homeworkId: Long,
        startTime: Instant,
        estimatedMinutes: Int,
        sessionRole: Role,
    ): ScheduleUpdateResult {
        // 统一判定：执行权（学生可为本人名下含家长布置的作业排定时间）+ 归属围栏
        val access = readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }
        if (access == TargetAccess.NotFound) {
            return ScheduleUpdateResult.NotFound
        }
        if (access == TargetAccess.Denied) {
            return ScheduleUpdateResult.PermissionDenied
        }
        val domain = (access as TargetAccess.Allowed).item
        // 进行中锁定：开始时刻是计时链路的既有事实，进行中不得再改时间（保留原排定）
        if (domain.status == HomeworkStatus.IN_PROGRESS) {
            return ScheduleUpdateResult.LockedWorkInProgress
        }
        if (HomeworkValidators.validateEstimatedMinutes(estimatedMinutes) is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.InvalidEstimatedMinutes
        }
        if (!domain.status.canTransitionTo(HomeworkStatus.PENDING)) {
            return ScheduleUpdateResult.StatusTransitionDenied
        }
        val startMillis = startTime.toEpochMilli()
        // 截止约束：**单一入口**——两类 deadline 语义（当天作业绝对时刻 / 阶段作业每日时刻）
        // 的类型分流只发生在 HomeworkValidators 内部，本处（与时间设定页预校验）不得再自写一套，
        // 否则「UI 先拦、仓库本会放行」这类判据分散的缺陷会复发
        val deadlineCheck = HomeworkValidators.validateScheduleWithinItemDeadline(
            item = domain,
            startMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
            zoneId = zoneId,
        )
        if (deadlineCheck is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.DeadlineExceeded
        }
        val conflictCheck = HomeworkValidators.validateTimeSlot(
            candidate = HomeworkTimeSlot.of(startMillis, estimatedMinutes),
            existing = homeworkItemDao.loadScheduledByStudent(domain.studentId).map { it.toDomain() },
            excludeItemId = domain.id,
        )
        if (conflictCheck is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.TimeConflict
        }
        val nextStatus = if (domain.status == HomeworkStatus.RECORDED) {
            HomeworkStatus.PENDING
        } else {
            domain.status
        }
        val updated = domain.copy(
            startTime = startTime,
            estimatedMinutes = estimatedMinutes,
            status = nextStatus,
        )
        homeworkItemDao.update(updated.toEntity())
        return ScheduleUpdateResult.Success(updated)
    }

    override suspend fun clearSchedule(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult =
        // 统一判定：执行权（学生可撤销本人名下作业的时间排定）+ 归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                if (domain.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：排定时间段是计时与防冲突校验的依据，进行中不允许撤销
                    HomeworkOperationResult.LockedWorkInProgress
                } else {
                    clearScheduleFor(domain)
                }
            },
        )

    /** 落库撤销排定：已完成回退为进行中（保证撤销后仍可重新排定，不留死局） */
    private suspend fun clearScheduleFor(domain: HomeworkItem): HomeworkOperationResult {
        val nextStatus = when (domain.status) {
            HomeworkStatus.PENDING -> HomeworkStatus.RECORDED
            HomeworkStatus.COMPLETED -> HomeworkStatus.IN_PROGRESS
            else -> domain.status
        }
        val updated = domain.copy(startTime = null, estimatedMinutes = null, status = nextStatus)
        homeworkItemDao.update(updated.toEntity())
        // 撤销排定的同时把「当天详情」回退（若该天此前已标记完成），避免每天详情残留已完成
        recordDayProgress(updated, nextStatus, reset = true)
        return HomeworkOperationResult.Success(updated)
    }

    // ---- 内容 / 类型 ----

    override suspend fun updateContent(
        homeworkId: Long,
        content: String,
        sessionRole: Role,
    ): HomeworkOperationResult =
        // 统一判定：改删权（学生仅可改自己录入项）+ 归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canModify(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                val trimmed = content.trim()
                when {
                    trimmed.isEmpty() -> HomeworkOperationResult.ContentInvalid(
                        HomeworkValidationError.BLANK_CONTENT,
                    )

                    trimmed.length > HomeworkConstants.MAX_CONTENT_LENGTH -> HomeworkOperationResult.ContentInvalid(
                        HomeworkValidationError.CONTENT_TOO_LONG,
                    )

                    else -> {
                        val updated = domain.copy(content = trimmed)
                        homeworkItemDao.update(updated.toEntity())
                        HomeworkOperationResult.Success(updated)
                    }
                }
            },
        )

    override suspend fun updateTemplate(
        homeworkId: Long,
        type: HomeworkType,
        stageRange: StageRange?,
        deadline: Instant?,
        sessionRole: Role,
    ): HomeworkOperationResult {
        // 统一判定：家长专属（改删权维度）+ 家长归属围栏。
        // 必须走 readAuthorized 而非只比对传入的 sessionRole——后者会让「传入角色」替代真实会话角色，
        // 学生会话一旦被误传 Role.PARENT 即可改本人名下作业的类型/阶段范围/截止时间（纵深防御缺口）。
        val access = readAuthorized(homeworkId, sessionRole) { domain ->
            HomeworkValidators.canModify(domain, sessionRole)
        }
        return access.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                // 类型/阶段范围/截止时间为家长专属修改项（学生仅可改自己录入项的内容与时间排定）
                if (sessionRole != Role.PARENT) {
                    return@fold HomeworkOperationResult.PermissionDenied
                }
                val check = HomeworkValidators.validateTypeChange(
                    type = type,
                    stageRange = stageRange,
                    creatorRole = domain.createdByRole,
                    dailyDeadlineTime = dailyDeadlineOf(type, deadline),
                )
                if (check is HomeworkValidation.Invalid) {
                    return@fold HomeworkOperationResult.TemplateInvalid(check.error)
                }
                // 候选 deadline 必须同时容纳既有排定时间段：与 updateSchedule 走**同一个**校验入口
                // （按类型分流只发生在 HomeworkValidators 内；此处不再自写一套判据）。
                // 用「候选类型 + 候选 deadline」构造候选作业项：阶段作业经 dailyDeadlineTime 解码每日时刻、
                // 当天作业取绝对 deadline，语义与落库后的读回口径一致。
                if (domain.startTime != null && domain.estimatedMinutes != null) {
                    val scheduleCheck = HomeworkValidators.validateScheduleWithinItemDeadline(
                        item = domain.copy(type = type, deadline = deadline),
                        startMillis = domain.startTime.toEpochMilli(),
                        estimatedMinutes = domain.estimatedMinutes,
                        zoneId = zoneId,
                    )
                    if (scheduleCheck is HomeworkValidation.Invalid) {
                        return@fold HomeworkOperationResult.TemplateInvalid(scheduleCheck.error)
                    }
                }
                val updated = domain.copy(
                    type = type,
                    stageRange = if (type == HomeworkType.STAGE) stageRange else null,
                    // **与新建路径同源编码**：阶段作业的 deadline 只承载每日时刻，
                    // 必须把「阶段起始日（= 作业创建日）+ 每日时刻」编码进列，否则读回时
                    // stageStartEpochDay 无法从编码还原、只能退回创建日兜底（口径漂移）。
                    // 起始日使用业务时区口径的创建日（不依赖 ZoneId.systemDefault）。
                    deadline = persistDeadline(type, deadline, domain.createdEpochDay(zoneId)),
                )
                homeworkItemDao.update(updated.toEntity())
                HomeworkOperationResult.Success(updated)
            },
        )
    }

    // ---- 删除 ----

    override suspend fun deleteHomework(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult {
        // 统一判定：改删权（学生仅可删自己录入项）+ 归属围栏；失败不改动任何数据
        val access = readAuthorized(homeworkId, sessionRole) { domain ->
            HomeworkValidators.canDelete(domain, sessionRole)
        }
        return access.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                // 通过统一判定的实体即库中当前行（判定与删除之间无挂起写入）
                val entity = (access as TargetAccess.Allowed).entity
                homeworkItemDao.delete(entity)
                HomeworkOperationResult.Success(domain)
            },
        )
    }

    // ---- 状态流转 ----

    override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.PENDING)

    override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.IN_PROGRESS)

    /**
     * 标记完成：**按类型分流**（见类注释「完成」的两种语义）——
     * 当天作业整条置已完成；阶段作业只完成「今天」并按每天进度收敛整条状态。
     */
    override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(
            homeworkId = homeworkId,
            sessionRole = sessionRole,
            target = HomeworkStatus.COMPLETED,
            onStage = { domain -> completeStageToday(domain) },
        )

    /**
     * 撤销完成：当天作业回退为进行中；阶段作业撤销的是**今天**的完成记录并回到「待完成」
     * （与 [complete] 的收敛口径对称，保证「今天还没做完」时能直接再次开始计时）。
     */
    override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(
            homeworkId = homeworkId,
            sessionRole = sessionRole,
            // 当天作业：除状态回退为进行中外，「当天详情」也必须回退（见 resetDayRecord）
            target = HomeworkStatus.IN_PROGRESS,
            resetDayRecord = true,
            onStage = { domain -> reopenStageToday(domain) },
        )

    // ---- 私有工具 ----

    /**
     * 统一状态流转入口：会话有效性 + 执行权 + 归属围栏 → 流转合法性校验 → 落库。
     *
     * @param resetDayRecord 是否把「当天详情」回退为「未开始」（撤销完成场景；仅对目标进行中生效），
     *   与 [startProgress] 区分：同样是「→ 进行中」，开始作业要写进行中，撤销完成要清掉今天的完成记录
     * @param onStage 阶段作业的专用分支（完成/撤销完成按「今天」表达，见类注释「完成」的两种语义）；
     *   为 null 时阶段作业与非阶段作业走同一套整条状态流转（如 markPending / startProgress）
     */
    private suspend fun transition(
        homeworkId: Long,
        sessionRole: Role,
        target: HomeworkStatus,
        resetDayRecord: Boolean = false,
        onStage: (suspend (HomeworkItem) -> HomeworkStatusResult)? = null,
    ): HomeworkStatusResult =
        // 执行权：学生可推进本人名下（含家长布置的）作业状态：待完成 → 进行中 → 已完成
        readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkStatusResult.NotFound },
            onDenied = { HomeworkStatusResult.PermissionDenied },
            onAllowed = { domain ->
                if (domain.isStage && onStage != null) {
                    onStage(domain)
                } else {
                    advance(domain, target, resetDayRecord)
                }
            },
        )

    /**
     * 按目标状态推进**整条**作业项并同步「当天详情」（当天作业与阶段作业的通用路径）。
     *
     * 合法性与幂等口径：非法流转返回 [HomeworkStatusResult.IllegalTransition]；
     * 同状态视为幂等成功，但仍要保证当天详情与目标状态一致
     * （否则首次开始后中断重试会丢失每天详情）。
     */
    private suspend fun advance(
        domain: HomeworkItem,
        target: HomeworkStatus,
        resetDayRecord: Boolean = false,
    ): HomeworkStatusResult = when {
        !HomeworkValidators.canTransition(domain.status, target) ->
            HomeworkStatusResult.IllegalTransition(domain.status, target)

        domain.status == target -> {
            recordDayProgress(domain, target, reset = resetDayRecord)
            HomeworkStatusResult.Success(domain)
        }

        else -> {
            val updated = domain.copy(status = target)
            homeworkItemDao.update(updated.toEntity())
            // 落到 core 的「作业每天详情」：
            // - 撤销完成（reopen）→ 回退「未开始」且清空执行数据；
            // - 开始 → 当天详情置「进行中」；完成 → 「已完成」并记录完成时刻
            recordDayProgress(updated, target, reset = resetDayRecord)
            HomeworkStatusResult.Success(updated)
        }
    }

    /**
     * 阶段作业「完成今天的作业」：**只把今天记进每天详情**，整条状态按每天进度收敛。
     *
     * 收敛规则（见类注释）：阶段覆盖日全部完成 → 整条已完成；否则回到「待完成」，
     * 使学生第二天（以及阶段范围内任意一天）可以直接开始计时，无需先「撤销完成」。
     * 阶段窗口外（今天不在覆盖区间内）拒绝本次完成，避免写入边界不明的每天详情。
     *
     * @return [HomeworkStatusResult.Success] 携带**收敛后**的作业项（阶段未走完时状态为 PENDING）
     */
    private suspend fun completeStageToday(domain: HomeworkItem): HomeworkStatusResult {
        if (!HomeworkValidators.canTransition(domain.status, HomeworkStatus.COMPLETED)) {
            return HomeworkStatusResult.IllegalTransition(domain.status, HomeworkStatus.COMPLETED)
        }
        if (domain.status == HomeworkStatus.COMPLETED) {
            // 整条已完成（阶段范围内全部天完成）：重复完成幂等成功，不改动任何数据
            return HomeworkStatusResult.Success(domain)
        }
        if (!isTodayWithinStageWindow(domain)) {
            // 阶段尚未开始或已结束：今天不属于该阶段，没有「今天的作业」可完成
            return HomeworkStatusResult.IllegalTransition(domain.status, HomeworkStatus.COMPLETED)
        }
        // 1) 「今天的完成」落到 core 的作业每天详情
        recordDayProgress(domain, HomeworkStatus.COMPLETED, reset = false)
        // 2) 整条状态按每天进度收敛（全部覆盖日完成才置已完成）
        return convergeStageStatus(domain)
    }

    /**
     * 阶段作业「撤销今天的完成」：今天回到「未开始」并清空执行数据，整条随之回到「待完成」。
     *
     * 与 [completeStageToday] 对称：撤销后今天仍可直接开始计时（PENDING 在 timer 可开始集合内）。
     * 只在今天已有完成记录时才有意义，故无完成记录时返回 [HomeworkStatusResult.IllegalTransition]。
     */
    private suspend fun reopenStageToday(domain: HomeworkItem): HomeworkStatusResult {
        val today = currentEpochDay()
        val completedToday = dailyRecordRepository.find(domain.id, today)?.status == HomeworkDayStatus.COMPLETED
        if (!completedToday) {
            return HomeworkStatusResult.IllegalTransition(domain.status, HomeworkStatus.IN_PROGRESS)
        }
        recordDayProgress(domain, HomeworkStatus.IN_PROGRESS, reset = true)
        // 撤销今天的完成 → 整条不再满足「全部天完成」，收敛回「待完成」（今天照常可开始/完成）
        val updated = domain.copy(status = HomeworkStatus.PENDING)
        if (updated.status != domain.status) {
            homeworkItemDao.update(updated.toEntity())
        }
        return HomeworkStatusResult.Success(updated)
    }

    /**
     * 阶段作业整条状态的进度收敛：阶段覆盖日全部完成 → [HomeworkStatus.COMPLETED]，
     * 否则 [HomeworkStatus.PENDING]（「整条还没走完」，仍可继续下一天开始计时）。
     *
     * 进度分母与分子与清单页同源（[StageDayRecords.progressOf]），因此「整条已完成」与
     * 家长端「已完成历史」分组判定不会出现两套口径。
     */
    private suspend fun convergeStageStatus(domain: HomeworkItem): HomeworkStatusResult {
        val records = dailyRecordRepository.loadByHomework(domain.id)
        val progress = StageDayRecords.progressOf(domain, records, currentEpochDay(), zoneId)
        val nextStatus = if (progress?.isAllCompleted == true) {
            HomeworkStatus.COMPLETED
        } else {
            HomeworkStatus.PENDING
        }
        val updated = domain.copy(status = nextStatus)
        if (updated.status != domain.status) {
            homeworkItemDao.update(updated.toEntity())
        }
        return HomeworkStatusResult.Success(updated)
    }

    /**
     * 今天是否落在该阶段作业的覆盖区间内（[StageDayRecords.isWithinCoverage] 同一口径）：
     * 起始日经 [HomeworkItem.stageStartEpochDayOr] 还原（编码不可还原时回退创建日），
     * 覆盖天数缺失（脏数据：type = STAGE 但无阶段范围）时返回 false，宁可拒绝也不写入边界不明的详情。
     */
    private fun isTodayWithinStageWindow(domain: HomeworkItem): Boolean {
        // 阶段作业的 stageStartEpochDayOr 恒非空（不可还原时回退创建日），兜底仅为类型完备
        val start = domain.stageStartEpochDayOr(zoneId) ?: domain.createdEpochDay(zoneId)
        return StageDayRecords.isWithinCoverage(currentEpochDay(), start, domain.stageCoveredDays)
    }

    // ---- 统一访问判定（会话有效性 + 权限维度 + 家长归属围栏） ----

    /**
     * 按 id 入口的统一访问判定（唯一的「是否可操作目标作业」口径）：读取作业后依次做
     * 「会话有效性/角色 + 权限维度」判定，最后补「归属围栏」判定。
     *
     * 判定顺序（失败即返回、不改动任何数据）：
     * 1) 作业不存在 → [TargetAccess.NotFound]；
     * 2) 会话无角色（登出等）或权限维度拒绝 → [TargetAccess.Denied]；
     * 3) 归属围栏（[isTargetStudentVisible]）：学生仅本人名下、家长仅名下学生 → 否则 [TargetAccess.Denied]。
     *
     * 权限维度由调用方以 [permission] 传入（改删权 / 调序权 / 执行权），故本入口保持既有权限语义不变，
     * 仅在其后叠加统一的归属维。[permission] 声明为 suspend 以便内联处直接取会话（学生 id）。
     */
    private suspend fun readAuthorized(
        homeworkId: Long,
        sessionRole: Role,
        permission: suspend (HomeworkItem) -> Boolean,
    ): TargetAccess {
        val entity = homeworkItemDao.findById(homeworkId) ?: return TargetAccess.NotFound
        val domain = entity.toDomain()
        val session = authRepository.currentSession()
        if (session.role != sessionRole || !permission(domain)) {
            return TargetAccess.Denied
        }
        return if (isTargetStudentVisible(session, domain.studentId)) {
            TargetAccess.Allowed(domain, entity)
        } else {
            TargetAccess.Denied
        }
    }

    /**
     * 执行权判定：家长可操作名下学生全部作业；学生仅可操作本人名下作业
     * （[CreatorRole] 不参与判定，故学生可为家长布置的作业排定时间与推进状态）。
     * 家长归属围栏由 [readAuthorized] 在权限维度之后统一补齐。
     */
    private suspend fun canOperate(domain: HomeworkItem, sessionRole: Role): Boolean =
        HomeworkValidators.canOperate(
            item = domain,
            sessionRole = sessionRole,
            sessionStudentId = currentStudentId(),
        )

    /** 当前会话的学生 id（家长会话为 null）：调序归属校验与执行权判定共用同一口径 */
    private suspend fun currentStudentId(): Long? = authRepository.currentSession().studentId

    /** 归属围栏读取当前会话后的判定入口（新增/修改模板等非「按 id 统一判定」调用点复用） */
    private suspend fun isTargetStudentVisible(studentId: Long): Boolean =
        isTargetStudentVisible(authRepository.currentSession(), studentId)

    /**
     * 目标学生是否属于会话 [session] 的可见范围（归属围栏的唯一口径）：
     * 学生会话仅限本人；家长会话仅限名下学生（经 auth 的 [AuthRepository.isStudentOwnedBy]，
     * 与 timer/stats 等模块共用同一归属判定，替代本模块原先私有的等价实现）；无会话一律拒绝。
     *
     * 异常语义：不在此处用 runCatching 包裹——[AuthRepository.isStudentOwnedBy] 契约已把数据层异常
     * 收敛为 false 并显式重抛 [CancellationException]（结构化并发语义得以保留），外层再包一层
     * runCatching 反而可能吞掉协程取消；若换用会抛异常的实现，异常将按「判定失败」向上传播而非被静默吞掉。
     */
    private suspend fun isTargetStudentVisible(
        session: SessionState,
        studentId: Long,
    ): Boolean {
        val parentId = session.parentId
        return when (session.role) {
            Role.STUDENT -> session.studentId == studentId
            Role.PARENT -> parentId != null && authRepository.isStudentOwnedBy(parentId, studentId)
            null -> false
        }
    }

    /** 统一访问判定结果：越权/无权与「作业不存在」必须可区分（分别映射到各自密封结果） */
    private sealed class TargetAccess {

        /** 作业不存在（可能已被删除） */
        data object NotFound : TargetAccess()

        /** 会话无效、权限维度不足或归属围栏不通过 */
        data object Denied : TargetAccess()

        /** 通过全部判定：携带领域模型与实体（供调用方继续业务流程/落库） */
        data class Allowed(
            val item: HomeworkItem,
            val entity: HomeworkItemEntity,
        ) : TargetAccess()
    }

    /** 三态判定的统一收口：各入口据此映射到自己的密封结果（失败分支不改动任何数据） */
    private suspend fun <T> TargetAccess.fold(
        onNotFound: () -> T,
        onDenied: () -> T,
        onAllowed: suspend (HomeworkItem) -> T,
    ): T = when (this) {
        TargetAccess.NotFound -> onNotFound()
        TargetAccess.Denied -> onDenied()
        is TargetAccess.Allowed -> onAllowed(item)
    }

    /** 「今天」的业务自然日（每天详情的自然日键，统一走业务时区折算，禁止 UTC 毫秒折算） */
    private fun currentEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId)

    /**
     * 候选 deadline 的「每日截止时刻」（仅阶段作业有意义）：
     * 阶段作业的 deadline 只承载 time-of-day，由 [HomeworkDailyDeadlineCodec] 解码；
     * 当天作业或空 deadline 返回 null（无每日时刻约束）。
     */
    private fun dailyDeadlineOf(type: HomeworkType, deadline: Instant?): LocalTime? =
        if (type == HomeworkType.STAGE) {
            deadline?.toEpochMilli()?.let(HomeworkDailyDeadlineCodec::decodeStageDaily)
        } else {
            null
        }

    /**
     * 落库 deadline 取值（**新建与编辑两条路径同源**）：
     * - 当天作业（TODAY）：绝对时刻原样落库；
     * - 阶段作业（STAGE）：把「阶段起始日 + 每日时刻」编码进列（与 [HomeworkTemplate.toItems] 一致），
     *   保证读回时 [HomeworkItem.stageStartEpochDay] 可由编码还原而非兜底。
     *
     * @param startEpochDay 阶段起始日（业务时区口径的作业创建日）
     */
    private fun persistDeadline(
        type: HomeworkType,
        deadline: Instant?,
        startEpochDay: Long,
    ): Instant? = if (type == HomeworkType.STAGE) {
        dailyDeadlineOf(type, deadline)?.let { time ->
            HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, time)
        }
    } else {
        deadline
    }

    /**
     * 状态流转落库后同步「作业每天详情」（core 契约，按业务时区折算自然日；仓库不直接碰 DAO）：
     * - 开始（目标进行中且 [reset] = false）→ 当天详情置「进行中」并记录开始时刻；
     * - 完成（目标已完成）→ 当天详情置「已完成」并记录完成时刻；
     * - 撤销完成 / 撤销排定（[reset] = true）→ 当天详情置回「未开始」并清空执行数据
     *   （不补做已过去的缺卡天；同一天重新开始会再次写入进行中）；
     * - 其余目标状态（如「待完成」）：**不写每天详情**——「已记录 → 待完成」只表达「已排定时间」，
     *   与「今天做了什么」无关（此前此处误落「已完成」，会把没做过的一天记成已完成，
     *   进而污染阶段进度与「全部天完成」判定）。
     *
     * 每天详情未注入真实实现（仅传清单替身的既有测试）时经 [NoopDailyRecordRepository] 空转，口径不变。
     */
    private suspend fun recordDayProgress(
        domain: HomeworkItem,
        target: HomeworkStatus,
        reset: Boolean,
    ) {
        val records = dailyRecordRepository
        val todayEpochDay = currentEpochDay()
        val nowMillis = clock.currentTimeMillis()
        when {
            reset || target == HomeworkStatus.RECORDED -> {
                val id = records.upsertStatus(
                    homeworkId = domain.id,
                    studentId = domain.studentId,
                    epochDay = todayEpochDay,
                    status = HomeworkDayStatus.NOT_STARTED,
                    nowMillis = nowMillis,
                )
                records.updateExecution(
                    id = id,
                    startedAtMillis = null,
                    estimatedMinutes = null,
                    actualMinutes = null,
                    pauseCount = 0,
                    pausedTotalMinutes = 0,
                    finishedAtMillis = null,
                )
            }

            target == HomeworkStatus.IN_PROGRESS -> {
                val id = records.upsertStatus(
                    homeworkId = domain.id,
                    studentId = domain.studentId,
                    epochDay = todayEpochDay,
                    status = HomeworkDayStatus.IN_PROGRESS,
                    nowMillis = nowMillis,
                )
                val existing = records.find(domain.id, todayEpochDay)
                records.updateExecution(
                    id = id,
                    startedAtMillis = existing?.startedAtMillis ?: nowMillis,
                    estimatedMinutes = domain.estimatedMinutes ?: existing?.estimatedMinutes,
                    actualMinutes = existing?.actualMinutes,
                    pauseCount = existing?.pauseCount ?: 0,
                    pausedTotalMinutes = existing?.pausedTotalMinutes ?: 0,
                    finishedAtMillis = null,
                )
            }

            target == HomeworkStatus.COMPLETED -> {
                val id = records.upsertStatus(
                    homeworkId = domain.id,
                    studentId = domain.studentId,
                    epochDay = todayEpochDay,
                    status = HomeworkDayStatus.COMPLETED,
                    nowMillis = nowMillis,
                )
                val existing = records.find(domain.id, todayEpochDay)
                records.updateExecution(
                    id = id,
                    startedAtMillis = existing?.startedAtMillis,
                    estimatedMinutes = existing?.estimatedMinutes ?: domain.estimatedMinutes,
                    actualMinutes = existing?.actualMinutes,
                    pauseCount = existing?.pauseCount ?: 0,
                    pausedTotalMinutes = existing?.pausedTotalMinutes ?: 0,
                    finishedAtMillis = nowMillis,
                )
            }

            // 其余目标状态（「已记录 → 待完成」的 PENDING）：不写每天详情——该流转只表达
            // 「已排定时间」，与「今天做了什么」无关（口径见 KDoc）
            else -> Unit
        }
    }

    /** 交换两条作业项的优先级（上移/下移的基本操作） */
    private suspend fun swapPriority(first: HomeworkItemEntity, second: HomeworkItemEntity) {
        homeworkItemDao.update(first.copy(priority = second.priority))
        homeworkItemDao.update(second.copy(priority = first.priority))
    }

    /** 追加排序：空清单取最小值，否则为当前最大值 + 步长（保证新增项落在清单末尾） */
    private suspend fun nextPriority(studentId: Long): Int {
        val max = homeworkItemDao.maxPriority(studentId) ?: return HomeworkConstants.MIN_PRIORITY
        return max + HomeworkConstants.PRIORITY_STEP
    }

    private fun now(): Instant = Instant.ofEpochMilli(clock.currentTimeMillis())

    private fun Throwable.asValidationError(): HomeworkValidationError =
        (this as? HomeworkValidationException)?.error
            ?: throw IllegalStateException("非校验异常，不应作为模板校验失败处理", this)

    private fun HomeworkItemEntity.toDomain(): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = parentAccountId,
        studentId = studentId,
        content = content,
        type = HomeworkType.fromName(type) ?: HomeworkType.TODAY,
        stageRange = StageRange.fromName(stageRange),
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = HomeworkStatus.fromName(status) ?: HomeworkStatus.INITIAL,
        createdByRole = CreatorRole.fromName(createdByRole) ?: CreatorRole.PARENT,
        createdAt = createdAt,
    )

    private fun HomeworkItem.toEntity(): HomeworkItemEntity = HomeworkItemEntity(
        id = id,
        parentAccountId = parentAccountId,
        studentId = studentId,
        content = content,
        type = type.name,
        stageRange = stageRange?.name,
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = status.name,
        createdByRole = createdByRole.name,
        createdAt = createdAt,
    )
}