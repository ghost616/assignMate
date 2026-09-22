package com.assignmate.app.navigation

import com.assignmate.app.navigation.AndroidApiLevelGuard.Rule
import com.assignmate.app.navigation.AndroidApiLevelGuard.Violation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级防回归：禁止低版本 Android 缺失（`ApiSince > minSdk`）的 java.time API（framework 宿主层公共约束）。
 */
class JavaTimeApiLevelGuardTest {

    // ---- 1. 规则与报告：违规样本命中、安全写法放行、报告带整改指引 ----

    @Test
    fun `每条规则都能命中对应的违规写法`() {
        val violated = mapOf(
            "JAVA_TIME_OF_INSTANT" to listOf(
                "LocalTime.ofInstant(instant, ZoneOffset.UTC)",
                "LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone)",
                "LocalDateTime.ofInstant(instant, ZoneId.systemDefault())",
            ),
        )

        assertEquals("违规样本必须与规则集逐一对应（增删规则须显式改本测试）：", RULE_IDS, violated.keys.sorted())

        violated.forEach { (ruleId, samples) ->
            val rule = ruleOf(ruleId)
            samples.forEach { sample ->
                assertTrue("该写法必须被规则命中：[$ruleId] $sample", rule.pattern.containsMatchIn(sample))
            }
        }
    }

    @Test
    fun `规则集被显式锁定且每条规则的最低等级都高于工程 minSdk`() {
        // 规则增删必须显式修改本测试（评审点），防止悄悄引入误报规则
        assertEquals("规则 id 集合应与评审锁定值一致：", RULE_IDS, AndroidApiLevelGuard.RULES.map { it.id }.sorted())

        // 规则只有 androidApi > minSdk 才可能真机缺失；≤ minSdk 的规则在 minSdk 29 上本就可用，属误报
        AndroidApiLevelGuard.RULES.forEach { rule ->
            assertTrue(
                "误报规则（androidApi=${rule.androidApi} <= minSdk=${AndroidApiLevelGuard.MIN_SDK}），不应被拦截：[${rule.id}]",
                rule.androidApi > AndroidApiLevelGuard.MIN_SDK,
            )
        }
    }

    @Test
    fun `ApiSince 26 就可用且事实存在的 java 时间写法一律不得被判违规`() {
        // 两条误报规则的收口回归：三参 LocalDate.of 与 LocalDateTime.ofEpochSecond 自 Java 8 即有、
        // Android ApiSince = 26（minSdk 29 上完全可用）；LocalDate.ofEpochDay / LocalTime.ofSecondOfDay
        // 同为 ApiSince 26。它们一个都不许再被判违规（单行、跨行、变量实参三种形态都要覆盖）。
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    fun literal() = LocalDate.of(2024, 5, 1)",
            "    fun vars(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)",
            "    fun epochDay(days: Long) = LocalDate.ofEpochDay(days)",
            "    fun secondOfDay(seconds: Long) = LocalTime.ofSecondOfDay(seconds)",
            "    fun nanoOfDay(nanos: Long) = LocalTime.ofNanoOfDay(nanos)",
            "    fun epochSecond(second: Long, nano: Int, offset: ZoneOffset) =",
            "        LocalDateTime.ofEpochSecond(second, nano, offset)",
            "    fun epochSecondInline(second: Long) = LocalDateTime.ofEpochSecond(second, 0, ZoneOffset.UTC)",
            "    fun epochSecondLiteral() = LocalDateTime.ofEpochSecond(1000L, 0, ZoneOffset.UTC)",
            "    fun localDateLiteral() = LocalDate.of(",
            "        2024,",
            "        5,",
            "        1,",
            "    )",
            "    fun localDateVars(year: Int, month: Int, day: Int) = LocalDate.of(",
            "        year,",
            "        month,",
            "        day,",
            "    )",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals(
            "ApiSince 26 的写法不得被判违规（历史误报已收口）：\n" + AndroidApiLevelGuard.describe(violations),
            emptyList<String>(),
            violations.map { it.file + ":" + it.line + " [" + it.rule.id + "] " + it.code },
        )
    }

    @Test
    fun `注释里的说明文字与 API 26 即可用的等价写法不得被判违规`() {
        val source = listOf(
            "package com.assignmate.app.demo",
            "import java.time.Instant",
            "",
            "// LocalTime.ofInstant(instant, ZoneOffset.UTC) 这里出现 ofInstant 只是注释说明，不得判违规",
            "/* LocalDate.ofInstant(value, zone) 块注释中的说明同样不得判违规 */",
            "object Demo {",
            "    fun offsetOf(seconds: Long) = LocalTime.ofSecondOfDay(seconds)",
            "    fun timeOf(hour: Int, minute: Int) = LocalTime.of(hour, minute)",
            "    fun dateOf(epochDay: Long) = LocalDate.ofEpochDay(epochDay)",
            "    fun dateOf(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)",
            "    fun instantOf(millis: Long) = Instant.ofEpochMilli(millis)",
            "    fun carried(instant: Instant, zone: java.time.ZoneId) = instant.atZone(zone).toLocalDate()",
            "}",
        ).joinToString("\n")

        assertEquals("安全写法与注释都不应产生违规：", emptyList<Violation>(), AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt"))
    }

    @Test
    fun `违规报告带文件与行号且能识别跨行书写的调用`() {
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    fun localTime(instant: Instant) = LocalTime.ofInstant(instant, ZoneOffset.UTC)",
            "    fun localDate(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()",
            "    fun broken(instant: Instant) = LocalDate.ofInstant(",
            "        instant,",
            "        ZoneOffset.UTC,",
            "    )",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals("应恰好命中两条 ofInstant 调用：", 2, violations.size)
        assertEquals("LocalTime.ofInstant 应定位到第 4 行：", 4, violations[0].line)
        assertEquals("跨行书写的 LocalDate.ofInstant 应定位到起始行第 6 行：", 6, violations[1].line)
        assertEquals("违规记录应带上文件标识：", "Sample.kt", violations[0].file)
        assertEquals("同 instant 规则应命中 LocalTime.ofInstant：", "JAVA_TIME_OF_INSTANT", violations[0].rule.id)
        assertEquals("跨行调用也应归到 instant 规则：", "JAVA_TIME_OF_INSTANT", violations[1].rule.id)
        assertTrue("违规记录应保留命中代码片段便于定位：", violations[0].code.contains("LocalTime.ofInstant"))
    }

    // ---- 1.5 跨行/假阳性/防误拼专项：ofInstant 跨行必须命中、字符串字面量不误报、相邻独立调用不被误拼 ----

    @Test
    fun `三参 LocalDate 的 of 调用单行与跨行书写都不得被判违规`() {
        // 三参 LocalDate.of 自 Java 8 即有、Android ApiSince = 26，minSdk 29 上完全可用；
        // 它曾被误列为 API 31 规则而误报，此处以三种形态钉住「不再误报」。
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    fun a() = LocalDate.of(",
            "        2024,",
            "        5,",
            "        1,",
            "    )",
            "",
            "    fun b() = LocalDate.of(2023, 12, 31)",
            "",
            "    fun c(year: Int, month: Int, day: Int) = LocalDate.of(",
            "        year,",
            "        month,",
            "        day,",
            "    )",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals(
            "三参 LocalDate.of（ApiSince 26）单行/跨行/变量实参都不得判违规：",
            emptyList<Int>(),
            violations.map { it.line },
        )
    }

    @Test
    fun `字符串字面量里的违规写法不得产生假阳性`() {
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    val sample = \"示例：LocalDate.ofInstant(x, z) 自 Android API 31 起才可用\"",
            "    val quoted = \"LocalTime.ofInstant(instant, ZoneOffset.UTC)\"",
            "    val multiline = \"\"\"",
            "        文案示例：LocalDateTime.ofInstant(instant, ZoneId.systemDefault())",
            "        以及 LocalDate.ofInstant(x, z)",
            "    \"\"\".trimIndent()",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals(
            "字符串（含三引号原始字符串）内的文案不得判违规：\n" + AndroidApiLevelGuard.describe(violations),
            emptyList<String>(),
            violations.map { it.file + ":" + it.line + " [" + it.rule.id + "] " + it.code },
        )
    }

    @Test
    fun `字符串模板插值内是真实代码必须照常扫描`() {
        // 取舍：字面文本抹除（消除假阳性），但插值里是会被真实执行的代码——照常扫描，
        // 否则「抹字符串」会新增漏报面。此处钉住该口径，并顺带锁住未规范化字符串的跨行剥离。
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    val label = \"今天是 ${'$'}{LocalDate.ofInstant(x, z)}\"",
            "    val other = \"今天：${'$'}today\"",
            "    val raw = \"\"\"",
            "        ${'$'}{LocalDateTime.ofInstant(instant, zone)}",
            "    \"\"\"",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals("插值里的真实调用必须命中：", listOf(4, 7), violations.map { it.line })
        assertEquals(
            "命中应归到 ofInstant 规则：",
            listOf("JAVA_TIME_OF_INSTANT", "JAVA_TIME_OF_INSTANT"),
            violations.map { it.rule.id },
        )
        assertTrue("命中代码应保留插值所在行原文：", violations[0].code.contains("ofInstant"))
    }

    @Test
    fun `简单插值成员调用的状态机不得吃掉后续字符串与真实调用`() {
        // 回归（离朱探针 D1/D3/L2）：曾让 `$name.foo(` 进入「由 } 收尾」的插值模式，
        // 结果参数列表的 `)` 无法收尾，字符串终止引号被当成代码吃掉 →
        // ① 规格原样写法漏报；② 紧随其后的真实调用被吞（假阴性）；③ 字面文案被暴露成代码（假阳性）。
        // 修复后由配对的 `)` 收尾，以上三种表现都必须消失。
        val d1 = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.foo(LocalDate.ofInstant(x, z))\"",
            "}",
        ).joinToString("\n")
        assertEquals(
            "规格原样的插值成员调用必须命中且行号正确：",
            listOf(2),
            AndroidApiLevelGuard.scanKotlinSource(d1, "Sample.kt").map { it.line },
        )

        val d3 = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.foo(bar)\"",
            "    val a = LocalTime.ofInstant(i, z)",
            "}",
        ).joinToString("\n")
        assertEquals(
            "插值成员调用的参数列表结束后，紧随其后的真实调用必须仍被扫描：",
            listOf(3),
            AndroidApiLevelGuard.scanKotlinSource(d3, "Sample.kt").map { it.line },
        )

        val l2 = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.foo(${'$'}{bar})\"",
            "    val doc = \"LocalTime.ofInstant(i, z) 只是文案\"",
            "    val ok = LocalDate.ofEpochDay(0L)",
            "}",
        ).joinToString("\n")
        assertEquals(
            "插值成员调用之后的字符串字面量文案必须仍被抹除（不得假阳性）：\n" +
                AndroidApiLevelGuard.describe(AndroidApiLevelGuard.scanKotlinSource(l2, "Sample.kt")),
            emptyList<Int>(),
            AndroidApiLevelGuard.scanKotlinSource(l2, "Sample.kt").map { it.line },
        )

        val nested = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.foo(bar(Baz.qux(LocalDate.ofInstant(x, z))))\"",
            "    val y = LocalDate.ofEpochDay(1L)",
            "}",
        ).joinToString("\n")
        assertEquals(
            "嵌套括号必须按配对深度收尾，不越过字符串边界：",
            listOf(2),
            AndroidApiLevelGuard.scanKotlinSource(nested, "Sample.kt").map { it.line },
        )

        // 合成形状 `$name.(`（点号后直接跟左括号；Kotlin 不可编译，仅人工构造字符串时出现）：
        // 按调用处理——点号保留并进入 INTERP_CALL，括号内的真实调用照常被扫描，不得漏报。
        val dottedCall = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.(LocalDate.ofInstant(x, z))\"",
            "    val y = LocalDate.ofEpochDay(1L)",
            "}",
        ).joinToString("\n")
        assertEquals(
            "合成形状点号加左括号时，括号内的真实调用不得漏报：",
            listOf(2),
            AndroidApiLevelGuard.scanKotlinSource(dottedCall, "Sample.kt").map { it.line },
        )

        // 对照：点号在字符串末尾（其后不是左括号）时不得越界、不得误报
        val trailingDot = listOf(
            "object Demo {",
            "    val x = \"${'$'}name.\"",
            "    val y = LocalDate.ofEpochDay(1L)",
            "}",
        ).joinToString("\n")
        assertEquals(
            "点号在串尾时不得越界、不得误报：",
            emptyList<Int>(),
            AndroidApiLevelGuard.scanKotlinSource(trailingDot, "Sample.kt").map { it.line },
        )
    }

    @Test
    fun `简单插值的成员访问与常见尾随边界都必须照常扫描且不抛异常`() {
        // 收口上一版「只保留 !! / ?.」留下的极窄漏报面：$name.foo(...) 内是真实代码，必须命中。
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    val a = \"${'$'}formatter.foo(LocalDate.ofInstant(x, z))\"",
            "    val b = \"${'$'}formatter.bar?.baz(LocalTime.ofInstant(i, z))\"",
            "    val c = \"${'$'}formatter.qux!!.quux(LocalDateTime.ofInstant(i, z))\"",
            "    val d = \"${'$'}formatter.plain(LocalDate.ofInstant(y, w)) ${'$'}tail\"",
            "}",
        ).joinToString("\n")

        val violations = AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt")

        assertEquals("插值成员调用内的违规必须命中：", listOf(4, 5, 6, 7), violations.map { it.line })
        assertEquals(
            "命中应归到 ofInstant 规则：",
            List(4) { "JAVA_TIME_OF_INSTANT" },
            violations.map { it.rule.id },
        )

        // 常见尾随边界：冒号/左括号/逗号/右括号/非空断言/安全调用 —— 都不得抛异常、不得误报
        val boundaries = listOf(
            "object Demo {",
            "    val a = \"${'$'}name:\"",
            "    val b = \"${'$'}name(\"",
            "    val c = \"${'$'}name,\"",
            "    val d = \"${'$'}name)\"",
            "    val e = \"${'$'}name!!\"",
            "    val f = \"${'$'}name?.length\"",
            "    val g = \"${'$'}name.foo(\"",
            "    val h = \"${'$'}\"",
            "    val i = \"${'$'}\" + \"${'$'}name.\"",
            "    val offsetOf(seconds: Long) = LocalTime.ofSecondOfDay(seconds)",
            "    val dateOf(days: Long) = LocalDate.ofEpochDay(days)",
            "}",
        ).joinToString("\n")

        assertEquals(
            "插值尾随边界与 ApiSince 26 写法都不得判违规：\n" + AndroidApiLevelGuard.describe(AndroidApiLevelGuard.scanKotlinSource(boundaries, "Sample.kt")),
            emptyList<Int>(),
            AndroidApiLevelGuard.scanKotlinSource(boundaries, "Sample.kt").map { it.line },
        )
    }

    @Test
    fun `相邻两行各自闭合的独立调用不得被拼成假命中`() {
        // 三行各自括号闭合的独立调用，其中只有第三行是真实违规（ofInstant，ApiSince 31）：
        // 拼接边界必须以语句/括号边界为限——若把相邻行无脑拼接，行 4 的 ApiSince 26 写法
        // 会与行 6 的 ofInstant 对偶成假命中。此处钉住「不多报」。
        val source = listOf(
            "package com.assignmate.app.demo",
            "",
            "object Demo {",
            "    fun a() = LocalDate.of(2024, 5, 1)",
            "    fun b() = LocalDateTime.ofEpochSecond(1000L, 0, ZoneOffset.UTC)",
            "    fun c(instant: Instant) = LocalTime.ofInstant(instant, ZoneOffset.UTC)",
            "}",
        ).joinToString("\n")

        assertEquals(
            "只有第三行（ofInstant）应命中，ApiSince 26 的相邻行不得被拼成假命中：",
            listOf(6),
            AndroidApiLevelGuard.scanKotlinSource(source, "Sample.kt").map { it.line },
        )

        // 反证：上一行括号未闭合时属于同一条语句，拼接后必须命中，且只报一次（行号回推到起始行）
        val splitSameStatement = listOf(
            "object Demo {",
            "    fun a() = LocalTime.ofInstant(",
            "        instant,",
            "        ZoneOffset.UTC,",
            "    )",
            "    fun b() = LocalDate.ofEpochDay(0L)",
            "}",
        ).joinToString("\n")

        assertEquals(
            "跨行同一条语句仍应命中一次，且行号指向 ofInstant 起始行：",
            listOf(2),
            AndroidApiLevelGuard.scanKotlinSource(splitSameStatement, "Sample.kt").map { it.line },
        )
    }

    @Test
    fun `失败信息明确给出违规文件与行号并附两条整改方向`() {
        val source = listOf(
            "object Demo {",
            "    fun timeOfDay(instant: Instant) = LocalTime.ofInstant(instant, ZoneOffset.UTC)",
            "}",
        ).joinToString("\n")

        val violation = AndroidApiLevelGuard.scanKotlinSource(source, "Demo.kt").single()
        val report = violation.describe()
        val reportOfAll = AndroidApiLevelGuard.describe(listOf(violation))

        listOf(report, reportOfAll).forEach { text ->
            assertTrue("失败信息应含文件与行号：$text", text.contains("Demo.kt:2"))
            assertTrue("失败信息应含命中代码：$text", text.contains("LocalTime.ofInstant"))
            assertTrue("失败信息应点明该 API 的最低 Android 等级：$text", text.contains("31"))
            assertTrue("失败信息应点明该 API 等级口径为 ApiSince：$text", text.contains("ApiSince"))
            assertTrue("失败信息应点明本工程 minSdk：$text", text.contains("29"))
            assertTrue("失败信息应给出等价写法整改方向：$text", text.contains("等价写法"))
            assertTrue("失败信息应给出启用 core library desugaring 的整改方向：$text", text.contains("core library desugaring"))
            // 整改示例必须是真实存在的 API（旧文案里的 LocalTime.ofEpochSecond 并不存在，会误导修复者）
            listOf(
                "Instant.ofEpochMilli(m).atZone(zone).toLocalTime()",
                "LocalTime.ofSecondOfDay(s)",
                "LocalTime.ofNanoOfDay(n)",
            ).forEach { realApi ->
                assertTrue("整改示例必须是真实且 ApiSince<=26 的 API：$realApi", text.contains(realApi))
            }
            assertTrue("整改示例不得再出现不存在的 LocalTime.ofEpochSecond：$text", !text.contains("LocalTime.ofEpochSecond"))
            assertTrue("整改示例的可用性理由应写明 ApiSince<=26：$text", text.contains("ApiSince<=26"))
        }
    }

    // ---- 2. 真实源码守卫：app/src/main/java 下全部 Kotlin 源码当前无违规 ----

    @Test
    fun `全应用 Kotlin 主源码不得出现低版本 Android 缺失的 java 时间系列 API`() {
        val dir = File(repoRoot(), MAIN_SOURCE_ROOT)
        assertTrue("主源码目录不存在：${dir.absolutePath}", dir.isDirectory)

        val kotlinFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()
        assertTrue("守卫必须真的扫到源码文件（路径错则本断言失败，避免空扫即通过）：", kotlinFiles.size >= MIN_EXPECTED_MAIN_FILES)

        val violations = kotlinFiles.flatMap { file ->
            AndroidApiLevelGuard.scanKotlinSource(file.readText(), file.relativeTo(repoRoot()).path.replace('\\', '/'))
        }

        assertEquals(
            "主源码出现低版本 Android 缺失的 java.time API（真机 API<31 会抛 NoSuchMethodError）：\n" + AndroidApiLevelGuard.describe(violations),
            emptyList<String>(),
            violations.map { it.file + ":" + it.line },
        )
    }

    @Test
    fun `守卫自身源码的说明文字不得被判违规`() {
        val guardSource = readSource("src/main/java/com/assignmate/app/navigation/AndroidApiLevelGuard.kt")

        assertTrue("守卫源码应以示例文字说明被禁 API（说明本身不得判违规）：", guardSource.contains("LocalTime.ofInstant"))
        assertEquals(
            "守卫自身源码不应产生违规：",
            emptyList<Violation>(),
            AndroidApiLevelGuard.scanKotlinSource(guardSource, "AndroidApiLevelGuard.kt"),
        )
    }

    @Test
    fun `守卫口径与工程 minSdk 同源且每条规则都带整改说明`() {
        val minSdkFromGradle = readSource("app/build.gradle.kts")
            .let { gradle -> Regex("minSdk\\s*=\\s*(\\d+)").find(gradle)?.groupValues?.get(1)?.toInt() }

        assertNotNull("未在 app/build.gradle.kts 中读到 minSdk：", minSdkFromGradle)
        assertEquals("守卫 MIN_SDK 应与 app/build.gradle.kts 的 minSdk 一致：", minSdkFromGradle, AndroidApiLevelGuard.MIN_SDK)

        AndroidApiLevelGuard.RULES.forEach { rule ->
            assertTrue(
                "被禁 API 的最低 Android 等级应高于工程 minSdk：[${rule.id}] androidApi=${rule.androidApi}",
                rule.androidApi > AndroidApiLevelGuard.MIN_SDK,
            )
            assertTrue("每条规则都应带一句话说明：[${rule.id}]", rule.description.isNotBlank())
        }
    }

    // ---- 测试工具 ----

    /** 按 id 取规则（id 与失败信息中打印的一致） */
    private fun ruleOf(id: String): Rule =
        AndroidApiLevelGuard.RULES.first { it.id == id }

    /**
     * 按相对仓库根的路径读取源码（正斜杠口径）。
     *
     * Gradle 单测的工作目录并不稳定（app/ 或仓库根），故依次尝试「仓库根 + 相对路径」「工作目录 + 相对路径」
     * 「工作目录 + app/ + 相对路径」三种口径，避免因工作目录差异把「路径没找对」误报成「守卫扫出违规」。
     */
    private fun readSource(relative: String): String {
        val candidates = listOf(
            File(repoRoot(), relative),
            File(relative),
            File("app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件：$relative（已尝试：${candidates.joinToString { it.absolutePath }}）", file)
        return file!!.readText()
    }

    /** 自工作目录向上查找仓库根（同时命中 app/src/main/java 与 settings.gradle.kts，避免命中验证副本目录） */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, MAIN_SOURCE_ROOT).isDirectory && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录向上未找到仓库根：${File("").absolutePath}")
    }

    private companion object Constants {

        const val MAIN_SOURCE_ROOT = "app/src/main/java"

        /** 主源码文件数下限：低于此值说明扫描路径失效（防止空扫即通过） */
        const val MIN_EXPECTED_MAIN_FILES = 150

        /**
         * 评审锁定的规则 id 集合（升序）。
         *
         * 规则增删必须显式改本测试，形成评审点；这也防止悄悄把 `ApiSince <= minSdk` 的
         * 误报规则（如历史上的三参 LocalDate.of、LocalDateTime.ofEpochSecond，均为 ApiSince 26）加回来。
         */
        val RULE_IDS = listOf("JAVA_TIME_OF_INSTANT")
    }
}
