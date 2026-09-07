package com.betterloka.async;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * One view's data: at most one request in flight, and an answer that no longer matches the view is
 * dropped rather than displayed.
 *
 * <p>A slot belongs to a single view — the Market's Search results and its Deals sweep hold one
 * each — so a late answer physically cannot be written into a view that did not ask for it. That was
 * the fault this replaces: three views shared one result field and one loading flag, so switching
 * tabs mid-search put the search's answer in the new tab and left the new tab's own load unstarted.
 *
 * <p>Every method here runs on the client thread, and so does {@code reply}. The only thing that
 * touches another thread is the work itself. That keeps the state single-threaded without a lock,
 * which matters because it is read from {@code render} on every frame.
 */
public final class AsyncSlot<T> {
    /** The work itself, run off the client thread. May throw; the slot turns that into an error. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    /** What a slot is doing, for a view that has to draw something for each case. */
    public enum State {
        /** Nothing asked for yet. */
        IDLE,
        LOADING,
        READY,
        FAILED
    }

    private final String name;
    private final Executor reply;

    /**
     * Bumped by anything that makes an in-flight answer irrelevant: a new request, a view change, a
     * closing screen. An answer carrying an older number is discarded.
     */
    private int generation;

    private Future<?> inFlight;
    private State state = State.IDLE;
    private T value;
    private Throwable error;
    private long startedNanos;
    private long tookMillis;

    /**
     * @param name  what this slot loads, for the timing log
     * @param reply runs an action on the client thread, and only while the view is still there —
     *              a screen passes one that checks it is still the open screen
     */
    public AsyncSlot(String name, Executor reply) {
        this.name = name;
        this.reply = reply;
    }

    /**
     * Runs {@code work}, cancelling whatever this slot had going.
     *
     * <p>The previous request is cancelled rather than merely ignored: an ignored request still
     * holds a worker thread and a permit from the shared budget, which is how one abandoned search
     * used to delay every other screen.
     */
    public void start(ExecutorService worker, Work<T> work) {
        cancel();
        int mine = ++generation;
        state = State.LOADING;
        error = null;
        startedNanos = System.nanoTime();
        inFlight = worker.submit(() -> {
            try {
                deliver(mine, work.run(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // A cancelled request has nothing to say; the generation check would drop it anyway.
            } catch (Exception | LinkageError e) {
                deliver(mine, null, e);
            }
        });
    }

    private void deliver(int mine, T result, Throwable failure) {
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        reply.execute(() -> {
            // Checked here rather than at the worker, because the view can change in between.
            if (mine != generation) {
                return;
            }
            inFlight = null;
            tookMillis = elapsedMillis;
            if (failure != null) {
                state = State.FAILED;
                error = failure;
            } else {
                state = State.READY;
                value = result;
            }
        });
    }

    /**
     * Abandons whatever is in flight and interrupts it if it has already started.
     *
     * <p>Leaves any value already loaded alone: switching away from a tab and back should not throw
     * away what it had.
     */
    public void cancel() {
        generation++;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        if (state == State.LOADING) {
            state = value != null ? State.READY : State.IDLE;
        }
    }

    /** Forgets the loaded value too, so the next look at this view fetches again. */
    public void reset() {
        cancel();
        value = null;
        error = null;
        state = State.IDLE;
    }

    public State state() {
        return state;
    }

    public boolean loading() {
        return state == State.LOADING;
    }

    public boolean failed() {
        return state == State.FAILED;
    }

    /** @return what loaded, or {@code null} if nothing has. */
    public T value() {
        return value;
    }

    public T valueOr(T fallback) {
        return value == null ? fallback : value;
    }

    public Throwable error() {
        return error;
    }

    /** How long the last completed request took, for the timing log. */
    public long tookMillis() {
        return tookMillis;
    }

    public String name() {
        return name;
    }
}
