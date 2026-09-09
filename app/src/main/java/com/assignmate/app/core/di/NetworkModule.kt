package com.assignmate.app.core.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.assignmate.app.core.domain.util.CoreConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * 网络层 Hilt 模块：OkHttpClient（超时 + 仅 debug 日志，鉴权头脱敏）与 Retrofit.Builder 基础装配。
 *
 * - 日志：仅 debug 构建打印 HTTP 日志，级别为 HEADERS（不打印 body）。
 *   避免 OCR 请求体（图片 base64 data URL）与响应明文内容落日志，同时满足
 *   “不打印图片内容/密钥/验证码”的安全约束；Authorization 头额外脱敏。
 * - Retrofit：提供 Builder（未绑定 baseUrl），OCR 与后续在线能力按各自 baseUrl 构建实例复用同一 OkHttpClient。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // 降级为 HEADERS：日志仅含请求/响应头，不含任何请求/响应体内容
            level = if (debug) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
            redactHeader(AUTHORIZATION_HEADER)
        }
        return OkHttpClient.Builder()
            .connectTimeout(CoreConstants.NETWORK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CoreConstants.NETWORK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CoreConstants.NETWORK_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /** 供 OCR/在线能力按各自服务地址构建 Retrofit；统一复用上方单例 OkHttpClient */
    @Provides
    @Singleton
    fun provideRetrofitBuilder(okHttpClient: OkHttpClient): Retrofit.Builder =
        Retrofit.Builder().client(okHttpClient)

    private const val AUTHORIZATION_HEADER = "Authorization"
}
