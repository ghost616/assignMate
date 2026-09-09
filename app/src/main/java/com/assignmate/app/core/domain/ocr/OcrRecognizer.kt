package com.assignmate.app.core.domain.ocr

/**
 * OCR/图片识别能力抽象：输入图片返回可编辑文本，成败经 [OcrResult] 显式表达。
 *
 * 默认实现为云端多模态大模型（chat/completions 协议，厂商可配置），由 Hilt 绑定；
 * 业务模块（homework 作业拍照录入等）仅依赖本接口，不感知具体厂商与传输细节。
 */
interface OcrRecognizer {

    /**
     * 识别图片中的文字。
     *
     * @param image  待识别图片
     * @param config 厂商配置（云端调用必填）；配置缺失/未启用时返回 [OcrResult.Failure.NotConfigured]
     * @return 成功返回可编辑文本；无网络/请求失败返回 [OcrResult.Failure.NetworkError]
     * @throws 本实现不向外抛异常，全部异常收敛为 [OcrResult.Failure]
     */
    suspend fun recognize(image: OcrImage, config: OcrConfig): OcrResult
}
