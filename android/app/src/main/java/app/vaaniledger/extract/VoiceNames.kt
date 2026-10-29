package app.vaaniledger.extract

/**
 * Who a spoken entry is about.
 *
 * A chat has a sender; a voice note does not, so "निरखर्भ ने ₹2000 के दो
 * जूते खरीदे" reached the confirm sheet with no customer. The speaker names
 * them the way people do: "X ने ...", "X ne ...", "X has bought ...",
 * "X ko ... diya". Only the voice path uses this; chats keep the sender.
 */
object VoiceNames {

    private val PATTERNS = listOf(
        Regex("""^\s*(\S+(?:\s+\S+)?)\s+ने\s"""),
        Regex("""^\s*(\S+)\s+को\s"""),
        Regex("""^\s*([A-Za-z]+)\s+(?:ne|ko)\s""", RegexOption.IGNORE_CASE),
        Regex("""^\s*([A-Za-z]+)\s+(?:has|had|have)?\s*(?:bought|took|taken|purchased|paid|gave)\b""", RegexOption.IGNORE_CASE),
    )

    /** Words that start a sentence but are never a customer. */
    private val NOT_NAMES = setOf("मैंने", "मैं", "हमने", "i", "we", "maine", "humne", "mujhe", "मुझे", "aaj", "आज")

    fun customerIn(text: String): String? {
        for (p in PATTERNS) {
            val name = p.find(text)?.groupValues?.get(1)?.trim() ?: continue
            if (name.lowercase() in NOT_NAMES) continue
            // "मुझे" inside the captured pair ("निरखर्भ मुझे") is not part of the name.
            return name.split(Regex("""\s+""")).first().replaceFirstChar { it.uppercase() }
        }
        return null
    }
}
