package com.assignmate.app.core.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/**
 * Room 全局类型转换器：java.time 类型 <-> 数据库原生类型。
 *
 * 业务模块若引入新列类型（枚举以 String name 存储，见 OcrRetryTaskEntity.status 约定），
 * 应在各自实体中复用本转换器或补充局部 @TypeConverter，避免在 SQL 层散落格式约定。
 */
class AppTypeConverters {

    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToEpochDay(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochDayToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}
