package com.assignmate.app.homework.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 作业表单的日期/时间选择对话框与文本口径工具。
 *
 * 口径约定（与 HomeworkTemplateViewModel / HomeworkTimeSetViewModel 的表单解析一致）：
 * - 日期文本 `yyyy-MM-dd`、时间文本 `HH:mm`（24 小时制），空串或非法格式按「未填写」处理；
 * - Material3 DatePicker 的选中值是「所选日期的 UTC 零点毫秒」（框架约定），
 *   故与本地日期互转一律经 [ZoneOffset.UTC]，避免按业务时区换算导致日期偏移一天。
 */

/** 表单日期文本格式 */
internal val HOMEWORK_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 表单时间文本格式（24 小时制） */
internal val HOMEWORK_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 解析日期文本（空串/格式非法返回 null） */
internal fun parseHomeworkDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), HOMEWORK_DATE_FORMAT) }.getOrNull()

/** 解析时间文本（空串/格式非法返回 null） */
internal fun parseHomeworkTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim(), HOMEWORK_TIME_FORMAT) }.getOrNull()

/** 日期文本 -> DatePicker 初始选中值（UTC 零点毫秒）；无有效日期时返回 null（默认展示当月） */
internal fun dateTextToPickerMillis(text: String): Long? =
    parseHomeworkDate(text)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

/** 时刻 -> 日期文本（按业务时区折算） */
internal fun instantToDateText(instant: Instant, zoneId: ZoneId): String =
    instant.atZone(zoneId).toLocalDate().format(HOMEWORK_DATE_FORMAT)

/** 时刻 -> 时间文本（按业务时区折算，秒与纳秒截断到分钟） */
internal fun instantToTimeText(instant: Instant, zoneId: ZoneId): String {
    val local = instant.atZone(zoneId).toLocalTime()
    return LocalTime.of(local.hour, local.minute).format(HOMEWORK_TIME_FORMAT)
}

/** DatePicker 选中值（UTC 零点毫秒）-> 日期文本 */
internal fun pickerMillisToDateText(selectedUtcMillis: Long): String =
    Instant.ofEpochMilli(selectedUtcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .format(HOMEWORK_DATE_FORMAT)

/** 日期选择对话框：确认后回填 `yyyy-MM-dd` 文本 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDatePickerDialog(
    initialDateText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = dateTextToPickerMillis(initialDateText),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = state.selectedDateMillis
                    if (selected == null) {
                        onDismiss()
                    } else {
                        onConfirm(pickerMillisToDateText(selected))
                    }
                },
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    ) {
        DatePicker(state = state)
    }
}

/** 时间选择对话框：确认后回填 `HH:mm` 文本（24 小时制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkTimePickerDialog(
    initialTimeText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = parseHomeworkTime(initialTimeText)
    val state = rememberTimePickerState(
        initialHour = initial?.hour ?: DEFAULT_PICKER_HOUR,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择时间") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime.of(state.hour, state.minute).format(HOMEWORK_TIME_FORMAT))
                },
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/** 时间选择器默认落点：放学后的常见写作业时刻 */
private const val DEFAULT_PICKER_HOUR = 18
