package app.offlineresearch.profiles

/** Which profile the user asked for. AUTO decides from the phone's RAM. */
enum class ProfileChoice {
    AUTO, LOW, HIGH;

    companion object {
        /** Lenient parse for values coming from settings or an adb intent; unknown text means AUTO. */
        fun parse(text: String?): ProfileChoice =
            entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: AUTO
    }
}

object ProfileSelector {
    /**
     * Phones sold as "12 GB" report about 11 to 11.6 GB of total memory and
     * "8 GB" phones about 7.3 to 7.6 GB, so 10 GB separates the two tiers.
     */
    const val HIGH_PROFILE_MIN_RAM_BYTES = 10_000_000_000L

    const val LOW_ASSET = "low.json"
    const val HIGH_ASSET = "high.json"

    /** The bundled profile file for [choice] on a phone with [totalRamBytes] of memory. */
    fun assetFor(choice: ProfileChoice, totalRamBytes: Long): String = when (choice) {
        ProfileChoice.LOW -> LOW_ASSET
        ProfileChoice.HIGH -> HIGH_ASSET
        ProfileChoice.AUTO -> if (totalRamBytes >= HIGH_PROFILE_MIN_RAM_BYTES) HIGH_ASSET else LOW_ASSET
    }
}
