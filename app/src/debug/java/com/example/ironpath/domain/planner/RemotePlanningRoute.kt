package com.example.ironpath.domain.planner

/** Fixed debug-only routes. Unknown selections must never choose a paid default. */
enum class RemotePlanningRoute(val label: String, val model: String, val provider: String? = null) {
    GEMINI("Gemini · 3.5 Flash", "gemini-3.5-flash"),
    DEEPSEEK("DeepSeek · Flash", "deepseek-flash"),
    OPENROUTER_OPENAI("OpenRouter · GPT-4.1 mini · OpenAI", "openai/gpt-4.1-mini", "openai"),
    OPENROUTER_QWEN("OpenRouter · Qwen3.8 Flash · Alibaba", "qwen/qwen3.8-flash", "alibaba");

    val option: RemotePlanningOption
        get() = RemotePlanningOption(id = name, label = label)

    companion object {
        fun fromId(id: String): RemotePlanningRoute? = entries.firstOrNull { it.name == id }
    }
}
