package com.onlinealarmkur.jetbrains

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

@NonNls
private const val BUNDLE = "messages.AlarmTimerBundle"

internal object AlarmTimerBundle {
    private val noDefaultFallback = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    // Parsing a pattern on every parameterized message is per-second work while a timer runs. The
    // bundles ship inside the plugin, so a resolved (locale, key) always yields the same pattern.
    private val formats = ConcurrentHashMap<Pair<Locale, String>, MessageFormat>()

    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): @Nls String = messageFor(locale(), key, *params)

    fun messageFor(
        requestedLocale: Locale,
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): @Nls String {
        val effectiveLocale = AlarmTimerLocale.resolve(requestedLocale)
        val value = unescapeMnemonicAmpersands(
            ResourceBundle.getBundle(BUNDLE, effectiveLocale, AlarmTimerBundle::class.java.classLoader, noDefaultFallback)
                .getString(key),
        )
        if (params.isEmpty()) return value
        val format = formats.computeIfAbsent(effectiveLocale to key) { MessageFormat(value, effectiveLocale) }
        // One cached instance is now shared, and MessageFormat.format is not thread-safe.
        return synchronized(format) { format.format(params) }
    }

    fun locale(): Locale = AlarmTimerLocale.resolve(DynamicBundle.getLocale())

    private fun unescapeMnemonicAmpersands(value: String): String = value.replace("\\&", "&")
}

internal object AlarmTimerLocale {
    fun resolve(locale: Locale): Locale = when (locale.language.lowercase(Locale.ROOT)) {
        "en" -> Locale.ENGLISH
        "de" -> Locale.GERMAN
        "es" -> Locale.forLanguageTag("es")
        "fr" -> Locale.FRENCH
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "ru" -> Locale.forLanguageTag("ru")
        "tr" -> Locale.forLanguageTag("tr")
        "pt" -> if (locale.country.equals("BR", ignoreCase = true)) Locale.forLanguageTag("pt-BR") else Locale.ENGLISH
        "zh" -> if (
            locale.country.equals("CN", ignoreCase = true) ||
            locale.country.equals("SG", ignoreCase = true) ||
            locale.script.equals("Hans", ignoreCase = true)
        ) {
            Locale.SIMPLIFIED_CHINESE
        } else {
            Locale.ENGLISH
        }
        else -> Locale.ENGLISH
    }
}
