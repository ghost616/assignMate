package com.assignmate.app.core.di

import com.assignmate.app.core.data.db.OcrRetryTaskRepositoryImpl
import com.assignmate.app.core.data.ocr.AndroidOcrImageFileCleaner
import com.assignmate.app.core.data.ocr.DataStoreOcrConfigStore
import com.assignmate.app.core.data.ocr.OcrCacheCleanerImpl
import com.assignmate.app.core.data.ocr.remote.ChatCompletionsOcrRecognizer
import com.assignmate.app.core.domain.ocr.OcrCacheCleaner
import com.assignmate.app.core.domain.ocr.OcrConfigStore
import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * OCR 能力 Hilt 绑定模块：接口 -> 默认实现。
 *
 * - OcrRecognizer：云端多模态大模型实现（chat/completions，厂商参数可配置）；
 * - OcrConfigStore：DataStore 持久化实现（apiKey 经 ValueEncryptor 密文落盘，加密器绑定见 PrefsModule）；
 * - PendingOcrRepository：Room 实现（拍摄暂存、联网重试任务表）。
 * - OcrImageFileCleaner：图片文件删除抽象 -> 平台实现（真实删除 + 文件缺失静默容错）；
 * - OcrCacheCleaner：识别缓存清理契约 -> 组合实现（清空任务记录 + 删除对应图片）。
 *
 * 覆写约定：本模块是上述接口的唯一 @Binds 声明点。同一类型若在业务模块再次 @Binds，
 * 会导致 Dagger/Hilt 出现重复绑定而编译失败，业务模块不应重复绑定；
 * 如需替换默认实现：直接调整本模块的绑定（如换成新实现类），
 * 或改用 @Named/限定符定义多个实现并在注入处显式指定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindOcrRecognizer(impl: ChatCompletionsOcrRecognizer): OcrRecognizer

    @Binds
    @Singleton
    abstract fun bindOcrConfigStore(impl: DataStoreOcrConfigStore): OcrConfigStore

    @Binds
    @Singleton
    abstract fun bindPendingOcrRepository(impl: OcrRetryTaskRepositoryImpl): PendingOcrRepository

    @Binds
    @Singleton
    abstract fun bindOcrImageFileCleaner(impl: AndroidOcrImageFileCleaner): OcrImageFileCleaner

    @Binds
    @Singleton
    abstract fun bindOcrCacheCleaner(impl: OcrCacheCleanerImpl): OcrCacheCleaner
}
