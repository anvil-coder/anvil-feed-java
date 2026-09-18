package tech.anvilcoder.feed;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * At most {@code cap} events per fingerprint per window (FR-2 d). A storm is deduplicated inside the
 * process, before it reaches the queue, the socket, or the ingest's 600-requests-per-minute budget.
 *
 * <p>The table is bounded too. An unbounded map keyed by fingerprint is a memory leak that only shows up
 * in the one situation this class exists for — an application producing thousands of DISTINCT errors —
 * and FR-2 d forbids unbounded growth without qualification.
 *
 * <p><b>What the bound may not cost.</b> Evicting the OLDEST entry to make room is the obvious rule and it
 * is wrong, because the oldest entry is exactly the one a storming fingerprint occupies: it was tracked
 * from its first occurrence and its window is refreshed in place, so under a churn of distinct newcomers
 * the gate would evict precisely the fingerprint it was capping and the storm would walk straight back
 * through with a fresh count of 1. Only an EXPIRED window is ever evicted — an expired window is capping
 * nothing, so dropping it costs nothing — and when the table is full and nothing has expired the newcomer
 * is counted against a shared overflow bucket instead: still capped, no longer counted apart. Letting it
 * through because there is no room to remember it would be an uncapped storm on the one code path that
 * exists to cap storms. (The PHP twin carries the same rule; task-3 review finding 2.)
 *
 * <p>Every method is {@code synchronized}: one gate serves every application thread.
 */
public final class RateGate {

    private static final int MAX_TRACKED = 500;

    private static final class Window {
        long start;
        int count;
        int suppressed;

        Window(long start) {
            this.start = start;
            this.count = 1;
        }
    }

    private final int cap;
    private final int windowMs;
    private final LongSupplier clockMillis;

    /**
     * Insertion order is age order, and {@link #allow} keeps it that way by REMOVING a key before it
     * re-inserts it — the invariant {@link #makeRoom} reads when it looks for the oldest expired entry.
     */
    private final Map<String, Window> windows = new LinkedHashMap<>(64);

    /** The shared window of the fingerprints there was no room to track apart. */
    private Window overflow;

    /** The monotonic default: this class only ever measures an ELAPSED interval. */
    public RateGate(int cap, int windowMs) {
        this(cap, windowMs, () -> System.nanoTime() / 1_000_000L);
    }

    /**
     * @param clockMillis milliseconds, the same shape {@link Sender} takes. Injected in tests and in a
     *                    host with its own clock, so that no test of a window ever has to sleep.
     */
    public RateGate(int cap, int windowMs, LongSupplier clockMillis) {
        // A cap of zero would drop everything and a window of zero would make every event a new window,
        // i.e. no cap at all. Both are a configuration mistake, and neither may turn error reporting off
        // or leave it uncapped without anybody noticing. AnvilFeedConfig clamps them to >= 0; this is the
        // floor for a gate somebody constructs directly, and for the difference between 0 and 1.
        this.cap = Math.max(1, cap);
        this.windowMs = Math.max(1, windowMs);
        this.clockMillis = clockMillis == null ? () -> System.nanoTime() / 1_000_000L : clockMillis;
    }

    /** @return false when this fingerprint is over the cap in its current window */
    public synchronized boolean allow(String fingerprint) {
        long now = clockMillis.getAsLong();
        Window window = windows.get(fingerprint);

        if (window != null && !hasExpired(window, now)) {
            if (window.count < cap) {
                window.count++;
                return true;
            }
            window.suppressed++;
            return false;
        }

        // A new window for this fingerprint. The key is removed first so that re-insertion puts it at the
        // END of the iteration order — which is what makes insertion order equal to age order.
        windows.remove(fingerprint);
        if (!makeRoom(now)) {
            return allowOverflow(now);
        }
        windows.put(fingerprint, new Window(now));
        return true;
    }

    /**
     * How many events the current window has dropped for that fingerprint — and, for one the table had no
     * room for, how many the overflow bucket it was counted against has dropped. Zero for a fingerprint
     * that has not been seen. The caller uses {@code 1} as the edge that says the line about the storm
     * once per window.
     */
    public synchronized int suppressed(String fingerprint) {
        Window window = windows.get(fingerprint);
        if (window != null) {
            return window.suppressed;
        }
        return overflow == null ? 0 : overflow.suppressed;
    }

    /** How many fingerprints are tracked apart. Package-private: a guard, not API. */
    synchronized int tracked() {
        return windows.size();
    }

    /** Room for one more, by dropping the oldest EXPIRED entry — never a live one. */
    private boolean makeRoom(long now) {
        if (windows.size() < MAX_TRACKED) {
            return true;
        }
        Iterator<Map.Entry<String, Window>> oldestFirst = windows.entrySet().iterator();
        while (oldestFirst.hasNext()) {
            if (hasExpired(oldestFirst.next().getValue(), now)) {
                oldestFirst.remove();
                return true;
            }
        }
        return false;   // every tracked fingerprint is live; the newcomer goes to the shared bucket
    }

    private boolean allowOverflow(long now) {
        if (overflow == null || hasExpired(overflow, now)) {
            overflow = new Window(now);
            return true;
        }
        if (overflow.count < cap) {
            overflow.count++;
            return true;
        }
        overflow.suppressed++;
        return false;
    }

    private boolean hasExpired(Window window, long now) {
        long elapsed = now - window.start;
        // A clock that stepped BACKWARDS (NTP, `date -s`, an injected clock in a host's own test) would
        // otherwise hold a window open for as long as the step was large, muting a fingerprint past its
        // window on a long-lived worker.
        return elapsed >= windowMs || elapsed < 0;
    }
}
