package com.assignmate.app.core.domain.ocr

import kotlinx.coroutines.flow.Flow

/**
 * 待重试 OCR 任务状态
 */
enum class PendingOcrStatus {
    /** 待识别：已登记尚未发起（如拍照后暂无网络） */
    PENDING,

    /** 识别中：正在请求云端，进程被杀后下次启动可恢复重试 */
    PROCESSING,

    /** 已识别成功：待清理 */
    SUCCEEDED,

    /** 重试仍失败：待人工处理或清理 */
    FAILED,
}

/**
 * “拍摄图片本地暂存、联网后重试识别”的领域任务：
 * 拍照后图片落盘（localImagePath），登记一条任务；网络恢复/手动重试时按状态取回并调用 OcrRecognizer。
 *
 * @param createdAtMillis 登记时刻 epoch 毫秒，由可注入 Clock 提供（测试可固定时间源）
 */
data class PendingOcrTask(
    val id: Long = 0L,
    val localImagePath: String,
    val createdAtMillis: Long,
    val status: PendingOcrStatus = PendingOcrStatus.PENDING,
    val retryCount: Int = 0,
)

/**
 * 待重试 OCR 任务的本地存储接口（默认实现基于 Room，见 core.data.db 的 OcrRetryTask 系列）。
 * 业务模块（homework 拍照录入）只依赖本接口登记/查询/清理，不感知表结构。
 */
interface PendingOcrRepository {

    /** 登记一条待重试任务，返回自增 id */
    suspend fun add(task: PendingOcrTask): Long

    /** 观察待识别/识别中任务列表（PENDING + PROCESSING），便于联网后自动重试 */
    fun observePending(): Flow<List<PendingOcrTask>>

    /** 按状态查询任务（如联网后取 PENDING 批量重试） */
    suspend fun loadByStatus(status: PendingOcrStatus): List<PendingOcrTask>

    /** 更新任务状态与重试计数（识别成功后置 SUCCEEDED，彻底失败置 FAILED） */
    suspend fun updateStatus(id: Long, status: PendingOcrStatus, retryCount: Int = 0)

    /** 删除任务（图片文件清理由调用方负责） */
    suspend fun remove(id: Long)
}
