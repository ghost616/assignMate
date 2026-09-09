package com.assignmate.app.auth.di

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.AuthRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * auth 模块 Hilt 绑定模块：[AuthRepository] -> 默认实现 [AuthRepositoryImpl]。
 *
 * core 侧未声明同名绑定，不存在重复绑定冲突；如需替换实现（如未来远端账号服务），
 * 仅调整本处 @Binds 指向即可，UI/仓库消费者零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}