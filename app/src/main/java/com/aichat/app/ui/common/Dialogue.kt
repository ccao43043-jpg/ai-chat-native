package com.aichat.app.ui.common

/*
 * RP 正文分层：把一条助手消息切成「台词 / 动作 / 内心 / 旁白」四类，并识别说话人。
 *
 * 记号约定不是我自己发明的 —— 仓库里的 RP 提示词早就这么要求模型了：
 *   plugins/rp/Worlds.kt:22   「动作放 *星号*；对白用中文引号」
 *   plugins/rp/Worlds.kt:23   fewshot 例：「角色：*把热茶推过去* 「先喝一口。我听着。」」
 *   plugins/rp/AutoCharService.kt:310 「用「角色名：台词」格式」
 *   plugins/rp/RpChapterCore.kt:44    「玩家用【玩家】，角色用【角色】」
 * 主流 RP 客户端（SillyTavern 的 Dialogue Colorizer、MiniTavern 的气泡设置）也是同一套：
 * 引号 = 说话、星号 = 动作、（…）= 内心戏、其余 = 旁白。所以这里按同构规则解析。
 *
 * 三个设计约束：
 *  1) 纯 Kotlin，不碰 Compose —— 好写纯 JVM 单测（app/src/test/.../DialogueTest.kt）；
 *  2) 流式安全：引号/星号还没闭合时必须把已经到达的部分照常切开并渲染，
 *     否则打字过程中台词会一卡一卡地闪成旁白；
 *  3) 不误伤普通聊天：isDialogue() 是闸门，代码块/表格/列表/中文占比低的回复一律
 *     继续走原来的 MarkdownText，非 RP 对话的观感一点不变。
 */

/** 一段被识别过角色的正文。 */
sealed interface DlgPart {
    /** 「…」/“…”/"…" 里的台词；speaker 是识别出的说话人，可能为空 */
    data class Speech(val speaker: String?, val text: String) : DlgPart

    /** *…* 包起来的动作、神态、语气 */
    data class Action(val text: String) : DlgPart

    /** （…）/(…) 包起来的内心独白；括号已在解析时剥掉，渲染时补回 */
    data class Inner(val text: String, val speaker: String? = null) : DlgPart

    /** 其余叙述性文字：场景、旁白、过渡句 */
    data class Narration(val text: String) : DlgPart

    /** 代码块 / 表格 / 列表 / 标题等结构化块：仍交给 Markdown 渲染，不参与分层 */
    data class Block(val block: MdBlock) : DlgPart
}

object Dialogue {

    /** 名字字段允许的字符（不含空白与标点，避免把整句话吞成"名字"） */
    private const val NAME_CHARS = """[^\s：:，。、！？；…"'「」『』“”*（）()\[\]]"""

    private val PLAIN_NAME = Regex("""^($NAME_CHARS{1,16})\s*[：:]""")
    private val BRACKET_NAME = Regex("""^【([^】\n]{1,16})】\s*[：:]""")

    /**
     * 长得像"标签"而不像人名的词。
     *
     * 没有这道闸门，`注意：本回答……`、`结论：……`、`时间：2024 年` 全会被判成
     * 某人在说话，进而套上一个带颜色的气泡 —— 正文里突然冒出彩色块，比不分层还难看。
     */
    private val NOT_NAMES = setOf(
        "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱", "自己", "大家",
        "注", "备注", "注意", "提示", "例", "例如", "示例", "示例对白", "说明", "总结", "结论",
        "回答", "问题", "答案", "原因", "结果", "背景", "时间", "地点", "人物", "场景",
        "系统", "旁白", "作者", "译者", "格式", "要点", "小结", "正文", "对话", "动作", "内心",
        "ooc", "ps", "note", "system", "assistant", "user", "narrator", "tip", "example",
    )

    /** 出现在括号里就该当旁白处理的前缀（OOC 场外音，不是角色内心） */
    private val OOC = Regex("""^\s*(ooc|OOC|系统|注|例|提示|player|玩家|说明|旁白)""")

    /** 台词标记：引号后至少两个字 */
    private val HAS_QUOTED = Regex("""[「『“"][^」』”"\n]{2,}""")

    /** 行首就是引号（剧本式排版；正文里顺手引个词不会落在行首） */
    private val QUOTE_AT_LINE_START = Regex("""(?m)^[ \t]*[「『“"]""")

    /** 动作标记：星号夹住非空白内容 */
    private val HAS_ACTION = Regex("""\*[^*\s][^*\n]*\*""")

    /** 说话人前缀 + 紧随其后的台词/动作标记 */
    private val SPEAKER_AT = Regex(
        """(?m)^\s*(?:【([^】\n]{1,16})】|($NAME_CHARS{1,16}))\s*[：:][ \t]*([「『“"*（(])""",
    )

    private val HEADING = Regex("""(?m)^\s*#{1,6}\s""")
    private val BULLET = Regex("""(?m)^\s*[-*+]\s+\S""")
    private val TABLE_SEP = Regex("""\|\s*:?-{2,}""")

    /**
     * 这条消息该不该用对话式渲染。
     *
     * 保守是刻意的：分层的收益只在 RP 正文里成立，而一旦在技术问答里误开，
     * 满屏衬线灰字会把代码解释读成小说。所以要求
     *   - 没有围栏代码块 / 标题 / 列表 / 表格（结构化内容本来就有 Markdown 排版）；
     *   - 中文占比 30% 以上、至少 10 个汉字（英文/混排回复不碰）；
     *   - 并且出现下面任意一种"确实在写对话"的组合：
     *       · 动作 + 引号台词（`*推门进来*「早。」`）
     *       · 「名字：」前缀 + 引号台词或动作（`林墨：「早。」`）
     *       · 行首就是引号的剧本式排版（`「你终于来了。」她转过身。`）
     * 只看"有没有引号"是不行的 —— 技术回复里 `以「幂等」为例`、`所谓“事务”`
     * 到处都是引号，那样会让整篇解释被渲染成小说体。
     */
    fun isDialogue(text: String): Boolean {
        if (text.length < 12) return false
        if (text.contains("```") || text.contains("~~~")) return false
        if (HEADING.containsMatchIn(text)) return false
        if (BULLET.findAll(text).count() >= 2) return false
        if (TABLE_SEP.containsMatchIn(text)) return false

        val cjk = text.count { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
        if (cjk < 10 || cjk * 10 < text.length * 3) return false

        val quoted = HAS_QUOTED.containsMatchIn(text)
        val action = HAS_ACTION.containsMatchIn(text)
        val speaker = hasRealSpeaker(text)
        return when {
            action && quoted -> true
            speaker && (quoted || action) -> true
            QUOTE_AT_LINE_START.containsMatchIn(text) -> true
            else -> false
        }
    }

    /**
     * 切分正文。
     *
     * @param defaultSpeaker 台上唯一角色的名字（会话 persona）。裸台词（没有「名字：」
     *        前缀的引号句）归给它 —— 单角色 RP 是最常见的形态，没有它台词就都是"无名"。
     */
    fun parts(text: String, defaultSpeaker: String? = null): List<DlgPart> {
        if (text.isBlank()) return emptyList()
        val known = collectSpeakers(text, defaultSpeaker)
        val out = ArrayList<DlgPart>()
        // 说话人要跨段沿用：Markdown.segments 按空行切段（每次换行分段在小说排版里
        // 极常见），如果每段都从 null 重新开始，「苏晚：（…他倒是会做人。）」
        // 后面紧跟的裸台词就会掉回会话默认角色 —— 同一个人上下两句被染成两种颜色。
        val carry = SpeakerCarry()
        for (seg in Markdown.segments(text)) {
            val blocks = Markdown.parseSegment(seg)
            val only = blocks.singleOrNull()
            if (only is MdBlock.Paragraph) out += scan(only.text, known, defaultSpeaker, carry)
            else blocks.forEach { out += DlgPart.Block(it) }
        }
        return out
    }

    /** 跨段携带的「当前说话人」（parts 内部用，不对外暴露） */
    private class SpeakerCarry { var name: String? = null }

    /**
     * 先扫一遍全篇，收集「确定是说话人」的名字。
     *
     * 为什么要两遍：`林墨：早啊。`（无引号）要不要算台词，取决于林墨在本篇里
     * 有没有真的带引号说过话。一次扫描做不到这个判断，先收集再判定即可。
     */
    private fun collectSpeakers(text: String, default: String?): Set<String> {
        val out = LinkedHashSet<String>()
        default?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (m in SPEAKER_AT.findAll(text)) {
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            if (isName(n)) out += n
        }
        return out
    }

    /** 有没有「真的是人名」的说话人前缀（闸门用；`注意：` 这类标签不算） */
    private fun hasRealSpeaker(text: String): Boolean = SPEAKER_AT.findAll(text).any {
        isName((it.groupValues[1].ifEmpty { it.groupValues[2] }).trim())
    }

    private fun isName(n: String): Boolean {
        if (n.isEmpty() || n.length > 16) return false
        if (n.lowercase() in NOT_NAMES) return false
        if (n.all { it.isDigit() }) return false          // 10:30
        if (Regex("""^\d{1,2}$""").matches(n)) return false
        return true
    }

    /** 在 line start 处尝试吃掉「名字：」前缀，返回 (名字, 名后位置) */
    private fun speakerAt(text: String, i: Int, known: Set<String>): Pair<String, Int>? {
        val head = text.substring(i, minOf(text.length, i + 24))
        val m = BRACKET_NAME.find(head) ?: PLAIN_NAME.find(head) ?: return null
        val name = (m.groups[1]?.value ?: return null).trim()
        if (!isName(name)) return null
        // 无引号也没有星号时，只有确认过的说话人才能把整句当台词：
        // 否则 `注意：……` 这类标签会被误判（NOT_NAMES 之外还有漏网的可能）。
        return name to (i + m.range.last + 1)
    }

    private fun hasMarker(s: String) = s.any { it in "「『“\"*（(" }

    private fun scan(text: String, known: Set<String>, default: String?, carry: SpeakerCarry): List<DlgPart> {
        val out = ArrayList<DlgPart>()
        val buf = StringBuilder()
        var i = 0
        var lineStart = true
        var innerOk = true
        // 当前的说话人：一旦出现过「名字：」就沿用下去（含跨段）
        // （小说里同一段对话的后续行通常不再重复写名字），没出现过则用 default
        var cur: String? = carry.name

        fun setCur(n: String?) { cur = n; carry.name = n }

        fun flush() {
            val s = buf.toString().trim()
            buf.setLength(0)
            if (s.isNotEmpty()) out += DlgPart.Narration(s)
        }

        while (i < text.length) {
            val c = text[i]

            if (c == '\n') {
                buf.append(c); i++; lineStart = true; innerOk = true
                continue
            }

            if (lineStart) {
                lineStart = false
                val hit = speakerAt(text, i, known)
                if (hit != null) {
                    flush()
                    setCur(hit.first)
                    val lineEnd = text.indexOf('\n', hit.second).let { if (it < 0) text.length else it }
                    val rest = text.substring(hit.second, lineEnd)
                    // 「林墨：早啊。」——有引号时交给下面的扫描器；没引号但名字可信就直接收作台词
                    if (!hasMarker(rest) && hit.first in known) {
                        val body = rest.trim()
                        if (body.isNotEmpty()) out += DlgPart.Speech(hit.first, body)
                        i = lineEnd
                        continue
                    }
                    i = hit.second
                    continue
                }
                innerOk = true
            }

            when {
                // 反斜杠转义：\* 不当动作记号
                c == '\\' && i + 1 < text.length -> { buf.append(text[i + 1]); i += 2 }

                c == '「' || c == '『' || c == '“' || c == '"' -> {
                    val close = when (c) { '「' -> '」'; '『' -> '』'; '“' -> '”'; else -> '"' }
                    val end = text.indexOf(close, i + 1)
                    // 流式：还没等到右引号时把已到的部分照样当台词渲染
                    val body = (if (end < 0) text.substring(i + 1) else text.substring(i + 1, end)).trim()
                    flush()
                    if (body.isNotEmpty()) out += DlgPart.Speech(cur ?: default, body)
                    i = if (end < 0) text.length else end + 1
                    innerOk = true
                }

                // **粗体**：标记留给行内解析，不要当成一对动作星号
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) { buf.append(c); i++ } else { buf.append(text, i, end + 2); i = end + 2 }
                }

                c == '*' -> {
                    val nx = text.getOrNull(i + 1)
                    // `* 列表项`、孤立的星号、`**` 已在上一条处理：都当普通字符
                    if (nx == null || nx.isWhitespace() || nx == '*') { buf.append(c); i++ }
                    else {
                        val end = text.indexOf('*', i + 1)
                        val body = (if (end < 0) text.substring(i + 1) else text.substring(i + 1, end)).trim()
                        flush()
                        if (body.isNotEmpty()) out += DlgPart.Action(body)
                        i = if (end < 0) text.length else end + 1
                        innerOk = false
                    }
                }

                (c == '（' || c == '(') && innerOk -> {
                    val close = if (c == '（') '）' else ')'
                    val end = text.indexOf(close, i + 1)
                    val body = if (end < 0) "" else text.substring(i + 1, end).trim()
                    if (end > 0 && body.isNotEmpty() && !OOC.containsMatchIn(body)) {
                        flush()
                        // 内心独白也归当前说话人：`苏晚：（他今天……）「我不冷。」`
                        // 这两句必须同色，否则读者会以为是两个人在想
                        out += DlgPart.Inner(body, cur ?: default)
                        i = end + 1
                        innerOk = false
                    } else { buf.append(c); i++ }
                }

                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return out
    }
}