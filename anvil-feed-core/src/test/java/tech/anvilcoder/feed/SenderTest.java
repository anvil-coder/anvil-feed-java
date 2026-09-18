package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision table of FR-4, driven through {@link RecordingTransport}. The socket properties — no
 * redirect, a total budget, the response fixtures over the wire — are in {@link HttpTransportTest} and
 * {@link ResponseConformanceTest}; they cannot be proved here and are not claimed here.
 *
 * <p>Every case is the counterpart of a reviewed PHP case (C9/C17), and the response bodies are the
 * conformance fixtures rather than transcriptions of them.
 */
class SenderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));

    private static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    private final RecordingLogger written = new RecordingLogger();

    private long now = 1_000_000L;

    void advance(long millis) {
        now += millis;
    }

    private AnvilFeedConfig config() {
        return AnvilFeedConfig.builder().token(TOKEN).service("shop-api").build(written.logger());
    }

    private AnvilFeedConfig config(java.util.function.UnaryOperator<AnvilFeedConfig.Builder> tweak) {
        return tweak.apply(AnvilFeedConfig.builder().token(TOKEN).service("shop-api"))
                .build(written.logger());
    }

    private static FeedEvent event(String id) {
        return new FeedEvent(id, Map.of("event_id", id, "service", "shop-api"));
    }

    private static String id(int index) {
        return String.format("11111111-1111-4111-8111-%012d", index);
    }

    private EventQueue queue() {
        return new EventQueue(1000, written.logger());
    }

    private Sender sender(EventQueue queue, Transport transport) {
        return new Sender(config(), queue, transport, written.logger(), () -> now);
    }

    private Sender sender(EventQueue queue, Transport transport, AnvilFeedConfig config) {
        return new Sender(config, queue, transport, written.logger(), () -> now);
    }

    /** The answer the ingest really gives: every id it stored, named. */
    private static String accepted(String... ids) {
        return "{\"accepted\":[\"" + String.join("\",\"", ids) + "\"],\"rejected\":[]}";
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(CONFORMANCE.resolve("responses").resolve(name));
    }

    // ------------------------------------------------------------------ shape

    @Test
    void aSingleEventGoesToTheObjectEndpointAsAnObject() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, accepted(id(1)), null));

        sender(queue, transport).flush();

        assertThat(transport.urls().get(0)).endsWith("/api/feed/events");
        assertThat(transport.bodies().get(0)).startsWith("{");
        assertThat(queue.size()).isZero();
    }

    @Test
    void twoEventsGoToTheBatchEndpointAsAnArray() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        queue.push(event(id(2)));
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flush();

        assertThat(transport.urls().get(0)).endsWith("/api/feed/events/batch");
        assertThat(transport.bodies().get(0)).startsWith("[");
    }

    @Test
    void noBatchEverExceedsTheIngestsFiftyEventCap() throws Exception {
        EventQueue queue = queue();
        for (int i = 0; i < 120; i++) {
            queue.push(event(id(i)));
        }
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flush();

        assertThat(transport.calls).hasSize(3);
        for (String body : transport.bodies()) {
            assertThat(JSON.readTree(body).size())
                    .describedAs("a batch above 50 is refused 400 and the WHOLE request is lost")
                    .isLessThanOrEqualTo(50);
        }
        assertThat(queue.size()).isZero();
    }

    // ------------------------------------------------------------------ 429

    @Test
    void a429PausesForExactlyRetryAfterAndKeepsTheEvents() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(429, "", 60));

        Sender sender = sender(queue, transport);
        sender.flush();
        assertThat(queue.size()).describedAs("a rate limit never costs an event").isEqualTo(1);
        assertThat(sender.pausedUntilMillis()).isEqualTo(now + 60_000L);

        advance(59_000L);
        sender.flush();
        assertThat(transport.calls)
                .describedAs("no further request inside the Retry-After window")
                .hasSize(1);

        advance(2_000L);
        sender.flush();
        assertThat(transport.calls)
                .describedAs("sending resumes once the window is over")
                .hasSize(2);
    }

    @Test
    void a429WithoutARetryAfterHeaderFallsBackToTheDocumentedSixtySeconds() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(429, "", null));

        Sender sender = sender(queue, transport);
        sender.flush();

        assertThat(sender.pausedUntilMillis())
                .describedAs("the ingest's own literal, never 0 — that would be a hot loop against a "
                        + "limiter")
                .isEqualTo(now + 60_000L);
    }

    // ------------------------------------------------------------------ 401 / 400 / 403

    @Test
    void a401StopsSendingForTheProcessLifetime() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(401, "", null));

        Sender sender = sender(queue, transport);
        sender.flush();
        queue.push(event(id(2)));
        sender.flush();

        // Behaviour first, the flag second: an implementation that sets the flag and keeps posting would
        // otherwise pass the part of this test anybody reads the name of.
        assertThat(transport.calls)
                .describedAs("nothing is sent again for the lifetime of the process")
                .hasSize(1);
        assertThat(queue.size())
                .describedAs("a stopped sender that keeps queueing is a memory leak with a nice name")
                .isZero();
        assertThat(sender.isStopped()).isTrue();
    }

    @Test
    void a400DropsTheBatchAndNeverRetries() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(400, fixture("400-schema-violation.json"), null));

        sender(queue, transport).flush();

        assertThat(queue.size()).isZero();
        assertThat(transport.calls).hasSize(1);
        assertThat(written.matching("schema_violation"))
                .describedAs("the reason code is the one field a reader can act on")
                .isNotEmpty();
    }

    @Test
    void a400KeepsTheSenderAliveAndIsSaidOncePerProcess() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        String body = fixture("400-schema-violation.json");
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(400, body, null), IngestResponse.of(400, body, null));

        Sender sender = sender(queue, transport);
        sender.flush();
        queue.push(event(id(2)));
        sender.flush();

        assertThat(transport.calls).describedAs("a 400 is not a reason to stop sending").hasSize(2);
        assertThat(sender.isStopped()).isFalse();
        assertThat(written.matching("answered 400"))
                .describedAs("one identical paragraph per batch is how a log stops being read")
                .hasSize(1);
    }

    @Test
    void a403DropsTheBatchSaysItOnceAndKeepsSending() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(403, "", null), IngestResponse.of(403, "", null));

        Sender sender = sender(queue, transport);
        sender.flush();
        queue.push(event(id(2)));
        sender.flush();

        assertThat(queue.size()).isZero();
        assertThat(sender.isStopped()).isFalse();
        assertThat(written.matching("Rotate the token")).hasSize(1);
    }

    @Test
    void onceMeansOncePerProcessEvenWithASecondLoggerInstance() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(403, "", null), IngestResponse.of(403, "", null));

        // Two FeedLogger objects over one sink — a Spring provider binding one per bean is exactly this.
        // FeedLogger.once() is per instance, so this is the wiring that would double every "said once".
        AnvilFeedConfig config = AnvilFeedConfig.builder().token(TOKEN).service("shop-api")
                .build(written.secondInstance());
        Sender sender = new Sender(config, queue, transport, written.secondInstance(), () -> now);
        sender.flush();
        queue.push(event(id(2)));
        sender.flush();

        assertThat(written.matching("answered 403"))
                .describedAs("the once-flag belongs to the Sender, not to whichever logger the host "
                        + "happened to inject")
                .hasSize(1);
    }

    // ------------------------------------------------------------------ 503 / 202

    @Test
    void a503RepeatsTheWholeBatchAfterRetryAfter() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        queue.push(event(id(2)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(503, fixture("503-transient.json"), 30));

        Sender sender = sender(queue, transport);
        sender.flush();
        assertThat(queue.size())
                .describedAs("the WHOLE batch is repeated; dedup on (organisation, event_id) makes it safe")
                .isEqualTo(2);
        assertThat(sender.pausedUntilMillis()).isEqualTo(now + 30_000L);

        advance(31_000L);
        sender.flush();
        assertThat(queue.size()).isZero();
    }

    @Test
    void partialSuccessClearsOnlyTheEventsThatGotAVerdict() throws Exception {
        EventQueue queue = queue();
        queue.push(event("f4b1a0c2-0f4a-4a1e-9d7a-2b6c5d8e1f30"));
        queue.push(event("9b1c7f2e-5a34-4c18-8e0d-1f7a2b3c4d5e"));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, fixture("202-partial.json"), null));

        sender(queue, transport).flush();

        assertThat(queue.size())
                .describedAs("accepted and rejected are both final verdicts")
                .isZero();
        assertThat(transport.calls).hasSize(1);
    }

    @Test
    void anOperatorSideRejectionIsSaidOnceRatherThanOncePerEvent() throws Exception {
        EventQueue queue = queue();
        queue.push(event("9b1c7f2e-5a34-4c18-8e0d-1f7a2b3c4d5e"));
        String body = fixture("202-binding-disabled.json");
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, body, null), IngestResponse.of(202, body, null));

        Sender sender = sender(queue, transport);
        sender.flush();
        queue.push(event("9b1c7f2e-5a34-4c18-8e0d-1f7a2b3c4d5e"));
        sender.flush();

        assertThat(written.matching("binding_disabled")).hasSize(1);
        assertThat(written.matching("cockpit"))
                .describedAs("the operator, not the developer, is the one who can fix it")
                .isNotEmpty();
    }

    @Test
    void anEventTheIngestNeverNamedIsSentOnceMoreAndThenDropped() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        // A proxy that rewrote the body, a truncated answer, an `accepted` list re-spelled: the status is
        // 202 and the event is in neither list.
        String empty = "{\"accepted\":[],\"rejected\":[]}";
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, empty, null),
                IngestResponse.of(202, empty, null),
                IngestResponse.of(202, empty, null));

        sender(queue, transport).flush();

        assertThat(transport.calls)
                .describedAs("exactly one more attempt — dedup makes the repeat safe")
                .hasSize(2);
        assertThat(queue.size())
                .describedAs("and then it goes, or the queue never empties again")
                .isZero();
        assertThat(written.matching("202 twice without naming it")).isNotEmpty();
        assertThat(written.matching(id(1)))
                .describedAs("the event that was lost is named; a count would not let anybody find it")
                .isNotEmpty();
    }

    @Test
    void anAnswerThatNamesOnlySomeOfTheBatchKeepsOnlyTheRest() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        queue.push(event(id(2)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, accepted(id(1)), null));

        sender(queue, transport).flush();

        assertThat(transport.calls).hasSize(2);
        assertThat(JSON.readTree(transport.bodies().get(1)).path("event_id").asText())
                .describedAs("only the event that got no verdict goes again; the accepted one is done")
                .isEqualTo(id(2));
        assertThat(queue.size()).isZero();
    }

    @Test
    void anUnreadable202DoesNotClearTheBatchOnTheStrengthOfTheStatusAlone() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(202, "<html>a proxy error page with a 202 status</html>", null));

        sender(queue, transport).flush();

        assertThat(transport.calls)
                .describedAs("the second attempt reaches the healthy default answer")
                .hasSize(2);
        assertThat(queue.size()).isZero();
    }

    @Test
    void theEventIdIsMatchedExactlyAsItWasSpelled() {
        EventQueue queue = queue();
        queue.push(event("F4B1A0C2-0F4A-4A1E-9D7A-2B6C5D8E1F30"));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(202,
                "{\"accepted\":[\"F4B1A0C2-0F4A-4A1E-9D7A-2B6C5D8E1F30\"],\"rejected\":[]}", null));

        sender(queue, transport).flush();

        assertThat(transport.bodies().get(0))
                .describedAs("the ingest echoes the sender's own spelling on purpose; an SDK that "
                        + "re-spells it can never match the answer against what it posted")
                .contains("F4B1A0C2-0F4A-4A1E-9D7A-2B6C5D8E1F30");
        assertThat(queue.size())
                .describedAs("an upper-case id must clear the buffer, not resend for ever")
                .isZero();
    }

    // ------------------------------------------------------------------ backoff

    @Test
    void aTimeoutBacksOffExponentiallyWithJitter() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.failure("connection refused"));

        Sender sender = sender(queue, transport);
        sender.flush();

        assertThat(queue.size()).isEqualTo(1);
        assertThat(sender.pausedUntilMillis()).isBetween(now + 800L, now + 1200L);
        assertThat(sender.isStopped()).isFalse();
    }

    @Test
    void theBackoffGrowsAndIsCapped() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        IngestResponse[] failures = new IngestResponse[12];
        java.util.Arrays.fill(failures, IngestResponse.failure("connection refused"));
        RecordingTransport transport = new RecordingTransport(failures);

        Sender sender = sender(queue, transport);
        List<Long> waits = new ArrayList<>();
        for (int attempt = 0; attempt < 12; attempt++) {
            sender.flush();
            waits.add(sender.pausedUntilMillis() - now);
            now = sender.pausedUntilMillis() + 1;
        }

        assertThat(waits.get(3)).describedAs("the wait grows with consecutive failures")
                .isGreaterThan(waits.get(0));
        assertThat(waits).describedAs("capped: 30 s plus at most 20 % jitter").allSatisfy(
                wait -> assertThat(wait).isLessThanOrEqualTo(36_000L));
        assertThat(queue.size()).describedAs("the event stays in the queue through every retry").isEqualTo(1);
    }

    @Test
    void aSuccessfulSendForgetsTheBackoff() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.failure("connection refused"),
                IngestResponse.failure("connection refused"),
                IngestResponse.failure("connection refused"),
                IngestResponse.of(202, accepted(id(1)), null),
                IngestResponse.failure("connection refused"));

        Sender sender = sender(queue, transport);
        for (int attempt = 0; attempt < 3; attempt++) {
            sender.flush();
            now = sender.pausedUntilMillis() + 1;
        }
        sender.flush();                                  // the 202
        queue.push(event(id(2)));
        sender.flush();                                  // fails again, but as a FIRST failure

        assertThat(sender.pausedUntilMillis())
                .describedAs("a delivery that worked resets the attempt counter; otherwise one bad minute "
                        + "costs 30 s for ever")
                .isBetween(now + 800L, now + 1200L);
    }

    @Test
    void aPauseResetsTheBackoffBecauseTheIngestAnsweredAtAll() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.failure("connection refused"),
                IngestResponse.failure("connection refused"),
                IngestResponse.failure("connection refused"),
                IngestResponse.of(429, "", 60),
                IngestResponse.failure("connection refused"));

        Sender sender = sender(queue, transport);
        for (int attempt = 0; attempt < 3; attempt++) {
            sender.flush();
            now = sender.pausedUntilMillis() + 1;
        }
        sender.flush();                                  // the 429
        now = sender.pausedUntilMillis() + 1;
        sender.flush();                                  // fails again — as a FIRST failure

        assertThat(sender.pausedUntilMillis())
                .describedAs("a 429 is a healthy answer from a reachable ingest; carrying a failure streak "
                        + "across it resumes at the escalated delay after a pause that proved the opposite")
                .isBetween(now + 800L, now + 1200L);
    }

    // ------------------------------------------------------------------ 413 and the body cap

    @Test
    void a413HalvesTheBatchAndSendsBothHalves() throws Exception {
        EventQueue queue = queue();
        for (int i = 0; i < 4; i++) {
            queue.push(event(id(i)));
        }
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(413, fixture("413-payload-too-large.json"), null),
                IngestResponse.of(202, accepted(id(0), id(1)), null),
                IngestResponse.of(202, accepted(id(2), id(3)), null));

        sender(queue, transport).flush();

        assertThat(transport.calls).describedAs("one refused batch of four, then two halves of two")
                .hasSize(3);
        assertThat(queue.size()).isZero();
        assertThat(JSON.readTree(transport.bodies().get(1)).size()).isEqualTo(2);
        assertThat(JSON.readTree(transport.bodies().get(2)).size()).isEqualTo(2);
        assertThat(written.matching("payload_too_large")).isNotEmpty();
    }

    @Test
    void theSecondHalfOfASplitIsNeverLostWhenTheFirstHalfPauses() throws Exception {
        EventQueue queue = queue();
        for (int i = 0; i < 4; i++) {
            queue.push(event(id(i)));
        }
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(413, fixture("413-payload-too-large.json"), null),
                IngestResponse.of(429, "", 60));

        sender(queue, transport).flush();

        assertThat(queue.size())
                .describedAs("the half that was never attempted must go back into the queue, not onto "
                        + "the floor")
                .isEqualTo(4);
        assertThat(queue.take(4).stream().map(FeedEvent::id).toList())
                .describedAs("and it must go back in order")
                .containsExactly(id(0), id(1), id(2), id(3));
    }

    @Test
    void aSingleEventTheIngestCallsTooLargeIsDroppedRatherThanRetriedForEver() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.of(413, fixture("413-payload-too-large.json"), null));

        sender(queue, transport).flush();

        assertThat(queue.size()).describedAs("there is nothing left to split").isZero();
        assertThat(transport.calls).hasSize(1);
        assertThat(written.matching(id(1))).isNotEmpty();
    }

    @Test
    void aBodyIsSplitBeforeItReachesTheIngestsSizeLimit() {
        EventQueue queue = queue();
        for (int i = 0; i < 6; i++) {
            queue.push(new FeedEvent(id(i), Map.of("event_id", id(i), "title", "x".repeat(50_000))));
        }
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flush();

        assertThat(transport.calls).describedAs("a 300 KB batch cannot go out as one body").hasSizeGreaterThan(1);
        for (String body : transport.bodies()) {
            assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .describedAs("the ingest answers 413 above 262144 bytes; the sender must not get "
                            + "there by itself")
                    .isLessThanOrEqualTo(262_144);
        }
        assertThat(queue.size()).isZero();
    }

    // ------------------------------------------------------------------ FR-2: nothing reaches the host

    @Test
    void anEventThatCannotBeEncodedIsDroppedInsteadOfPoisoningTheQueue() {
        EventQueue queue = queue();
        // A value Jackson has no serialiser for. The realistic origin is a beforeSend hook or a context
        // map a host application filled with one of its own objects.
        Map<String, Object> unserialisable = new HashMap<>();
        unserialisable.put("event_id", id(1));
        unserialisable.put("tags", Map.of("whatever", new Object()));
        queue.push(new FeedEvent(id(1), unserialisable));
        queue.push(event(id(2)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(202, accepted(id(2)), null));

        sender(queue, transport).flush();

        assertThat(transport.calls).describedAs("the encodable event still travels").hasSize(1);
        assertThat(transport.bodies().get(0)).describedAs("and it travels alone, as an object").startsWith("{");
        assertThat(queue.size()).isZero();
        assertThat(written.matching("cannot be encoded")).isNotEmpty();
    }

    @Test
    void aTransportThatThrowsNeverReachesTheHostApplication() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        Transport throwing = (url, body, timeoutMs) -> {
            throw new IllegalStateException("the host application's HTTP client exploded");
        };

        Sender sender = new Sender(config(), queue, throwing, written.logger(), () -> now);
        sender.flush();

        assertThat(queue.size()).describedAs("treated as a transport failure: the event is kept").isEqualTo(1);
        assertThat(sender.pausedUntilMillis()).isGreaterThan(now);
    }

    @Test
    void aCredentialInATransportFailureNeverReachesTheLog() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        // A host's own debugging decorator dumping the request it failed on.
        Transport leaking = (url, body, timeoutMs) -> {
            throw new IllegalStateException("request failed: POST /api/feed/events\n"
                    + "Authorization: Bearer " + TOKEN);
        };

        new Sender(config(), queue, leaking, written.logger(), () -> now).flush();

        String everything = String.join("\n", written.lines());
        assertThat(everything)
                .describedAs("FR-9 (e): the SDK keeps this promise itself rather than trusting every host")
                .doesNotContain(TOKEN)
                .doesNotContain("Bearer anv_srv_0");
        assertThat(everything).contains("[Filtered]");
    }

    @Test
    void aLoggerThatExplodesDoesNotCostTheFlush() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        FeedLogger hostile = FeedLogger.delegatingTo((level, message) -> {
            throw new IllegalStateException("the host's log appender is down");
        });
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(400, "", null));

        Sender sender = new Sender(config(), queue, transport, hostile, () -> now);
        sender.flush();

        assertThat(queue.size()).isZero();
    }

    // ------------------------------------------------------------------ the deadline

    @Test
    void flushRespectsItsDeadlineAndKeepsWhatItCouldNotSend() {
        EventQueue queue = queue();
        for (int i = 0; i < 3; i++) {
            queue.push(event(id(i)));
        }
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flush(now - 1_000L);

        assertThat(transport.calls).describedAs("a deadline already past means no request at all").isEmpty();
        assertThat(queue.size()).describedAs("and nothing is lost by the attempt").isEqualTo(3);
    }

    @Test
    void eachRequestGetsWhatIsLeftOfTheFlushDeadlineAsItsBudget() {
        EventQueue queue = queue();
        for (int i = 0; i < 3; i++) {
            queue.push(event(id(i)));
        }
        List<Integer> budgets = new ArrayList<>();
        // One event per batch, and every request costs 800 ms of the 2 s the flush promised the host.
        Transport slow = (url, body, timeoutMs) -> {
            budgets.add(timeoutMs);
            advance(800L);
            return IngestResponse.of(202, RecordingTransport.acceptEverything(body), null);
        };

        sender(queue, slow, config(builder -> builder.batchSize(1))).flush();

        assertThat(budgets)
                .describedAs("a deadline that only decides whether the NEXT request may start lets a flush "
                        + "run to deadline + request_timeout_ms — 4 s against FR-2 (c)'s 2 s")
                .containsExactly(2000, 1200, 400);
    }

    @Test
    void theSenderHandsOverWhatIsLeftOfTheDeadlineItWasGiven() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flush(now + 10_000L);

        assertThat(transport.budgets())
                .describedAs("clamping that down to request_timeout_ms is the transport's job and is "
                        + "proved in HttpTransportTest")
                .containsExactly(10_000);
    }

    @Test
    void flushWithinTakesABudgetRatherThanADeadline() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport).flushWithin(5_000L);

        assertThat(transport.budgets()).containsExactly(5_000);
    }

    // ------------------------------------------------------------------ nothing to do

    @Test
    void aDisabledClientSendsNothing() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport();

        sender(queue, transport, config(builder -> builder.enabled(false))).flush();

        assertThat(transport.calls).isEmpty();
    }

    @Test
    void anEmptyQueueIsNotARequest() {
        RecordingTransport transport = new RecordingTransport();

        sender(queue(), transport).flush();

        assertThat(transport.calls)
                .describedAs("a shutdown hook on a healthy request must cost nothing")
                .isEmpty();
    }

    // ------------------------------------------------------------------ 3xx

    @Test
    void aRedirectIsDroppedAndNamedRatherThanCountedAsAnUnexpectedStatus() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        String elsewhere = "https://elsewhere.example/api/feed/events";
        RecordingTransport transport = new RecordingTransport(
                IngestResponse.redirect(301, elsewhere), IngestResponse.redirect(301, elsewhere));

        Sender sender = sender(queue, transport);
        sender.flush();
        queue.push(event(id(2)));
        sender.flush();

        assertThat(queue.size()).describedAs("a redirect cannot be retried into success").isZero();
        assertThat(sender.isStopped()).isFalse();
        List<String> said = written.matching("never follows a redirect");
        assertThat(said).describedAs("said once per process, like every other permanent refusal").hasSize(1);
        assertThat(said.get(0))
                .describedAs("the Location is the one thing that tells the operator what their endpoint is "
                        + "really pointing at")
                .contains(elsewhere)
                .contains("301");
    }

    // ------------------------------------------------------------------ the daemon thread

    @Test
    void theDaemonThreadDrainsTheQueueWithoutAnybodyCallingFlush() throws Exception {
        EventQueue queue = queue();
        CountDownLatch delivered = new CountDownLatch(1);
        Transport transport = (url, body, timeoutMs) -> {
            delivered.countDown();
            return IngestResponse.of(202, RecordingTransport.acceptEverything(body), null);
        };

        try (Sender sender = new Sender(config(builder -> builder.flushIntervalMs(10)), queue, transport,
                written.logger(), System::currentTimeMillis)) {
            sender.start();
            queue.push(event(id(1)));

            assertThat(delivered.await(10, TimeUnit.SECONDS))
                    .describedAs("FR-3: sending is off the host's own thread")
                    .isTrue();
            assertThat(sender.isRunning()).isTrue();
        }
    }

    @Test
    void theDaemonThreadSurvivesATransportThatThrowsOnEveryCall() throws Exception {
        EventQueue queue = queue();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch thrice = new CountDownLatch(3);
        Transport exploding = (url, body, timeoutMs) -> {
            attempts.incrementAndGet();
            thrice.countDown();
            throw new IllegalStateException("boom");
        };

        try (Sender sender = new Sender(config(builder -> builder.flushIntervalMs(5)), queue, exploding,
                written.logger(), System::currentTimeMillis)) {
            sender.start();
            for (int i = 0; i < 5; i++) {
                queue.push(event(id(i)));
            }

            assertThat(thrice.await(30, TimeUnit.SECONDS))
                    .describedAs("a sender thread that dies on the first exception is an SDK that stops "
                            + "reporting and never says so")
                    .isTrue();
            assertThat(sender.isRunning()).isTrue();
        }
        assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void closeFlushesWhatIsLeftAndStopsTheThread() throws Exception {
        EventQueue queue = queue();
        RecordingTransport transport = new RecordingTransport();
        Sender sender = new Sender(config(builder -> builder.flushIntervalMs(3_600_000)), queue, transport,
                written.logger(), System::currentTimeMillis);
        sender.start();
        queue.push(event(id(1)));

        sender.close();

        assertThat(transport.calls)
                .describedAs("whatever is still queued when the JVM goes down gets its last chance")
                .hasSize(1);
        assertThat(sender.isRunning()).isFalse();
        assertThat(queue.size()).isZero();
    }

    @Test
    void startIsIdempotentAndCloseIsTotal() {
        EventQueue queue = queue();
        Sender sender = new Sender(config(), queue, new RecordingTransport(), written.logger(), () -> now);

        sender.start();
        sender.start();
        sender.close();
        sender.close();

        assertThat(sender.isRunning()).isFalse();
    }

    @Test
    void aDisabledSenderNeverStartsAThread() {
        EventQueue queue = queue();
        try (Sender sender = new Sender(config(builder -> builder.enabled(false)), queue,
                new RecordingTransport(), written.logger(), () -> now)) {
            sender.start();

            assertThat(sender.isRunning())
                    .describedAs("a host that switched the SDK off pays for no thread at all")
                    .isFalse();
        }
    }

    /** The conformance response bodies are data, not transcriptions: this proves they were read. */
    @Test
    void theConformanceFixturesUsedHereAreTheOnesOnDisk() throws Exception {
        JsonNode parsed = JSON.readTree(fixture("202-partial.json"));

        assertThat(parsed.path("rejected").path(0).path("reason_code").asText()).isEqualTo("no_binding");
    }

    // ------------------------------------------------------------------ C16: the 407 is the proxy's

    @Test
    void a407IsTheProxysAnswerAndKeepsTheEventsQueued() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(
                new IngestResponse(407, "", null, false, "the proxy corp-gw.internal:3128", null));

        Sender sender = sender(queue, transport);
        sender.flush();

        assertThat(queue.size())
                .describedAs("a proxy whose credentials are fixed five minutes later must lose nothing — "
                        + "this is the environment C11/C15 exist for")
                .isEqualTo(1);
        assertThat(sender.isStopped()).isFalse();
        assertThat(sender.pausedUntilMillis())
                .describedAs("backed off like any transport failure, not dropped and not paused for ever")
                .isBetween(now + 800L, now + 1200L);

        List<String> said = written.matching("PROXY refused");
        assertThat(said).describedAs("the explanation is said once per process; the per-attempt backoff line beside it is not that explanation").hasSize(1);
        assertThat(said.get(0))
                .describedAs("C16: the operator must be sent to the PROXY, not to the Anvil token")
                .contains("corp-gw.internal:3128")
                .contains("PROXY refused")
                .contains("not the Anvil ingest")
                .contains("jdk.http.auth.tunneling.disabledSchemes");
        // The general statement, rather than a list of shapes to remember: nothing in this line looks
        // like a credential to the SDK's own redactor, so redacting it changes nothing.
        assertThat(IngestResponse.redact(said.get(0)))
                .describedAs("C15/X11: the line an operator actually reads carries no credential, in "
                        + "cleartext or in Base64")
                .isEqualTo(said.get(0));
    }

    @Test
    void a407IsNeverReportedAsAnUnexpectedStatus() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        RecordingTransport transport = new RecordingTransport(IngestResponse.of(407, "", null));

        sender(queue, transport).flush();

        assertThat(written.matching("unexpected"))
                .describedAs("the generic 4xx row drops the batch and then drops every later event in "
                        + "silence — the one outcome C16 forbids")
                .isEmpty();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(written.matching("the configured proxy"))
                .describedAs("a transport that cannot name the proxy still says it was a proxy")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ FR-2 (c): the bound on the call

    /**
     * The deadline has to cover the wait for the lock, not only the work behind it. Task 10's adapter is
     * told it may call {@code flushWithin(500)} after a response while the daemon pump is inside its own
     * {@code flushWithin(2000)} on the next line of this class.
     */
    @Test
    void aFlushThatCannotGetInsideTheBudgetGivesUpWithinIt() throws Exception {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // A transport that parks inside the flush, so the lock is genuinely held by somebody else.
        Transport slow = (url, body, timeoutMs) -> {
            holding.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return IngestResponse.of(202, RecordingTransport.acceptEverything(body), null);
        };
        Sender sender = new Sender(config(), queue, slow, written.logger(), System::currentTimeMillis);

        Thread holder = new Thread(() -> sender.flushWithin(10_000));
        holder.setDaemon(true);
        holder.start();
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        boolean gotIn = sender.flushWithin(100);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        release.countDown();
        holder.join(10_000);

        // The BOUND first, the flag second. A build that returns false after waiting out the other
        // thread would otherwise pass the assertion whose name anybody reads.
        assertThat(elapsedMs)
                .describedAs("FR-2 (c) is a bound on the CALL — a 100 ms budget may not become the "
                        + "pump's full request_timeout_ms plus 100 ms")
                .isLessThan(1_000L);
        assertThat(gotIn)
                .describedAs("a flush that could not get in says so; the next turn does it anyway")
                .isFalse();
    }

    @Test
    void noRequestIsIssuedBelowTheMinimumBudgetAndNoBackoffIsArmed() {
        EventQueue queue = queue();
        queue.push(event(id(1)));
        queue.push(event(id(2)));
        List<Integer> budgets = new ArrayList<>();
        // The first request eats all but 30 ms of the 2 s flush.
        Transport slow = (url, body, timeoutMs) -> {
            budgets.add(timeoutMs);
            advance(1_970L);
            return IngestResponse.of(202, RecordingTransport.acceptEverything(body), null);
        };

        Sender sender = sender(queue, slow, config(builder -> builder.batchSize(1)));
        sender.flush();

        assertThat(budgets)
                .describedAs("30 ms is below the documented floor of " + Sender.MINIMUM_REQUEST_BUDGET_MS
                        + " ms: the second request is NOT attempted")
                .containsExactly(2000);
        assertThat(queue.size())
                .describedAs("and the batch it would have carried is back in the queue, not lost")
                .isEqualTo(1);
        assertThat(sender.pausedUntilMillis())
                .describedAs("not attempted is not failed: a doomed 1 ms request would arm a ~1 s backoff "
                        + "at the end of EVERY flush that drains a deep queue, and the log would blame "
                        + "the network")
                .isZero();
        assertThat(written.matching("transport failure")).isEmpty();
    }

    // ------------------------------------------------------------------ the shutdown hook

    @Test
    void everyStartedSenderRemovesItsShutdownHookOnClose() {
        int before = Sender.REGISTERED_SHUTDOWN_HOOKS.get();

        for (int i = 0; i < 100; i++) {
            Sender sender = new Sender(config(builder -> builder.flushIntervalMs(3_600_000)), queue(),
                    new RecordingTransport(), written.logger(), () -> now);
            sender.start();
            sender.close();
        }

        assertThat(Sender.REGISTERED_SHUTDOWN_HOOKS.get())
                .describedAs("Runtime cannot be asked what it holds, so a hundred cycles of a Spring "
                        + "context refresh would otherwise accumulate a hundred hooks and a hundred "
                        + "retained senders with nothing to notice it")
                .isEqualTo(before);
    }

    @Test
    void aSenderThatWasNeverStartedRegistersNoHookAtAll() {
        int before = Sender.REGISTERED_SHUTDOWN_HOOKS.get();

        for (int i = 0; i < 100; i++) {
            new Sender(config(), queue(), new RecordingTransport(), written.logger(), () -> now).close();
        }

        assertThat(Sender.REGISTERED_SHUTDOWN_HOOKS.get()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the log flood

    @Test
    void aPersistentlyRewritten202IsSaidOnceInFullAndThenCounted() {
        EventQueue queue = queue();
        for (int i = 0; i < 6; i++) {
            queue.push(event(id(i)));
        }
        // A proxy that strips the body: every answer is a 202 that names nothing, for ever.
        String empty = "{\"accepted\":[],\"rejected\":[]}";
        IngestResponse[] stripped = new IngestResponse[40];
        java.util.Arrays.fill(stripped, IngestResponse.of(202, empty, null));
        RecordingTransport transport = new RecordingTransport(stripped);

        sender(queue, transport, config(builder -> builder.batchSize(1))).flush();

        assertThat(written.matching("twice without naming it"))
                .describedAs("said in FULL once — the id is the useful part and a reader needs one — and "
                        + "not once per event: 500 events a minute would be 500 warnings a minute in the "
                        + "log this class is everywhere else careful not to flood")
                .hasSize(1);
        assertThat(written.matching("further event(s) dropped in this flush"))
                .describedAs("the rest are counted, and the count is said")
                .hasSize(1);
        assertThat(queue.size()).isZero();
    }
}
