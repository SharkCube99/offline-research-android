package app.offlineresearch.rag

/** A piece of an answer: plain text, or a citation of one numbered source. */
sealed interface AnswerPart {
    data class Text(val text: String, val bold: Boolean = false) : AnswerPart

    /** [number] is 1-based and always refers to an existing source. */
    data class Citation(val number: Int) : AnswerPart
}

object Citations {
    // [1], [12], [1, 2], [1,2,3]. Anything else in brackets is ordinary text.
    private val MARKER = Regex("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]")

    // The Markdown that models write unasked: "* item" and "- item" lists,
    // "## Heading" lines and **bold**. Nothing else is interpreted.
    private val BULLET = Regex("(?m)^[ \\t]*[*\\-•][ \\t]+")
    private val HEADING = Regex("(?m)^[ \\t]*#{1,6}[ \\t]+(.+?)[ \\t]*$")
    private val TOKEN = Regex("\\*\\*|" + MARKER.pattern)

    /**
     * Splits an answer into text and citations. A number with no matching
     * source (the model invented it) is dropped rather than shown as a link
     * to nothing. List markers become bullets, and headings and **bold** become
     * bold text; a "**" still waiting for its partner while the answer streams
     * is treated as open, so asterisks never show.
     */
    fun parse(answer: String, sourceCount: Int): List<AnswerPart> {
        val text = HEADING.replace(BULLET.replace(answer, "• ")) { "**${it.groupValues[1].replace("**", "")}**" }
        val parts = mutableListOf<AnswerPart>()
        var position = 0
        var bold = false
        for (match in TOKEN.findAll(text)) {
            if (match.range.first > position) parts += AnswerPart.Text(text.substring(position, match.range.first), bold)
            if (match.value == "**") {
                bold = !bold
            } else {
                match.groupValues[1].split(',')
                    .map { it.trim().toInt() }
                    .filter { it in 1..sourceCount }
                    .forEach { parts += AnswerPart.Citation(it) }
            }
            position = match.range.last + 1
        }
        if (position < text.length) parts += AnswerPart.Text(text.substring(position), bold)
        return parts
    }

    /** Source numbers the answer cites, in order of first use. */
    fun cited(answer: String, sourceCount: Int): List<Int> =
        parse(answer, sourceCount).filterIsInstance<AnswerPart.Citation>().map { it.number }.distinct()

    /** Numbers the answer cites that match no source. */
    fun invalid(answer: String, sourceCount: Int): List<Int> =
        MARKER.findAll(answer)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim().toInt() }
            .filter { it !in 1..sourceCount }
            .distinct()
            .toList()

    private val NOT_COVERED_LINE = Regex(
        "(?im)^[ \\t>*_-]*" + Regex.escape(PromptBuilder.NOT_COVERED.trimEnd('.')) + "\\.?[ \\t*_]*$\\n?",
    )

    /**
     * Removes the fixed "not covered" sentence when it stands on a line of its
     * own next to an answer that cites sources: the model answered and then
     * added the refusal anyway, and the two contradict each other. An answer
     * without citations keeps the sentence, since it may be all that is true.
     */
    fun dropStrayNotCovered(answer: String): String {
        if (!NOT_COVERED_LINE.containsMatchIn(answer)) return answer
        val rest = NOT_COVERED_LINE.replace(answer, "")
        return if (MARKER.containsMatchIn(rest)) rest.trimEnd() else answer
    }

    // Models reword the line a little ("From my general knowledge (not from the offline sources):").
    private val UNSOURCED_LINE = Regex("(?i)general knowledge[^\\n]{0,12}not from the (?:offline )?sources")

    /** True when part of the answer is the model's own knowledge, with no source behind it. */
    fun hasUnsourced(answer: String): Boolean = UNSOURCED_LINE.containsMatchIn(answer)

    /** True when the answer says the sources do not cover the question. */
    fun isNotCovered(answer: String): Boolean =
        dropStrayNotCovered(answer).contains(PromptBuilder.NOT_COVERED.trimEnd('.'), ignoreCase = true)
}
