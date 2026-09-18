package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;
import shop.CheckoutService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFactoryTest {

    private static EventFactory factory(String environment) {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .environment(environment)
                .build(FeedLogger.toSystemLogger());
        return new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> frames(Map<String, Object> payload) {
        return (List<Map<String, Object>>) map(payload.get("exception")).get("stacktrace");
    }

    @Test
    void aMessageEventStillCarriesAnException() {
        Map<String, Object> payload = CheckoutService
                .report(factory("production"), "Reconciliation skipped", Map.of()).payload();

        Map<String, Object> exception = map(payload.get("exception"));
        assertEquals(EventFactory.MESSAGE_TYPE, exception.get("type"));
        assertEquals("Reconciliation skipped", exception.get("message"));
        assertFalse(frames(payload).isEmpty());
    }

    @Test
    void keysAreInCanonicalOrder() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertEquals(List.of("schema_version", "event_id", "timestamp", "title", "level", "environment",
                        "service", "language", "sdk", "exception"),
                payload.keySet().stream().limit(10).toList());
    }

    @Test
    void eventIdIsACanonicalUuid() {
        FeedEvent event = factory("production").fromThrowable(CheckoutService.charge(), "error", Map.of());

        assertTrue(event.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "the ingest refuses anything but a canonical UUID: " + event.id());
        assertEquals(event.id(), event.payload().get("event_id"));
    }

    /** Spring's own profile names routinely look like this. The ingest would answer schema_violation. */
    @Test
    void anEnvironmentTheIngestWouldRefuseIsRepairedRatherThanSent() {
        Map<String, Object> payload = factory("Prod_EU")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertEquals("prod-eu", payload.get("environment"));
        assertTrue(AnvilFeedConfig.ENVIRONMENT.matcher((String) payload.get("environment")).matches());
    }

    @Test
    void releaseIsOmittedRatherThanGuessed() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertFalse(payload.containsKey("release"));
    }

    @Test
    void theApplicationsOwnFramesAreMarkedInApp() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        Map<String, Object> crashSite = frames(payload).get(0);
        assertEquals("charge", crashSite.get("function"));
        assertEquals(Boolean.TRUE, crashSite.get("in_app"), "shop.CheckoutService is the customer's code");
        assertThat(frames(payload)).anySatisfy(frame -> assertEquals(Boolean.FALSE, frame.get("in_app")));
    }

    /**
     * A synthesised stack that starts inside anvil-feed would put OUR frames at the head — and
     * {@code in_app} is what the server's fingerprint partitions on, so every captureMessage() of an
     * application would collapse into one incident.
     */
    @Test
    void aSynthesisedStackStartsAtTheCallerNotInsideTheSdk() {
        Map<String, Object> payload = CheckoutService
                .report(factory("production"), "Reconciliation skipped", Map.of()).payload();

        assertEquals("report", frames(payload).get(0).get("function"));
        assertEquals(Boolean.TRUE, frames(payload).get(0).get("in_app"));
    }

    @Test
    void theTestEventSaysWhatItIsAndIsSafeToClose() {
        Map<String, Object> payload = CheckoutService.verify(factory("production")).payload();

        Map<String, Object> exception = map(payload.get("exception"));
        assertEquals(EventFactory.TEST_TYPE, exception.get("type"));
        assertEquals(EventFactory.TEST_MESSAGE, exception.get("message"));
        assertEquals("Anvil Feed test event", payload.get("title"));
        assertEquals("error", payload.get("level"));
    }

    /** Task 11's release check has exactly one symbol to pin. */
    @Test
    void theSdkNameAndVersionHaveExactlyOneSource() {
        Map<String, Object> sdk = map(factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload().get("sdk"));

        assertEquals(EventFactory.SDK_NAME, sdk.get("name"));
        assertEquals(EventFactory.SDK_VERSION, sdk.get("version"));
        assertEquals("anvil-feed-java", sdk.get("name"));
    }

    @Test
    void aLevelTheWireDoesNotKnowBecomesError() {
        assertEquals("error", EventFactory.coerceLevel("info"));
        assertEquals("error", EventFactory.coerceLevel(null));
        assertEquals("warning", EventFactory.coerceLevel("warning"));
        assertEquals("error", factory("production")
                .fromThrowable(CheckoutService.charge(), "info", Map.of()).payload().get("level"));
    }

    /** A hook that writes a level the wire does not know must not reach the socket either. */
    @Test
    void theSecondPassOverAHooksOutputReappliesTheLevelEnum() {
        EventFactory factory = factory("production");
        Map<String, Object> payload =
                new LinkedHashMap<>(factory.fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());
        payload.put("level", "info");
        payload.put("tags", new LinkedHashMap<>(Map.of("api_key", "sk-live-99887766")));

        Map<String, Object> finalised = factory.finalise(payload);

        assertEquals("error", finalised.get("level"));
        assertEquals("[Filtered]", map(finalised.get("tags")).get("api_key"));
    }

    @Test
    void aBlankTitleFallsBackToTheExceptionTypeRatherThanCostingTheEvent() {
        Map<String, Object> payload =
                CheckoutService.report(factory("production"), "   ", Map.of()).payload();

        assertEquals(EventFactory.MESSAGE_TYPE, payload.get("title"));
    }

    /**
     * Every one of these blocks is typed {@code object} in schema 1.0; an empty one is refused, and the
     * event is then lost for a field the sender never needed to send.
     */
    @Test
    void blocksThatEndUpEmptyAreDroppedRatherThanSentAsAnEmptyMap() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of("url", "file:///etc/passwd")));
        context.put("tags", new LinkedHashMap<>(Map.of("user.ip_hash", "deadbeef")));

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload();

        assertFalse(payload.containsKey("request"), "a request whose only member was a file:// URL");
        assertFalse(payload.containsKey("tags"), "tags whose only member was the ingest's own pseudonym");
    }

    @Test
    void onlyTheLastThirtyBreadcrumbsAndTheFirstTwentyNineTagsSurvive() {
        List<String> crumbs = IntStream.range(0, 40).mapToObj(i -> "step-" + i).toList();
        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.range(0, 40).forEach(i -> tags.put("t" + i, "v" + i));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("breadcrumbs", crumbs);
        context.put("tags", tags);

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload();

        List<?> kept = (List<?>) payload.get("breadcrumbs");
        assertEquals(30, kept.size());
        assertEquals("step-10", kept.get(0), "the TAIL explains the fault; the server keeps the tail too");
        assertEquals("step-39", kept.get(29));
        // 29, not 30: AnvilFeedEventMapper.tags() takes the first slot for its own user.ip_hash.
        assertEquals(29, map(payload.get("tags")).size());
        assertTrue(map(payload.get("tags")).containsKey("t0"));
        assertFalse(map(payload.get("tags")).containsKey("t29"));
    }

    /**
     * The order is the server's own ({@code IncidentRedaction.redact}: redact, THEN truncate). Each value
     * below starts one character UNDER its cap and is built of short denylist assignments, so scrubbing —
     * the only step that may grow a value — carries it over the cap from the far side. Under the inverted
     * order every one of these ends up longer than the schema allows and the event is refused.
     */
    @Test
    void redactionGrowsTextAndEveryCapStillHolds() {
        String message = grows(1_999);
        String crumb = grows(999);
        String tagValue = grows(999);
        String url = "https://app.example/x?" + "token=y&".repeat(100);
        // The negative control: every input is UNDER its cap before the scrubber ever sees it, so the
        // only thing that can carry it over is redaction.
        assertThat(message.length()).isEqualTo(1_999);
        assertThat(crumb.length()).isEqualTo(999);
        assertThat(url.length()).isLessThan(1_000);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of("method", "POST", "url", url)));
        context.put("breadcrumbs", new ArrayList<>(List.of(crumb)));
        context.put("tags", new LinkedHashMap<>(Map.of("k", tagValue)));

        Map<String, Object> payload = factory("production")
                .fromThrowable(new IllegalStateException(message), "error", context).payload();

        assertEquals(2_000, ((String) map(payload.get("exception")).get("message")).length());
        assertEquals(1_000, ((String) map(payload.get("request")).get("url")).length());
        assertEquals(1_000, ((String) ((List<?>) payload.get("breadcrumbs")).get(0)).length());
        assertEquals(1_000, ((String) map(payload.get("tags")).get("k")).length());
        assertThat((String) map(payload.get("exception")).get("message"))
                .contains("[Filtered]").endsWith(" [truncated]");
        assertThat((String) map(payload.get("request")).get("url"))
                .startsWith("https://").contains("token=[Filtered]");
    }

    /** The title has its own cap and its own growth: a message event's title IS the message. */
    @Test
    void aTitleThatOnlyGrowsOverItsCapWhileBeingScrubbedIsStillBounded() {
        String message = grows(511);
        assertThat(message.length()).isEqualTo(511);

        Map<String, Object> payload = CheckoutService.report(factory("production"), message, Map.of()).payload();

        assertEquals(512, ((String) payload.get("title")).length());
        assertThat((String) payload.get("title")).contains("[Filtered]").endsWith(" [truncated]");
    }

    /** "token=y;" is eight characters and scrubs to seventeen. Nothing exotic makes a value grow. */
    private static String grows(int length) {
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            text.append("token=y;");
        }
        return text.substring(0, length);
    }
}
