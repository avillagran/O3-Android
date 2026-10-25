package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.shell.PendingRequest
import dev.avillagran.ot3android.core.shell.RowStatus
import dev.avillagran.ot3android.core.shell.ShellFixtures
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.shell.SidebarRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMappingTest {

    private val thread = ShellReducer.parse(ShellFixtures.body(1, emptyList(), listOf(ShellFixtures.thread("t")))).threads.single()

    private fun row(status: RowStatus, kind: String? = null, snoozed: Boolean = false, settled: Boolean = false) = SidebarRow(
        environmentId = "e",
        thread = thread.copy(pendingRuntimeRequest = kind?.let { PendingRequest("r", it, true) }),
        status = status,
        pinned = false,
        settled = settled,
        snoozed = snoozed,
    )

    @Test
    fun interimMappingCoversApprovalInputWorkingAndFailedOnly() {
        assertEquals(RowStatusDetail.Approval, StatusMapping.detail(row(RowStatus.NeedsYou, "approval")))
        assertEquals(RowStatusDetail.Input, StatusMapping.detail(row(RowStatus.NeedsYou, "user_input")))
        assertEquals("an unknown request kind asks for input", RowStatusDetail.Input, StatusMapping.detail(row(RowStatus.NeedsYou, "something_new")))
        assertEquals(RowStatusDetail.Working, StatusMapping.detail(row(RowStatus.Working)))
        assertEquals(RowStatusDetail.Failed, StatusMapping.detail(row(RowStatus.Failed)))
        assertEquals(RowStatusDetail.None, StatusMapping.detail(row(RowStatus.Quiet)))

        val produced = RowStatus.entries.flatMap { status -> listOf("approval", "user_input").map { StatusMapping.detail(row(status, it.takeIf { status == RowStatus.NeedsYou })) } }.toSet()
        val untilU2 = setOf(RowStatusDetail.PlanReady, RowStatusDetail.Waiting, RowStatusDetail.Goal, RowStatusDetail.Limited, RowStatusDetail.Woke, RowStatusDetail.Done)
        assertTrue("no Done, Plan, Woke or Limited until U2", produced.intersect(untilU2).isEmpty())
    }

    @Test
    fun theTenStatesFallIntoOt3sBuckets() {
        assertEquals(11, RowStatusDetail.entries.size)
        val expected = mapOf(
            RowStatusDetail.Approval to StatusBucket.Waiting,
            RowStatusDetail.Input to StatusBucket.Waiting,
            RowStatusDetail.PlanReady to StatusBucket.Waiting,
            RowStatusDetail.Waiting to StatusBucket.Waiting,
            RowStatusDetail.Goal to StatusBucket.Working,
            RowStatusDetail.Working to StatusBucket.Working,
            RowStatusDetail.Limited to StatusBucket.Failed,
            RowStatusDetail.Failed to StatusBucket.Failed,
            RowStatusDetail.Woke to StatusBucket.Woke,
            RowStatusDetail.Done to StatusBucket.Done,
            RowStatusDetail.None to StatusBucket.Idle,
        )
        expected.forEach { (detail, bucket) -> assertEquals(detail.name, bucket, StatusMapping.bucket(detail)) }
    }

    @Test
    fun pendingIsWaitingAndAwake() {
        assertTrue(StatusMapping.pending(row(RowStatus.NeedsYou, "approval")))
        assertFalse("a snoozed thread is not pending", StatusMapping.pending(row(RowStatus.NeedsYou, "approval", snoozed = true)))
        assertFalse(StatusMapping.pending(row(RowStatus.Working)))
        assertFalse(StatusMapping.pending(row(RowStatus.Failed)))
    }
}
