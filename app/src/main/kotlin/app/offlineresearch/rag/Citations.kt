package app.offlineresearch.rag

/** A piece of an answer: plain text, or a citation of one numbered source. */
sealed interface AnswerPart {
    data class Text(val text: String) : AnswerPart

    /** [number] is 1-based and always refers to an existing source. */
    data class Citation(val number: Int) : AnswerPart
}

object Citations {
    // [1], [12], [1, 2], [1,2,3]. Anything else in brackets is ordinary text.
    private val MARKER = Regex("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]")

    /**
     * Splits an answer into text and citations. A number with no matching
     * source (the model invented it) is dropped rather than shown as a link
     * to nothing.
     */
    fun parse(answer: String, sourceCount: Int): List<AnswerPart> {
        val parts = mutableListOf<AnswerPart>()
        var position = 0
        for (match in MARKER.findAll(answer)) {
            if (match.range.first > position) parts += AnswerPart.Text(answer.substring(position, match.range.first))
            match.groupValues[1].split(',')
                .map { it.trim().toInt() }
                .filter { it in 1..sourceCount }
                .forEach { parts += AnswerPart.Citation(it) }
            position = match.range.last + 1
        }
        if (position < answer.length) parts += AnswerPart.Text(answer.substring(position))
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

    /** True when the answer is the fixed "not covered" reply. */
    fun isNotCovered(answer: String): Boolean =
        answer.contains(PromptBuilder.NOT_COVERED.trimEnd('.'), ignoreCase = true)
}
