package tech.anvilcoder.feed.spring;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * One fault that MIGHT become an incident, parked on the request until the dispatch has said whether it
 * was handled.
 *
 * <p>The verdict cannot be reached where the fault is first seen. {@link AnvilFeedExceptionResolver} runs
 * at {@code HIGHEST_PRECEDENCE}, which is the only place the ORIGINAL exception and its request context
 * are both available — but at that moment nobody knows yet whether an application {@code @ExceptionHandler}
 * is about to turn it into a deliberate answer. That is known one layer out, in
 * {@link AnvilFeedRequestFilter}: a dispatch that RESOLVED the exception returns normally, and a dispatch
 * that did not rethrows it. So the resolver records here and the filter decides.
 *
 * <p>It is a request attribute and not a thread-local: it dies with the request, it cannot leak onto a
 * pooled worker, and an ASYNC or ERROR re-dispatch of the same request sees the same record.
 */
final class AnvilFeedCandidate {

    static final String ATTRIBUTE = AnvilFeedCandidate.class.getName();

    /** The exception as it was raised — not the {@code ServletException} the servlet layer rewraps it in. */
    private final Throwable error;

    /** Built where the request was still intact, and used unchanged wherever the verdict is reached. */
    private final Map<String, Object> context;

    private boolean reported;

    private AnvilFeedCandidate(Throwable error, Map<String, Object> context) {
        this.error = error;
        this.context = context;
    }

    static AnvilFeedCandidate park(HttpServletRequest request, Throwable error,
                                   Map<String, Object> context) {
        AnvilFeedCandidate candidate = new AnvilFeedCandidate(error, context);
        try {
            request.setAttribute(ATTRIBUTE, candidate);
        } catch (RuntimeException | Error ours) {
            // A request implementation that refuses attributes still gets its event, from the filter's
            // own fallback path. FR-2 b.
        }
        return candidate;
    }

    static AnvilFeedCandidate on(HttpServletRequest request) {
        try {
            return request.getAttribute(ATTRIBUTE) instanceof AnvilFeedCandidate parked ? parked : null;
        } catch (RuntimeException | Error ours) {
            return null;
        }
    }

    Throwable error() {
        return error;
    }

    Map<String, Object> context() {
        return context;
    }

    /** True the first time; the request is single-threaded at both call sites. */
    boolean claim() {
        if (reported) {
            return false;
        }
        reported = true;
        return true;
    }

    boolean reported() {
        return reported;
    }
}
