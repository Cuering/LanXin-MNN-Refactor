package com.lanxin.localllm.domain

/**
 * 本地/云端模型回复清洗：剥离 think 块、无标签元分析、`[[…]]` 隐藏标签。
 *
 * 用于引擎出口 / 气泡 / TTS / 陪伴路径，保证用户默认只见正文。
 * - [forDisplay]：气泡（可保留 emoji）
 * - [forSpeech]：TTS 播报（在 display 基础上再剥 emoji / 颜文字 / 装饰）
 *
 * 对齐原项目 LocalReplySanitizer + 妹居 TTS 前剥标签原则：
 * **文字生成可带内部标签，用户气泡与 TTS 永不暴露。**
 */
object ReplySanitizer {

    private const val THINK_OPEN = "<think>"

    /**
     * 默认 system 侧引导：短答、角色正文、不输出 think / 隐藏标签 / 元分析 / emoji。
     * 模型「不学标签」——输出约束明确禁止内部协议外泄。
     */
    const val NO_THINK_INSTRUCTION: String =
        "【输出约束·强制】你是陪伴角色「兰心/兰儿」，用第一人称直接对用户说话。" +
            "只输出面向用户的可见短正文（问候约 1～2 句，日常不超过 4 句）。" +
            "【禁止思考外泄】不要输出任何思考过程——无论是否带 <think> 标签。" +
            "禁止：分析/理由/判断/步骤推理、工具检查、Markdown 报告、编号拆解用户意图。" +
            "禁止无标签思考句式，例如「让我分析」「首先…其次…」「用户说的是…所以我…」" +
            "「我应该…」「检查工具」「回应建议」「没有 xxx_tool」等。" +
            "不要输出 <think>、</think>，不要输出 [[mood=…]]、[[listen]] 等双方括号隐藏标签。" +
            "不要写「系统已明确角色设定」等元话术。不要使用 emoji、表情符号或颜文字。" +
            "开场第一句就必须是对用户说的话，不要先写内部推理。"

    /** @deprecated 使用 [NO_THINK_INSTRUCTION] */
    const val NO_THINK_OR_TAGS_INSTRUCTION: String = NO_THINK_INSTRUCTION

    private val CLOSED_THINK_REGEX = Regex("""(?is)<think\b[^>]*>.*?</think\s*>""")
    private val ORPHAN_THINK_CLOSE = Regex("""(?is)</think\s*>""")
    private val ANY_BRACKET_TAG_REGEX = Regex("""\[\[[^\]]*]]""")
    private val UNCLOSED_BRACKET_TAG_REGEX = Regex("""\[\[[^\]]*$""")
    /** 妹居风格：尖括号内部协议标签，如 <好感变化:+1> */
    private val ANGLE_META_TAG_REGEX = Regex("""<[^>\n]{1,40}>""")
    private val KAOMOJI_REGEX = Regex(
        """[（(][^\n]{0,12}[（(）)][^\n]{0,8}[）)]|[>＞][_＿.．]{1,3}[<＜]|[TＴ][TＴ]|[><＞＜]{0,1}[oO0。·]{0,1}[wWω][oO0。·]{0,1}[><＞＜]{0,1}"""
    )
    private val DECOR_RUN_REGEX = Regex("""[★☆♪♫※＊✦✧✩✪✫✬✭✮✯✰]{1,}""")
    private val META_SECTION_HEADER = Regex(
        """(?im)^\s{0,3}(?:#{1,6}\s*)?(?:\*\*)?(?:回应建议|分析|理由|判断|工具可用性|检查工具|注意事项)(?:\*\*)?[：:\s]*$"""
    )
    private val META_LEAD_LINE = Regex(
        """(?im)^\s*(?:让我分析一下|让我思考|让我想想|我来分析|接下来分析|分析一下这个问题|""" +
            """查看可用工具|检查工具可用性|生成友好回应|首先[，,]?分析|逐步分析|""" +
            """用户意图是|用户说的是|所以我应该|我应该回复|注意[：:].*隐藏标签|""" +
            """只能用可见内容回复).*"""
    )
    private val META_LINE_MARKERS = listOf(
        "查看可用工具",
        "greeting_tool",
        "没有专门",
        "没有 greeting",
        "直接回复即可",
        "craft a warm",
        "Let me craft",
        "Let me analyze",
        "系统时间",
        "可用工具",
        "tool can",
        "不需要复杂的推理",
        "不需要调用工具",
        "自然语言回复即可",
        "系统已明确角色设定与输出规范",
        // 无标签思考常见泄漏
        "让我思考",
        "让我想想",
        "我来分析",
        "分析用户",
        "用户意图",
        "用户说的是",
        "所以我应该",
        "我应该回复",
        "我应该回应",
        "接下来我",
        "首先分析",
        "逐步分析",
        "推理过程",
        "思考过程",
        "内部推理",
        "chain of thought",
        "step by step"
    )
    private val NUMBERED_META = Regex("""^\d+[\.、]\s*(用户|检查|生成|注意|查看)""")
    private val BOLD_META_KEY = Regex("""^\*\*[^*]+\*\*[：:]?""")

    /**
     * @property displayText 气泡正文（无 mood/动作标签；默认无 think；可保留 emoji）
     * @property speechText TTS 播报（display 基础上再剥 emoji/装饰）
     * @property thinkingText 剥离的思考；[showThinking]=false 时为 null
     */
    data class Cleaned(
        val displayText: String,
        val speechText: String = displayText,
        val thinkingText: String? = null
    )

    /**
     * 从 [raw] 提取思考与正文。
     * - 始终剥 mood / 动作 `[[…]]`、尖括号内部标签、无标签元分析
     * - [showThinking]=false：丢弃 think；true：思考进 [Cleaned.thinkingText]
     * - [speechText] 始终剥 emoji
     */
    fun clean(raw: String, showThinking: Boolean = false): Cleaned {
        if (raw.isEmpty()) return Cleaned(displayText = "", speechText = "")
        val thinking = extractThinking(raw)?.trim()?.takeIf { it.isNotEmpty() }
        val withoutThink = stripThinkingBlocks(raw)
        val withoutMeta = stripMetaAnalysis(withoutThink)
        val display = stripHiddenTags(withoutMeta)
        val speech = stripEmojiAndDecorations(display)
        return if (showThinking) {
            Cleaned(displayText = display, speechText = speech, thinkingText = thinking)
        } else {
            Cleaned(displayText = display, speechText = speech, thinkingText = null)
        }
    }

    /** 气泡正文：mood/动作/think/元分析按规则剥；可保留 emoji。 */
    fun forDisplay(raw: String, showThinking: Boolean = false): String =
        clean(raw, showThinking = showThinking).displayText

    /**
     * TTS 播报正文：在 [forDisplay] 基础上再剥 emoji、颜文字与装饰。
     * 气泡用 [forDisplay]；播报必须走本方法，避免 TTS 念出标签/笑脸。
     */
    fun forSpeech(raw: String, showThinking: Boolean = false): String =
        clean(raw, showThinking = showThinking).speechText

    /** 已清洗展示正文再剥 emoji/装饰，供 TTS。 */
    fun stripEmojiAndDecorations(text: String): String {
        if (text.isEmpty()) return text
        val withoutEmoji = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                if (isEmojiOrDecorationCodePoint(cp)) append(' ')
                else appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        val withoutKaomoji = KAOMOJI_REGEX.replace(withoutEmoji, " ")
        val withoutDecor = DECOR_RUN_REGEX.replace(withoutKaomoji, " ")
        return collapseWhitespace(withoutDecor)
    }

    fun stripThinkingBlocks(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        if (result.contains("think", ignoreCase = true)) {
            result = CLOSED_THINK_REGEX.replace(result, " ")
            val openIdx = indexOfIgnoreCase(result, THINK_OPEN)
            if (openIdx >= 0) result = result.substring(0, openIdx)
        }
        result = ORPHAN_THINK_CLOSE.replace(result, " ")
        return collapseWhitespace(result)
    }

    /**
     * 剥离无标签「分析报告」式泄漏。优先抽取 `## 回应建议` 正文。
     */
    fun stripMetaAnalysis(text: String): String {
        if (text.isEmpty()) return text
        val suggested = extractSuggestedReply(text)
        if (suggested != null) return collapseWhitespace(suggested)

        val lines = text.replace("\r\n", "\n").split('\n')
        val kept = mutableListOf<String>()
        var dropRest = false
        for (line in lines) {
            val trimmed = line.trim()
            if (dropRest) continue
            if (trimmed.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept += ""
                continue
            }
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                if (kept.any { it.isNotBlank() }) dropRest = true
                continue
            }
            if (META_SECTION_HEADER.containsMatchIn(trimmed)) {
                if (trimmed.contains("分析") || trimmed.contains("理由") ||
                    trimmed.contains("判断") || trimmed.contains("工具")
                ) {
                    dropRest = true
                }
                continue
            }
            if (META_LEAD_LINE.containsMatchIn(trimmed)) continue
            if (META_LINE_MARKERS.any { trimmed.contains(it, ignoreCase = true) }) continue
            if (NUMBERED_META.containsMatchIn(trimmed)) continue
            if (BOLD_META_KEY.containsMatchIn(trimmed) &&
                (trimmed.contains("分析") || trimmed.contains("理由") || trimmed.contains("判断"))
            ) {
                dropRest = true
                continue
            }
            kept += line
        }
        val joined = collapseWhitespace(kept.joinToString("\n"))
        return dropLeadingBareThinking(joined)
    }

    /**
     * 无标签思考：开头连续「推理句」后才出现对用户说话。
     * 若前半像元分析、后半像对用户短答，只保留后半。
     */
    fun dropLeadingBareThinking(text: String): String {
        if (text.isEmpty()) return text
        val lines = text.replace("\r\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return collapseWhitespace(text)
        fun isBareThink(line: String): Boolean {
            if (META_LEAD_LINE.containsMatchIn(line)) return true
            if (META_LINE_MARKERS.any { line.contains(it, ignoreCase = true) }) return true
            if (NUMBERED_META.containsMatchIn(line)) return true
            if (line.startsWith("首先") || line.startsWith("其次") || line.startsWith("然后") ||
                line.startsWith("最后") || line.startsWith("综上")
            ) {
                if (line.contains("分析") || line.contains("用户") || line.contains("应该") ||
                    line.contains("工具") || line.contains("回复")
                ) return true
            }
            if (line.contains("我应该") || line.contains("所以我") || line.contains("用户说")) return true
            return false
        }
        fun looksLikeUserFacing(line: String): Boolean {
            if (isBareThink(line)) return false
            if (line.length > 120) return false
            // 对用户说话：含称呼/问候/情态，或不含明显元分析词
            val speechHints = listOf("哥哥", "你", "呀", "呢", "啦", "哦", "嗯", "好", "在", "想")
            return speechHints.any { line.contains(it) } &&
                !line.contains("分析") && !line.contains("工具") && !line.contains("意图")
        }
        var firstSpeech = -1
        for (i in lines.indices) {
            if (looksLikeUserFacing(lines[i]) && !isBareThink(lines[i])) {
                firstSpeech = i
                break
            }
        }
        if (firstSpeech <= 0) return collapseWhitespace(text)
        // 前面全是 bare think 才裁
        if ((0 until firstSpeech).all { isBareThink(lines[it]) || lines[it].length < 2 }) {
            return collapseWhitespace(lines.drop(firstSpeech).joinToString("\n"))
        }
        return collapseWhitespace(text)
    }

    fun extractSuggestedReply(text: String): String? {
        val m = Regex(
            """(?is)(?:^|\n)\s{0,3}#{1,6}\s*回应建议\s*\n+(.*?)(?=\n\s*---|\n\s*\*\*分析|\n\s*#{1,6}\s*分析|\z)"""
        ).find(text) ?: return null
        val body = m.groupValues[1]
            .lines()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                t.isNotEmpty() &&
                    t != "---" &&
                    !META_SECTION_HEADER.containsMatchIn(t) &&
                    !META_LEAD_LINE.containsMatchIn(t) &&
                    META_LINE_MARKERS.none { t.contains(it, ignoreCase = true) }
            }
            .joinToString("\n")
            .trim()
        return body.takeIf { it.isNotEmpty() && !looksLikePureMeta(it) }
    }

    fun extractThinking(text: String): String? {
        if (text.isEmpty() || !text.contains("think", ignoreCase = true)) return null
        val parts = mutableListOf<String>()
        CLOSED_THINK_REGEX.findAll(text).forEach { m ->
            val inner = m.value
                .replace(Regex("""(?is)^<think\b[^>]*>"""), "")
                .replace(Regex("""(?is)</think\s*>$"""), "")
                .trim()
            if (inner.isNotEmpty()) parts += inner
        }
        val afterClosed = CLOSED_THINK_REGEX.replace(text, "")
        val openIdx = indexOfIgnoreCase(afterClosed, THINK_OPEN)
        if (openIdx >= 0) {
            val fromOpen = afterClosed.substring(openIdx)
            val afterTag = fromOpen.indexOf('>').let { if (it >= 0) fromOpen.substring(it + 1) else "" }
            val tail = afterTag.trim()
            if (tail.isNotEmpty()) parts += tail
        }
        return parts.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * system prompt 按需追加「不输出 think/标签/元分析」约束。
     * [showThinking]=true 时不追加。
     */
    fun appendOutputConstraint(systemPrompt: String?, showThinking: Boolean): String? {
        if (showThinking) return systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        val base = systemPrompt?.trim().orEmpty()
        if (base.contains("【输出约束】") && base.contains("不要输出")) {
            return base.ifEmpty { null }
        }
        return if (base.isEmpty()) NO_THINK_INSTRUCTION
        else "$base\n\n$NO_THINK_INSTRUCTION"
    }

    /**
     * 剥离隐藏协议标签：
     * - `[[mood=…]]` / `[[listen]]` / 任意 `[[…]]`
     * - 妹居风格 `<好感变化:+1>` 等尖括号内部标签（保留 HTML-like 的长内容不误伤：限 40 字符）
     */
    fun stripHiddenTags(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        if (result.contains("[[")) {
            result = ANY_BRACKET_TAG_REGEX.replace(result, " ")
            if (result.contains("[[")) {
                result = UNCLOSED_BRACKET_TAG_REGEX.replace(result, " ")
                result = result.replace("[[", " ")
            }
        }
        // 仅剥短尖括号协议标签；避免误伤正常中文比较符号较少见，保守限长
        if (result.contains('<') && result.contains('>')) {
            result = ANGLE_META_TAG_REGEX.replace(result) { m ->
                val inner = m.value
                // 保留常见 think 已由上游处理；这里剥好感/信任/心情等内部协议
                when {
                    inner.contains("好感") || inner.contains("信任") ||
                        inner.contains("心情") || inner.contains("mood", ignoreCase = true) ||
                        inner.contains("变化") -> " "
                    else -> inner
                }
            }
        }
        return collapseWhitespace(result)
    }

    private fun isEmojiOrDecorationCodePoint(cp: Int): Boolean {
        if (cp == 0x200D || cp == 0x20E3 || cp == 0xFE0E || cp == 0xFE0F) return true
        if (cp in 0x1F3FB..0x1F3FF) return true
        if (cp in 0x2600..0x27BF) return true
        if (cp in 0x1F300..0x1F5FF) return true
        if (cp in 0x1F600..0x1F64F) return true
        if (cp in 0x1F680..0x1F6FF) return true
        if (cp in 0x1F700..0x1F77F) return true
        if (cp in 0x1F780..0x1F7FF) return true
        if (cp in 0x1F800..0x1F8FF) return true
        if (cp in 0x1F900..0x1F9FF) return true
        if (cp in 0x1FA00..0x1FAFF) return true
        if (cp in 0x1F1E0..0x1F1FF) return true
        if (cp in 0x2460..0x24FF) return true
        return false
    }

    private fun looksLikePureMeta(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (META_LEAD_LINE.containsMatchIn(t)) return true
        if (META_LINE_MARKERS.any { t.contains(it, ignoreCase = true) }) return true
        if (t.contains("分析：") || t.contains("**分析**")) return true
        return false
    }

    private fun collapseWhitespace(text: String): String =
        text
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex(""" *\n[ \t]*"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

    private fun indexOfIgnoreCase(haystack: String, needle: String): Int {
        return haystack.lowercase().indexOf(needle.lowercase())
    }
}
