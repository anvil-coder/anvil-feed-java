package tech.anvilcoder.feed.spring;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.io.IOException;

/**
 * Tells the SDK what this thread is serving, catches what the dispatcher servlet could not, and reaches
 * the verdict on what {@link AnvilFeedExceptionResolver} parked.
 *
 * <p><b>The scope (FR-2 d b).</b> {@link AnvilFeedClient#enterRequest(String)} is the only way the core
 * learns the path, and the loop guard needs it: an exception raised while serving {@code /api/feed/**} is
 * the ingest's own failure and reporting it would be the loop. This is not hypothetical here —
 * {@code dark-factory} runs this starter AND hosts the ingest, and there the two are the same process.
 * The scope is closed in a {@code finally} on the thread that opened it; a scope left open on a POOLED
 * worker would silently switch error reporting off for that thread for the life of the JVM.
 *
 * <p>It is registered at {@link Ordered#HIGHEST_PRECEDENCE} so that the scope is open for every other
 * filter too, and it is a {@code REQUEST}-dispatch filter (Spring Boot's default for a {@code Filter}
 * bean), so a container's ERROR re-dispatch to {@code /error} does not open a second scope around the
 * error page. One consequence is worth knowing: on an {@code ASYNC} dispatch the resolver runs on a
 * thread that has no open scope. Nothing is lost, because a capture whose own context carries a
 * {@code request.url} has the loop guard applied to THAT — but application code calling
 * {@code AnvilFeedClient.captureException} directly on such a thread has no scope to consult.
 *
 * <p><b>The verdict.</b> The resolver cannot know what a fault is about to become; this layer can,
 * because it runs after the whole dispatch. Two signals decide, and neither is a heuristic:
 *
 * <ul>
 *   <li>the chain <b>threw</b> — nothing resolved the fault, and it is an incident;</li>
 *   <li>the chain <b>returned</b> — something resolved it, and then <b>the status the client actually
 *       saw</b> decides. {@code >= 500} is a server failure and an incident, whoever rendered it; below
 *       that it is a deliberate answer and is not.</li>
 * </ul>
 *
 * <p>The status and not "who handled it" is the authority, and that is the whole correction of fix round
 * 2. The commonest global-error-handler idiom in Spring is a {@code @ControllerAdvice} that turns any
 * unexpected {@code RuntimeException} — a {@code NullPointerException}, say — into a 500
 * {@code ProblemDetail}. Under a "handled means not reported" rule that fault disappears silently, and it
 * is the one fault a repair agent most wants. Reading the final status also covers the cases no rule about
 * handlers could: a {@code response.sendError(500)} that committed the response inside the dispatch, and
 * a container's ERROR dispatch to {@code /error} where the error page sets the status.
 *
 * <p><b>The catch.</b> An exception thrown by another filter — a security filter, a rate limiter, a
 * request-logging decorator — never reaches the resolver, because it never reaches the dispatcher servlet.
 * Those are exactly the faults nobody sees, and they are unhandled by construction. The throwable is
 * captured and then <b>rethrown unchanged</b>: this filter observes, it does not handle.
 */
public final class AnvilFeedRequestFilter implements Filter, Ordered {

    /** The line between "the application answered" and "the application failed". */
    static final int SERVER_FAILURE = 500;

    private final AnvilFeedClient client;
    private final AnvilFeedIgnoredPaths ignoredPaths;

    public AnvilFeedRequestFilter(AnvilFeedClient client, AnvilFeedIgnoredPaths ignoredPaths) {
        this.client = client;
        this.ignoredPaths = ignoredPaths;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }

        // The raw, still-encoded request URI: the core's loop guard percent-decodes exactly once, so
        // decoding here as well would decode twice and turn a literal "%2500" into "%00".
        AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest(http.getRequestURI());
        try {
            chain.doFilter(request, response);
            settle(http, response);
        } catch (IOException | ServletException | RuntimeException | Error theirs) {
            capture(theirs, http, response);
            throw theirs;
        } finally {
            scope.close();
        }
    }

    /**
     * The dispatch finished without throwing, so something answered. The status the client saw decides,
     * and {@link #settle} is total — it is inside the try only so that the scope still closes, and it
     * cannot be the thing the catch below sees.
     */
    private void settle(HttpServletRequest request, ServletResponse response) {
        try {
            AnvilFeedCandidate parked = AnvilFeedCandidate.on(request);
            if (parked == null || parked.reported()) {
                return;
            }
            int status = response instanceof HttpServletResponse http ? http.getStatus() : 0;
            if (status < SERVER_FAILURE) {
                return;   // a deliberate answer, whoever produced it
            }
            if (ignoredPaths.ignores(request)) {
                return;
            }
            parked.observedStatus(status);
            if (parked.claim() && AnvilFeedReported.firstTime(parked.error())) {
                client.captureException(parked.error(), parked.context());
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b.
        }
    }

    private void capture(Throwable error, HttpServletRequest request, ServletResponse response) {
        try {
            if (ignoredPaths.ignores(request)) {
                return;
            }

            AnvilFeedCandidate parked = AnvilFeedCandidate.on(request);
            if (parked != null) {
                // The resolver saw the fault as it was raised, with its request context. What reached
                // here is the ServletException the servlet layer rewrapped it in, and an event about the
                // wrapper names the wrong type and carries the wrong frames.
                if (parked.claim() && AnvilFeedReported.firstTime(parked.error())) {
                    client.captureException(parked.error(), parked.context());
                }
                return;
            }

            Integer declared = AnvilFeedRequestContext.declaredStatusOf(error);
            if (declared != null && declared < 500) {
                return;   // the same client-error rule the resolver applies
            }
            if (!AnvilFeedReported.firstTime(error)) {
                return;
            }
            HttpServletResponse http = response instanceof HttpServletResponse typed ? typed : null;
            client.captureException(error, AnvilFeedRequestContext.of(request, http, error));
        } catch (RuntimeException | Error ours) {
            // FR-2 b. The host's exception is on its way out; ours may not join it.
        }
    }
}
