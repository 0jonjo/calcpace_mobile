package app.calcpace.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HcStatusTest {
    // HealthConnectClient's values in connect-client 1.1.0; the table must not depend on them.
    private val unavailable = 1
    private val updateRequired = 2
    private val available = 3

    @Test
    fun theSdkStatusTable() {
        assertEquals(HcStatus.AVAILABLE, HcStatus.of(available, available, updateRequired))
        assertEquals(HcStatus.INSTALL_REQUIRED, HcStatus.of(updateRequired, available, updateRequired))
        assertEquals(HcStatus.UNAVAILABLE, HcStatus.of(unavailable, available, updateRequired))
        assertEquals(HcStatus.UNAVAILABLE, HcStatus.of(42, available, updateRequired))
    }

    @Test
    fun theConstantsAreOnlyCompared() {
        assertEquals(HcStatus.AVAILABLE, HcStatus.of(7, 7, 8))
        assertEquals(HcStatus.INSTALL_REQUIRED, HcStatus.of(8, 7, 8))
        assertEquals(HcStatus.UNAVAILABLE, HcStatus.of(3, 7, 8))
    }

    @Test
    fun theWireNamesAreTheContracts() {
        assertEquals(listOf("available", "install_required", "unavailable"),
            listOf(HcStatus.AVAILABLE, HcStatus.INSTALL_REQUIRED, HcStatus.UNAVAILABLE))
    }
}
