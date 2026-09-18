package tech.anvilcoder.feed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded FIFO, safe to use from the host application's request threads and the sender's daemon thread
 * at once. FR-2 (d): it never exceeds {@code max_queue_size}. FR-3 (b): an overflow is visible, never
 * silent — one warning per drop event, carrying the running total, so a support engineer reading a log can
 * tell "the endpoint was down for five minutes" from "this process produces more errors than it can
 * report".
 *
 * <p>The OLDEST goes when it is full: the newest error is the one somebody is looking at right now.
 *
 * <p>The counterpart of {@code AnvilFeed\Queue} in the PHP SDK, method for method (C9). Nothing here
 * throws.
 */
public final class EventQueue {

    private final Deque<FeedEvent> events = new ArrayDeque<>();
    private final int maxSize;
    private final FeedLogger logger;
    private int dropped;

    /**
     * A bound of zero or less would make every push a drop plus a warning — a log flood on the one code
     * path that exists to prevent one — and a negative one would make {@link #droppedCount()}
     * over-report. {@link AnvilFeedConfig} clamps it too; this is the guard for a queue somebody
     * constructs directly.
     */
    public EventQueue(int maxSize, FeedLogger logger) {
        this.maxSize = Math.max(1, maxSize);
        this.logger = logger == null ? FeedLogger.toSystemLogger() : logger;
    }

    public synchronized void push(FeedEvent event) {
        if (event == null) {
            return;
        }
        events.addLast(event);
        trim();
    }

    /**
     * Puts a batch back at the FRONT, in its original order — the 429, the 503 and the unsent half of a
     * 413 split. Order matters: an incident's first occurrence is what the inbox shows as "first seen".
     */
    public synchronized void unshiftAll(List<FeedEvent> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (int i = batch.size() - 1; i >= 0; i--) {
            FeedEvent event = batch.get(i);
            if (event != null) {
                events.addFirst(event);
            }
        }
        trim();
    }

    public synchronized List<FeedEvent> take(int count) {
        int wanted = Math.min(Math.max(0, count), events.size());
        List<FeedEvent> taken = new ArrayList<>(wanted);
        for (int i = 0; i < wanted; i++) {
            taken.add(events.pollFirst());
        }
        return taken;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized int droppedCount() {
        return dropped;
    }

    public synchronized void clear() {
        events.clear();
    }

    /** The bound in force, after the clamp. */
    public int maxSize() {
        return maxSize;
    }

    private void trim() {
        int overflow = events.size() - maxSize;
        if (overflow <= 0) {
            return;
        }
        for (int i = 0; i < overflow; i++) {
            events.pollFirst();
        }
        dropped += overflow;
        logger.warning("anvil-feed: local queue full at " + maxSize + ", dropped " + overflow
                + " event(s) (" + dropped + " since start).");
    }
}
