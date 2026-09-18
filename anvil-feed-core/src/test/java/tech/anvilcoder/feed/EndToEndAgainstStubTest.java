package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import shop.CheckoutService;
import shop.ConformanceCaller;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The Java SDK against the PUBLISHED contract, over a socket, end to end.
 *
 * <p>Everything before task 12 measured this SDK against something this repository wrote — {@link
 * IngestStub} and PHP's {@code stub-router.php} are both readings of the spec by the hands that wrote the
 * implementation, so neither can catch a misunderstanding they share with it.
 * {@code conformance/stub/server.mjs} is written from dark-factory's own {@code docs/anvil-feed-ingest.md},
 * {@code AnvilFeedIngestController} and {@code AnvilFeedEventMapper}, enforces their rules in the
 * controller's order, and journals every request. This file drives the SDK through its PUBLIC entry points
 * only — {@code AnvilFeed.init} → {@code captureException} / {@code captureMessage} /
 * {@code captureTestEvent} → {@code flush} — and then reads that journal.
 *
 * <p><b>What is not repeated here.</b> {@link MockServerTest} already walks every entry of
 * {@code conformance/responses/index.json} at the socket and {@link ResponseConformanceTest} walks the same
 * index at the sender. The scenarios below exist for the claims those cannot make: that the answer came
 * from a replay of the SERVER's rules, that the bytes on the wire validate against {@code schema/v1.0},
 * that a redelivery is the same {@code event_id} twice at the ingest's own dedup gate, and — through
 * {@code conformance/stub/compare.mjs} — that the PHP SDK put the same document on the same socket.
 *
 * <p>Nothing here sleeps: the clock is injected and frozen, and a pause is an equality.
 *
 * <p><b>A machine without {@code node} fails this class rather than skipping it.</b>
 * {@code conformance/tools/validate.mjs} is already a prerequisite of this repository's build, and a
 * conformance suite that quietly skips itself on the machine that matters is worse than no suite — the
 * rule {@link MockServerTest} and PHP's {@code MockServerTest} already hold.
 */
class EndToEndAgainstStubTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));
    private static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    /** The run whose single accepted request {@code conformance/stub/compare.mjs} reads. */
    private static final String CROSS_LANGUAGE_RUN = "c9";

    /** The message BOTH SDKs throw, so that {@code exception.message} can be compared rather than masked. */
    private static final String CROSS_LANGUAGE_MESSAGE = "Cannot read property id of undefined";

    /**
     * The context both SDKs capture with, so that the two wire bodies can be compared field for field.
     * Its PHP twin is {@code AgainstStubTest::CROSS_LANGUAGE_CONTEXT}; the two lists are held together by
     * {@code compare.mjs}, which fails the moment one side sends a field the other does not.
     */
    private static final Map<String, Object> CROSS_LANGUAGE_CONTEXT = Map.of(
            "request", Map.of("method", "POST", "url", "https://shop.example/api/checkout",
                    "status_code", 500),
            "user_id", "u-4711",
            "breadcrumbs", List.of("cart.add sku=A1", "checkout.start"),
            "tags", Map.of("tier", "gold"));

    private static Process stub;
    private static int port;
    private static Path journal;

    /** Frozen, so every pause below is an exact equality. */
    private long now = 1_700_000_000_000L;

    private AnvilFeedClient client;
    private RecordingLogger written;

    @BeforeAll
    static void startTheStub() throws Exception {
        journal = CONFORMANCE.resolve("stub/.journals/java.jsonl");
        Files.createDirectories(journal.getParent());

        ProcessBuilder builder = new ProcessBuilder("node", CONFORMANCE.resolve("stub/server.mjs").toString());
        builder.environment().put("STUB_JOURNAL", journal.toString());
        // `testing` is a binding the operator switched OFF, which is one of the four answers a first
        // integration meets and the only one no other scenario can produce.
        builder.environment().put("STUB_DISABLED_ENVIRONMENTS", "testing");
        builder.redirectErrorStream(true);
        try {
            stub = builder.start();
        } catch (java.io.IOException noNode) {
            fail("the ingest stub could not be started. Node 22+ has to be on PATH; see "
                    + "conformance/stub/README.md. " + noNode.getMessage());
        }

        BufferedReader out = new BufferedReader(
                new InputStreamReader(stub.getInputStream(), StandardCharsets.UTF_8));
        String line = out.readLine();
        Matcher matcher = Pattern.compile("listening (\\d+)").matcher(line == null ? "" : line);
        if (!matcher.find()) {
            StringBuilder rest = new StringBuilder(line == null ? "" : line);
            while (out.ready()) {
                rest.append('\n').append(out.readLine());
            }
            fail("the ingest stub did not report a port; it said: " + rest);
        }
        port = Integer.parseInt(matcher.group(1));
        out.readLine();   // "journal <path>"; the path was handed in, so nothing is read out of it
    }

    @AfterAll
    static void stopTheStub() {
        if (stub != null) {
            stub.destroy();
        }
    }

    @AfterEach
    void takeTheFacadeDown() {
        if (client != null) {
            // The sender takes one last bounded pass over the queue on close; draining first keeps a
            // finished test from adding a request the next one would read.
            client.queue().clear();
            client.close();
            client = null;
        }
        AnvilFeed.reset();
    }

    // ------------------------------------------------------------------ the harness

    private String endpoint(String run, String scenario) {
        return "http://127.0.0.1:" + port + "/" + run + "/" + scenario + "/api/feed";
    }

    private AnvilFeedClient install(String run) {
        return install(run, "accepted", (builder) -> builder);
    }

    private AnvilFeedClient install(String run, String scenario) {
        return install(run, scenario, (builder) -> builder);
    }

    /**
     * The client every scenario drives, installed on the public facade. {@code create} builds its own
     * {@link HttpTransport}; {@code start()} is deliberately NOT called, because a background flush would
     * add requests no assertion below expects.
     */
    private AnvilFeedClient install(String run, String scenario,
                                    java.util.function.UnaryOperator<AnvilFeedConfig.Builder> extra) {
        written = new RecordingLogger();
        AnvilFeedConfig config = extra.apply(AnvilFeedConfig.builder()
                        .endpoint(endpoint(run, scenario))
                        .token(TOKEN)
                        .service("disponio-api")
                        .environment("production")
                        .contextLines(0)
                        .maxFrames(3))
                .build(written.logger());

        AnvilFeed.reset();
        client = AnvilFeedClient.create(config, null, written.logger(), GitContext.none(), () -> now);
        AnvilFeed.init(client);
        return client;
    }

    private static HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN);
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Tells the stub what to answer to the next requests of this run, one scenario per request. */
    private static void script(String run, String... answers) throws Exception {
        String body = JSON.writeValueAsString(Map.of("run", run, "answers", List.of(answers)));
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__control"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).describedAs("the stub did not accept the scenario script")
                .isEqualTo(200);
    }

    /** Every journal line of one run, in arrival order. */
    private static List<JsonNode> journal(String run) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode entry = JSON.readTree(line);
            if (run.equals(entry.path("run").asText())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ the contract itself

    /**
     * The server's own golden example, unmodified, answered exactly as the published document prints the
     * answer. If one line of this class is the proof that the stub is the contract, it is this one.
     */
    @Test
    void theSectionThreeExampleIsAcceptedVerbatim() throws Exception {
        String example = Files.readString(CONFORMANCE.resolve("valid/section-3-example.json"));

        HttpResponse<String> answer = post("/raw/accepted/api/feed/events", example);

        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(answer.body())
                .isEqualTo("{\"accepted\":[\"f4b1a0c2-0f4a-4a1e-9d7a-2b6c5d8e1f30\"],\"rejected\":[]}");
    }

    record Refused(String file, String reasonCode) {

        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<Refused> refusedFixtures() throws Exception {
        List<Refused> rows = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(Files.readString(CONFORMANCE.resolve("cases.json")))) {
            if (!entry.path("reason_code").isNull()) {
                rows.add(new Refused(entry.path("file").asText(), entry.path("reason_code").asText()));
            }
        }
        assertThat(rows).describedAs("conformance/cases.json lost refused fixtures").hasSizeGreaterThan(8);
        return rows.stream();
    }

    /**
     * Every fixture {@code conformance/cases.json} marks refused, answered with the reason code it names —
     * by a stub that got those rules from {@code AnvilFeedEventMapper}, not from this repository's own
     * fixtures. Both SDK suites run this walk, and they are the only two readers of that corpus that talk
     * to a server at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedFixtures")
    void aRefusedFixtureIsAnsweredWithTheReasonCodeTheContractNames(Refused refused) throws Exception {
        HttpResponse<String> answer = post("/raw/accepted/api/feed/events",
                Files.readString(CONFORMANCE.resolve(refused.file())));

        assertThat(answer.statusCode())
                .describedAs("%s is refused per event, not per request", refused.file()).isEqualTo(202);
        JsonNode body = JSON.readTree(answer.body());
        assertThat(body.path("accepted")).describedAs("%s was accepted", refused.file()).isEmpty();
        assertThat(body.path("rejected").path(0).path("reason_code").asText())
                .describedAs(refused.file()).isEqualTo(refused.reasonCode());
    }

    /**
     * The one published sentence the running code does not keep. {@code docs/anvil-feed-ingest.md:55} says
     * an {@code Origin} is refused with 403 "whatever the token says"; {@code AnvilFeedIngestController}
     * checks the token first (:196-207) and the {@code Origin} afterwards (:216-222). Both directions are
     * pinned so that a change on either side is a red test rather than a surprise.
     */
    @Test
    void anOriginIsRefusedAfterTheTokenIsChecked() throws Exception {
        assertThat(post("/raw/accepted/api/feed/events", "{}",
                "Origin", "https://somebodys-page.example").statusCode())
                .describedAs("a resolved token plus an Origin is 403").isEqualTo(403);

        HttpResponse<String> badToken = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/raw/accepted/api/feed/events"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer nope")
                        .header("Origin", "https://somebodys-page.example")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(badToken.statusCode())
                .describedAs("the controller answers 401 to an Origin carried by a token it never resolved")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ the SDK, end to end

    /**
     * Scenario 1. A real thrown exception through the public API: exactly one request, one event, one
     * {@code 202}, and the body the SDK put on the socket is valid against
     * {@code schema/v1.0/event.schema.json}. This is also the run {@code compare.mjs} reads.
     *
     * <p>The throwable is created in {@code shop/} rather than here: every frame of
     * {@code tech.anvilcoder.feed} is dropped, so a throwable constructed in this class would travel with
     * an empty stack and the assertion would be measuring nothing.
     */
    @Test
    void aRealExceptionIsAcceptedAndWhatWentOnTheWireIsSchemaValid() throws Exception {
        install(CROSS_LANGUAGE_RUN);

        String id = ConformanceCaller.fail(CheckoutService.failWith(CROSS_LANGUAGE_MESSAGE),
                CROSS_LANGUAGE_CONTEXT);
        assertThat(id).isNotNull();
        AnvilFeed.flush();

        List<JsonNode> requests = journal(CROSS_LANGUAGE_RUN);
        assertThat(requests).describedAs("one capture is one request").hasSize(1);
        JsonNode request = requests.get(0);
        assertThat(request.path("status").asInt()).isEqualTo(202);
        assertThat(request.path("endpoint_shape").asText())
                .describedAs("a single event is posted to /api/feed/events as an object").isEqualTo("object");
        assertThat(request.path("events")).hasSize(1);
        assertThat(request.path("events").path(0).path("verdict").asText()).isEqualTo("accepted");
        assertThat(request.path("events").path(0).path("event_id").asText()).isEqualTo(id);
        assertThat(request.path("events").path(0).path("schema_valid").asBoolean())
                .describedAs("the body is not valid against schema/v1.0: %s",
                        request.path("events").path(0).path("schema_errors"))
                .isTrue();
        assertThat(request.path("headers").path("authorization").asText()).startsWith("Bearer anv_srv_");
        assertThat(request.path("headers").has("origin"))
                .describedAs("this SDK must never set an Origin").isFalse();

        JsonNode body = request.path("body");
        assertThat(body.path("language").asText()).isEqualTo("java");
        assertThat(body.path("environment").asText()).isEqualTo("production");
        assertThat(body.path("service").asText()).isEqualTo("disponio-api");
        assertThat(body.path("request").path("url").asText()).isEqualTo("https://shop.example/api/checkout");
        assertThat(body.path("request").path("status_code").asInt()).isEqualTo(500);
        assertThat(body.path("user").path("id").asText()).isEqualTo("u-4711");
        assertThat(body.path("breadcrumbs").toString()).isEqualTo("[\"cart.add sku=A1\",\"checkout.start\"]");
        assertThat(body.path("tags").path("tier").asText()).isEqualTo("gold");
        assertThat(body.path("exception").path("message").asText())
                .describedAs("the message is what compare.mjs holds the two SDKs to; the type and the "
                        + "frames it cannot")
                .isEqualTo(CROSS_LANGUAGE_MESSAGE);
        assertThat(client.queue().size()).describedAs("an accepted event is cleared").isZero();
        assertThat(client.sender().isStopped()).isFalse();
    }

    /**
     * Scenario 2, first half. A hundred captures of ONE fault leave the process bounded by
     * {@code fingerprint_cap} per window — the local cap, measured where it matters: on the socket.
     */
    @Test
    void aStormOfOneFaultIsBoundedByTheFingerprintCapOnTheWire() throws Exception {
        install("storm", "accepted", (builder) -> builder.fingerprintCap(10).fingerprintWindowMs(60000));

        for (int i = 0; i < 100; i++) {
            ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        }
        AnvilFeed.flush();

        int delivered = 0;
        for (JsonNode entry : journal("storm")) {
            delivered += entry.path("events").size();
        }
        assertThat(delivered)
                .describedAs("100 captures of one fingerprint, cap 10, one window").isEqualTo(10);
    }

    /** Scenario 2, second half. Whatever does leave, leaves in batches the ingest accepts: at most 50. */
    @Test
    void everyRequestIsABatchOfAtMostFifty() throws Exception {
        install("batching", "accepted", (builder) -> builder.fingerprintCap(1000));

        for (int i = 0; i < 100; i++) {
            AnvilFeed.captureMessage("distinct fault number " + i, "warning", Map.of());
        }
        AnvilFeed.flush();

        List<JsonNode> entries = journal("batching");
        assertThat(entries).describedAs("100 events at batch_size 50").hasSize(2);
        int total = 0;
        for (JsonNode entry : entries) {
            assertThat(entry.path("endpoint_shape").asText())
                    .describedAs("a batch goes to /api/feed/events/batch").isEqualTo("array");
            assertThat(entry.path("events").size())
                    .describedAs("a batch carries at most 50 events").isLessThanOrEqualTo(50);
            assertThat(entry.path("status").asInt()).isEqualTo(202);
            total += entry.path("events").size();
        }
        assertThat(total).isEqualTo(100);
    }

    /**
     * Scenario 3. A {@code 503} and then a {@code 202}: the SAME {@code event_id} arrives twice, and the
     * ingest's own dedup gate — {@code (organisation, event_id)} — is the one that counts it once.
     * At-least-once is the SDK's contract; exactly-once is the server's, and the journal's
     * {@code deliveries} is where that division is visible.
     */
    @Test
    void a503AndThenA202DeliverTheSameEventIdTwice() throws Exception {
        script("redelivery", "transient");
        install("redelivery");

        String id = ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        assertThat(id).isNotNull();
        AnvilFeed.flush();

        List<JsonNode> afterTheFailure = journal("redelivery");
        assertThat(afterTheFailure).hasSize(1);
        assertThat(afterTheFailure.get(0).path("status").asInt()).isEqualTo(503);
        assertThat(afterTheFailure.get(0).path("retry_after").asText()).isEqualTo("30");
        assertThat(client.queue().size()).describedAs("the WHOLE batch is kept after a 503").isEqualTo(1);
        assertThat(client.sender().pausedUntilMillis()).isEqualTo(now + 30_000L);

        AnvilFeed.flush();
        assertThat(journal("redelivery")).describedAs("nothing goes out during the pause").hasSize(1);

        now += 30_000L;
        AnvilFeed.flush();

        List<JsonNode> afterTheRetry = journal("redelivery");
        assertThat(afterTheRetry).hasSize(2);
        assertThat(afterTheRetry.get(1).path("status").asInt()).isEqualTo(202);
        assertThat(afterTheRetry.get(1).path("events").path(0).path("event_id").asText())
                .describedAs("the same id, resent").isEqualTo(id);
        assertThat(afterTheRetry.get(1).path("events").path(0).path("deliveries").asInt())
                .describedAs("the ingest saw the id twice and stored it once").isEqualTo(2);
        assertThat(client.queue().size()).isZero();
    }

    /**
     * Scenario 4. A {@code 429} with {@code Retry-After}: nothing goes out before the deadline the SERVER
     * named, and the deadline is read off the answer rather than off a constant the SDK could have guessed.
     */
    @Test
    void a429SendsNothingBeforeTheRetryAfterDeadline() throws Exception {
        script("ratelimit", "rate-limited");
        install("ratelimit");

        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        AnvilFeed.flush();

        List<JsonNode> entries = journal("ratelimit");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("status").asInt()).isEqualTo(429);
        assertThat(entries.get(0).path("retry_after").asText()).isEqualTo("60");
        assertThat(client.sender().pausedUntilMillis()).isEqualTo(now + 60_000L);
        assertThat(client.queue().size()).describedAs("no event is dropped by a 429").isEqualTo(1);

        now += 59_000L;
        AnvilFeed.flush();
        assertThat(journal("ratelimit"))
                .describedAs("one second before the deadline: no request").hasSize(1);

        now += 1_000L;
        AnvilFeed.flush();
        List<JsonNode> resumed = journal("ratelimit");
        assertThat(resumed).describedAs("at the deadline the send resumes").hasSize(2);
        assertThat(resumed.get(1).path("status").asInt()).isEqualTo(202);
        // The proof is the COUNT after +59 s, not a wall-clock stamp: the stub's `at` is real time and the
        // SDK's clock is injected, so comparing them would assert that this machine's clock runs forwards.
    }

    /**
     * Scenario 5. A {@code 401} stops the process for its lifetime — captures included, not only sends —
     * and says so once.
     */
    @Test
    void a401StopsTheProcessForItsLifetimeAndSaysSoOnce() throws Exception {
        install("unauthorized", "accepted", (builder) -> builder.token("anv_srv_too-short"));

        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        AnvilFeed.flush();

        List<JsonNode> entries = journal("unauthorized");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("status").asInt()).isEqualTo(401);
        assertThat(entries.get(0).path("headers").path("authorization").asText())
                .describedAs("the SDK presented the token it was configured with; the 401 was EARNED by "
                        + "that token and not by a request the ingest could not read")
                .isEqualTo("Bearer anv_srv_… (17 characters)");
        assertThat(client.sender().isStopped()).isTrue();
        assertThat(client.queue().size()).describedAs("the queue is drained, not retried").isZero();

        assertThat(ConformanceCaller.fail(CheckoutService.charge(), Map.of()))
                .describedAs("a stopped client stops ACCEPTING, not merely stops sending").isNull();
        AnvilFeed.flush();
        assertThat(journal("unauthorized")).describedAs("nothing more is ever sent").hasSize(1);

        assertThat(written.matching("the ingest answered 401"))
                .describedAs("exactly one line for the lifetime of the process").hasSize(1);
    }

    /** Scenario 6. A redirect is never followed, so the target receives nothing at all. */
    @Test
    void aRedirectIsNeverFollowedAndTheTargetReceivesNothing() throws Exception {
        install("redirected", "redirect");

        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        AnvilFeed.flush();

        List<JsonNode> entries = journal("redirected");
        assertThat(entries).describedAs("the redirect itself, and nothing else").hasSize(1);
        assertThat(entries.get(0).path("status").asInt()).isEqualTo(302);
        assertThat(entries.get(0).path("location").asText()).contains("/target/api/feed/events");
        for (JsonNode entry : entries) {
            assertThat(entry.path("path").asText())
                    .describedAs("the redirect target received a request; the token would have travelled "
                            + "with it")
                    .doesNotContain("/target/");
        }
        assertThat(client.queue().size()).isZero();
        assertThat(written.matching("never follows a redirect")).isNotEmpty();
    }

    /**
     * Scenario 7. The PII fixture as a caller's context: what reaches the socket carries no denylisted
     * value, and the blocks {@code conformance/pii/expected.json} describes are reproduced field for field.
     */
    @Test
    void noDenylistedValueEverReachesTheSocket() throws Exception {
        JsonNode raw = JSON.readTree(Files.readString(CONFORMANCE.resolve("pii/raw.json")));
        JsonNode expected = JSON.readTree(Files.readString(CONFORMANCE.resolve("pii/expected.json")));
        install("pii");

        ConformanceCaller.reportTheAuthFailure(raw.path("exception").path("message").asText(), Map.of(
                "request", JSON.convertValue(raw.path("request"), Map.class),
                "user_id", raw.path("user").path("id").asText(),
                "breadcrumbs", JSON.convertValue(raw.path("breadcrumbs"), List.class),
                "tags", JSON.convertValue(raw.path("tags"), Map.class)));
        AnvilFeed.flush();

        List<JsonNode> entries = journal("pii");
        assertThat(entries).hasSize(1);
        JsonNode body = entries.get(0).path("body");

        assertThat(body.path("request")).isEqualTo(expected.path("request"));
        assertThat(body.path("user"))
                .describedAs("user.ip and user.ip_hash are never sent").isEqualTo(expected.path("user"));
        assertThat(body.path("breadcrumbs")).isEqualTo(expected.path("breadcrumbs"));
        assertThat(body.path("tags")).isEqualTo(expected.path("tags"));
        assertThat(body.path("exception").path("message").asText())
                .isEqualTo(expected.path("exception").path("message").asText());

        String onTheWire = body.toString();
        for (String secret : List.of("eyJhbGciOiJIUzI1NiJ9.abc", "sk-live-99887766", "4111111111111111",
                "CH9300762011623852957", "ada@example.org", "203.0.113.9", "sid=abc", "abc123")) {
            assertThat(onTheWire).describedAs("a denylisted value reached the socket")
                    .doesNotContain(secret);
        }
        assertThat(entries.get(0).path("events").path(0).path("schema_valid").asBoolean())
                .describedAs("the scrubbed body must itself be a sendable event").isTrue();
    }

    /**
     * Scenario 8. The loop guard: an exception raised while serving {@code /api/feed/**} is not reported,
     * or the SDK's own failures become the SDK's own traffic.
     */
    @Test
    void aCaptureFromInsideTheFeedPathNeverReachesTheIngest() throws Exception {
        install("loopguard");

        String id;
        AnvilFeedClient.enterRequest("https://app.anvil-coder.tech/api/feed/events");
        try {
            id = ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        } finally {
            AnvilFeedClient.leaveRequest();
        }

        assertThat(id).describedAs("an ignored request path is one of the seven documented nulls").isNull();
        assertThat(client.queue().size()).isZero();
        AnvilFeed.flush();
        assertThat(journal("loopguard")).isEmpty();
    }

    /**
     * Scenario 9, at the core's level: {@code captureTestEvent()} reads the ingest's answer. The four
     * verdicts a developer is shown are assembled in the adapters — {@code anvil.feed.send-test-event} and
     * {@code anvil-feed:test} — and {@code AnvilFeedTestEventEndToEndTest} proves those four against this
     * same stub. What belongs here is the raw material: an accepted id, and a rejection that names the
     * ingest's own reason code.
     */
    @Test
    void aTestEventIsAcceptedAndCarriesTheIdTheCockpitIsSearchedFor() throws Exception {
        install("testevent");

        String id = ConformanceCaller.verifyTheIntegration();
        assertThat(id).isNotNull();
        AnvilFeed.flush();

        List<JsonNode> entries = journal("testevent");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("status").asInt()).isEqualTo(202);
        assertThat(entries.get(0).path("response").path("accepted").path(0).asText()).isEqualTo(id);
        assertThat(entries.get(0).path("body").path("exception").path("type").asText())
                .isEqualTo("AnvilFeed.TestEvent");
        assertThat(entries.get(0).path("events").path(0).path("schema_valid").asBoolean()).isTrue();
        assertThat(client.sender().isStopped()).isFalse();
    }

    /** An unbound environment is the first thing a real integration meets, and it is not retryable. */
    @Test
    void anUnboundEnvironmentIsAnsweredNoBindingAndTheEventIsNotResent() throws Exception {
        install("nobinding", "accepted", (builder) -> builder.environment("staging"));

        ConformanceCaller.verifyTheIntegration();
        AnvilFeed.flush();

        List<JsonNode> entries = journal("nobinding");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("response").path("rejected").path(0).path("reason_code").asText())
                .isEqualTo("no_binding");
        assertThat(client.queue().size())
                .describedAs("a verdict is final, even a refusing one").isZero();
        assertThat(client.sender().isStopped()).isFalse();

        AnvilFeed.flush();
        assertThat(journal("nobinding"))
                .describedAs("a 4xx-equivalent verdict is never retried").hasSize(1);
    }

    /**
     * A binding that exists and is switched off. It is the third of the four verdicts a first integration
     * meets, it is FINAL — the event is not resent — and it is the one an SDK is most tempted to retry,
     * because a 202 with an empty `accepted` list looks like nothing happened.
     */
    @Test
    void aDisabledBindingIsFinalAndTheEventIsNotResent() throws Exception {
        install("bindingdisabled", "accepted", (builder) -> builder.environment("testing"));

        ConformanceCaller.verifyTheIntegration();
        AnvilFeed.flush();

        List<JsonNode> entries = journal("bindingdisabled");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path("response").path("rejected").path(0).path("reason_code").asText())
                .isEqualTo("binding_disabled");
        assertThat(client.queue().size()).isZero();
        assertThat(client.sender().isStopped()).isFalse();

        AnvilFeed.flush();
        assertThat(journal("bindingdisabled")).hasSize(1);
    }

    /**
     * A server that answers the status line and then withholds the body past any sane request budget. The
     * event is KEPT — a read timeout is transient and the batch has not been refused — the sender is not
     * stopped, and the whole flush returns long before the stub would have finished.
     *
     * <p>The elapsed time is measured rather than slept through: the assertion is that the budget reached
     * the socket, and a flush that took the stub's five seconds would fail it.
     */
    @Test
    void aSlowIngestIsATransientFailureBoundedByTheConfiguredBudget() throws Exception {
        install("slowingest", "slow", (builder) -> builder.requestTimeoutMs(300).connectTimeoutMs(300));

        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        long startedAt = System.nanoTime();
        AnvilFeed.flush();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(journal("slowingest")).describedAs("the request did reach the stub").hasSize(1);
        assertThat(elapsedMs)
                .describedAs("the 300 ms budget has to reach the socket; the stub withholds the body for "
                        + "five seconds")
                .isLessThan(3_000L);
        assertThat(client.queue().size()).describedAs("a read timeout is transient, not a refusal")
                .isEqualTo(1);
        assertThat(client.sender().isStopped()).isFalse();
        assertThat(written.matching("transport failure")).isNotEmpty();
    }

    /**
     * The two long-window answers of {@code conformance/responses/index.json}. 137 and 900 are deliberately
     * not the documented 60 and 30: an SDK that lost the {@code Retry-After} header and fell back to its
     * own constant is indistinguishable from one that read it, for as long as every answer carries the
     * default.
     */
    @Test
    void thePauseIsTheRetryAfterTheServerSentAndNotAConstantTheSdkKnows() throws Exception {
        script("longwindow", "rate-limited-long-window");
        install("longwindow");
        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        AnvilFeed.flush();
        assertThat(journal("longwindow").get(0).path("retry_after").asText()).isEqualTo("137");
        assertThat(client.sender().pausedUntilMillis()).isEqualTo(now + 137_000L);

        takeTheFacadeDown();
        script("longwindow2", "transient-long-window");
        install("longwindow2");
        ConformanceCaller.fail(CheckoutService.charge(), Map.of());
        AnvilFeed.flush();
        assertThat(journal("longwindow2").get(0).path("retry_after").asText()).isEqualTo("900");
        assertThat(client.sender().pausedUntilMillis()).isEqualTo(now + 900_000L);
        assertThat(client.queue().size()).describedAs("a 503 keeps the whole batch").isEqualTo(1);
    }

    /**
     * The mapper trims nothing: a padded id reaches {@code UUID.fromString} with its padding
     * ({@code AnvilFeedEventMapper.java:97-114}). A stub that trimmed first would answer 202 to a body the
     * running ingest refuses per event — which is the proxy this whole task exists to make expensive.
     */
    @Test
    void aPaddedEventIdIsRefusedBecauseTheMapperTrimsNothing() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode event =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(Files.readString(CONFORMANCE.resolve("valid/minimal.json")));
        event.put("event_id", "  " + event.path("event_id").asText() + "  ");

        HttpResponse<String> answer = post("/raw/accepted/api/feed/events", JSON.writeValueAsString(event));
        JsonNode body = JSON.readTree(answer.body());

        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(body.path("accepted")).isEmpty();
        assertThat(body.path("rejected").path(0).path("reason_code").asText()).isEqualTo("schema_violation");
        assertThat(body.path("rejected").path(0).path("message").asText()).contains("canonical UUID");
    }

    /**
     * Review finding 7. {@code AnvilFeedEventMapper.majorIsSupported} parses the major with
     * {@code Integer.parseInt}, which THROWS on trailing garbage, so {@code "1abc"} is
     * {@code unsupported_schema_version} at the running ingest. The stub read it with
     * {@code Number.parseInt}, which reads the {@code 1} and stops, and answered 202 — the one branch on
     * which this replay was looser than the server it replays. No SDK sends anything but {@code "1.0"},
     * so nothing shipped depended on it; "rule for rule" is the stub's own claim about itself, and a
     * claim nothing checks is the proxy this whole task exists to make expensive.
     */
    @Test
    void aSchemaVersionWithATrailingGarbageMajorIsRefusedLikeTheMapperRefusesIt() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode event =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        JSON.readTree(Files.readString(CONFORMANCE.resolve("valid/minimal.json")));
        event.put("schema_version", "1abc");

        HttpResponse<String> answer = post("/raw/accepted/api/feed/events", JSON.writeValueAsString(event));
        JsonNode body = JSON.readTree(answer.body());

        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(body.path("accepted")).isEmpty();
        assertThat(body.path("rejected").path(0).path("reason_code").asText())
                .isEqualTo("unsupported_schema_version");
    }

    /** Disabled is the fourth verdict, and it reaches no socket at all. */
    @Test
    void aDisabledSdkSendsNothing() throws Exception {
        install("disabled", "accepted", (builder) -> builder.enabled(false));

        assertThat(ConformanceCaller.verifyTheIntegration()).isNull();
        AnvilFeed.flush();

        assertThat(journal("disabled")).isEmpty();
        assertThat(client.queue().size()).isZero();
    }
}
