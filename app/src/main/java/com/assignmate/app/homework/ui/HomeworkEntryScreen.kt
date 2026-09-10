package com.assignmate.app.homework.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.io.File
import java.time.LocalDate

/**
 * 作业录入页：四种录入方式统一入口（手动 / 拍照识别 / 相册识别 / 语音录入），
 * 识别或输入结果统一汇入同一「可编辑文本 → 确认保存」流程；
 * 并提供「待重试识别」批量重试入口与权限申请/拒绝处理。
 *
 * 权限：拍照前申请 CAMERA、语音前申请 RECORD_AUDIO，拒绝时给出可读提示（引导去系统设置）。
 * 说明：相机/录音真实行为需在真机验证；FileProvider 声明由 framework 计划补全。
 */
@Composable
fun HomeworkEntryRoute(
    studentId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onGoToOcrSettings: (() -> Unit)? = null,
    viewModel: HomeworkEntryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle(initialValue = 0)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel, studentId) { viewModel.start(studentId) }

    // ---- 权限与系统 Intent 启动器 ----
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success -> viewModel.onCameraResult(success) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onCameraClick() else viewModel.onCameraPermissionDenied() }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> viewModel.onGalleryPicked(uri?.let { copyToCache(context, it) }) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onVoiceStart() else viewModel.onAudioPermissionDenied() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeworkEntryEvent.LaunchCamera -> {
                    val file = File(event.outputPath)
                    cameraLauncher.launch(
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        ),
                    )
                }

                is HomeworkEntryEvent.Saved -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)
                    onSaved()
                }

                is HomeworkEntryEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Long,
                )

                is HomeworkEntryEvent.NeedsOcrConfiguration -> {
                    // 未配置识别服务：提示 + 提供去设置页的引导（settings 路由由 framework 注入）
                    val result = snackbarHostState.showSnackbar(
                        message = "${event.message}（点击「去设置」填写厂商参数）",
                        actionLabel = if (onGoToOcrSettings != null) "去设置" else null,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onGoToOcrSettings?.invoke()
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HomeworkEntryContent(
            uiState = uiState,
            pendingCount = pendingCount,
            callbacks = HomeworkEntryCallbacks(
                onBack = onBack,
                onMethodChange = viewModel::onMethodChange,
                onContentChange = viewModel::onContentChange,
                onTypeChange = viewModel::onTypeChange,
                onStageRangeChange = viewModel::onStageRangeChange,
                onDeadlineDateChange = viewModel::onDeadlineDateChange,
                onDeadlineTimeChange = viewModel::onDeadlineTimeChange,
                onSubmit = viewModel::onSubmit,
                onCameraClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.onCameraClick() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onGalleryClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onVoiceClick = {
                    if (uiState.voiceListening) {
                        viewModel.onVoiceStop()
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            viewModel.onVoiceStart()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                onVoiceCancel = viewModel::onVoiceCancel,
                onRetryPending = viewModel::onRetryPendingClick,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 录入页回调集合（权限与 Intent 启动在 Route 层完成，内容函数保持无状态） */
class HomeworkEntryCallbacks(
    val onBack: () -> Unit,
    val onMethodChange: (HomeworkEntryMethod) -> Unit,
    val onContentChange: (String) -> Unit,
    val onTypeChange: (HomeworkType) -> Unit,
    val onStageRangeChange: (StageRange) -> Unit,
    val onDeadlineDateChange: (String) -> Unit,
    val onDeadlineTimeChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onCameraClick: () -> Unit,
    val onGalleryClick: () -> Unit,
    val onVoiceClick: () -> Unit,
    val onVoiceCancel: () -> Unit,
    val onRetryPending: () -> Unit,
)

/** 录入页内容（无状态） */
@Composable
fun HomeworkEntryContent(
    uiState: HomeworkEntryUiState,
    pendingCount: Int,
    callbacks: HomeworkEntryCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = callbacks.onBack) { Text(text = "返回") }
        Text(
            text = "录入作业",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在准备…")
            uiState.missingSession -> CoreEmptyPlaceholder(text = "会话已失效，请返回重新进入")
            else -> {
                EntryMethodPicker(uiState = uiState, callbacks = callbacks)
                if (pendingCount > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    PendingRetryCard(
                        pendingCount = pendingCount,
                        retrying = uiState.retrying,
                        onRetry = callbacks.onRetryPending,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                EntryForm(uiState = uiState, callbacks = callbacks)
            }
        }
    }
}

/** 四种录入方式入口（当前方式高亮；语音方式展示实时回显） */
@Composable
private fun EntryMethodPicker(
    uiState: HomeworkEntryUiState,
    callbacks: HomeworkEntryCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "选择录入方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.method == HomeworkEntryMethod.MANUAL,
                    onClick = { callbacks.onMethodChange(HomeworkEntryMethod.MANUAL) },
                    label = { Text(text = HomeworkEntryMethod.MANUAL.label) },
                )
                FilterChip(
                    selected = uiState.method == HomeworkEntryMethod.CAMERA,
                    onClick = { callbacks.onMethodChange(HomeworkEntryMethod.CAMERA) },
                    label = { Text(text = HomeworkEntryMethod.CAMERA.label) },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.method == HomeworkEntryMethod.GALLERY,
                    onClick = { callbacks.onMethodChange(HomeworkEntryMethod.GALLERY) },
                    label = { Text(text = HomeworkEntryMethod.GALLERY.label) },
                )
                FilterChip(
                    selected = uiState.method == HomeworkEntryMethod.VOICE,
                    onClick = { callbacks.onMethodChange(HomeworkEntryMethod.VOICE) },
                    label = { Text(text = HomeworkEntryMethod.VOICE.label) },
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            when (uiState.method) {
                HomeworkEntryMethod.MANUAL -> AssignMateBigButton(
                    text = "开始手动输入",
                    onClick = { callbacks.onMethodChange(HomeworkEntryMethod.MANUAL) },
                )

                HomeworkEntryMethod.CAMERA -> {
                    Text(
                        text = HomeworkEntryMethod.CAMERA.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AssignMateBigButton(
                        text = if (uiState.processing) "识别中…" else "拍照识别",
                        onClick = callbacks.onCameraClick,
                        enabled = !uiState.processing,
                    )
                }

                HomeworkEntryMethod.GALLERY -> {
                    Text(
                        text = HomeworkEntryMethod.GALLERY.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AssignMateBigButton(
                        text = if (uiState.processing) "识别中…" else "从相册选择",
                        onClick = callbacks.onGalleryClick,
                        enabled = !uiState.processing,
                    )
                }

                HomeworkEntryMethod.VOICE -> {
                    Text(
                        text = HomeworkEntryMethod.VOICE.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (uiState.voiceListening) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "🎤 正在聆听…${uiState.voicePartial}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AssignMateBigButton(
                        text = if (uiState.voiceListening) "结束并采用" else "开始说话",
                        onClick = callbacks.onVoiceClick,
                    )
                    if (uiState.voiceListening) {
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = callbacks.onVoiceCancel) { Text(text = "取消这次语音") }
                    }
                }
            }
        }
    }
}

/** 待重试识别入口（联网后批量重试） */
@Composable
private fun PendingRetryCard(
    pendingCount: Int,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "有 $pendingCount 张图片待识别",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "联网后点下方按钮重试识别，结果会自动填入内容框",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AssignMateBigButton(
                text = if (retrying) "重试中…" else "重试识别（$pendingCount）",
                onClick = onRetry,
                enabled = !retrying,
                containerColor = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** 统一的作业内容编辑与保存表单（四种录入方式共用同一份内容） */
@Composable
private fun EntryForm(
    uiState: HomeworkEntryUiState,
    callbacks: HomeworkEntryCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = uiState.content,
                onValueChange = callbacks.onContentChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("作业内容（识别结果可继续修改）") },
                placeholder = { Text("例如：语文第 3 课生字各写两遍") },
                minLines = 3,
                isError = uiState.contentError != null,
                supportingText = {
                    Text(
                        text = uiState.contentError
                            ?: "${uiState.content.length}/${HomeworkConstants.MAX_CONTENT_LENGTH}",
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "作业类型", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeworkType.entries.forEach { type ->
                    FilterChip(
                        selected = uiState.type == type,
                        onClick = { callbacks.onTypeChange(type) },
                        label = { Text(text = type.label) },
                    )
                }
            }
            if (uiState.type == HomeworkType.STAGE) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "阶段范围", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageRange.entries.forEach { range ->
                        FilterChip(
                            selected = uiState.stageRange == range,
                            onClick = { callbacks.onStageRangeChange(range) },
                            label = { Text(text = range.label) },
                        )
                    }
                }
                if (uiState.stageError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FormError(uiState.stageError)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "覆盖：${LocalDate.ofEpochDay(uiState.todayEpochDay)} 至 " +
                        "${LocalDate.ofEpochDay(uiState.lastEpochDay)}（每天一条、可逐日完成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.deadlineEditable) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "截止时间（家长设置）", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.deadlineDate,
                        onValueChange = callbacks.onDeadlineDateChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("日期") },
                        placeholder = { Text("2025-01-02") },
                        singleLine = true,
                        isError = uiState.deadlineError != null,
                    )
                    OutlinedTextField(
                        value = uiState.deadlineTime,
                        onValueChange = callbacks.onDeadlineTimeChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("时间") },
                        placeholder = { Text("21:00") },
                        singleLine = true,
                        isError = uiState.deadlineError != null,
                    )
                }
                if (uiState.deadlineError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FormError(uiState.deadlineError)
                }
            }
            if (uiState.formError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FormError(uiState.formError)
            }
            Spacer(modifier = Modifier.height(16.dp))
            AssignMateBigButton(
                text = if (uiState.submitting) "保存中…" else "保存到作业清单",
                onClick = callbacks.onSubmit,
                enabled = uiState.canSubmit,
            )
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun FormError(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 把相册选中的图片复制到应用缓存，返回本地路径（识别链路统一走本地文件）。
 *
 * 按源图 MIME 决定扩展名：png/webp 若被强行命名为 .jpg，后续 MIME 推断会与内容不符
 * （真实实现按内容压缩为 JPEG，但扩展名与 MIME 保持一致更利于排查与复用）。
 */
private fun copyToCache(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val extension = when (mimeType) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val dir = java.io.File(context.cacheDir, "homework").apply { if (!exists()) mkdirs() }
        val target = java.io.File(dir, "gallery_${java.util.UUID.randomUUID()}$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()