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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.auth.domain.Role
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
 *
 * 「识别服务未配置」的引导按会话角色区分（口径为 [HomeworkEntryUiState.role]，源自 auth 会话）：
 * 家长会话渲染「去设置」按钮（经 [onGoToOcrSettings] 跳设置页），学生会话**不给**该入口
 * （学生无权维护厂商配置，否则会撞上无权访问的页面），改为就地提示「请让家长先配置识别服务」；
 * 会话失效（role 为 null）沿用既有会话失效处理，不额外引导。
 *
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

    /**
     * 识别服务未配置且本会话无权配置时的就地卡片正文（非空即渲染，当前仅学生会话产生）。
     *
     * 取值来自 [ocrSetupGuidanceAction] 的 `cardText`（收敛产物），**不是**静态短句：
     * 重试汇总「识别成功 N 张，其余仍失败：…」时学生同样能看到成功条数。
     */
    var ocrSetupHint by remember { mutableStateOf<String?>(null) }

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
                    // 未配置提示的**唯一渲染点**：正文与「去设置」动作由 [ocrSetupGuidanceAction]
                    // 统一推导（其内部经 ocrNoticeFor 收敛），UI 只是消费该结果：
                    // 学生取 cardText 渲染就地卡片、家长/会话失效取 snackbarMessage + actionLabel
                    // 渲染一条 Snackbar。两个分支消费同一产物，故正文不会出现两份来源。
                    val guidance = ocrSetupGuidance(uiState.role, onGoToOcrSettings != null)
                    val action = ocrSetupGuidanceAction(guidance, event.message)
                    when (guidance) {
                        // 学生无权维护厂商配置：不给「去设置」入口，就地卡片渲染收敛后的正文
                        is OcrSetupGuidance.StudentAskParent -> ocrSetupHint = action.cardText

                        // 家长/会话失效：渲染这一条提示（家长分支附「去设置」动作，未注入回调时无按钮）
                        is OcrSetupGuidance.Parent, OcrSetupGuidance.SessionInvalid -> {
                            val result = snackbarHostState.showSnackbar(
                                message = action.snackbarMessage,
                                actionLabel = action.actionLabel,
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onGoToOcrSettings?.invoke()
                            }
                        }
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
            ocrSetupHint = ocrSetupHint,
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
    /** 识别未配置且本会话无权配置时的就地卡片正文（学生会话；内容为收敛产物，可能含「识别成功 N 张」汇总） */
    ocrSetupHint: String? = null,
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
                if (ocrSetupHint != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OcrSetupHintCard(text = ocrSetupHint)
                }
                Spacer(modifier = Modifier.height(12.dp))
                EntryForm(uiState = uiState, callbacks = callbacks)
            }
        }
    }
}

/**
 * 识别服务未配置的就地卡片（学生会话专用）：学生无权维护 OCR 厂商配置，
 * 因此不渲染「去设置」入口，改为在页面内说明需要家长先完成配置。
 *
 * 正文来自 [ocrSetupGuidanceAction] 的收敛产物（零指向设置页的文字），
 * 可包含重试汇总的成功条数等非设置页信息。
 */
@Composable
private fun OcrSetupHintCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
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

/** 「去设置」入口文案（家长会话渲染为 Snackbar 按钮；学生会话不渲染该按钮） */
internal const val OCR_SETTINGS_ACTION_LABEL: String = "去设置"

/**
 * 学生会话下「请家长配置」的引导短句：**只作为文案片段/断言锚点使用**，
 * 不作为渲染来源——学生就地卡片渲染的一律是 [ocrNoticeFor] 的收敛产物
 * （见 [HomeworkEntryRoute] 的 NeedsOcrConfiguration 分支），它可能包含「识别成功 N 张」等汇总信息。
 */
internal const val OCR_PARENT_SETUP_HINT: String = "请让家长先配置识别服务"

/**
 * 「识别服务未配置」的引导**片段**定义（三处文案收敛为两半：原始片段 + 学生改写片段）。
 *
 * - [OCR_RAW_SETTINGS_GUIDANCE]：底层原文中指向设置页的片段（Handler 的 failureHint 首句）；
 * - [OCR_PARENT_SETUP_GUIDANCE]：学生侧替换后的片段，不含任何指向设置页的字样。
 *
 * 为什么不直接写整句常量：重试汇总可能是「识别成功 2 张，其余仍失败：<原始片段>」，
 * 只做**片段替换**才能让学生同时看到成功条数与「请家长先配置」引导；
 * 整句文案一律由 [ocrNoticeFor] 从底层原文现场收敛产出，不再维护第二份整句常量
 * （历史教训：整句常量与收敛结果并存的写法，会让渲染分支不小心取到静态短句而丢掉汇总信息）。
 */
private const val OCR_RAW_SETTINGS_GUIDANCE: String =
    "识别服务未配置，请到设置页开启并填写厂商参数"

/** 学生侧替换后的引导片段：不含任何指向设置页的字样，保留「图片已保存待重试」 */
private const val OCR_PARENT_SETUP_GUIDANCE: String = "识别服务未配置，请让家长先配置识别服务"

/**
 * 学生侧**兜底**展示文案：底层原文恰为首句「识别服务未配置…（图片已保存待重试）」时的收敛结果。
 *
 * 仅用于测试与可读性参照（例如断言无汇总信息时卡片应显示什么）；
 * **渲染分支不得直接取本常量**——学生卡片渲染的一律是 [ocrNoticeFor] 的产物
 * （见 [HomeworkEntryRoute] 的 NeedsOcrConfiguration 分支），否则会丢掉「识别成功 N 张」等汇总信息。
 */
internal const val OCR_PARENT_SETUP_NOTICE: String = "$OCR_PARENT_SETUP_GUIDANCE（图片已保存待重试）"

/**
 * 一条「识别服务未配置」的**用户可见提示**（纯数据，可单测）：
 * 正文文案 + 可选的 Snackbar 动作按钮。
 *
 * 约定（避免双重收敛）：本对象是「最终文案 + 动作」的**唯一产出**，
 * 调用方拿到后直接渲染即可，**不得**再用其中的正文去调 [OcrSetupGuidance.notice] / [ocrNoticeFor]
 * 二次收敛（把成品当原文再喂回去，语义上就不再是「原文」了）。
 *
 * @property message 正文文案：家长 = 底层原文（**不含**按钮说明）、学生 = 请家长配置版本
 *                   （片段改写、保留「识别成功 N 张」等计数）、role=null = 底层原文
 * @property actionLabel 非空表示渲染「去设置」按钮（仅家长且已注入 onGoToOcrSettings）；
 *                       为 null 表示无按钮，且正文不会声称可点击
 */
internal data class OcrNotice(
    val message: String,
    val actionLabel: String?,
)

/**
 * 按会话角色把「未配置」底层原文改写为**学生可见**版本（纯函数）。
 *
 * 只做**片段替换**而非整体替换：重试汇总里的「识别成功 N 张」等计数信息必须原样保留。
 * 家长/会话失效（role != STUDENT）不改写。
 */
internal fun studentSafeOcrMessage(role: Role?, baseMessage: String): String =
    if (role == Role.STUDENT) {
        baseMessage.replace(OCR_RAW_SETTINGS_GUIDANCE, OCR_PARENT_SETUP_GUIDANCE)
    } else {
        baseMessage
    }

/**
 * 「识别服务未配置」提示的**渲染入口**（UI 唯一收敛点，纯函数、无副作用、可独立单测）。
 *
 * 为什么收敛放在 UI 这一层且只有这一处：正文与「去设置」动作必须由同一处推导，
 * 才能保证二者一致；ViewModel 只负责识别失败分派与「表单内联错误是否由本提示承担」的取舍
 * （未配置场景不再写 formError，见 HomeworkEntryViewModel），Snackbar 正文不预先收敛，
 * 避免同一提示被两层各收敛一次（家长侧会重复、或同一提示被渲染两条）。
 *
 * 「去设置」的呈现口径：**动作只走 Snackbar 的 actionLabel（按钮），不写进正文**。
 * 因此：
 * - 家长有按钮时：正文 = 底层原文（`…（图片已保存待重试）`，与既有原文逐字一致、无重复后缀），
 *   按钮 = 「去设置」；有按钮自然可点击，无需在正文里再声称「点击…」；
 * - 家长无按钮时：正文同样 = 底层原文（**不声称可点击**）、[OcrNotice.actionLabel] 为 null，
 *   即「只提示、无按钮」；
 * - 学生：正文按**片段改写**为请家长配置版本（[studentSafeOcrMessage]，保留一切非设置页信息
 *   与「识别成功 N 张」等计数）、无按钮；
 * - role = null（会话失效）：沿用底层原文、不额外引导、无按钮。
 *
 * 角色口径 role 取自 [HomeworkEntryUiState.role]（源自 auth 会话，未自造第二套来源）。
 *
 * @param baseMessage 底层识别失败原文（可为重试汇总「识别成功 N 张，其余仍失败：…」）
 * @param hasSettingsEntry 是否已注入 [HomeworkEntryRoute] 的 onGoToOcrSettings 回调
 */
internal fun ocrNoticeFor(role: Role?, hasSettingsEntry: Boolean, baseMessage: String): OcrNotice =
    when (role) {
        Role.PARENT -> OcrNotice(
            message = baseMessage,
            actionLabel = OCR_SETTINGS_ACTION_LABEL.takeIf { hasSettingsEntry },
        )

        Role.STUDENT -> OcrNotice(
            message = studentSafeOcrMessage(role, baseMessage),
            actionLabel = null,
        )

        null -> OcrNotice(message = baseMessage, actionLabel = null)
    }

/**
 * 引导意图 → **UI 分支动作**的纯函数（可单测）：把「该角色该渲染什么」固化成数据，
 * 供 [HomeworkEntryRoute] 消费，也让单测能对准渲染分支**真实消费值**（而不是各写一遍推导）。
 *
 * - 学生：只渲染就地卡片，[OcrSetupAction.cardText] 取收敛产物正文（**可能包含**
 *   「识别成功 N 张」等汇总信息，绝不能退化为静态短句）；非学生分支为 null（不渲染卡片）；
 * - 家长/会话失效：只渲染一条 Snackbar，正文/动作取收敛产物；[OcrSetupAction.actionLabel]
 *   仅在家长且已注入设置页入口时非空。
 */
internal data class OcrSetupAction(
    val cardText: String?,
    val snackbarMessage: String,
    val actionLabel: String?,
)

/**
 * 由引导意图与事件原文推导 UI 分支动作（纯函数）。
 *
 * @param guidance 当前角色对应的引导意图（见 [ocrSetupGuidance]）
 * @param baseMessage 事件携带的底层原文（可为重试汇总「识别成功 N 张，其余仍失败：…」）
 */
internal fun ocrSetupGuidanceAction(
    guidance: OcrSetupGuidance,
    baseMessage: String,
): OcrSetupAction {
    val notice = guidance.notice(baseMessage)
    return OcrSetupAction(
        cardText = notice.message.takeIf { guidance is OcrSetupGuidance.StudentAskParent },
        snackbarMessage = notice.message,
        actionLabel = notice.actionLabel,
    )
}

/**
 * 「识别服务未配置」时的引导意图（按会话角色区分，可单测的纯逻辑）。
 *
 * 为什么按角色区分：OCR 厂商参数只能由家长维护，给学生会话渲染「去设置」入口会把学生
 * 引到无权访问的设置页；因此学生分支改为就地提示请家长配置。
 *
 * **本接口只暴露一个渲染产物入口 [notice]**（正文 + 动作，见 [OcrNotice]）：
 * UI 两个分支消费的是同一产物（家长取正文+动作渲染 Snackbar、学生取正文渲染就地卡片），
 * 因此不存在「静态兜底文案」与「收敛结果」两份来源。
 */
internal sealed interface OcrSetupGuidance {

    /** 「去设置」按钮文案（仅家长且已注入回调时非空）；为 null 表示不渲染按钮 */
    val actionLabel: String?

    /**
     * 该角色下「未配置」提示的渲染结果（正文 + 动作，见 [OcrNotice]）。
     * 这是正文与按钮一致性的唯一来源，也是 UI 两个分支实际消费的值。
     */
    fun notice(baseMessage: String): OcrNotice

    /**
     * 家长会话：厂商配置由家长维护——已注入设置页入口则渲染「去设置」按钮，
     * 未注入（[actionLabel] 为 null）时保持既有行为：只提示、无按钮（正文亦不声称可点击）。
     */
    data class Parent(override val actionLabel: String?) : OcrSetupGuidance {

        override fun notice(baseMessage: String): OcrNotice = ocrNoticeFor(
            role = Role.PARENT,
            hasSettingsEntry = actionLabel != null,
            baseMessage = baseMessage,
        )
    }

    /**
     * 学生会话：不渲染「去设置」按钮，改为就地卡片渲染请家长配置正文；
     * 正文按角色片段改写（[studentSafeOcrMessage]），绝不夹带指向设置页的引导。
     */
    data object StudentAskParent : OcrSetupGuidance {

        override val actionLabel: String? = null

        override fun notice(baseMessage: String): OcrNotice =
            ocrNoticeFor(role = Role.STUDENT, hasSettingsEntry = false, baseMessage = baseMessage)
    }

    /** 会话失效（role 为 null）：沿用既有会话失效处理，不额外引导 */
    data object SessionInvalid : OcrSetupGuidance {

        override val actionLabel: String? = null

        override fun notice(baseMessage: String): OcrNotice =
            ocrNoticeFor(role = null, hasSettingsEntry = false, baseMessage = baseMessage)
    }
}

/**
 * 由会话角色与「是否已注入设置页入口」推导未配置引导意图。
 *
 * @param role 当前会话角色（取自 auth 会话，随 [HomeworkEntryUiState.role] 下发）；
 *             会话失效为 null（识别事件必发生在 start() 之后，正常不会读到 null）
 * @param hasSettingsEntry 是否已注入 [HomeworkEntryRoute] 的 onGoToOcrSettings 回调
 */
internal fun ocrSetupGuidance(role: Role?, hasSettingsEntry: Boolean): OcrSetupGuidance = when (role) {
    Role.PARENT -> OcrSetupGuidance.Parent(
        actionLabel = OCR_SETTINGS_ACTION_LABEL.takeIf { hasSettingsEntry },
    )

    Role.STUDENT -> OcrSetupGuidance.StudentAskParent
    null -> OcrSetupGuidance.SessionInvalid
}