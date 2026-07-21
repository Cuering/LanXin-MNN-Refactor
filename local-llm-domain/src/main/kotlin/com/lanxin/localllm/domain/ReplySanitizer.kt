package com.lanxin.localllm.domain

/**
 * 清洗本地模型输出：think 块、元分析、隐藏标签。
 * 在引擎出口统一调用，避免元提示泄漏到气泡/TTS。
 */
object ReplySanitizer {

    const val NO_THINK_INSTRUCTION: String =
        "【输出约束】你是陪伴角色，用第一人称直接对用户说话。" +
            "只输出面向用户的可见短正文（问候约 1～2 句，日常不超过 4 句）。" +
            "禁止输出思考过程、工具检查、分析/理由/Markdown 报告结构。" +
            "不要输出 <think>、</think>，不要输出 [[mood=…]] 等隐藏标签。" +
            "不要写「系统已明确角色设定与输出规范」等元话术。"

    private val CLOSED_THINK = Regex("""(?is)<think\b[^>]*>.*?</think\s*>""")
    private val ORPHAN_CLOSE = Regex("""(?is)</think\s*>""")
    private val HIDDEN_TAG = Regex("""\[\[.*?]]""")
    private val META_MARKERS = listOf(
        "查看可用工具", "greeting_tool", "系统已明确角色设定与输出规范",
        "让我分析", "检查工具可用性", "craft a warm", "Let me analyze",
        "可用工具", "不需要调用工具", "自然语言回复即可"
    )

    data class Cleaned(val displayText: String, val thinkingText: String? = null)

    fun clean(raw: String, showThinking: Boolean = false): Cleaned {
        if (raw.isBlank()) return Cleaned("")
        var thinking: String? = null
        val closed = CLOSED_THINK.findAll(raw).map { it.value }.toList()
        if (closed.isNotEmpty()) {
            thinking = closed.joinToString("\n") {
                it.replace(Regex("""(?is)</?think\b[^>]*>"""), "").trim()
            }.ifBlank { null }
        }
        var body = raw.replace(CLOSED_THINK, " ")
            .replace(ORPHAN_CLOSE, " ")
            .replace(HIDDEN_TAG, " ")
        body = body.lineSequence()
            .filter { line ->
                val t = line.trim()
                if (t.isEmpty()) return@filter true
                META_MARKERS.none { m -> t.contains(m, ignoreCase = true) }
            }
            .joinToString("\n")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return Cleaned(
            displayText = body,
            thinkingText = if (showThinking) thinking else null
        )
    }
}
