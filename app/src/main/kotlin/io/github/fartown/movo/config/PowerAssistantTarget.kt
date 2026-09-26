package io.github.fartown.movo.config

internal enum class PowerAssistantTarget(
    val persistedValue: String,
) {
    MOVO("movo"),
    GEMINI("gemini"),
    OEM("oem"),
    ;

    companion object {
        fun resolve(
            persistedValue: String?,
            legacyPowerKeyTakeover: Boolean,
        ): PowerAssistantTarget = entries.firstOrNull {
            it.persistedValue == persistedValue
        } ?: if (legacyPowerKeyTakeover) {
            GEMINI
        } else {
            OEM
        }
    }
}
