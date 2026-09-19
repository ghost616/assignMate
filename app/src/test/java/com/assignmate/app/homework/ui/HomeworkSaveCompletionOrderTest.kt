package com.assignmate.app.homework.ui

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存后立即回清单」的时序 + 提示不丢失单测（复审必修项：保存后立即回清单）。
 *
 * 修复前的缺陷形态（录入 / 模板 / 时间设定三个页面一致）：
 * ```
 * is HomeworkEntryEvent.Saved -> {
 *     snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)  // 挂起约 10 秒
 *     onSaved(event.homeworkId)                                                       // 提示结束后才回清单
 * }
 * ```
 * 后果：① 用户要等提示条消失才回得到清单；② 提示期间页面离开组合 → 协程被取消，
 * onSaved（含 framework 的「按 id 精确同步提醒」）**完全不执行**。
 *
 * 本类锁定修复后的契约：
 * 1. **顺序**：onSaved 先于提示派发（立即回清单，提示不阻塞导航）；
 * 2. **抗取消**：提示是**非挂起**写入，即便 onSaved 触发导航把本页协程取消，提示依然落袋；
 * 3. **不丢失**：清单页是展示点，展示后消费清空，且不会误清随后发布的新提示；
 * 4. **结构**：三个保存页的 Saved 分支不得再出现提示条（读码断言守住，防止回退成旧形态）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSaveCompletionOrderTest {

    // ---- 1~3. 时序与提示载体行为 ----

    @Test
    fun `保存成功后先回清单再派发提示（提示条不阻塞导航）`() {
        val holder = HomeworkSaveNoticeHolder()
        val calls = mutableListOf<String>()
        var noticeWhenNavigating: String? = null
        var snapshotTaken = false

        dispatchSaveCompletion(
            dispatchMessage = SAVED_MESSAGE,
            saveNotice = holder,
            onSaved = {
                calls += ON_SAVED
                // 在 onSaved 内部取快照：此刻提示必须**尚未**派发，证明顺序是「先回清单、后派发提示」
                noticeWhenNavigating = holder.notice.value
                snapshotTaken = true
            },
        )

        assertEquals("onSaved 必须被调用（回清单 + 同步提醒）", listOf(ON_SAVED), calls)
        assertTrue("必须在 onSaved 内取过快照，否则下一条断言无意义", snapshotTaken)
        assertNull("回清单那一刻提示尚未派发：导航不被提示条阻塞", noticeWhenNavigating)
        assertEquals("提示文案不得丢失（由清单页在导航后展示）", SAVED_MESSAGE, holder.notice.value)
    }

    @Test
    fun `提示派发不挂起：onSaved 中离开组合取消协程也不会吞掉提示`() = runTest {
        val holder = HomeworkSaveNoticeHolder()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<String>()

        scope.launch {
            dispatchSaveCompletion(
                dispatchMessage = SAVED_MESSAGE,
                saveNotice = holder,
                onSaved = {
                    calls += ON_SAVED
                    // 复刻「导航离开组合 → 本页 LaunchedEffect 被取消」：
                    // 旧实现此处 await showSnackbar，取消后 onSaved 与其后的提醒同步都不执行
                    scope.cancel()
                },
            )
        }
        advanceUntilIdle()

        assertEquals("onSaved 必须已执行（提醒同步必发）", listOf(ON_SAVED), calls)
        assertEquals(
            "非挂起写入没有挂起点，协程被取消也不影响提示落袋",
            SAVED_MESSAGE,
            holder.notice.value,
        )
    }

    @Test
    fun `无附加文案的保存（模板编辑路径）只回清单不派发提示`() {
        val holder = HomeworkSaveNoticeHolder()
        val calls = mutableListOf<String>()

        dispatchSaveCompletion(
            dispatchMessage = null,
            saveNotice = holder,
            onSaved = { calls += ON_SAVED },
        )

        assertEquals(listOf(ON_SAVED), calls)
        assertNull("编辑保存本就无附加提示（与修复前行为一致）", holder.notice.value)
    }

    @Test
    fun `清单页展示后消费清空且不会重复弹出`() {
        val holder = HomeworkSaveNoticeHolder()

        holder.publish(SAVED_MESSAGE)
        assertEquals(SAVED_MESSAGE, holder.notice.value)

        holder.consume(SAVED_MESSAGE)
        assertNull("展示完毕即清空，避免重新进入清单页时重复弹出", holder.notice.value)

        holder.consume(SAVED_MESSAGE)
        assertNull("重复消费幂等", holder.notice.value)
    }

    @Test
    fun `展示期间发布的新提示不会被旧提示的消费清掉`() {
        val holder = HomeworkSaveNoticeHolder()

        holder.publish(OLD_MESSAGE)
        holder.publish(SAVED_MESSAGE)
        assertEquals("只保留最新一条（确认性提示无需排队）", SAVED_MESSAGE, holder.notice.value)

        holder.consume(OLD_MESSAGE)
        assertEquals("迟到的旧提示消费不得清掉新提示", SAVED_MESSAGE, holder.notice.value)

        holder.consume(SAVED_MESSAGE)
        assertNull(holder.notice.value)
    }

    // ---- 4. 结构契约：收尾编排顺序 + 三个页面不再 await 提示条 ----

    @Test
    fun `收尾编排先回清单再发布提示（源码顺序契约）`() {
        val source = readMainSource("ui/HomeworkSaveNotice.kt")
        val onSavedIndex = source.indexOf("onSaved()")
        val publishIndex = source.indexOf("saveNotice.publish(")

        assertTrue("收尾编排必须调用 onSaved", onSavedIndex >= 0)
        assertTrue("收尾编排必须发布提示", publishIndex >= 0)
        assertTrue(
            "onSaved 必须先于提示发布：用户先回清单，提示不得阻塞导航",
            onSavedIndex < publishIndex,
        )
    }

    @Test
    fun `三个保存页的 Saved 分支都走收尾编排且不再出现提示条`() {
        val entry = savedBranchOf(
            relativePath = "ui/HomeworkEntryScreen.kt",
            branchMarker = "is HomeworkEntryEvent.Saved ->",
            nextMarker = "is HomeworkEntryEvent.ShowMessage ->",
        )
        assertTrue("录入页 Saved 分支必须走收尾编排", entry.contains("dispatchSaveCompletion("))
        assertTrue("录入页 Saved 分支必须透传新建作业 id", entry.contains("onSaved(event.homeworkId)"))
        assertFalse("录入页 Saved 分支不得再出现提示条（旧形态会阻塞导航并被取消吞掉）", entry.contains("showSnackbar"))

        val template = savedBranchOf(
            relativePath = "ui/HomeworkTemplateScreen.kt",
            branchMarker = "is HomeworkTemplateEvent.Saved ->",
            nextMarker = "is HomeworkTemplateEvent.ShowMessage ->",
        )
        assertEquals(
            "模板页两个保存分支（编辑 / 新建）都要走收尾编排",
            2,
            Regex("dispatchSaveCompletion\\(").findAll(template).count(),
        )
        assertEquals(
            "模板页两个分支都要透传事件 id",
            2,
            Regex("onSaved\\(event\\.homeworkId\\)").findAll(template).count(),
        )
        assertFalse("模板页 Saved 分支不得再 await 提示条", template.contains("showSnackbar"))

        val timeSet = savedBranchOf(
            relativePath = "ui/HomeworkTimeSetScreen.kt",
            branchMarker = "is TimeSetEvent.Saved ->",
            nextMarker = "is TimeSetEvent.ShowMessage ->",
        )
        assertTrue("时间设定页 Saved 分支同样先回清单再派发提示", timeSet.contains("dispatchSaveCompletion("))
        assertTrue(
            "时间设定页仍须先外抛「按新时刻同步到点提醒」",
            timeSet.contains("onHomeworkScheduleSaved(event.homeworkId)"),
        )
        assertFalse("时间设定页 Saved 分支不得再 await 提示条", timeSet.contains("showSnackbar"))
    }

    @Test
    fun `清单页是保存成功提示的展示点（消费并用 Short 展示）`() {
        val listSource = readMainSource("ui/HomeworkListScreen.kt")

        assertTrue(
            "清单页必须订阅跨页待展示提示",
            listSource.contains("homeworkSaveNotice.notice.filterNotNull().collect"),
        )
        assertTrue(
            "展示后必须消费清空（否则重新进入清单页会重复弹出）",
            listSource.contains("homeworkSaveNotice.consume(message)"),
        )
        assertTrue(
            "确认性提示用 Short 展示：不为它占用 10 秒",
            listSource.contains("duration = SnackbarDuration.Short"),
        )
    }

    // ---- 测试工具 ----

    private fun readMainSource(relativePath: String): String {
        val file = File(repoRoot(), "$HOMEWORK_MAIN_ROOT/$relativePath")
        check(file.isFile) { IOException("源文件不存在: ${file.absolutePath}") }
        return file.readText()
    }

    /** 取出某个事件分支到下一个事件分支之间的源码片段（用于断言分支内部形态） */
    private fun savedBranchOf(relativePath: String, branchMarker: String, nextMarker: String): String {
        val source = readMainSource(relativePath)
        val start = source.indexOf(branchMarker)
        assertTrue("$relativePath 缺少分支标记：$branchMarker", start >= 0)
        val end = source.indexOf(nextMarker, start + branchMarker.length)
        assertTrue("$relativePath 缺少分支结束标记：$nextMarker", end >= 0)
        return source.substring(start, end)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, HOMEWORK_MAIN_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $HOMEWORK_MAIN_ROOT")
    }

    private companion object Constants {

        const val HOMEWORK_MAIN_ROOT = "app/src/main/java/com/assignmate/app/homework"

        const val SAVED_MESSAGE = "已添加 1 项作业"
        const val OLD_MESSAGE = "已添加 1 项作业（旧）"
        const val ON_SAVED = "onSaved"
    }
}