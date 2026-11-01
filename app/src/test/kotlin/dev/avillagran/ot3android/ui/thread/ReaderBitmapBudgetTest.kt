package dev.avillagran.ot3android.ui.thread

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderBitmapBudgetTest {
    @Test fun totalBudgetIncludesActiveImagesAndWaitersCancelWithoutLeaking() = runTest {
        val budget = ReaderBitmapBudget(10)
        val first = budget.acquire(7)
        val pending = async { budget.acquire(4) }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(7, budget.used)
        pending.cancel()
        first.close()
        first.close()
        assertEquals(0, budget.used)
        val all = budget.acquire(10)
        assertEquals(10, budget.used)
        all.close()
    }

    @Test fun disposingAnImageUnblocksAnotherWithinTheSameBudget() = runTest {
        val budget = ReaderBitmapBudget(10)
        val first = budget.acquire(8)
        val pending = async { budget.acquire(8) }
        runCurrent()
        first.close()
        val second = pending.await()
        assertEquals(8, budget.used)
        second.close()
        assertEquals(0, budget.used)
    }

    @Test fun visibleWaitersCannotBeStarvedBySmallerNewRequests() = runTest {
        val budget = ReaderBitmapBudget(10)
        val holder = budget.acquire(7)
        val large = async { budget.acquire(8) }
        runCurrent()
        val small = async { budget.acquire(2) }
        runCurrent()
        assertFalse(small.isCompleted)
        holder.close()
        val next = large.await()
        assertEquals(10, budget.used)
        next.close()
        small.await().close()
        assertEquals(0, budget.used)
    }

    @Test fun workerFreesLeaseAndPermitWhenItsCallerNeverResumes() = kotlinx.coroutines.runBlocking {
        val budget = ReaderBitmapBudget(10)
        // Nothing runs this scheduler after the attempt starts, like a destroyed host's dispatcher.
        val host = StandardTestDispatcher()
        val scope = CoroutineScope(host)
        repeat(3) { // More rounds than the two reader permits.
            val working = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val lease = budget.acquire(4)
            val attempt = scope.launch { readerImageWorker({ lease.close() }) { working.countDown(); finish.await() } }
            host.scheduler.runCurrent()
            assertTrue(working.await(5, TimeUnit.SECONDS))
            attempt.cancel()
            finish.countDown()
            withTimeout(5_000) { while (budget.used != 0) delay(5) }
        }
        assertEquals(1, withTimeout(5_000) { readerImageWorker({}) { 1 } })
    }

    @Test fun viewportExcludesOffscreenHoldersAndWaiters() {
        assertTrue(readerImageNearViewport(10f, 100f, 0f, 100f, 400, 800))
        assertFalse(readerImageNearViewport(900f, 1100f, 0f, 100f, 400, 800))
        assertFalse(readerImageNearViewport(1700f, 1900f, 0f, 100f, 400, 800))
        assertFalse(readerImageNearViewport(-1000f, -900f, 0f, 100f, 400, 800))
        assertFalse(readerImageNearViewport(10f, 100f, 900f, 1000f, 400, 800))
    }
}
