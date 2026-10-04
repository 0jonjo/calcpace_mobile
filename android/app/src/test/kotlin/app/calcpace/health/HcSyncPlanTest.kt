package app.calcpace.health

import app.calcpace.health.HcSyncPlan.After
import org.junit.Assert.assertEquals
import org.junit.Test

class HcSyncPlanTest {
    @Test
    fun noChangesTokenMeansTheInitialRead() {
        assertEquals(HcSyncPlan.Step.Initial, HcSyncPlan.next(null))
        assertEquals(HcSyncPlan.Step.Changes("abc"), HcSyncPlan.next("abc"))
    }

    @Test
    fun whatEachAnswerMeansForTheChangesToken() {
        mapOf(
            200 to After.ADVANCE,
            401 to After.UNLINK,
            400 to After.ADVANCE, 409 to After.ADVANCE, 413 to After.ADVANCE, 422 to After.ADVANCE,
            429 to After.RETRY, 500 to After.RETRY, 502 to After.RETRY, 503 to After.RETRY,
            HcSyncPlan.NO_ANSWER to After.RETRY,
            201 to After.RETRY, 204 to After.RETRY, 301 to After.RETRY, 403 to After.RETRY, 404 to After.RETRY,
        ).forEach { (status, after) -> assertEquals("$status", after, HcSyncPlan.after(status)) }
    }

    @Test
    fun aSyncMovesOnOnlyWhenEveryRequestDid() {
        assertEquals(After.ADVANCE, HcSyncPlan.overall(emptyList()))
        assertEquals(After.ADVANCE, HcSyncPlan.overall(listOf(After.ADVANCE, After.ADVANCE)))
        assertEquals(After.RETRY, HcSyncPlan.overall(listOf(After.ADVANCE, After.RETRY)))
        assertEquals(After.UNLINK, HcSyncPlan.overall(listOf(After.UNLINK, After.RETRY)))
    }
}
