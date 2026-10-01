package app.offlineresearch.ui

private val closedThinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

/**
 * Removes Qwen3-style reasoning from text that is still streaming in. A closed
 * `<think>...</think>` block is dropped; an unclosed one hides everything after
 * it, so half-finished reasoning never flashes on screen.
 */
fun stripThinking(raw: String): String {
    val withoutClosed = closedThinkBlock.replace(raw, "")
    val open = withoutClosed.indexOf("<think>")
    val visible = if (open >= 0) withoutClosed.substring(0, open) else withoutClosed
    return visible.trimStart()
}
