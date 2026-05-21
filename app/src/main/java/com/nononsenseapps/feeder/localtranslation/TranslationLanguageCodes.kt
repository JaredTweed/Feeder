package com.nononsenseapps.feeder.localtranslation

import java.util.Locale

fun normalizeLanguageCode(language: String): String {
    val normalized =
        language
            .trim()
            .lowercase(Locale.ROOT)
            .replace('_', '-')
            .substringBefore('-')

    return when (normalized) {
        "english", "en" -> "en"
        "german", "de" -> "de"
        "french", "fr" -> "fr"
        "spanish", "es" -> "es"
        "portuguese", "pt" -> "pt"
        "italian", "it" -> "it"
        "dutch", "nl" -> "nl"
        "polish", "pl" -> "pl"
        "russian", "ru" -> "ru"
        "czech", "cs" -> "cs"
        "estonian", "et" -> "et"
        "bulgarian", "bg" -> "bg"
        "icelandic", "is" -> "is"
        "norwegian", "nb", "nn" -> "nb"
        "persian", "fa" -> "fa"
        "ukrainian", "uk" -> "uk"
        else -> normalized
    }
}
