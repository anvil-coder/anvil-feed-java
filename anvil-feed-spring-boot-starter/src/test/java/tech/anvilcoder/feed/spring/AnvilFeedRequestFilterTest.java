package tech.anvilcoder.feed.spring;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import shop.CheckoutService;
import tech.anvilcoder.feed.AnvilFeedClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.map;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.onlyEvent;

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

    /** What the servlet layer really does: it rewraps what the handler threw. */
    private static MockFilterChain chainRewrapping(Throwable original) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) throws ServletException {
                throw new ServletException("Request processing failed: " + original, original);
            }
        };
    }

    @Test
    void runsBeforeEveryOtherFilter() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                AnvilFeedTestSupport.filter(AnvilFeedTestSupport.client()).getOrder());
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

        AnvilFeedTestSupport.filter(AnvilFeedTestSupport.client()).doFilter(
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
        AnvilFeedRequestFilter filter = AnvilFeedTestSupport.filter(AnvilFeedTestSupport.client());

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/feed/events"),
                new MockHttpServletResponse(), chainThrowing(new IllegalStateException("boom"))))
                .isInstanceOf(IllegalStateException.class);

        assertNull(AnvilFeedClient.currentRequestPath(),
                "the next request on this worker would otherwise inherit /api/feed/events");
    }

    // ------------------------------------------------------------------ the verdict

    /**
     * The dispatch rethrew, so nothing handled it: the PARKED original becomes the event, not the
     * {@code ServletException} the servlet layer rewrapped it in.
     */
    @Test
    void aParkedFaultTheDispatchCouldNotResolveBecomesTheEvent() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bookings/4711");
        RuntimeException original = CheckoutService.charge();
        AnvilFeedTestSupport.resolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, original);

        assertThatThrownBy(() -> AnvilFeedTestSupport.filter(client).doFilter(request,
                new MockHttpServletResponse(), chainRewrapping(original)))
                .isInstanceOf(ServletException.class);

        assertEquals("java.lang.IllegalStateException", map(onlyEvent(client).get("exception")).get("type"));
    }

    /** The dispatch returned with a 402: a deliberate answer, not an incident. */
    @Test
    void aParkedFaultAnsweredBelow500IsDropped() throws Exception {
        assertEquals(0, throughAHandlerAnswering(402), "a handled exception is a deliberate answer");
    }

    /**
     * The dispatch returned with a 500: whoever rendered it, the client saw a server failure. This is the
     * {@code @ControllerAdvice}-to-{@code ProblemDetail} idiom, and it is the one fault a repair agent
     * most wants.
     */
    @Test
    void aParkedFaultAnsweredWithA500IsAnIncident() throws Exception {
        assertEquals(1, throughAHandlerAnswering(500));
    }

    /** {@code sendError} commits the response inside the dispatch; the status is still the authority. */
    @Test
    void aCommittedErrorResponseIsReadTheSameWay() throws Exception {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bookings/4711");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AnvilFeedTestSupport.resolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, CheckoutService.charge());

        AnvilFeedTestSupport.filter(client).doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest inner,
                                 jakarta.servlet.ServletResponse outer) throws java.io.IOException {
                ((MockHttpServletResponse) outer).sendError(503);
            }
        });

        assertEquals(1, client.queue().size());
        assertEquals(503, map(AnvilFeedTestSupport.onlyEvent(client).get("request")).get("status_code"));
    }

    /** One dispatch through a handler that answers {@code status}; the number of events it produced. */
    private static int throughAHandlerAnswering(int status) throws Exception {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pay");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AnvilFeedTestSupport.resolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, CheckoutService.charge());

        response.setStatus(status);
        AnvilFeedTestSupport.filter(client).doFilter(request, response, new MockFilterChain());

        return client.queue().size();
    }

    /** The faults nobody sees: an exception from another filter never reaches the dispatcher servlet. */
    @Test
    void anExceptionFromAnotherFilterBecomesAnEventAndIsRethrown() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        IllegalStateException failure = new IllegalStateException("the security filter broke");

        assertThatThrownBy(() -> AnvilFeedTestSupport.filter(client).doFilter(
                new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                chainThrowing(failure)))
                .isSameAs(failure);

        assertThat(map(onlyEvent(client).get("request"))).containsEntry("method", "GET");
    }

    /** The same gate as the resolver's: a filter that refuses a request with a 4xx is not an incident. */
    @Test
    void aClientErrorFromAnotherFilterIsNotAnIncident() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();

        assertThatThrownBy(() -> AnvilFeedTestSupport.filter(client).doFilter(
                new MockHttpServletRequest("GET", "/orders"), new MockHttpServletResponse(),
                chainThrowing(new ResponseStatusException(HttpStatus.FORBIDDEN, "no"))))
                .isInstanceOf(ResponseStatusException.class);

        assertEquals(0, client.queue().size());
    }

    /** An ingest under a context path: the full URI does not match the default pattern, the path does. */
    @Test
    void theIngestsOwnPathIsRecognisedUnderAContextPath() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop/api/feed/events");
        request.setContextPath("/shop");

        assertThatThrownBy(() -> AnvilFeedTestSupport.filter(client).doFilter(request,
                new MockHttpServletResponse(), chainThrowing(new IllegalStateException("the ingest broke"))))
                .isInstanceOf(IllegalStateException.class);

        assertEquals(0, client.queue().size());
    }

    /** Scopes nest, so a container's ERROR re-dispatch restores the outer path rather than clearing it. */
    @Test
    void anInnerScopeRestoresTheOuterPath() throws Exception {
        AnvilFeedClient.RequestScope outer = AnvilFeedClient.enterRequest("/outer");
        try {
            AnvilFeedTestSupport.filter(AnvilFeedTestSupport.client()).doFilter(
                    new MockHttpServletRequest("GET", "/inner"), new MockHttpServletResponse(),
                    new MockFilterChain());
            assertEquals("/outer", AnvilFeedClient.currentRequestPath());
        } finally {
            outer.close();
        }
    }

    /**
     * The container's ERROR re-dispatch of the same request. Spring Boot registers a plain {@code Filter}
     * bean for {@code REQUEST} only, so this does not happen in production — but the record is parked on
     * the REQUEST, so even a host that registers this filter for every dispatch type gets one event.
     */
    @Test
    void anErrorRedispatchOfTheSameRequestDoesNotProduceASecondEvent() throws Exception {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bookings/4711");
        RuntimeException original = CheckoutService.charge();
        AnvilFeedTestSupport.resolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, original);

        assertThatThrownBy(() -> AnvilFeedTestSupport.filter(client).doFilter(request,
                new MockHttpServletResponse(), chainRewrapping(original)))
                .isInstanceOf(ServletException.class);

        request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        AnvilFeedTestSupport.filter(client).doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain());

        assertEquals(1, client.queue().size(), "one fault is one event across the re-dispatch");
    }
}
