package app.offlineresearch.rag

/** Builds the answerer's prompt. This is where the answer contract lives. */
object PromptBuilder {
    /** The exact reply when the sources do not answer the question. */
    const val NOT_COVERED = "Not covered by the offline sources."

    private val CONTRACT = """
        You are an offline research assistant. You have no internet access. Answer the question using the numbered sources below.

        Rules:
        - State a fact only if a source supports it, and cite the source number in square brackets right after the fact, like [1] or [2][3].
        - You may add your own general reasoning to connect or explain the facts, but do not present anything as a fact unless a source supports it.
        - Cite only the source numbers given. Never invent a source.
        - A source may be an excerpt; "…" marks where text was left out.
        - If the sources do not contain the information needed to answer, reply with exactly: $NOT_COVERED
        - If the sources answer only part of the question, answer that part with citations and say which part is not covered.
        - Be clear and concise.
    """.trimIndent()

    /** [suffix] carries model-specific switches from the profile, such as "/no_think". */
    fun system(suffix: String = ""): String = if (suffix.isBlank()) CONTRACT else "$CONTRACT\n\n${suffix.trim()}"

    fun user(question: String, sources: List<Passage>): String = buildString {
        append("Sources:\n")
        sources.forEachIndexed { index, passage ->
            append("\n[").append(index + 1).append("] ").append(passage.excerpt).append('\n')
        }
        append("\nQuestion: ").append(question.trim())
    }
}
