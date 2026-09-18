package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-2 d, all three halves of it: the reentrancy flag, the ignored path and the per-fingerprint cap.
 *
 * <p>Nothing here sleeps. The gate's clock is injected, which is the only way a sixty-second window can be
 * asserted on in a unit suite without becoming the slowest test in the build.
 */
class LoopGuardTest {

    // ------------------------------------------------------------------ the path rule

    @Test
    void ignoresTheIngestsOwnPaths() {
        LoopGuard guard = new LoopGuard(List.of("/api/feed/**"));

        assertTrue(guard.ignoresPath("/api/feed/events"));
        assertTrue(guard.ignoresPath("/api/feed/events/batch"));
        assertTrue(guard.ignoresPath("/api/feed/events?x=1"));
        assertFalse(guard.ignoresPath("/api/feedback"));
        assertFalse(guard.ignoresPath("/api/bookings"));
    }

    /**
     * {@code /api/feed/**} has to cover {@code /api/feed} itself: that is the route the ingest's own
     * collection endpoint sits on, and it is the plain spelling an operator writes in config. Without it
     * the two spellings of one intention protect different sets of paths, and the one that misses is the
     * default.
     */
    @Test
    void aTrailingDoubleStarCoversTheDirectoryItself() {
        LoopGuard guard = new LoopGuard(List.of("/api/feed/**"));

        assertTrue(guard.ignoresPath("/api/feed"));
        assertTrue(guard.ignoresPath("/api/feed/"));
        assertFalse(guard.ignoresPath("/api/feedback"), "the boundary is the slash, not the prefix");
    }

    @Test
    void aSingleStarStopsAtASegmentBoundary() {
        LoopGuard guard = new LoopGuard(List.of("/internal/*"));

        assertTrue(guard.ignoresPath("/internal/health"));
        assertFalse(guard.ignoresPath("/internal/health/deep"));
    }

    /**
     * Every one of these shapes reaches a front controller through a rewrite rule or a permissive router.
     * A guard that answered "not ignored" to any of them would report the ingest's own failure to the
     * ingest — which is the loop this class exists to stop.
     */
    @Test
    void matchingIsCaseInsensitiveAndResolvesDotSegments() {
        LoopGuard guard = new LoopGuard(List.of("/api/feed/**"));

        assertTrue(guard.ignoresPath("/API/FEED/events"));
        assertTrue(guard.ignoresPath("/app/../api/feed/events"));
        assertTrue(guard.ignoresPath("//api/feed//events"));
        assertTrue(guard.ignoresPath("/api/feed/./events"));
        assertTrue(guard.ignoresPath("/api/feed/"));
        assertFalse(guard.ignoresPath("/api/feedback/create"));
    }

    @Test
    void aPatternWrittenInMixedCaseMatches() {
        assertTrue(new LoopGuard(List.of("/API/Feed/**")).ignoresPath("/api/feed/events"));
    }

    /**
     * A host that hands over a full URL must get the same answer as one that hands over the path: the
     * SDK's own {@code request.url} is an absolute URL by the time the contract has seen it.
     */
    @Test
    void aFullUrlIsAnsweredLikeThePath() {
        LoopGuard guard = new LoopGuard(List.of("/api/feed/**"));

        assertTrue(guard.ignoresPath("https://app.anvil-coder.tech/api/feed/events"));
        assertTrue(guard.ignoresPath("https://APP.EXAMPLE/API/Feed/events?token=x"));
        assertFalse(guard.ignoresPath("https://app.example/api/feedback"));
    }

    @Test
    void aBlankOrUnconfiguredPathIsNeverIgnored() {
        LoopGuard guard = new LoopGuard(List.of("/api/feed/**"));

        assertFalse(guard.ignoresPath(null));
        assertFalse(guard.ignoresPath(""));
        assertFalse(new LoopGuard(List.of()).ignoresPath("/api/feed/events"));
    }

    /** A host framework hands over whatever is in its property file, nulls and blanks included. */
    @Test
    void aListWithNullsAndBlanksInItDoesNotReachARegex() {
        LoopGuard guard = new LoopGuard(java.util.Arrays.asList(null, "  ", " /api/feed/** "));

        assertTrue(guard.ignoresPath("/api/feed/events"));
        assertFalse(guard.ignoresPath("/api/bookings"));
    }

    // ------------------------------------------------------------------ the reentrancy rule

    @Test
    void reentrancyIsShut() {
        LoopGuard guard = new LoopGuard(List.of());

        assertTrue(guard.enterSend());
        assertFalse(guard.enterSend(), "the sender never captures its own failures");
        guard.leaveSend();
        assertTrue(guard.enterSend());
    }

    /**
     * Per THREAD and not per process. The sender runs on its own daemon thread, and an application thread
     * failing at the same moment has nothing to do with it — a process-wide flag would silently drop the
     * customer's own errors for as long as a flush was in flight.
     */
    @Test
    void reentrancyIsPerThread() throws Exception {
        LoopGuard guard = new LoopGuard(List.of());
        assertTrue(guard.enterSend());

        AtomicBoolean otherThreadGotIn = new AtomicBoolean();
        Thread other = new Thread(() -> otherThreadGotIn.set(guard.enterSend()));
        other.start();
        other.join();

        assertTrue(otherThreadGotIn.get(), "another thread's capture is not the sender's own failure");
        assertTrue(guard.isSending());
    }

    // ------------------------------------------------------------------ the volume rule

    @Test
    void theRateGateCapsOneFingerprintPerWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(3, 60_000, now::get);

        assertTrue(gate.allow("f"));
        assertTrue(gate.allow("f"));
        assertTrue(gate.allow("f"));
        assertFalse(gate.allow("f"));
        assertTrue(gate.allow("other"), "a different fault is not suppressed");

        now.addAndGet(61_000L);
        assertTrue(gate.allow("f"), "the window moves on");
    }

    @Test
    void theSuppressedCountIsOneOnTheFirstDropAndClimbsFromThere() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(1, 60_000, now::get);

        assertEquals(0, gate.suppressed("f"), "a fingerprint nobody has seen has dropped nothing");
        assertTrue(gate.allow("f"));
        assertEquals(0, gate.suppressed("f"));
        assertFalse(gate.allow("f"));
        assertEquals(1, gate.suppressed("f"), "1 is the edge the client says the storm line on");
        assertFalse(gate.allow("f"));
        assertEquals(2, gate.suppressed("f"));
    }

    /**
     * The review's own probe, and the whole reason the eviction rule is what it is. The oldest entry is
     * exactly the one a storming fingerprint occupies — it was tracked from its first occurrence and its
     * window is refreshed in place — so a gate that evicted the oldest to make room would evict precisely
     * the fingerprint it was capping, and the storm would walk back through with a fresh count of 1.
     */
    @Test
    void aStormingFingerprintIsNotEvictedByAChurnOfDistinctOnes() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(3, 60_000, now::get);

        for (int i = 0; i < 3; i++) {
            assertTrue(gate.allow("HOT"));
        }
        assertFalse(gate.allow("HOT"), "capped before the churn");

        for (int i = 0; i < 2_000; i++) {
            gate.allow("cold-" + i);   // every one of them live, inside the same window
        }

        assertFalse(gate.allow("HOT"), "the cap survived the churn — nothing evicted a live entry");
    }

    /**
     * A full table with nothing expired does not let a newcomer through: it counts it against a shared
     * overflow bucket. Still capped, no longer counted apart. Letting it through because there is no room
     * to remember it would be an uncapped storm on the one code path that exists to cap storms.
     */
    @Test
    void aFingerprintTheTableHasNoRoomForIsStillCapped() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(3, 60_000, now::get);

        for (int i = 0; i < 600; i++) {
            gate.allow("live-" + i);   // the table fills and stays live
        }

        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (gate.allow("newcomer-" + i)) {
                allowed++;
            }
        }

        assertTrue(allowed <= 3, "the overflow bucket caps them together, but " + allowed + " got through");
        assertTrue(gate.suppressed("newcomer-0") > 0, "and the drops are countable, so they can be said");
    }

    @Test
    void anExpiredEntryIsTheOneThatMakesRoom() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(2, 60_000, now::get);

        for (int i = 0; i < 600; i++) {
            gate.allow("old-" + i);
        }
        assertTrue(gate.tracked() <= 500);

        now.addAndGet(61_000L);   // every tracked window has expired now
        assertTrue(gate.allow("fresh"));
        assertTrue(gate.allow("fresh"));
        assertFalse(gate.allow("fresh"), "the newcomer got a window of its own, so it can be capped");
    }

    @Test
    void theTableOfTrackedFingerprintsIsBounded() {
        AtomicLong now = new AtomicLong(1_000L);
        RateGate gate = new RateGate(10, 60_000, now::get);

        for (int i = 0; i < 5_000; i++) {
            gate.allow("distinct-" + i);
        }

        assertTrue(gate.tracked() <= 500, "tracked " + gate.tracked());
    }

    /**
     * A clock that stepped BACKWARDS — NTP, {@code date -s}, a host driving its own clock in a test —
     * would otherwise hold a window open for as long as the step was large, muting a fingerprint past its
     * window on a long-lived worker.
     */
    @Test
    void aClockThatStepsBackwardsDoesNotMuteAFingerprintForEver() {
        AtomicLong now = new AtomicLong(10_000_000L);
        RateGate gate = new RateGate(1, 60_000, now::get);

        assertTrue(gate.allow("f"));
        assertFalse(gate.allow("f"));

        now.set(1_000L);
        assertTrue(gate.allow("f"), "a backwards step opens a new window rather than freezing the old one");
    }

    /**
     * A cap of zero would drop everything and a window of zero would make every event a new window, i.e.
     * no cap at all. Both are a configuration mistake, and neither may turn error reporting off or leave
     * it uncapped without anybody noticing.
     */
    @Test
    void aZeroCapOrZeroWindowIsFlooredRatherThanObeyed() {
        AtomicLong now = new AtomicLong(1_000L);

        RateGate noCap = new RateGate(0, 60_000, now::get);
        assertTrue(noCap.allow("f"), "a cap of 0 does not switch error reporting off");
        assertFalse(noCap.allow("f"), "…and it does not leave it uncapped either");

        RateGate noWindow = new RateGate(1, 0, now::get);
        assertTrue(noWindow.allow("f"));
        assertFalse(noWindow.allow("f"), "a window of 0 is not 'every event is a new window'");
    }
}
