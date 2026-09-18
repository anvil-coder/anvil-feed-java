package tech.anvilcoder.feed.spring;

import jakarta.servlet.http.HttpServletRequest;

import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.LoopGuard;

/**
 * {@code ignored_request_paths}, asked of BOTH spellings of the path a servlet request has.
 *
 * <p>The core applies the rule to whatever the capture hands it, which is the full URL
 * ({@code request.url}) — and {@code getRequestURL()} includes the CONTEXT PATH. An ingest deployed with
 * {@code server.servlet.context-path=/shop} therefore serves {@code /shop/api/feed/events}, which the
 * default pattern {@code /api/feed/**} does not match: the loop guard would be off for the one deployment
 * shape it exists for, silently, and every failure of the ingest would be reported to the ingest.
 *
 * <p>So the starter asks twice: once about the raw URI as the container gave it, and once about the same
 * URI with the context path removed. Either match is a match. A pattern an operator writes without the
 * context path — which is what everybody writes, and what the default is — therefore works under a
 * context path as well, and a pattern written WITH it still works. The default is unchanged.
 */
final class AnvilFeedIgnoredPaths {

    private final LoopGuard guard;

    AnvilFeedIgnoredPaths(AnvilFeedConfig config) {
        this.guard = new LoopGuard(config.ignoredRequestPaths());
    }

    /** Never throws: a path that cannot be read is not a reason to lose an event. */
    boolean ignores(HttpServletRequest request) {
        try {
            String uri = request.getRequestURI();
            if (uri == null) {
                return false;
            }
            if (guard.ignoresPath(uri)) {
                return true;
            }
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                    && uri.startsWith(contextPath)) {
                String within = uri.substring(contextPath.length());
                return guard.ignoresPath(within.isEmpty() ? "/" : within);
            }
            return false;
        } catch (RuntimeException | Error ours) {
            return false;
        }
    }
}
