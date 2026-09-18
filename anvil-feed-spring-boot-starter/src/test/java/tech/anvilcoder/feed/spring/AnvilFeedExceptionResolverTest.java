package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.map;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.onlyEvent;

class AnvilFeedExceptionResolverTest {

    @AfterEach
    void clearTheDeduplicationRegistry() {
        AnvilFeedReported.forget();
    }

    @Test
    void runsBeforeEverythingElse() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                new AnvilFeedExceptionResolver(AnvilFeedTestSupport.client()).getOrder());
    }

    /**
     * The single most important assertion in the starter. A non-null answer here means the SDK handled the
     * exception, and the application's own error page, its {@code @ControllerAdvice} and its problem
     * details all stop running.
     */
    @Test
    void neverHandlesTheException() {
        assertNull(new AnvilFeedExceptionResolver(AnvilFeedTestSupport.client()).resolveException(
                new MockHttpServletRequest("POST", "/api/bookings"), new MockHttpServletResponse(),
                null, new RuntimeException("boom")));
    }

    @Test
    void capturesTheRequestWithItsQueryScrubbed() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reset");
        request.setQueryString("token=abc123&user=7");
        request.setServerName("app.example");

        new AnvilFeedExceptionResolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, new RuntimeException("boom"));

        Map<String, Object> block = map(onlyEvent(client).get("request"));
        assertEquals("POST", block.get("method"));
        assertThat(String.valueOf(block.get("url"))).endsWith("?token=[Filtered]&user=7");
    }

    /** The route PATTERN, so one incident is one route rather than one id. */
    @Test
    void theRoutePatternTravelsAsATag() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bookings/4711");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/bookings/{id}");

        new AnvilFeedExceptionResolver(client)
                .resolveException(request, new MockHttpServletResponse(), null, new RuntimeException("boom"));

        assertEquals("/bookings/{id}", map(onlyEvent(client).get("tags")).get("route"));
    }

    /**
     * FR-6 c: a field that cannot be determined is left out, never guessed. At the moment an exception
     * reaches a resolver the response is still 200 — the application's error handling has not run.
     */
    @Test
    void theStatusIsSentOnlyWhereItIsKnown() {
        AnvilFeedClient unknown = AnvilFeedTestSupport.client();
        new AnvilFeedExceptionResolver(unknown).resolveException(new MockHttpServletRequest("GET", "/x"),
                new MockHttpServletResponse(), null, new RuntimeException("still on its way to a 500"));
        assertThat(map(onlyEvent(unknown).get("request"))).doesNotContainKey("status_code");

        AnvilFeedClient carried = AnvilFeedTestSupport.client();
        new AnvilFeedExceptionResolver(carried).resolveException(new MockHttpServletRequest("GET", "/x"),
                new MockHttpServletResponse(), null,
                new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "taken"));
        assertEquals(409, map(onlyEvent(carried).get("request")).get("status_code"));
    }

    @Test
    void anExceptionRaisedServingTheIngestsOwnPathIsNotReported() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/feed/events");
        request.setServerName("app.example");

        new AnvilFeedExceptionResolver(client).resolveException(request, new MockHttpServletResponse(),
                null, new RuntimeException("the ingest itself failed"));

        assertEquals(0, client.queue().size(), "dark-factory runs this starter AND hosts the ingest");
    }

    /** FR-2 b: the SDK's own failure may not reach the application's error path. */
    @Test
    void aClientThatThrowsCostsTheEventAndNothingElse() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        client.beforeSend(payload -> {
            throw new IllegalStateException("a hook that throws");
        });

        assertNull(new AnvilFeedExceptionResolver(client).resolveException(
                new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), null,
                new RuntimeException("boom")));
    }
}
