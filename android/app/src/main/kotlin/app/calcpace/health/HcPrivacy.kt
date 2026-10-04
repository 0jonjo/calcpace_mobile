package app.calcpace.health

import app.calcpace.auth.AppAuthPaths

/**
 * The privacy policy's Health Connect section, in the phone's language when
 * the site speaks it: what Health Connect's "privacy policy" link opens
 * (PermissionsRationaleActivity). The same page the Play listing links to.
 *
 * Kept free of Android types so the rules are unit tested on the JVM.
 */
object HcPrivacy {
    private const val SECTION = "privacy#health-connect"
    private val SITE_LOCALES = AppAuthPaths.LOCALES.split('|').toSet()
    private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")

    /** @param languageTag a BCP 47 tag, as Locale.toLanguageTag() gives it ("pt-BR", "zh-Hant-TW"). */
    fun url(baseUrl: String, languageTag: String?): String {
        val prefix = siteLocale(languageTag)?.let { "$it/" }.orEmpty()
        return "${baseUrl.trimEnd('/')}/$prefix$SECTION"
    }

    /** The site's locale prefix for [languageTag], or null for English and anything the site lacks. */
    fun siteLocale(languageTag: String?): String? {
        val parts = languageTag.orEmpty().replace('_', '-').split('-').map { it.lowercase() }
        val locale = when (val language = parts.first()) {
            "pt" -> "pt-BR"
            "zh" -> if ("hant" in parts || parts.drop(1).any { it in TRADITIONAL_CHINESE_REGIONS }) "zh-TW" else null
            "nb", "nn", "no" -> "no"
            "en" -> null
            else -> language
        }
        return locale?.takeIf { it in SITE_LOCALES }
    }
}
