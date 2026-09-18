package tech.anvilcoder.feed.spring;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which throwables this process has already turned into an event. FR-1 a: one fault, one event.
 *
 * <p>Spring hands the same throwable to more than one of this starter's capture points on purpose. An
 * unhandled controller exception is seen by {@link AnvilFeedExceptionResolver} and then — because the
 * resolver deliberately does not handle it — travels back out through {@link AnvilFeedRequestFilter},
 * which is the only capture point a failure in another filter would ever reach. Without this registry the
 * ordinary case would produce two events with two ids and one fingerprint, and the cockpit would show a
 * doubled count for every failing request. The Laravel package solved the same problem the same way
 * ({@code AnvilFeed\Laravel\Reported}); this is its twin, with a {@link WeakHashMap} where PHP has a
 * {@code WeakMap}.
 *
 * <p>Weak keys and identity comparison: an entry disappears with the throwable it is about, so there is
 * nothing to bound, nothing to expire and nothing to leak. {@link Throwable} does not override
 * {@code equals}, so two distinct faults that happen to look alike are two entries — which is right, the
 * fingerprint is what groups them and it lives in the core.
 */
final class AnvilFeedReported {

    private static final Set<Throwable> SEEN =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private AnvilFeedReported() {
    }

    /**
     * How deep the cause chain is walked. A chain longer than this is a chain nobody reads anyway, and an
     * unbounded walk over a self-referencing chain is a hang inside an error handler.
     */
    private static final int MAX_CAUSES = 20;

    /**
     * True exactly once per fault. Never throws.
     *
     * <p>Per FAULT and not per throwable INSTANCE, which is the part that had to be learnt from a red
     * test: Spring's {@code FrameworkServlet.processRequest} catches what the handler threw and rethrows a
     * <b>new</b> {@code ServletException} wrapping it. The resolver therefore sees
     * {@code IllegalStateException} and the filter sees a {@code ServletException} that has never been
     * seen before — one fault, two objects, and with an identity check alone, two events with two ids and
     * one fingerprint for every failing request. So the CAUSE CHAIN is consulted: a throwable whose cause
     * has already become an event is the same fault wearing a wrapper.
     */
    static boolean firstTime(Throwable error) {
        if (error == null) {
            return false;
        }
        try {
            synchronized (SEEN) {
                Throwable at = error;
                for (int depth = 0; at != null && depth < MAX_CAUSES; depth++) {
                    if (SEEN.contains(at)) {
                        return false;
                    }
                    Throwable cause = at.getCause();
                    at = cause == at ? null : cause;   // a self-referencing chain is not walked twice
                }
                SEEN.add(error);
                return true;
            }
        } catch (RuntimeException | Error ours) {
            // A registry that fails must not suppress the report it exists to deduplicate.
            return true;
        }
    }

    /** For tests, and for a host that wants the slate clean. */
    static void forget() {
        SEEN.clear();
    }
}
