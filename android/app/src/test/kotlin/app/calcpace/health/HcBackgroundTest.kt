package app.calcpace.health

import dev.hotwire.core.bridge.KotlinXJsonConverter
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HcBackgroundTest {
    // The Json the app's bridge replies go through (CalcpaceApplication),
    // which leaves null properties out (explicitNulls = false).
    private val json = KotlinXJsonConverter().json

    @Test
    fun theTriState() {
        assertEquals(true, HcBackground.of(featureAvailable = true, granted = true))
        assertEquals(false, HcBackground.of(featureAvailable = true, granted = false))
        assertNull(HcBackground.of(featureAvailable = false, granted = false))
        // A permission left over from a phone that had the feature asks for nothing here.
        assertNull(HcBackground.of(featureAvailable = false, granted = true))
    }

    @Test
    fun aMissingFeatureIsSentAsAnExplicitNull() {
        assertEquals(
            """{"status":"available","linked":true,"background":null}""",
            json.encodeToString(HealthComponent.State("available", linked = true, background = null)),
        )
        assertEquals(
            """{"status":"unavailable","granted":false,"background":null}""",
            json.encodeToString(HealthComponent.Enabled("unavailable", granted = false, background = null)),
        )
        assertEquals(
            """{"status":"install_required","background":null}""",
            json.encodeToString(HealthComponent.Background("install_required", background = null)),
        )
    }

    @Test
    fun trueAndFalseAreSentAsBooleans() {
        assertEquals(
            """{"status":"available","background":false}""",
            json.encodeToString(HealthComponent.Background("available", background = false)),
        )
        assertEquals(
            """{"status":"available","background":true}""",
            json.encodeToString(HealthComponent.Background("available", background = true)),
        )
        assertEquals(
            """{"status":"available","linked":false,"background":true}""",
            json.encodeToString(HealthComponent.State("available", linked = false, background = true)),
        )
    }

    @Test
    fun theGrantIsStillLeftOutWhenThereIsNone() {
        val enabled = HealthComponent.Enabled("available", granted = true, background = false)
        assertEquals(
            """{"status":"available","granted":true,"background":false}""",
            json.encodeToString(enabled),
        )
        assertEquals(
            """{"status":"available","granted":true,"background":null,"grant":"g"}""",
            json.encodeToString(HealthComponent.Enabled("available", granted = true, background = null, grant = "g")),
        )
        // copy() (the confirmation's answer) keeps the background as it was.
        assertEquals(
            """{"status":"available","granted":false,"background":false}""",
            json.encodeToString(enabled.copy(granted = false, grant = null)),
        )
    }
}
