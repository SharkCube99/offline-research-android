package app.offlineresearch.rag

/** Builds the answerer's prompt. This is where the answer contract lives. */
object PromptBuilder {
    /** The exact reply when the question cannot be answered offline. */
    const val NOT_COVERED = "Not covered by the offline sources."

    /**
     * The line the model puts above what it says from its own knowledge. The
     * app looks for it to warn the reader that this part has no source.
     */
    const val UNSOURCED = "From general knowledge, not from the offline sources:"

    private const val OPENING =
        "You are an offline research assistant with no internet access. Answer the question from the numbered sources below."

    private val FROM_SOURCES = """
        - Give the most helpful answer the sources allow. Use the relevant details in them: names, numbers, addresses, steps.
        - Cite the source number in square brackets right after each fact, like [1] or [2][3]. Cite only the source numbers given.
        - If the question asks for the best, for recommendations or for places, and a source lists places, give the places on that list with their details and note that the list is not ranked.
        - Show each calculation as an equation with its numbers, like 235.2 / 7.5 = 31.36. The app checks the arithmetic.
    """.trimIndent()

    // The original contract: nothing is stated as fact without a source.
    private val SOURCES_ONLY = """
        - You may add brief reasoning of your own to connect or explain the facts, but state as a fact only what a source supports.
        - If the sources cover only part of the question, answer that part and say which part is not covered.
        - Only if the sources hold nothing that bears on the question, reply with exactly: $NOT_COVERED
        - Use that sentence only as your whole reply. Never add it to an answer.
    """.trimIndent()

    // The reader is offline and has nothing else to ask. A labelled answer from
    // the model's own knowledge serves them better than a refusal, as long as
    // they can see which part has no source behind it.
    private val OWN_KNOWLEDGE = """
        - Next to a source number, state as a fact only what that source supports.
        - If the sources do not answer the question, or answer only part of it, answer the rest from your own general knowledge. Start that part with a line that reads exactly: $UNSOURCED
        - Below that line cite no source numbers. Keep to what is widely known and does not change quickly, and say so when you are unsure.
        - Do not describe what the sources lack or apologise for them. Just answer.
        - Reply with exactly "$NOT_COVERED" only when neither the sources nor general knowledge can answer, for example when the answer depends on where the reader is right now and no location is given, or on today's prices, weather or opening hours.
        - Use that sentence only as your whole reply. Never add it to an answer.
    """.trimIndent()

    private const val EXCERPTS = "- A source may be an excerpt; \"…\" marks where text was left out."

    /**
     * [suffix] carries model-specific switches from the profile, such as "/no_think".
     * [ownKnowledge] lets the model answer what the sources leave out, under the [UNSOURCED] line.
     */
    fun system(suffix: String = "", ownKnowledge: Boolean = true): String {
        val rules = listOf(FROM_SOURCES, if (ownKnowledge) OWN_KNOWLEDGE else SOURCES_ONLY, EXCERPTS).joinToString("\n")
        val contract = "$OPENING\n\nHow to answer:\n$rules"
        return if (suffix.isBlank()) contract else "$contract\n\n${suffix.trim()}"
    }

    /** [location] is the reader's city from the phone's GPS, when the question depends on it. */
    fun user(question: String, sources: List<Passage>, location: String? = null): String = buildString {
        append("Sources:\n")
        if (sources.isEmpty()) append("\n(the search found none)\n")
        sources.forEachIndexed { index, passage ->
            append("\n[").append(index + 1).append("] ").append(passage.excerpt).append('\n')
        }
        if (location != null) append("\nThe reader's location, from the phone's GPS: ").append(location).append('\n')
        append("\nQuestion: ").append(question.trim())
    }
}
