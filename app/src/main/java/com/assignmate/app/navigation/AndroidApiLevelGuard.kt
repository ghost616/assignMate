package com.assignmate.app.navigation

/**
 * 源码级 Android API 等级守卫（framework 宿主层公共约束，纯 Kotlin、无 Android 依赖）。
 *
 * 为什么需要它：本工程 minSdk 29 且 app/build.gradle.kts 未启用 core library desugaring，
 * 而编译期用的是 JDK——其 java.time 已含 Android 低版本缺失的新增 API。例如
 * `LocalTime.ofInstant(instant, zone)` / `LocalDate.ofInstant(instant, zone)` 是
 * **Android ApiSince = 31** 才补齐的静态工厂：编译毫无告警、单元测试在本机 JVM 上照样通过，
 * 但在 API 29/30 真机上执行到该行即抛 `NoSuchMethodError`。
 * 这类缺陷无法靠「跑一遍单元测试」发现，只能由源码扫描兜住。
 *
 * 用法（见 JavaTimeApiLevelGuardTest）：对 app/src/main/java 下全部 Kotlin 源码逐文件调用
 * [scanKotlinSource]，期望结果恒为空列表；一旦非空即说明引入了低版本 Android 缺失的 API。
 *
 * **规则收录原则（宁缺毋滥）**：只有能给出 Android `ApiSince` 依据、且 `ApiSince > MIN_SDK`
 * 的 API 才允许进入 [RULES]（见 [Rule.androidApi]）。**当前唯一一条规则的依据出处**见 [RULES] 的 KDoc
 * （开发者文档 `LocalTime.ofInstant` / `LocalDate.ofInstant` 页面标注 Added in API level 31，
 * 与 Java 9 引入该方法的版本事实自洽）——依据必须可核验，禁止只写数字空口断言。
 * 历史教训：曾把 `LocalDate.of(year, month, day)` 三参重载与 `LocalDateTime.ofEpochSecond(epochSecond, nano, offset)`
 * 当成「Java 9 新增、API 31 才可用」而误报——两者自 Java 8 即有、Android **ApiSince = 26**，
 * 在 minSdk 29 上完全可用，故已从规则集删除，本文件不再对其作任何违规断言。
 *
 * 扫描口径（四步，逐条与实现对齐）：
 * 1. **先抹掉注释与字符串字面量**（[stripLiteralsAndComments]）：块注释/行注释、单行字符串
 *    `"..."`、三引号原始字符串 `"""..."""` 的**字面文本**一律替换为空格，而**字符位、换行与行号完全不变**。
 *    因此 KDoc 里「不得再用 ofInstant」这类说明文字、以及文案/示例里写到的
 *    `LocalDate.ofInstant(instant, zone)` 文本都不会被判违规。
 * 2. **按「括号未闭合才续行拼接」跨行重组代码**（[groupCodeBlocks]）：一段代码只有在上一行
 *    存在未闭合的 `(`/`[`/`{`（括号深度 > 0）时才会与下一行拼接，括号闭合即结束该段。
 *    **取舍（刻意如此）**：相邻两行的**独立**调用（括号各自闭合，如
 *    `val a = LocalTime.ofInstant(instant, zone)` 与下一行 `val b = LocalDate.ofEpochDay(0)`）
 *    永不被拼成一条假命中——拼接边界严格以语句/括号边界为限，宁可少拼也不虚构跨语句调用；
 *    代价是「上一行括号未闭合、下一行却开始一条新语句」的畸形代码可能被拼在一起，
 *    但那种写法本身即语法错误，且被禁 API 仍需完整出现在拼接串中才会命中。
 * 3. **对每段拼接串逐规则匹配并回推起始行号**：命中的 [Violation.line] 指向**命中片段的起始行**
 *    （即 `ofInstant(` 所在行），而非拼接段的起始行；[Violation.code] 取该行的原始源码。
 * 4. 同一行同一规则只产出一条违规（多段/多次匹配去重），结果按行号升序返回。
 *
 * 字符串模板插值（`"${...}"`）口径：插值里的**是真实代码，照常扫描**（`"${LocalDate.ofInstant(i, z)}"`
 * 会命中，且行号正确；三引号原始字符串中的插值同样照常扫描）；字符串的**字面文本**才被抹除。
 * 故本守卫不因「抹字符串」而新增漏报面。简单插值 `$name` 只保留变量名与紧跟的成员访问尾巴
 * （`$name.foo(` → 保留 `name.foo(` 并把其后内容按代码扫描，见 [consumeInterpolationTail]），
 * 因为 `$` 不是代码、保留它会破坏相邻 token 的匹配。
 *
 * 已知边界（刻意不覆盖，避免误报）：
 * - `LocalDate.of(年, 月, 日)`、`LocalDateTime.ofEpochSecond(...)`、`LocalDate.ofEpochDay(...)`、
 *   `LocalTime.ofSecondOfDay(...)` 等 **Android ApiSince = 26** 的 API 在 minSdk 29 上可用，无一被 [RULES] 收录，一律不判违规；
 * - 无法给出 `ApiSince` 依据的候选 API 一律不收录（宁缺毋滥）；
 * - 合成形状 `$name.(...)`（简单插值变量后紧跟点号再紧跟左括号，例如 `"$name.(LocalDate.ofInstant(i, z))"`）：
 *   **Kotlin 语法不允许**（`$name.` 之后必须跟成员名，`.` 后直接写 `(` 无法编译），仅在人工构造字符串时出现；
 *   实现按「视为调用参数」收口——保留点号并进入 [Mode.INTERP_CALL]，故其括号内的真实调用照常被扫描（不会漏报）；
 * - 静态导入/别名写法（`import java.time.LocalTime.ofInstant`）与反射调用不在覆盖范围。
 *
 * 失败信息由 [Violation.describe] 生成，明确给出「违规文件/行 + 命中代码 + 整改方向」，
 * 避免无指引的裸失败。
 */
object AndroidApiLevelGuard {

    /**
     * 一条「低版本 Android 缺失」调用规则。
     *
     * @param id 规则标识（用于失败信息前缀与排查）
     * @param pattern 命中即违规的正则（在「已抹掉注释与字符串字面量」的代码上匹配）
     * @param androidApi 该 API 在 Android 上经 `ApiSince` 查证的最低 API 等级；
     *   收录前提是 `androidApi > MIN_SDK`，否则在本工程 minSdk 上本就可用、属误报
     * @param description 该 API 的一句话说明（面向修复者），须与 `ApiSince` 事实一致
     */
    data class Rule(
        val id: String,
        val pattern: Regex,
        val androidApi: Int,
        val description: String,
    )

    /**
     * 一条违规记录。
     *
     * @param file 相对仓库根的文件路径（正斜杠，便于与 IDE/报错定位对齐）
     * @param line 行号（1 起）；跨行调用时为**命中片段起始行**
     * @param code 命中片段起始行的原始代码（trim 后；该行为空时取拼接段内首个非空行）
     * @param rule 命中规则
     */
    data class Violation(
        val file: String,
        val line: Int,
        val code: String,
        val rule: Rule,
    ) {

        /**
         * 失败信息：指明违规位置、命中 API 的 `ApiSince` 与工程 minSdk，
         * 并给出两条整改方向（含各自的可用性理由）。
         */
        fun describe(): String = buildString {
            append(file).append(':').append(line)
            append(" [").append(rule.id).append(']')
            append(" 命中 Android ApiSince=").append(rule.androidApi)
            append("（本工程 minSdk=").append(MIN_SDK).append("）的 java.time API：").append(code)
            append("；").append(rule.description)
            append("。整改方向：① 改用 ApiSince<=26 的等价写法——")
            append("由瞬时值取本地时间用 Instant.ofEpochMilli(m).atZone(zone).toLocalTime()")
            append("（Instant#atZone 与 ZonedDateTime#toLocalTime 均自 Java 8 / Android API 26 起可用）；")
            append("由当日秒数取时间用 LocalTime.ofSecondOfDay(s)、由当日纳秒数取时间用 LocalTime.ofNanoOfDay(n)")
            append("（两者同为 Java 8 / Android API 26 起可用，且都是 java.time.LocalTime 的真实静态工厂）；")
            append("只要日期用 Instant.ofEpochMilli(m).atZone(zone).toLocalDate()。")
            append("② 或在 app/build.gradle.kts 启用 core library desugaring")
            append("（isCoreLibraryDesugaringEnabled = true + coreLibraryDesugaring 依赖），让低版本 Android 也拿到 JDK 的 java.time 实现。")
        }
    }

    /**
     * 工程 minSdk（低于此等级可用的 API 不会被拦截）。
     *
     * 与 app/build.gradle.kts 的 minSdk 29 保持一致；调整 minSdk 时须同步本常量与 [RULES] 的判定口径。
     */
    const val MIN_SDK: Int = 29

    /**
     * 被拦截的 API 清单。
     *
     * 收录前提：有 `ApiSince` 依据且 `ApiSince > MIN_SDK`（在 minSdk 29 上确实不可用）。
     *
     * 当前唯一一条 `JAVA_TIME_OF_INSTANT` 的依据出处（可核验）：
     * - Android 开发者文档 `LocalTime.ofInstant(java.time.Instant, java.time.ZoneId)` 页面
     *   （`https://developer.android.com/reference/java/time/LocalTime#ofInstant(java.time.Instant,%20java.time.ZoneId)`）
     *   标注 **Added in API level 31**；`LocalDate.ofInstant` / `LocalDateTime.ofInstant` 同属该家族、同为 API 31；
     * - 版本事实自洽：`ofInstant` 是 Java 9 引入的静态工厂（Java 8 的 java.time 尚无此方法），
     *   而 Android 直到 API 31 才补齐到该 JDK 版本；
     * - 现场自洽：本工程 minSdk 29（荣耀 V10 / Android 10 真机为 API 29），执行到该行即抛 `NoSuchMethodError`。
     *
     * 因此该规则 `androidApi = 31`，且满足 `androidApi(=31) > MIN_SDK(=29)`。
     */
    val RULES: List<Rule> = listOf(
        Rule(
            id = "JAVA_TIME_OF_INSTANT",
            pattern = Regex("\\bLocal(?:Date|Time|DateTime)\\s*\\.\\s*ofInstant\\s*\\("),
            androidApi = 31,
            description = "ofInstant 家族在 Android 上的 ApiSince = 31（依据：开发者文档 ofInstant 页面标注 Added in API level 31），低于 31 的真机执行到该行即抛 NoSuchMethodError",
        ),
    )

    /**
     * 扫描一段 Kotlin 源码，返回全部违规记录（按行号升序）。
     *
     * @param source 源码文本（生产文件完整内容；测试亦可用合成样本驱动）
     * @param file 用于失败信息的文件标识（相对仓库根路径）
     */
    fun scanKotlinSource(source: String, file: String): List<Violation> {
        val codeLines = stripLiteralsAndComments(source)
        val violations = mutableListOf<Violation>()
        val seen = mutableSetOf<String>()

        groupCodeBlocks(codeLines).forEach { block ->
            val charLines = block.map { codeLines[it - 1] }
            val capacity = charLines.sumOf { it.length + 1 }
            val joined = StringBuilder(capacity)
            val linesOfChar = IntArray(capacity)
            var cursor = 0
            charLines.forEachIndexed { index, line ->
                if (index > 0) {
                    joined.append(' ')
                    linesOfChar[cursor] = block[index]
                    cursor++
                }
                line.forEach { char ->
                    joined.append(char)
                    linesOfChar[cursor] = block[index]
                    cursor++
                }
            }
            val text = joined.toString()

            RULES.forEach { rule ->
                rule.pattern.findAll(text).forEach { match ->
                    val line = linesOfChar[match.range.first]
                    if (seen.add(rule.id + '#' + line)) {
                        violations += Violation(
                            file = file,
                            line = line,
                            code = snippetAt(codeLines, block.first(), line),
                            rule = rule,
                        )
                    }
                }
            }
        }
        return violations.sortedWith(compareBy({ it.line }, { RULES.indexOf(it.rule) }))
    }

    /** 违规记录列表 → 失败信息（逐条含文件/行/整改方向；无违规则返回空串）。 */
    fun describe(violations: List<Violation>): String =
        violations.joinToString(separator = "\n") { it.describe() }

    /**
     * 把（已抹除注释与字符串字面量的）代码行按「括号未闭合才续行」分组。
     *
     * 维护一个括号深度：只有当上一行结束（或当前累计）深度 > 0 —— 即存在未闭合的
     * `(`/`[`/`{` —— 时才与下一行归入同一段；深度归零即结束该段。因此相邻两行各自闭合的
     * **独立**调用不会被拼成一段（不会产生跨语句的假命中）。
     *
     * 空白行不会延长当前段（否则会在拼接串里插入多余空格）；只有在本段已由「括号未闭合」开启时
     * 才会被收进段内，故跳过它是安全的。
     *
     * @return 每段对应的原始行号列表（1 起，升序）
     */
    private fun groupCodeBlocks(codeLines: List<String>): List<List<Int>> {
        val blocks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var depth = 0

        codeLines.forEachIndexed { index, line ->
            if (current.isEmpty() && line.isBlank()) return@forEachIndexed
            current += index + 1
            depth += bracketDelta(line)
            if (depth <= 0) {
                blocks += current
                current = mutableListOf()
                depth = 0
            }
        }
        if (current.isNotEmpty()) blocks += current
        return blocks
    }

    /** 一行的括号净增量（已抹除字符串与注释，故可直接计数）。 */
    private fun bracketDelta(line: String): Int =
        line.count { it == '(' || it == '[' || it == '{' } - line.count { it == ')' || it == ']' || it == '}' }

    /** 取命中行的原始代码；该行为空时取本段内首个非空行，保证失败信息永远有可定位片段。 */
    private fun snippetAt(codeLines: List<String>, blockStart: Int, line: Int): String {
        val hit = codeLines[line - 1].trim()
        if (hit.isNotEmpty()) return hit
        val index = (blockStart..line).firstOrNull { it - 1 in codeLines.indices && codeLines[it - 1].isNotBlank() }
        return if (index == null) "" else codeLines[index - 1].trim()
    }

    /**
     * 抹掉块注释、行注释与字符串字面量，**保持字符位、换行与行数不变**（被抹字符替换为空格）。
     *
     * - 注释：KDoc/块注释与 `//` 行注释中的说明文字（如「不得再用 ofInstant」）不得判违规；
     * - 字符串字面量：单行 `"..."`（含 `\` 转义）与三引号原始字符串 `"""..."""` 的**字面文本**
     *   一律抹除，消除文案/示例里内联写出违规写法造成的假阳性；
     * - 字符串模板插值：`${...}` 内是**真实代码**，保留并照常扫描（普通字符串与三引号原始字符串都支持）；
     *   简单插值 `$name` 保留变量名与紧跟的成员访问尾巴（见 [consumeInterpolationTail]），
     *   故 `"$name.foo(LocalDate.ofInstant(i, z))"` 这类插值内真实调用不会被漏掉；
     *   **调用参数按配对的 `)` 收尾**（[Mode.INTERP_CALL]）：`$name.foo(` 的参数列表由它自己的 `)` 结束，
     *   不会把字符串的终止引号当成代码吃掉（否则会漏掉紧随其后的真实调用、并可能把字面文案误报为代码）；
     *   **取舍（与实现逐字一致）**：实现只认两种插值入口——`${`（进入 `INTERP`，由 `}` 收尾）与
     *   `$` + 标识符起始（保留变量名与成员访问尾巴）；**其余 `$` 一律不写入 `out`**（既不保留也不报错，
     *   按字符串字面文本抹除），故不存在「原始字符串里字面写出 `${...}` 被当作代码」的情形：
     *   三引号原始字符串中的 `${...}` 会被扫描，是因为它**在 Kotlin 里本来就是插值**（原始字符串同样支持模板），
     *   而非误报；token 匹配不受影响，因为 `$` 本身不是被禁 API 的组成部分；
     * - 未闭合的注释/字符串按「一直延续到文件末尾」处理（抹到末尾），不会抛异常也不会串位。
     */
    private fun stripLiteralsAndComments(source: String): List<String> {
        val out = CharArray(source.length) { if (source[it] == '\n') '\n' else ' ' }
        // 模式栈：CODE / STRING / RAW_STRING / INTERP（`${...}` 由 `}` 收尾）/
        // INTERP_CALL（`$name.foo(` 由**配对的 `)`** 收尾）；顶层恒为 CODE
        val modes = mutableListOf(Mode.CODE)
        // 与 modes 同步的 INTERP_CALL 括号深度栈（仅 INTERP_CALL 层有值）
        val callDepths = mutableListOf(0)
        var index = 0

        while (index < source.length) {
            when (modes.last()) {
                Mode.CODE, Mode.INTERP -> when {
                    source.startsWith("//", index) -> {
                        while (index < source.length && source[index] != '\n') index++
                    }

                    source.startsWith("/*", index) -> {
                        index += 2
                        while (index < source.length && !source.startsWith("*/", index)) index++
                        if (index < source.length) index += 2
                    }

                    source.startsWith("\"\"\"", index) -> {
                        index += 3
                        pushMode(modes, callDepths, Mode.RAW_STRING)
                    }

                    source[index] == '"' -> {
                        index++
                        pushMode(modes, callDepths, Mode.STRING)
                    }

                    source[index] == '}' && modes.last() == Mode.INTERP -> {
                        popMode(modes, callDepths)
                        index++
                    }

                    else -> {
                        out[index] = source[index]
                        index++
                    }
                }

                // `$name.foo(...)` 的参数列表是真实代码：按括号深度扫描，遇配对的 `)` 即回到字符串上下文。
                // 关键：退出条件是**配对的 `)`** 而非 `}`——否则字符串的终止引号会被当成代码吃掉，
                // 既漏掉紧随其后的真实调用（假阴性），又可能把字面文案暴露为代码（假阳性）。
                Mode.INTERP_CALL -> when {
                    source.startsWith("//", index) -> {
                        while (index < source.length && source[index] != '\n') index++
                    }

                    source.startsWith("/*", index) -> {
                        index += 2
                        while (index < source.length && !source.startsWith("*/", index)) index++
                        if (index < source.length) index += 2
                    }

                    source.startsWith("\"\"\"", index) -> {
                        index += 3
                        pushMode(modes, callDepths, Mode.RAW_STRING)
                    }

                    source[index] == '"' -> {
                        index++
                        pushMode(modes, callDepths, Mode.STRING)
                    }

                    source[index] == '(' -> {
                        callDepths[callDepths.lastIndex]++
                        out[index] = source[index]
                        index++
                    }

                    source[index] == ')' -> {
                        callDepths[callDepths.lastIndex]--
                        out[index] = source[index]
                        index++
                        if (callDepths.last() <= 0) popMode(modes, callDepths)
                    }

                    else -> {
                        out[index] = source[index]
                        index++
                    }
                }

                Mode.STRING, Mode.RAW_STRING -> {
                    val raw = modes.last() == Mode.RAW_STRING
                    when {
                        !raw && source[index] == '\\' -> index += 2

                        !raw && source[index] == '"' -> {
                            popMode(modes, callDepths)
                            index++
                        }

                        raw && source.startsWith("\"\"\"", index) -> {
                            popMode(modes, callDepths)
                            index += 3
                        }

                        source.startsWith("${'$'}{", index) -> {
                            pushMode(modes, callDepths, Mode.INTERP)
                            index += 2
                        }

                        source[index] == '$' && source.charAtOrNull(index + 1)?.isJavaIdentifierStart() == true -> {
                            // 简单插值 $name：$ 不是代码（抹掉），变量名与成员访问尾巴保留（见下）
                            index++
                            while (index < source.length && source[index].isJavaIdentifierPart()) {
                                out[index] = source[index]
                                index++
                            }
                            index = consumeInterpolationTail(source, out, modes, callDepths, index)
                        }

                        else -> index++
                    }
                }
            }
        }
        return out.concatToString().lines()
    }

    /**
     * 保留简单插值 `$name` 紧跟的尾巴，使 `$name.foo(...)` 内**是真实代码**的部分照常被扫描。
     *
     * 覆盖的常见边界（均不得抛异常、不得破坏既有匹配）：
     * - `$name!!` / `$name?.` / `$name!!.member`：`!!`、`?.`、`.` 与成员名都是代码，保留；
     * - `$name.member`：点号与成员名是代码，保留；
     * - `$name.member(`：成员名后紧跟 `(` 说明这是一次**真实调用**，故进入 [Mode.INTERP_CALL]，
     *   使其参数内（可能含被禁 API）按代码扫描——这正是仅保留 `!!` / `?.` 时留下的极窄漏报面
     *   （`$name.foo(LocalDate.ofInstant(...))` 会被整段抹掉）；
     *   **退出条件是配对的 `)`**（见 [Mode.INTERP_CALL]），不是 `}`：若等 `}` 收尾，字符串的终止引号
     *   会被当成代码吃掉，既漏掉紧随其后的真实调用，又可能把字面文案暴露为代码；
     * - `$name.(`（点号后直接跟左括号，**Kotlin 不可编译的合成形状**）：同按调用处理，保留点号并进入
     *   [Mode.INTERP_CALL]，使其括号内的真实调用照常被扫描；
     * - `$name:` / `$name(` / `$name,` / `$name)` 等：只保留变量名本身，其余原样交给字符串模式处理；
     * - 越界判定一律走 [charAtOrNull]，绝不越界访问。
     *
     * @return 处理后的游标位置
     */
    private fun consumeInterpolationTail(
        source: String,
        out: CharArray,
        modes: MutableList<Mode>,
        callDepths: MutableList<Int>,
        from: Int,
    ): Int {
        var index = from
        if (index >= source.length) return index

        while (true) {
            // 访问器前缀：`!!` 与 `?.` 都属于代码，保留；`.` 直接进入成员名
            if (source[index] == '!') {
                if (source.charAtOrNull(index + 1) != '!') return index
                out[index] = '!'
                out[index + 1] = '!'
                index += 2
            } else if (source[index] == '?') {
                if (source.charAtOrNull(index + 1) != '.') return index
                out[index] = '?'
                out[index + 1] = '.'
                index += 2
            } else if (source[index] != '.') {
                // `$name` 之后没有访问器（如 `$name,`、`$name)`），保持原样
                return index
            }

            // 访问器名的点号：`.`（`?.` 的点号已在上面消费），其后必须紧跟标识符
            if (source.charAtOrNull(index + 1)?.isJavaIdentifierStart() != true) {
                if (source.charAtOrNull(index + 1) == '(') {
                    // 合成形状 `$name.(...)`（Kotlin 不可编译，仅人工构造字符串时出现）：
                    // 视为一次调用，保留点号并进入 INTERP_CALL，使其参数内的真实调用照常被扫描（不漏报）
                    out[index] = '.'
                    pushMode(modes, callDepths, Mode.INTERP_CALL)
                    return index + 1
                }
                out[index] = ' '
                return index
            }
            out[index] = '.'
            index++
            while (index < source.length && source[index].isJavaIdentifierPart()) {
                out[index] = source[index]
                index++
            }
            // 成员名后紧跟 `(`：进入「调用参数按代码扫描」模式，由配对的 `)` 收尾
            //（`(` 本身不保留，避免它把相邻 token 粘成假命中）
            if (source.charAtOrNull(index) == '(') {
                pushMode(modes, callDepths, Mode.INTERP_CALL)
                index++
                return index
            }
            // 未跟 `(`：可能是链式访问（`$name.a?.b.c(...)`），继续吃下一个访问器；
            // 若下一个字符不是 `!` / `?` / `.` 开头的访问器则结束（如 `$name.foo,`、`$name.foo.`）。
            val next = source.charAtOrNull(index)
            if (next != '!' && next != '?' && next != '.') return index
        }
    }

    /** 入栈一个模式（INTERP_CALL 同步压入其括号深度初值 0）。 */
    private fun pushMode(modes: MutableList<Mode>, callDepths: MutableList<Int>, mode: Mode) {
        modes += mode
        callDepths += 0
    }

    /** 出栈一个模式（同步弹出括号深度）。 */
    private fun popMode(modes: MutableList<Mode>, callDepths: MutableList<Int>) {
        if (modes.size > 1) {
            modes.removeAt(modes.lastIndex)
            callDepths.removeAt(callDepths.lastIndex)
        }
    }

    /** 越界返回 null 的安全取字符（用于插值尾巴的边界判定，避免越界异常）。 */
    private fun String.charAtOrNull(index: Int): Char? = if (index in indices) this[index] else null

    /** 抹除状态机的上下文模式。 */
    private enum class Mode { CODE, STRING, RAW_STRING, INTERP, INTERP_CALL }
}
