package com.assignmate.app.core.domain.util

/**
 * core 层通用常量：数据库/DataStore/网络/OCR 等默认值集中收敛，禁止在实现中散落魔法值。
 *
 * 注意：后续业务模块（auth/homework/timer/stats/settings）涉及时间、限额等业务规则常量，
 * 应在各自模块内定义，不并入本文件，避免 core 反向耦合业务。
 */
object CoreConstants {

    // ---- 本地数据库 ----
    /** Room 数据库文件名（业务模块如需建库重命名请谨慎评估，涉及既有用户数据迁移） */
    const val DATABASE_NAME = "assignmate.db"

    /**
     * 当前数据库版本。约定：业务模块在 [DATABASE_NAME] 库中新增实体/DAO 时，
     * 统一在 core.data.db.AppDatabase 中注册实体并在此版本号 +1，同时补充 Migration（见 DatabaseModule），
     * 禁止使用破坏性降级（fallbackToDestructiveMigration）。
     *
     * 版本演进：v1 = ocr_retry_task（待重试 OCR 任务）；v2 = +parent_account（家长账号）、+student（学生档案）；
     * v3 = +homework_item（作业项）；v4 = +timer_session（计时执行会话）、+pause_record（暂停明细）；
     * v5 = +homework_daily_record（作业每天详情）、timer_session / pause_record 增加 epoch_day（业务自然日）。
     */
    const val DATABASE_VERSION = 5

    // ---- DataStore 偏好存储 ----
    /** 全局偏好 DataStore 文件名（OCR 厂商配置等键值存放于此） */
    const val DATASTORE_FILE_NAME = "assignmate_prefs"

    // ---- 网络层默认值 ----
    const val NETWORK_CONNECT_TIMEOUT_SECONDS = 15L
    const val NETWORK_READ_TIMEOUT_SECONDS = 30L
    const val NETWORK_WRITE_TIMEOUT_SECONDS = 30L

    // ---- OCR 默认厂商参数（用户可经设置页覆盖） ----
    /** 默认识别服务地址（OpenAI 兼容 chat/completions 协议，厂商可配置替换） */
    const val DEFAULT_OCR_API_BASE_URL = "https://api.openai.com/v1"
    /** 默认多模态模型名 */
    const val DEFAULT_OCR_MODEL_NAME = "gpt-4o-mini"
    /** 多模态大模型对话补全路径（相对 baseUrl 拼接） */
    const val OCR_CHAT_COMPLETIONS_PATH = "chat/completions"
    /** 提交给多模态大模型的识别指令：要求直接输出可编辑文本 */
    const val OCR_RECOGNIZE_PROMPT =
        "请识别图片中的全部文字，保留原有段落与换行，直接输出可编辑的纯文本，不要附加任何解释说明。"
}
