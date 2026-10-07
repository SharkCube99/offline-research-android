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

    // "The provided sources do not explicitly state ...", "since the sources do not specify this, I cannot ...".
    private val SOURCE_REMARK = Regex(
        "(?i)\\bsources? (?:do not|don't|does not|doesn't|did not|cannot|can't)\\b[^\\n]*?" +
            "\\b(?:state|specify|provide|contain|mention|cover|include|address|give|say|explain|discuss|list|confirm|answer|describe|detail|offer)\\b",
    )

    /**
     * Removes sentences that only remark on what the sources lack. The rules
     * tell the model not to write them; a large model writes them anyway, and
     * they tell the reader nothing. A sentence that also cites a source stays,
     * and so does the whole answer if nothing else would be left of it.
     * While the answer streams, only finished sentences are judged.
     */
    fun dropSourceRemarks(answer: String): String {
        if (!SOURCE_REMARK.containsMatchIn(answer)) return answer
        val kept = StringBuilder()
        var start = 0
        var removed = false
        for (end in sentenceEnds(answer)) {
            val sentence = answer.substring(start, end)
            val finished = sentence.trimEnd().lastOrNull().let { it == '.' || it == '!' || it == '?' }
            if (finished && SOURCE_REMARK.containsMatchIn(sentence) && !MARKER.containsMatchIn(sentence)) {
                removed = true
            } else {
                kept.append(sentence)
            }
            start = end
        }
        if (!removed) return answer
        val text = kept.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n\\n\\n+"), "\n\n").trim()
        // An answer made only of such remarks is a refusal in other words; leave it as written.
        return if (text.any { it.isLetterOrDigit() } && text.length >= 40) text else answer
    }

    /** Offsets just past each sentence: after ". ", "! ", "? " or a line break. The last one is the text's end. */
    private fun sentenceEnds(text: String): List<Int> {
        val ends = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val closes = char == '\n' ||
                ((char == '.' || char == '!' || char == '?') && (index + 1 == text.length || text[index + 1].isWhitespace()))
            if (closes) {
                // The space after the full stop belongs to the sentence it ends.
                var end = index + 1
                while (end < text.length && text[end] == ' ') end++
                ends += end
                index = end
            } else {
                index++
            }
        }
        if (ends.lastOrNull() != text.length) ends += text.length
        return ends
    }

    /**
     * Below the line that marks a part as unsourced, source numbers contradict
     * the line; they are removed there. Not when the line opens the answer:
     * then the model has mislabelled a sourced answer, and its citations are
     * the more useful half of the contradiction.
     */
    fun dropCitationsUnderUnsourced(answer: String): String {
        val label = UNSOURCED_LINE.find(answer) ?: return answer
        // The match begins inside the line ("From general knowledge ..."); what matters is the text above that line.
        val lineStart = answer.lastIndexOf('\n', label.range.first) + 1
        if (answer.substring(0, lineStart).none { it.isLetterOrDigit() }) return answer
        val below = answer.substring(label.range.last + 1)
        if (!MARKER.containsMatchIn(below)) return answer
        val cleaned = below.replace(Regex("[ \\t]*" + MARKER.pattern), "")
        return answer.substring(0, label.range.last + 1) + cleaned
    }

    /** Every clean-up of a model's answer, in order. */
    fun tidy(answer: String): String = dropCitationsUnderUnsourced(dropSourceRemarks(dropStrayNotCovered(answer)))

    /** True when the answer says the sources do not cover the question. */
    fun isNotCovered(answer: String): Boolean =
        dropStrayNotCovered(answer).contains(PromptBuilder.NOT_COVERED.trimEnd('.'), ignoreCase = true)
}
