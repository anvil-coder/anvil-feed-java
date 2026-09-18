package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import shop.ConformanceCaller;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The WHOLE client over a real socket, against a stub that refuses exactly what the published ingest
 * refuses. {@code AnvilFeed.init} → capture → {@code flush}, with {@link HttpTransport} doing the sending,
 * so what is asserted is what a customer's process actually does.
 *
 * <p>Every entry of {@code conformance/responses/index.json} is walked, and the pause is compared to the
 * {@code Retry-After} the SERVER sent rather than to a constant the SDK could have guessed — two entries
 * of that index carry 137 and 900 for precisely that reason. The clock is INJECTED and frozen, so a pause
 * is an equality rather than a window and no test sleeps to observe one.
 *
 * <p>A run that cannot bind a socket FAILS. There is no {@code assumeTrue} anywhere in this file: a
 * conformance suite that quietly skips itself on the machine that matters is worse than no suite.
 */
class MockServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The documented shape: {@code anv_srv_} and 32 base62 characters. The stub answers 401 to anything else. */
    private static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    /** A frozen instant, so every pause below is an exact equality. */
    private static final long NOW = 1_700_000_000_000L;

    private static final Path RESPONSES =
            Path.of(System.getProperty("anvilfeed.conformance")).resolve("responses");

    private static IngestStub stub;
    private static IngestStub redirectTarget;

    @BeforeAll
    static void startTheIngest() throws Exception {
        stub = new IngestStub();
        redirectTarget = new IngestStub();
        stub.redirectTo(redirectTarget);
    }

    @AfterAll
    static void stopTheIngest() {
        stub.close();
        redirectTarget.close();
    }

    @AfterEach
    void takeTheFacadeDown() {
        AnvilFeed.reset();
    }

    // ------------------------------------------------------------------ the harness

    /**
     * Everything an assertion keys on, SNAPSHOT at the end of the flush. The client is closed afterwards
     * and {@code close()} takes one last bounded pass over the queue, so reading the live objects after
     * the fact would be reading a different moment than the one under test.
     */
    record Run(int queueSize, boolean stopped, long pausedUntil, RecordingLogger written,
               List<IngestStub.Recorded> requests) {
    }

    /**
     * Drives the documented path against one scenario and hands back everything an assertion can key on.
     *
     * <p>{@code fingerprint_cap} is raised because every capture here comes from the same call site and is
     * therefore the same fault — the storm guard would otherwise suppress the batch this test needs, which
     * is the guard doing its job and not the subject of this file.
     */
    private Run drive(String scenario, int events) {
        return drive(stub, scenario, events);
    }

    private Run drive(IngestStub ingest, String scenario, int events) {
        int before = ingest.requests().size();
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .endpoint(ingest.endpoint(scenario))
                .token(TOKEN)
                .service("shop-api")
                .environment("production")
                .contextLines(0)
                .fingerprintCap(1000)
                .build(written.logger());

        AnvilFeed.reset();
        try (AnvilFeedClient client =
                     AnvilFeedClient.create(config, null, written.logger(), GitContext.none(), () -> NOW)) {
            AnvilFeed.init(client);
            for (int i = 0; i < events; i++) {
                ConformanceCaller.fail(new IllegalStateException("fault " + i), Map.of());
            }
            assertThat(client.queue().size())
                    .describedAs("every capture has to have been queued before the flush, or the scenario "
                            + "is being driven with fewer events than it thinks")
                    .isEqualTo(events);

            AnvilFeed.flush();
            return new Run(client.queue().size(), client.sender().isStopped(),
                    client.sender().pausedUntilMillis(), written,
                    ingest.requests().subList(before, ingest.requests().size()));
        } finally {
            AnvilFeed.reset();
        }
    }

    /**
     * The standing check every scenario also makes: the SDK never EARNS one of the four request-side
     * refusals. Stated in the contract's own terms rather than as "no 4xx", so a failure names the rule.
     */
    private static void nothingTheIngestWouldRefuseWasSent(List<IngestStub.Recorded> requests) {
        assertThat(requests).describedAs("a scenario that made no request proves nothing").isNotEmpty();
        for (IngestStub.Recorded request : requests) {
            assertThat(request.refusedBy())
                    .describedAs("the SDK earned a refusal from the ingest's own rules: %s", request.path())
                    .isNull();
            assertThat(request.header("origin"))
                    .describedAs("this SDK never sends an Origin; a server token presented with one is "
                            + "treated as leaked and answered 403 before it is even read")
                    .isNull();
            assertThat(request.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(request.header("content-type")).isEqualTo("application/json");
            assertThat(request.header("user-agent")).startsWith("anvil-feed-java/");
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.events())
                    .describedAs("a batch above 50 is answered 400, whole")
                    .isBetween(1, IngestStub.MAX_BATCH);
            assertThat(request.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .describedAs("a body above 262144 bytes is answered 413")
                    .isLessThanOrEqualTo(IngestStub.MAX_BODY_BYTES);
        }
    }

    // ------------------------------------------------------------------ the index, entry by entry

    record Entry(String scenario, int status, Integer retryAfter, String retry, int verdicts) {

        @Override
        public String toString() {
            return scenario;
        }
    }

    static Stream<Entry> indexEntries() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : JSON.readTree(Files.readString(RESPONSES.resolve("index.json")))) {
            String file = node.path("file").asText();
            String scenario = file.substring(0, file.length() - ".json".length());
            JsonNode body = JSON.readTree(Files.readString(RESPONSES.resolve(file)));
            entries.add(new Entry(scenario, node.path("status").asInt(),
                    node.path("retry_after").isNull() ? null : node.path("retry_after").asInt(),
                    node.path("retry").asText(),
                    Math.max(1, body.path("accepted").size() + body.path("rejected").size())));
        }
        assertThat(entries).hasSizeGreaterThanOrEqualTo(12);
        return entries.stream();
    }

    /**
     * Every published response, walked by the whole client over the socket. The batch is sized to the
     * fixture's own verdict count so that a 202 names every id it was sent — an answer that names fewer is
     * a different case ({@code SenderTest} owns it) and would muddy this one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("indexEntries")
    void theClientMakesTheDecisionTheIndexNamesOverARealSocket(Entry entry) {
        Run run = drive(entry.scenario(), entry.verdicts());

        nothingTheIngestWouldRefuseWasSent(run.requests());
        assertThat(run.stopped())
                .describedAs("none of these answers is a credential failure; only a 401 stops the process")
                .isFalse();

        switch (entry.retry()) {
            case "none" -> {
                assertThat(run.queueSize())
                        .describedAs("%s: the verdict is final — nothing of this batch is sent again",
                                entry.scenario())
                        .isZero();
                // Measured by REQUEST COUNT, not only by an empty queue: a sender that retried a 400
                // three times and then gave up leaves exactly the same queue behind. One batch in, one
                // request out — the 413 included, because a single event that is still too large has
                // nothing left to split and is dropped by id on the first answer.
                assertThat(run.requests())
                        .describedAs("%s: a final verdict is not retried — this is the assertion an "
                                + "empty queue cannot make", entry.scenario())
                        .hasSize(1);
                assertThat(run.pausedUntil())
                        .describedAs("%s: a final verdict is not a reason to stop sending anything else",
                                entry.scenario())
                        .isLessThanOrEqualTo(NOW);
            }
            case "after-delay", "whole-batch" -> {
                assertThat(run.queueSize())
                        .describedAs("%s: every event is kept", entry.scenario())
                        .isEqualTo(entry.verdicts());
                assertThat(run.pausedUntil())
                        .describedAs("%s: the pause is exactly the Retry-After the SERVER sent",
                                entry.scenario())
                        .isEqualTo(NOW + entry.retryAfter() * 1000L);
            }
            case "backoff" -> {
                assertThat(run.queueSize())
                        .describedAs("%s: the events are KEPT — this is the environment C11/C15 exist for",
                                entry.scenario())
                        .isEqualTo(entry.verdicts());
                assertThat(run.pausedUntil())
                        .describedAs("%s: the first backoff, jittered around one second", entry.scenario())
                        .isBetween(NOW + 800L, NOW + 1200L);
            }
            default -> fail("unknown retry verdict " + entry.retry());
        }

        for (String code : reasonCodes(entry.scenario())) {
            assertThat(run.written().matching(code))
                    .describedAs("%s: the reason code is the whole message; an SDK that swallows it leaves "
                            + "the customer with an empty inbox and no explanation", entry.scenario())
                    .isNotEmpty();
        }
    }

    /**
     * The discriminating half of the {@code Retry-After} claim: only entries whose window the SDK could
     * NOT have guessed. The provider fails if the index ever stops carrying at least two of them, because
     * at that point every pause assertion in this file becomes satisfiable by a hard-coded constant.
     */
    static Stream<Entry> windowsTheSdkCannotGuess() throws Exception {
        List<Entry> discriminating = indexEntries()
                .filter(entry -> entry.retryAfter() != null
                        && entry.retryAfter() != 60 && entry.retryAfter() != 30)
                .toList();
        assertThat(discriminating)
                .describedAs("60 and 30 are the SDK's own fallbacks; an index carrying only those cannot "
                        + "tell a sender that read the header from one that did not")
                .hasSizeGreaterThanOrEqualTo(2);
        return discriminating.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("windowsTheSdkCannotGuess")
    void thePauseIsTheHeadersValueAndNotTheSdksOwnFallback(Entry entry) {
        Run run = drive(entry.scenario(), entry.verdicts());

        assertThat(run.pausedUntil() - NOW)
                .describedAs("%s carries %d seconds precisely because the SDK cannot guess it",
                        entry.scenario(), entry.retryAfter())
                .isEqualTo(entry.retryAfter() * 1000L);
    }

    /** And the other branch of the {@code ??}: no header at all, and the documented default applies. */
    @Test
    void aMissingRetryAfterFallsBackToTheDocumentedDefault() {
        assertThat(drive("no-retry-after-429", 1).pausedUntil() - NOW)
                .describedAs("429 without a header: the documented 60 seconds").isEqualTo(60_000L);
        assertThat(drive("no-retry-after-503", 1).pausedUntil() - NOW)
                .describedAs("503 without a header: the documented 30 seconds").isEqualTo(30_000L);
    }

    // ------------------------------------------------------------------ the request the SDK makes

    /**
     * One event goes to {@code /events} as an OBJECT and two or more to {@code /events/batch} as an ARRAY.
     * The ingest answers 400 to the other shape on purpose, and only a socket can tell which was sent.
     */
    @Test
    void aSingleEventIsAnObjectAndABatchIsAnArray() {
        Run single = drive("accepted", 1);
        assertThat(single.requests()).hasSize(1);
        assertThat(single.requests().get(0).path()).endsWith("/api/feed/events");
        assertThat(single.requests().get(0).isArray()).isFalse();
        assertThat(single.queueSize()).isZero();

        Run batch = drive("accepted", 3);
        assertThat(batch.requests()).hasSize(1);
        assertThat(batch.requests().get(0).path()).endsWith("/api/feed/events/batch");
        assertThat(batch.requests().get(0).isArray()).isTrue();
        assertThat(batch.requests().get(0).events()).isEqualTo(3);
        assertThat(batch.queueSize()).isZero();

        nothingTheIngestWouldRefuseWasSent(single.requests());
        nothingTheIngestWouldRefuseWasSent(batch.requests());
    }

    /**
     * The stub's rules are a real gate and not decoration — four requests this test makes ITSELF, each
     * shaped the way {@code docs/anvil-feed-ingest.md} says earns a refusal.
     */
    @Test
    void theStubEnforcesTheIngestsOwnRequestRules() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(status(client, stub.endpoint("accepted") + "/events", "{}",
                    "Authorization", "Bearer " + TOKEN, "Origin", "https://app.example"))
                    .describedAs("an Origin is 403 before the token is even read").isEqualTo(403);
            assertThat(status(client, stub.endpoint("accepted") + "/events", "{}"))
                    .describedAs("no bearer at all is a bare 401").isEqualTo(401);
            assertThat(status(client, stub.endpoint("accepted") + "/events", "{}",
                    "Authorization", "Bearer nonsense"))
                    .describedAs("a token that is not anv_(srv|pub)_<32> is the same bare 401")
                    .isEqualTo(401);
            assertThat(status(client, stub.endpoint("accepted") + "/events", "not json",
                    "Authorization", "Bearer " + TOKEN))
                    .describedAs("a body that is not JSON is 400").isEqualTo(400);
            assertThat(status(client, stub.endpoint("accepted") + "/events/batch",
                    "[" + "{},".repeat(50) + "{}]", "Authorization", "Bearer " + TOKEN))
                    .describedAs("51 events is 400, the whole request").isEqualTo(400);
            assertThat(status(client, stub.endpoint("accepted") + "/events",
                    "{\"x\":\"" + "y".repeat(300_000) + "\"}", "Authorization", "Bearer " + TOKEN))
                    .describedAs("a body above 262144 bytes is 413").isEqualTo(413);
        }
    }

    private static int status(HttpClient client, String url, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** The SDK's own side of the same rule: a token the ingest refuses stops the process, once. */
    @Test
    void aTokenTheIngestRefusesStopsTheClientAndLaterCapturesReturnNull() {
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .endpoint(stub.endpoint("accepted"))
                .token("not-a-real-token")
                .service("shop-api").environment("production").contextLines(0)
                .build(written.logger());

        AnvilFeed.reset();
        try (AnvilFeedClient client =
                     AnvilFeedClient.create(config, null, written.logger(), GitContext.none(), () -> NOW)) {
            AnvilFeed.init(client);
            ConformanceCaller.fail(new IllegalStateException("boom"), Map.of());
            AnvilFeed.flush();

            assertThat(client.sender().isStopped()).isTrue();
            assertThat(client.queue().size()).describedAs("a stopped sender drains the queue").isZero();
            assertThat(ConformanceCaller.fail(new IllegalStateException("again"), Map.of()))
                    .describedAs("a stopped client stops ACCEPTING, not merely stops sending")
                    .isNull();
            assertThat(written.matching("401")).isNotEmpty();
        } finally {
            AnvilFeed.reset();
        }
    }

    @Test
    void a403TellsTheOperatorToRotateTheTokenAndDoesNotStop() {
        Run run = drive("forbidden", 1);

        assertThat(run.requests()).hasSize(1);
        assertThat(run.queueSize()).isZero();
        assertThat(run.stopped()).isFalse();
        assertThat(run.written().matching("rotate")).isNotEmpty();
        nothingTheIngestWouldRefuseWasSent(run.requests());
    }

    /**
     * The 413 split measured the only way it cannot be faked: by how many REQUESTS the ingest saw and how
     * many events each carried. Four events against a stub that refuses everything: 4, 2, 1 for the first
     * descent, then 3, 1 and 2, 1 and 1 for what the halves pushed back — eight requests, and the queue
     * empty because a single event that is still too large has nothing left to split.
     */
    @Test
    void a413IsAnsweredBySplittingAndTheSplitIsVisibleAsRequestCount() {
        Run run = drive("always-413", 4);

        assertThat(run.requests().stream().map(IngestStub.Recorded::events).toList())
                .describedAs("the halving, request by request")
                .containsExactly(4, 2, 1, 3, 1, 2, 1, 1);
        assertThat(run.queueSize()).isZero();
        assertThat(run.written().matching("payload_too_large")).isNotEmpty();
        assertThat(run.written().matching("larger than the ingest accepts"))
                .describedAs("a single event that is still too large is dropped BY ID").hasSize(4);
        nothingTheIngestWouldRefuseWasSent(run.requests());
    }

    /** A body the SDK builds is never above the ingest's own limit: the split happens BEFORE the request. */
    @Test
    void theSdkNeverEarnsA413HoweverLargeTheEventsAre() {
        RecordingLogger written = new RecordingLogger();
        int before = stub.requests().size();
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .endpoint(stub.endpoint("accepted")).token(TOKEN).service("shop-api")
                .environment("production").contextLines(0).fingerprintCap(1000)
                .build(written.logger());

        AnvilFeed.reset();
        try (AnvilFeedClient client =
                     AnvilFeedClient.create(config, null, written.logger(), GitContext.none(), () -> NOW)) {
            AnvilFeed.init(client);
            // Every tag VALUE is capped at 1000 characters, so one huge tag is not a huge event; 29 of
            // them at the cap is the largest event this SDK will ever build, about 29 KB.
            Map<String, Object> tags = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 29; i++) {
                tags.put("payload-" + i, "z".repeat(1_000));
            }
            for (int i = 0; i < 40; i++) {
                ConformanceCaller.fail(new IllegalStateException("huge " + i), Map.of("tags", tags));
            }
            AnvilFeed.flush();

            List<IngestStub.Recorded> requests =
                    stub.requests().subList(before, stub.requests().size());
            assertThat(requests).describedAs("the batch was split into several requests").hasSizeGreaterThan(1);
            nothingTheIngestWouldRefuseWasSent(requests);
            assertThat(client.queue().size()).isZero();
            assertThat(written.matching("413")).isEmpty();
        } finally {
            AnvilFeed.reset();
        }
    }

    /**
     * A redirect is never followed, and the assertion is what the TARGET saw: nothing at all. Elapsed time
     * or a status code could both be satisfied by a client that followed one and then complained.
     */
    @Test
    void aRedirectIsNeverFollowedAndTheTokenNeverReachesTheTarget() {
        int before = redirectTarget.requests().size();
        Run run = drive("redirect", 1);

        assertThat(redirectTarget.requests().size())
                .describedAs("the redirect target received a request; the token is now at a host the "
                        + "operator never configured")
                .isEqualTo(before);
        assertThat(run.queueSize()).isZero();
        assertThat(run.stopped()).isFalse();
        assertThat(run.written().matching("redirect")).isNotEmpty();
        assertThat(run.written().matching("/api/feed/events"))
                .describedAs("the Location is named, so an operator can see where they were being sent")
                .isNotEmpty();
        nothingTheIngestWouldRefuseWasSent(run.requests());
    }

    /**
     * A server that accepts and then takes longer than the budget is a TRANSIENT failure: the event is
     * kept and the sender is not stopped. There is no timing assertion here — the slowness is the stub's
     * and what is asserted is the outcome.
     */
    @Test
    void aTimeoutIsTransientAndNotARefusal() throws Exception {
        RecordingLogger written = new RecordingLogger();
        // Its OWN stub: this scenario answers 1.5 s late, and a request recorded that long after the
        // test has finished would land inside the next test's window.
        try (IngestStub slow = new IngestStub()) {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .endpoint(slow.endpoint("slow")).token(TOKEN).service("shop-api")
                .environment("production").contextLines(0).requestTimeoutMs(200)
                .build(written.logger());

        AnvilFeed.reset();
        try (AnvilFeedClient client =
                     AnvilFeedClient.create(config, null, written.logger(), GitContext.none(), () -> NOW)) {
            AnvilFeed.init(client);
            ConformanceCaller.fail(new IllegalStateException("slow"), Map.of());
            AnvilFeed.flush();

            assertThat(client.queue().size()).describedAs("a timeout keeps the event").isEqualTo(1);
            assertThat(client.sender().isStopped()).isFalse();
            assertThat(written.matching("transport failure")).isNotEmpty();
        } finally {
            AnvilFeed.reset();
        }
        }
    }

    /** A 500 that is not a 503 is the backoff path, and it keeps the events too. */
    @Test
    void anUnexpected5xxBacksOffAndKeepsTheEvents() {
        Run run = drive("unexpected-5xx", 2);

        assertThat(run.queueSize()).isEqualTo(2);
        assertThat(run.stopped()).isFalse();
        assertThat(run.pausedUntil())
                .describedAs("the first backoff is one second, plus or minus 20 % jitter")
                .isBetween(NOW + 800L, NOW + 1_200L);
        nothingTheIngestWouldRefuseWasSent(run.requests());
    }

    private static List<String> reasonCodes(String scenario) {
        List<String> codes = new ArrayList<>();
        try {
            for (JsonNode rejection : JSON.readTree(
                    Files.readString(RESPONSES.resolve(scenario + ".json"))).path("rejected")) {
                if (rejection.path("reason_code").isTextual()) {
                    codes.add(rejection.path("reason_code").asText());
                }
            }
        } catch (Exception notAVerdictBody) {
            // {} — the two rate-limit fixtures name no reason code.
        }
        return codes;
    }
}
