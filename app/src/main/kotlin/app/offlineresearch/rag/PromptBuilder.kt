package app.offlineresearch.rag

/** Builds the answerer's prompt. This is where the answer contract lives. */
object PromptBuilder {
    /** The exact reply when the sources do not answer the question. */
    const val NOT_COVERED = "Not covered by the offline sources."

    private val CONTRACT = """
        You are an offline research assistant with no internet access. Answer the question from the numbered sources below.

        How to answer:
        - Give the most helpful answer the sources allow. Use the relevant details in them: names, numbers, addresses, steps.
        - Cite the source number in square brackets right after each fact, like [1] or [2][3]. Cite only the source numbers given.
        - If the question asks for the best, for recommendations or for places, and a source lists places, give the places on that list with their details and note that the list is not ranked.
        - You may add brief reasoning of your own to connect or explain the facts, but state as a fact only what a source supports.
        - If the sources cover only part of the question, answer that part and say which part is not covered.
        - Only if the sources hold nothing that bears on the question, reply with exactly: $NOT_COVERED
        - Use that sentence only as your whole reply. Never add it to an answer.
        - A source may be an excerpt; "…" marks where text was left out.
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
