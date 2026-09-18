package tech.anvilcoder.feed.spring;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Which faults this process has already turned into an event. FR-1 a: one fault, one event.
 *
 * <p>Spring hands the same fault to more than one of this starter's capture points on purpose — an
 * {@code @Async} call reaches both the task decorator and the async handler, a scheduled task reaches
 * both the error handler and, if it escapes, the uncaught handler. The servlet path no longer needs this
 * registry to stay at one event ({@link AnvilFeedCandidate} does that per request, which is why the
 * request path does not touch a shared structure at all any more), but the background paths do, and this
 * is their backstop.
 *
 * <h2>Both directions</h2>
 * A fault is not one OBJECT. The servlet layer rewraps what a handler threw into a new
 * {@code ServletException}, and Spring's scheduling and async layers unwrap in the other direction. So a
 * throwable is "already reported" when it, or anything in its cause chain, has been reported — and the
 * WHOLE chain is recorded, not just the head. Recording only the head made the walk one-directional:
 * wrapper first then cause would have produced two events, which is the doubled cockpit count this class
 * exists to prevent.
 *
 * <h2>Weak, bounded, striped</h2>
 * Keys are weak and compared by identity ({@link Throwable} overrides neither {@code equals} nor
 * {@code hashCode}), so an entry disappears with the throwable it is about: nothing to bound, nothing to
 * expire, nothing to leak. The walk is bounded at {@link #MAX_CAUSES} hops and stops on a self-referencing
 * chain.
 *
 * <p>The entries are spread over {@link #STRIPES} independently locked {@link WeakHashMap}s, chosen by the
 * throwable's identity hash, rather than living behind one process-wide monitor. One monitor was the
 * earlier shape and it serialised every capture in the process — including the walk, inside the lock. The
 * decision is still atomic where it has to be: the HEAD of the chain is added under its own stripe's lock
 * and only the thread whose {@code add} returns true reports, so two threads racing on the same throwable
 * still produce one event. The rest of the chain is recorded without that guarantee, which can at worst
 * cost a suppression that was never load-bearing.
 *
 * <p><b>A reused throwable instance is reported once, for the life of that instance.</b> An application
 * that caches a stackless sentinel or a {@code static final} marker exception and rethrows it gets one
 * event ever. That is the right trade for the doubled-count problem and it is written down here because
 * it cannot be discovered from the outside.
 */
final class AnvilFeedReported {

    /** A chain longer than this is one nobody reads, and an unbounded walk inside an error path is a hang. */
    private static final int MAX_CAUSES = 20;

    /** Power of two, so the index is a mask rather than a modulo. */
    private static final int STRIPES = 16;

    private static final Set<Throwable>[] SEEN = newStripes();

    private AnvilFeedReported() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<Throwable>[] newStripes() {
        // A generic array cannot be created directly; the raw type is confined to this one line and every
        // element is written through the typed reference below.
        Set<Throwable>[] stripes = (Set<Throwable>[]) new Set[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = Collections.newSetFromMap(new WeakHashMap<>());
        }
        return stripes;
    }

    private static Set<Throwable> stripeFor(Throwable error) {
        return SEEN[(System.identityHashCode(error) >>> 4) & (STRIPES - 1)];
    }

    /** True exactly once per fault. Never throws. */
    static boolean firstTime(Throwable error) {
        if (error == null) {
            return false;
        }
        try {
            for (Throwable at = error, next; at != null; at = next) {
                if (contains(at)) {
                    return false;
                }
                next = at.getCause();
                next = next == at ? null : next;
            }
            if (!add(error)) {
                return false;   // another thread claimed the same throwable while we were walking
            }
            // The rest of the chain, so that a later capture handed the CAUSE finds this report.
            Throwable at = error.getCause();
            for (int depth = 1; at != null && depth < MAX_CAUSES; depth++) {
                add(at);
                Throwable next = at.getCause();
                at = next == at ? null : next;
            }
            return true;
        } catch (RuntimeException | Error ours) {
            // A registry that fails must not suppress the report it exists to deduplicate.
            return true;
        }
    }

    private static boolean contains(Throwable error) {
        Set<Throwable> stripe = stripeFor(error);
        synchronized (stripe) {
            return stripe.contains(error);
        }
    }

    private static boolean add(Throwable error) {
        Set<Throwable> stripe = stripeFor(error);
        synchronized (stripe) {
            return stripe.add(error);
        }
    }

    /**
     * For tests, and for a host that wants the slate clean.
     *
     * <p>{@link AnvilFeedShutdown} calls this, and the registry is PROCESS-global: two live contexts in one
     * JVM (a parent/child hierarchy, two applications in one process) means closing one clears the other's
     * state for a moment. The cost of that is a duplicate event in a window measured in milliseconds; the
     * cost of not clearing is holding a closed context's throwables until they are collected anyway.
     */
    static void forget() {
        for (Set<Throwable> stripe : SEEN) {
            synchronized (stripe) {
                stripe.clear();
            }
        }
    }
}
