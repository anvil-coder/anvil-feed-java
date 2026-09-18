package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tech.anvilcoder.feed.AnvilFeedClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnvilFeedRequestFilterTest {

    @AfterEach
    void clearThreadState() {
        AnvilFeedClient.leaveRequest();
        AnvilFeedReported.forget();
    }

    private static MockFilterChain chainThrowing(RuntimeException failure) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) {
                throw failure;
            }
        };
    }

    @Test
    void runsBeforeEveryOtherFilter() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                new AnvilFeedRequestFilter(AnvilFeedTestSupport.client()).getOrder());
    }

    /** What the core reads, and the only thing that makes the loop guard work for a request. */
    @Test
    void theScopeIsOpenForTheWholeChain() throws Exception {
        String[] seen = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) {
                seen[0] = AnvilFeedClient.currentRequestPath();
            }
        };

        new AnvilFeedRequestFilter(AnvilFeedTestSupport.client()).doFilter(
                new MockHttpServletRequest("GET", "/bookings/4711"), new MockHttpServletResponse(), chain);

        assertEquals("/bookings/4711", seen[0]);
    }

    /**
     * A scope left open on a POOLED worker is error reporting silently switched off for that thread for
     * the life of the JVM: the next request on the same worker inherits the previous request's path, and
     * if that path was an ignored one every one of its exceptions is dropped without a line anywhere.
     */
    @Test
    void theScopeIsClosedEvenWhenTheChainThrows() {
        AnvilFeedRequestFilter filter = new AnvilFeedRequestFilter(AnvilFeedTestSupport.client());

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/feed/events"),
                new MockHttpServletResponse(), chainThrowing(new IllegalStateException("boom"))))
                .isInstanceOf(IllegalStateException.class);

        assertNull(AnvilFeedClient.currentRequestPath(),
                "the next request on this worker would otherwise inherit /api/feed/events");
    }

    /** The faults nobody sees: an exception from another filter never reaches the dispatcher servlet. */
    @Test
    void anExceptionFromAnotherFilterBecomesAnEventAndIsRethrown() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        IllegalStateException failure = new IllegalStateException("the security filter broke");

        assertThatThrownBy(() -> new AnvilFeedRequestFilter(client).doFilter(
                new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                chainThrowing(failure)))
                .isSameAs(failure);

        assertThat(AnvilFeedTestSupport.map(AnvilFeedTestSupport.onlyEvent(client).get("request")))
                .containsEntry("method", "GET");
    }

    /** FR-1 a: the resolver already made the event and the dispatcher rethrew the same throwable here. */
    @Test
    void anExceptionTheResolverAlreadyReportedIsNotReportedTwice() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        RuntimeException failure = new IllegalStateException("boom");
        new AnvilFeedExceptionResolver(client).resolveException(new MockHttpServletRequest("GET", "/orders"),
                new MockHttpServletResponse(), null, failure);

        assertThatThrownBy(() -> new AnvilFeedRequestFilter(client).doFilter(
                new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                chainThrowing(failure)))
                .isSameAs(failure);

        assertEquals(1, client.queue().size(), "one fault is one event");
    }

    /** Scopes nest, so a container's ERROR re-dispatch restores the outer path rather than clearing it. */
    @Test
    void anInnerScopeRestoresTheOuterPath() throws Exception {
        AnvilFeedClient.RequestScope outer = AnvilFeedClient.enterRequest("/outer");
        try {
            new AnvilFeedRequestFilter(AnvilFeedTestSupport.client()).doFilter(
                    new MockHttpServletRequest("GET", "/inner"), new MockHttpServletResponse(),
                    new MockFilterChain());
            assertEquals("/outer", AnvilFeedClient.currentRequestPath());
        } finally {
            outer.close();
        }
    }
}
