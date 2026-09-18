package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import shop.CheckoutService;
import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.map;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.onlyEvent;

/**
 * The gate: which of the four classes of fault becomes an incident, and which does not.
 *
 * <p>FR-1 says <i>unbehandelte</i> exceptions, and the Laravel twin gates on {@code shouldReport()} for
 * the same reason. Without the gate a public API files an incident for every {@code /favicon.ico}, every
 * scanner probe and every rejected form — and the rate gate then spends a real fault's budget on it.
 */
class AnvilFeedExceptionResolverTest {

    @AfterEach
    void clearTheDeduplicationRegistry() {
        AnvilFeedReported.forget();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServerName("app.example");
        return request;
    }

    private static AnvilFeedCandidate resolve(AnvilFeedClient client, MockHttpServletRequest request,
                                              Exception error) {
        assertNull(AnvilFeedTestSupport.resolver(client)
                        .resolveException(request, new MockHttpServletResponse(), null, error),
                "a non-null answer means the SDK handled the exception, and the application's own error "
                        + "page, its @ControllerAdvice and its problem details all stop running");
        return AnvilFeedCandidate.on(request);
    }

    @Test
    void runsBeforeEverythingElse() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                AnvilFeedTestSupport.resolver(AnvilFeedTestSupport.client()).getOrder());
    }

    // ---------------------------------------------------------------- the four classes

    /** Class 4: nothing resolved it. Parked here; the filter turns it into the event when the chain throws. */
    @Test
    void anExceptionWithNoDeclaredStatusIsParkedAndNotYetReported() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();

        AnvilFeedCandidate parked = resolve(client, request("GET", "/bookings/4711"),
                CheckoutService.charge());

        assertNotNull(parked, "the original exception and its context have to survive the rewrap");
        assertThat(parked.reported()).isFalse();
        assertEquals(0, client.queue().size(), "whether it was handled is not known yet");
    }

    /** Class 2: the exception declares a client error. Not an incident, and not even parked. */
    @Test
    void aDeclaredClientErrorIsNotAnIncident() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("GET", "/bookings/4711");

        assertNull(resolve(client, request, new ResponseStatusException(HttpStatus.NOT_FOUND, "gone")));
        assertEquals(0, client.queue().size());
    }

    /**
     * Spring's own {@code DefaultHandlerExceptionResolver} family all implement {@code ErrorResponse}, so
     * one question answers for a missing parameter, a wrong method, an unreadable body and — the one that
     * floods a public host — {@code NoResourceFoundException} for every {@code /favicon.ico}.
     */
    @Test
    void springsOwnClientErrorsAreNotIncidentsEither() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();

        assertNull(resolve(client, request("PUT", "/orders"),
                new org.springframework.web.HttpRequestMethodNotSupportedException("PUT")));
        assertNull(resolve(client, request("GET", "/nothing"),
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                        org.springframework.http.HttpMethod.GET, "/favicon.ico", "/favicon.ico")));

        assertEquals(0, client.queue().size());
    }

    /** Class 3: the exception declares a server failure. An incident whatever happens to it next. */
    @Test
    void aDeclaredServerFailureIsReportedImmediately() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("GET", "/upstream");

        AnvilFeedCandidate parked = resolve(client, request,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "the payment provider is down"));

        assertThat(parked.reported()).isTrue();
        Map<String, Object> payload = onlyEvent(client);
        assertEquals(502, map(payload.get("request")).get("status_code"));
    }

    // ---------------------------------------------------------------- the context

    @Test
    void capturesTheRequestWithItsQueryScrubbed() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("POST", "/reset");
        request.setQueryString("token=abc123&user=7");

        resolve(client, request, new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        Map<String, Object> block = map(onlyEvent(client).get("request"));
        assertEquals("POST", block.get("method"));
        assertThat(String.valueOf(block.get("url"))).endsWith("?token=[Filtered]&user=7");
    }

    /** The route PATTERN, so one incident is one route rather than one id. */
    @Test
    void theRoutePatternTravelsAsATag() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("GET", "/bookings/4711");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/bookings/{id}");

        resolve(client, request, new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        assertEquals("/bookings/{id}", map(onlyEvent(client).get("tags")).get("route"));
    }

    /**
     * FR-6 (c) refuses a field filled with a placeholder, and an empty tag value is the same species: it
     * is a filter an operator can select and never a fact.
     */
    @Test
    void aBlankRoutePatternIsNoTagAtAll() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("GET", "/bookings/4711");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "  ");

        resolve(client, request, new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom"));

        assertThat(onlyEvent(client)).doesNotContainKey("tags");
    }

    @Test
    void anExceptionRaisedServingTheIngestsOwnPathIsNotReported() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("POST", "/api/feed/events");

        assertNull(resolve(client, request,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "the ingest itself failed")),
                "not even parked: dark-factory runs this starter AND hosts the ingest");
        assertEquals(0, client.queue().size());
    }

    /** The same, for an ingest deployed under a context path — the shape the guard exists for. */
    @Test
    void theIngestsOwnPathIsRecognisedUnderAContextPath() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        MockHttpServletRequest request = request("POST", "/shop/api/feed/events");
        request.setContextPath("/shop");

        assertNull(resolve(client, request,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "the ingest itself failed")));
        assertEquals(0, client.queue().size());
    }

    /** FR-2 b: the SDK's own failure may not reach the application's error path. */
    @Test
    void aClientThatThrowsCostsTheEventAndNothingElse() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        client.beforeSend(payload -> {
            throw new IllegalStateException("a hook that throws");
        });

        assertNull(AnvilFeedTestSupport.resolver(client).resolveException(request("GET", "/x"),
                new MockHttpServletResponse(), null,
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "boom")));
    }
}
