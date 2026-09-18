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
 * Tells the SDK what this thread is serving, and catches what the dispatcher servlet could not.
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
 * bean), so a container's ERROR re-dispatch does not open a second scope around the error page.
 *
 * <p><b>The catch.</b> An exception thrown by another filter — a security filter, a rate limiter, a
 * request-logging decorator — never reaches {@link AnvilFeedExceptionResolver}, because it never reaches
 * the dispatcher servlet. Those are exactly the faults nobody sees. The throwable is captured and then
 * <b>rethrown unchanged</b>: this filter observes, it does not handle. {@link AnvilFeedReported} keeps the
 * ordinary case — where the resolver already made the event and the dispatcher rethrew the same throwable
 * through here — at exactly one event (FR-1 a).
 */
public final class AnvilFeedRequestFilter implements Filter, Ordered {

    private final AnvilFeedClient client;

    public AnvilFeedRequestFilter(AnvilFeedClient client) {
        this.client = client;
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
        } catch (IOException | ServletException | RuntimeException | Error theirs) {
            capture(theirs, http, response);
            throw theirs;
        } finally {
            scope.close();
        }
    }

    private void capture(Throwable error, HttpServletRequest request, ServletResponse response) {
        try {
            if (!AnvilFeedReported.firstTime(error)) {
                return;   // the resolver already made the event, and the dispatcher rethrew it through here
            }
            HttpServletResponse http = response instanceof HttpServletResponse typed ? typed : null;
            client.captureException(error, AnvilFeedRequestContext.of(request, http, error));
        } catch (RuntimeException | Error ours) {
            // FR-2 b. The host's exception is on its way out; ours may not join it.
        }
    }
}
