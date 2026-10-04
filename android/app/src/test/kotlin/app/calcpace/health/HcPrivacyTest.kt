package app.calcpace.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HcPrivacyTest {
    private val base = "https://calcpace.app"

    private fun url(tag: String?) = HcPrivacy.url(base, tag)

    @Test
    fun portugueseIsTheSitesBrazilianPortuguese() {
        listOf("pt-BR", "pt", "pt-PT", "pt_BR").forEach {
            assertEquals(it, "$base/pt-BR/privacy#health-connect", url(it))
        }
    }

    @Test
    fun traditionalChineseIsTheSitesTaiwanese() {
        listOf("zh-TW", "zh-Hant-TW", "zh-Hant", "zh-HK").forEach {
            assertEquals(it, "$base/zh-TW/privacy#health-connect", url(it))
        }
    }

    @Test
    fun simplifiedChineseFallsBackToEnglish() {
        listOf("zh", "zh-CN", "zh-Hans-CN").forEach { assertEquals(it, "$base/privacy#health-connect", url(it)) }
    }

    @Test
    fun everyNorwegianIsTheSitesNorwegian() {
        listOf("nb", "nn", "no", "nb-NO").forEach { assertEquals(it, "$base/no/privacy#health-connect", url(it)) }
    }

    @Test
    fun theOtherSiteLocalesGoByLanguage() {
        mapOf(
            "es-AR" to "es", "de-AT" to "de", "fr-CA" to "fr", "ja-JP" to "ja", "it" to "it", "nl-BE" to "nl",
            "ko-KR" to "ko", "sv-SE" to "sv", "pl-PL" to "pl", "hu-HU" to "hu", "cs-CZ" to "cs", "ru-RU" to "ru",
            "ES" to "es"
        ).forEach { (tag, locale) -> assertEquals(tag, "$base/$locale/privacy#health-connect", url(tag)) }
    }

    @Test
    fun englishAndAnythingElseHaveNoPrefix() {
        listOf("en", "en-US", "en-GB", "ar-EG", "tr", "und", "", null).forEach {
            assertEquals("$it", "$base/privacy#health-connect", url(it))
        }
    }

    @Test
    fun aTrailingSlashOnTheBaseIsFine() {
        assertEquals("$base/de/privacy#health-connect", HcPrivacy.url("$base/", "de-DE"))
        assertEquals("http://10.0.2.2:3001/privacy#health-connect", HcPrivacy.url("http://10.0.2.2:3001", "en"))
    }
}
