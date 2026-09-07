package com.betterloka.async;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a view's data slot has to keep, written as the faults they replace.
 *
 * <p>The client thread is stood in for by a queue drained by hand, which is what the real one is:
 * every reply runs there, so draining it deliberately is both faithful and repeatable.
 */
class AsyncSlotTest {
    private ExecutorService worker;
    private final Deque<Runnable> clientThread = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        worker = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    /** Runs everything the workers have handed back, as a frame on the client thread would. */
    private void drainClientThread() throws InterruptedException {
        // Long enough for a worker to have replied; the tests all settle in milliseconds.
        Thread.sleep(120);
        while (!clientThread.isEmpty()) {
            clientThread.poll().run();
        }
    }

    private <T> AsyncSlot<T> slot(String name) {
        return new AsyncSlot<>(name, clientThread::add);
    }

    @Test
    void deliversWhatItLoaded() throws Exception {
        AsyncSlot<String> slot = slot("test");
        assertEquals(AsyncSlot.State.IDLE, slot.state());

        slot.start(worker, () -> "listings");
        assertTrue(slot.loading());

        drainClientThread();
        assertEquals(AsyncSlot.State.READY, slot.state());
        assertEquals("listings", slot.value());
    }

    /**
     * The Market fault: a search started, the view changed, and the search's answer was written into
     * the view that had never asked for it. Seventeen times in twenty in the recorded run.
     */
    @Test
    void dropsAnAnswerWhoseViewMovedOn() throws Exception {
        AsyncSlot<String> slot = slot("search");
        CountDownLatch release = new CountDownLatch(1);
        slot.start(worker, () -> {
            release.await();
            return "Diamond Sword";
        });

        // The player switches view while that is still out.
        slot.cancel();
        release.countDown();
        drainClientThread();

        assertNull(slot.value(), "an answer for a view that has gone must never be applied");
        assertEquals(AsyncSlot.State.IDLE, slot.state());
    }

    /** A superseded request must not be able to overwrite the one that replaced it. */
    @Test
    void keepsTheNewestOfTwoOverlappingRequests() throws Exception {
        AsyncSlot<String> slot = slot("search");
        CountDownLatch holdFirst = new CountDownLatch(1);

        slot.start(worker, () -> {
            holdFirst.await();
            return "stale";
        });
        slot.start(worker, () -> "fresh");

        drainClientThread();
        assertEquals("fresh", slot.value());

        holdFirst.countDown();
        drainClientThread();
        assertEquals("fresh", slot.value(), "the older request must not land on top of the newer");
    }

    /**
     * Cancelling has to actually stop the work, not merely ignore its answer: an ignored request
     * still holds a worker thread and a permit from the budget every screen shares, which is what
     * made one abandoned search slow every other tab.
     */
    @Test
    void cancellingInterruptsTheWorkItself() throws Exception {
        AsyncSlot<String> slot = slot("sweep");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        slot.start(worker, () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return "never";
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        slot.cancel();

        for (int i = 0; i < 100 && !interrupted.get(); i++) {
            Thread.sleep(20);
        }
        assertTrue(interrupted.get(), "a cancelled request must stop, not just be ignored");
    }

    /** A queued request that is cancelled before it starts should never run at all. */
    @Test
    void neverRunsWorkCancelledWhileStillQueued() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            AsyncSlot<String> blocker = slot("blocker");
            AsyncSlot<String> queued = slot("queued");
            CountDownLatch hold = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();

            blocker.start(single, () -> {
                hold.await();
                return "done";
            });
            queued.start(single, () -> {
                ran.incrementAndGet();
                return "should not happen";
            });

            queued.cancel();
            hold.countDown();
            Thread.sleep(200);

            assertEquals(0, ran.get(), "work cancelled before it started must not run");
        } finally {
            single.shutdownNow();
        }
    }

    /** A failure has to be visible, or the view sits on "loading" for an answer never coming. */
    @Test
    void reportsFailureRatherThanLoadingForever() throws Exception {
        AsyncSlot<String> slot = slot("search");
        slot.start(worker, () -> {
            throw new IllegalStateException("host unreachable");
        });

        drainClientThread();
        assertTrue(slot.failed());
        assertFalse(slot.loading(), "a failed view must not still look like it is loading");
        assertNotNull(slot.error());
    }

    /** One view's failure must leave the others alone. */
    @Test
    void oneViewFailingLeavesAnotherAlone() throws Exception {
        AsyncSlot<String> failing = slot("deals");
        AsyncSlot<String> healthy = slot("special");

        failing.start(worker, () -> {
            throw new IllegalStateException("no");
        });
        healthy.start(worker, () -> "fine");
        drainClientThread();

        assertTrue(failing.failed());
        assertEquals(AsyncSlot.State.READY, healthy.state());
        assertEquals("fine", healthy.value());
    }

    /** Switching away and back should not throw away what a view had already loaded. */
    @Test
    void keepsALoadedValueAcrossCancellation() throws Exception {
        AsyncSlot<String> slot = slot("deals");
        slot.start(worker, () -> "snapshot");
        drainClientThread();

        slot.cancel();
        assertEquals("snapshot", slot.value());
        assertEquals(AsyncSlot.State.READY, slot.state());
    }
}
