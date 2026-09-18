package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import shop.ConformanceCaller;
import shop.SecretHolder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared fixtures held against what this SDK actually puts ON THE WIRE, driven only through the public
 * facade — {@code AnvilFeed.init} → {@code captureException} / {@code captureMessage} /
 * {@code captureTestEvent} → {@code flush}. Nothing here reaches for {@link EventFactory} or
 * {@link Scrubber}: a denylist that stopped being handed to the client, or a second pass that stopped
 * running over a hook's output, leaves every component-level test green and every customer unprotected.
 *
 * <p>Three claims, one per direction:
 *
 * <ol>
 *   <li><b>{@code conformance/valid/}</b> — the SDK produces these shapes, compared field by field and
 *       KEY ORDER included. Four members are substituted and each is a language fact rather than a
 *       licence: {@code event_id} and {@code timestamp} cannot be equal twice, and {@code language} and
 *       {@code sdk} are the two the fixtures spell in PHP (Z1 exempts {@code sdk.name} and
 *       {@code sdk.version} by name). The {@code stacktrace} is substituted only after the frame this SDK
 *       built has been asserted to be a REAL caller frame — same key list, same order, a file that is not
 *       one of ours — because a frame belonging to the reporting library is the defect those two fixtures
 *       exist to refuse ({@code conformance/README.md}, the {@code valid/} row).</li>
 *   <li><b>{@code conformance/rejected/} and {@code sdk-forbidden/}</b> — the SDK never produces these
 *       shapes. Each fixture gets a predicate, the predicate is first asserted TRUE of the fixture (so a
 *       rewritten fixture cannot make the guard vacuous) and then FALSE of every event of a hostile
 *       corpus whose entries are inputs that WOULD produce the shape if the guard were not there.</li>
 *   <li><b>{@code conformance/pii/}</b> — {@code raw.json}'s own secrets, handed in as a caller's context,
 *       leave this process as {@code expected.json} and no other way.</li>
 * </ol>
 */
class FixtureConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));
    private static final Path SCHEMA = Path.of(System.getProperty("anvilfeed.schema"));

    /**
     * The address {@code pii/raw.json} carries, READ OUT OF the fixture rather than retyped. It may not
     * appear anywhere in anything this SDK sends — and a test file that spelled it as a literal would
     * plant it in its own source, which is what the {@code context_code} of an in-app frame carries.
     */
    private static final String THE_ADDRESS = addressOfTheRawFixture();

    /** Secrets the raw fixture plants; {@link #thePlantedSecretsAreReallyInTheRawFixture()} keeps the list honest. */
    private static final List<String> PLANTED_SECRETS = List.of(
            "sk-live-99887766", "eyJhbGciOiJIUzI1NiJ9.abc", "4111111111111111",
            "CH9300762011623852957", "ada@example.org", "sid=abc", "deadbeef");

    /** The source file names of this SDK. None of them may ever appear in a customer's stacktrace. */
    private static final List<String> OUR_OWN_FILES = List.of("AnvilFeed.java", "AnvilFeedClient.java",
            "AnvilFeedConfig.java", "EventFactory.java", "EventQueue.java", "FeedEvent.java",
            "FeedLogger.java", "Fingerprint.java", "GitContext.java", "HttpTransport.java",
            "IngestResponse.java", "Json.java", "LoopGuard.java", "RateGate.java", "Scrubber.java",
            "Sender.java", "SourceContext.java", "Transport.java");

    private static String addressOfTheRawFixture() {
        try {
            return String.valueOf(map(fixture("pii/raw.json").get("user")).get("ip"));
        } catch (Exception unreadable) {
            throw new AssertionError("pii/raw.json is unreadable", unreadable);
        }
    }

    @AfterEach
    void takeTheFacadeDown() {
        AnvilFeed.reset();
    }

    // ================================================================= the harness

    /**
     * The whole documented path, and what comes back is what the TRANSPORT was handed — the bytes, parsed
     * back. A payload that never reached a body is not a payload this SDK sent.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> onTheWire(AnvilFeedConfig config, RecordingLogger written,
                                                       Consumer<AnvilFeedClient> drive) {
        AnvilFeed.reset();
        RecordingTransport transport = new RecordingTransport();
        AnvilFeedClient client =
                AnvilFeedClient.create(config, transport, written.logger(), GitContext.none());
        AnvilFeed.init(client);
        try {
            drive.accept(client);
            AnvilFeed.flush();
        } finally {
            AnvilFeed.reset();
        }

        List<Map<String, Object>> sent = new ArrayList<>();
        for (String body : transport.bodies()) {
            try {
                JsonNode parsed = JSON.readTree(body);
                for (JsonNode event : parsed.isArray() ? parsed : List.of(parsed)) {
                    sent.add(JSON.convertValue(event, LinkedHashMap.class));
                }
            } catch (Exception unreadable) {
                throw new AssertionError("the SDK posted a body that is not JSON: " + body, unreadable);
            }
        }
        return sent;
    }

    private static List<Map<String, Object>> onTheWire(AnvilFeedConfig config,
                                                       Consumer<AnvilFeedClient> drive) {
        return onTheWire(config, new RecordingLogger(), drive);
    }

    /**
     * The configuration every capture in this file uses; {@code disponio-api} is the fixtures' own service.
     *
     * <p>{@code context_lines: 0} throughout, with one deliberate exception
     * ({@link #contextCodeIsScrubbedBeforeItLeavesTheProcess()}): {@code context_code} is read from the
     * checkout this suite runs in, so leaving it on would embed THIS file's own source — hostile-corpus
     * entries and all — in the events under test, and the assertions would be measuring the test's source
     * instead of the SDK. The rule itself is proved once, against a file written for it.
     */
    private static AnvilFeedConfig.Builder base() {
        return AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("disponio-api")
                .environment("production")
                .contextLines(0);
    }

    private static AnvilFeedConfig config() {
        return base().build(FeedLogger.toSystemLogger());
    }

    /**
     * {@code max_frames: 1} and {@code context_lines: 0}, exactly as the two golden PHP clients ask for
     * them: a golden document can then carry the WHOLE frame instead of a machine's absolute paths and a
     * checkout's source lines ({@code conformance/README.md}, the {@code valid/} row).
     */
    private static AnvilFeedConfig goldenConfig() {
        return base().maxFrames(1).build(FeedLogger.toSystemLogger());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String name) throws Exception {
        return JSON.readValue(Files.readString(CONFORMANCE.resolve(name)), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    private static JsonSchema schema() throws Exception {
        try (InputStream in = Files.newInputStream(SCHEMA.resolve("v1.0/event.schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    // ================================================================= valid/ — field for field

    /**
     * The frame is COMPARED before it is substituted: same keys in the same order, a file that belongs to
     * the caller and not to this SDK, {@code in_app} true, a real line. Only then is the fixture's PHP
     * frame put in its place, because a Java stack cannot name a {@code .php} file and the SHAPE is what
     * the fixture pins.
     */
    private static void substituteTheCallerFrame(Map<String, Object> actualException,
                                                 Map<String, Object> expectedException,
                                                 String expectedFunction) {
        List<Object> frames = list(actualException.get("stacktrace"));
        assertThat(frames)
                .describedAs("captureMessage()/captureTestEvent() synthesise an exception and the stack "
                        + "has to be the CALLER's; an empty one means every frame was dropped")
                .isNotNull().hasSize(1);

        Map<String, Object> frame = map(frames.get(0));
        List<Object> expectedFrames = list(expectedException.get("stacktrace"));
        Map<String, Object> expectedFrame = map(expectedFrames.get(0));

        assertThat(List.copyOf(frame.keySet()))
                .describedAs("the frame's members and their order are part of the contract")
                .isEqualTo(List.copyOf(expectedFrame.keySet()));
        assertThat(frame.get("file"))
                .describedAs("the frame is the customer's file, not one of this SDK's")
                .isEqualTo("ConformanceCaller.java");
        assertThat(frame.get("function")).isEqualTo(expectedFunction);
        assertThat(frame.get("in_app"))
                .describedAs("a caller's own file is in-app; the fingerprint's promotion depends on it")
                .isEqualTo(Boolean.TRUE);
        assertThat((Integer) frame.get("line"))
                .describedAs("a real line number, not a placeholder").isPositive();

        actualException.put("stacktrace", expectedException.get("stacktrace"));
    }

    /**
     * The four members a Java SDK cannot reproduce from a fixture written for the PHP one. Every other
     * member — and the key order — is compared.
     */
    private static void substituteTheLanguageFacts(Map<String, Object> actual,
                                                   Map<String, Object> expected) {
        assertThat(actual.get("event_id")).asString().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(Instant.parse(String.valueOf(actual.get("timestamp")))).isNotNull();
        assertThat(actual.get("language")).isEqualTo("java");
        assertThat(map(actual.get("sdk")))
                .containsEntry("name", "anvil-feed-java")
                .containsEntry("runtime", "server")
                .containsKey("version");

        actual.put("event_id", expected.get("event_id"));
        actual.put("timestamp", expected.get("timestamp"));
        actual.put("language", expected.get("language"));
        actual.put("sdk", expected.get("sdk"));
    }

    @Test
    void theMessageEventIsReproducedFieldForField() throws Exception {
        List<Map<String, Object>> sent =
                onTheWire(goldenConfig(), client -> ConformanceCaller.reportTheReconciliation());
        assertThat(sent).hasSize(1);

        Map<String, Object> actual = new LinkedHashMap<>(sent.get(0));
        Map<String, Object> expected = fixture("valid/message-event.json");

        Map<String, Object> exception = new LinkedHashMap<>(map(actual.get("exception")));
        substituteTheCallerFrame(exception, map(expected.get("exception")), "reportTheReconciliation");
        actual.put("exception", exception);
        substituteTheLanguageFacts(actual, expected);

        assertThat(actual).isEqualTo(expected);
        assertThat(List.copyOf(actual.keySet()))
                .describedAs("key order: the cheapest way to notice two SDKs have drifted apart")
                .isEqualTo(List.copyOf(expected.keySet()));
    }

    @Test
    void theTestEventIsReproducedFieldForField() throws Exception {
        List<Map<String, Object>> sent =
                onTheWire(goldenConfig(), client -> ConformanceCaller.verifyTheIntegration());
        assertThat(sent).hasSize(1);

        Map<String, Object> actual = new LinkedHashMap<>(sent.get(0));
        Map<String, Object> expected = fixture("valid/test-event.json");

        Map<String, Object> exception = new LinkedHashMap<>(map(actual.get("exception")));
        substituteTheCallerFrame(exception, map(expected.get("exception")), "verifyTheIntegration");
        actual.put("exception", exception);
        substituteTheLanguageFacts(actual, expected);

        assertThat(actual).isEqualTo(expected);
        assertThat(List.copyOf(actual.keySet())).isEqualTo(List.copyOf(expected.keySet()));
        assertThat(map(actual.get("exception")).get("message"))
                .describedAs("FR-8 a: the sentence a customer reads in the cockpit is the shared one")
                .isEqualTo(map(expected.get("exception")).get("message"));
    }

    /**
     * {@code valid/minimal.json} is the smallest event the ingest accepts, and it is reproduced from a REAL
     * throw carrying the fixture's own message. Two members are language facts on top of the four above:
     * {@code exception.type} is the caller's class name, which in Java is package-qualified, and
     * {@code title} is composed from it. So the composition RULE is asserted from both sides instead —
     * the fixture's own title must equal its own type, a colon, a space and its own message, and so must
     * this SDK's.
     */
    @Test
    void theMinimalEventsEveryMandatoryFieldIsReproduced() throws Exception {
        List<Map<String, Object>> sent =
                onTheWire(goldenConfig(), client -> ConformanceCaller.reportTheMinimalFault());
        assertThat(sent).hasSize(1);

        Map<String, Object> actual = sent.get(0);
        Map<String, Object> expected = fixture("valid/minimal.json");
        Map<String, Object> expectedException = map(expected.get("exception"));
        Map<String, Object> actualException = map(actual.get("exception"));

        for (String shared : List.of("schema_version", "level", "environment", "service")) {
            assertThat(actual.get(shared)).describedAs(shared).isEqualTo(expected.get(shared));
        }
        assertThat(actualException.get("message"))
                .describedAs("the caught exception's message, verbatim")
                .isEqualTo("Cannot read property 'id' of undefined");
        assertThat(actualException.get("type"))
                .describedAs("the caller's own class; PHP's fixture spells the root-namespace form")
                .isEqualTo("shop.TypeError");
        assertThat(String.valueOf(expectedException.get("type")))
                .describedAs("both spellings are the same simple name")
                .isEqualTo("TypeError");

        String rule = expectedException.get("type") + ": " + actualException.get("message");
        assertThat(expected.get("title"))
                .describedAs("the fixture itself obeys title = type + \": \" + message")
                .isEqualTo(rule);
        assertThat(actual.get("title"))
                .describedAs("and so does this SDK, with its own spelling of the type")
                .isEqualTo(actualException.get("type") + ": " + actualException.get("message"));

        assertThat(actual.keySet())
                .describedAs("every member the smallest acceptable event names is present")
                .containsAll(expected.keySet());
        assertThat(List.copyOf(actual.keySet()).subList(0, expected.size()))
                .describedAs("and in the same order — this SDK adds nothing before them")
                .isEqualTo(List.copyOf(expected.keySet()));
    }

    /**
     * The server's own golden documents survive this SDK's canonical ordering byte for byte, including the
     * members no SDK builds ({@code enrichment}, {@code agent_context}). The reversed control is what makes
     * the assertion able to fail at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenDocuments")
    void aGoldenDocumentSurvivesTheSdksCanonicalOrdering(String name) throws Exception {
        Map<String, Object> document = fixture(name);

        assertThat(List.copyOf(FeedEvent.inCanonicalOrder(document).keySet()))
                .isEqualTo(List.copyOf(document.keySet()));

        List<String> reversed = new ArrayList<>(document.keySet());
        Collections.reverse(reversed);
        Map<String, Object> shuffled = new LinkedHashMap<>();
        reversed.forEach(key -> shuffled.put(key, document.get(key)));
        assertThat(List.copyOf(FeedEvent.inCanonicalOrder(shuffled).keySet()))
                .describedAs("the control: a document handed over in the wrong order comes back in the right"
                        + " one, so the assertion above is not true of every input")
                .isEqualTo(List.copyOf(document.keySet()));
    }

    static Stream<String> goldenDocuments() {
        return Stream.of("valid/section-3-example.json", "valid/enriched.json", "valid/minimal.json",
                "valid/message-event.json", "valid/test-event.json", "pii/expected.json");
    }

    // ================================================================= rejected/ + sdk-forbidden/

    /** A shape the ingest or the schema refuses, and the question "does this payload have it?". */
    record Refusal(String file, Predicate<Map<String, Object>> describes) {

        @Override
        public String toString() {
            return file;
        }
    }

    private static boolean blank(Object value) {
        return !(value instanceof String text) || text.isBlank();
    }

    static Stream<Refusal> refusedShapes() {
        return Stream.of(
                new Refusal("rejected/missing-event-id.json",
                        payload -> blank(payload.get("event_id"))),
                new Refusal("rejected/bad-event-id.json",
                        payload -> !String.valueOf(payload.get("event_id")).matches(
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                                        + "[0-9a-fA-F]{12}")),
                new Refusal("rejected/bad-environment.json",
                        payload -> !AnvilFeedConfig.ENVIRONMENT.matcher(
                                String.valueOf(payload.get("environment"))).matches()),
                new Refusal("rejected/missing-exception-type.json",
                        payload -> map(payload.get("exception")) == null),
                new Refusal("rejected/exception-without-type.json",
                        payload -> map(payload.get("exception")) != null
                                && blank(map(payload.get("exception")).get("type"))),
                new Refusal("rejected/unsupported-schema-version.json",
                        payload -> !"1.0".equals(payload.get("schema_version"))),
                new Refusal("rejected/bad-timestamp-impossible-date.json",
                        payload -> !parses(payload.get("timestamp"))),
                new Refusal("rejected/bad-timestamp-too-precise.json",
                        payload -> fractionalDigits(payload.get("timestamp")) > 9),
                new Refusal("rejected/whitespace-only-title.json",
                        payload -> blank(payload.get("title"))),

                new Refusal("sdk-forbidden/user-ip.json",
                        payload -> map(payload.get("user")) != null
                                && map(payload.get("user")).containsKey("ip")),
                new Refusal("sdk-forbidden/tag-user-ip-hash.json",
                        payload -> map(payload.get("tags")) != null
                                && map(payload.get("tags")).containsKey("user.ip_hash")),
                new Refusal("sdk-forbidden/level-info.json",
                        payload -> !List.of("error", "warning", "fatal").contains(payload.get("level"))),
                new Refusal("sdk-forbidden/request-url-file-scheme.json",
                        payload -> map(payload.get("request")) != null
                                && map(payload.get("request")).get("url") instanceof String url
                                && !url.toLowerCase(Locale.ROOT).matches("^https?://.*")),
                new Refusal("sdk-forbidden/missing-language.json",
                        payload -> blank(payload.get("language"))),
                new Refusal("sdk-forbidden/service-too-long.json",
                        payload -> String.valueOf(payload.get("service")).length()
                                > AnvilFeedConfig.MAX_SERVICE_CHARS),

                // The point of this one is not the server/schema gap: raw.json is the document that must
                // never be on the wire at all, and user.ip is what makes it that document.
                new Refusal("pii/raw.json",
                        payload -> map(payload.get("user")) != null
                                && map(payload.get("user")).containsKey("ip")));
    }

    private static boolean parses(Object timestamp) {
        try {
            Instant.parse(String.valueOf(timestamp));
            return true;
        } catch (RuntimeException notAnInstant) {
            return false;
        }
    }

    private static int fractionalDigits(Object timestamp) {
        String text = String.valueOf(timestamp);
        int dot = text.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        int end = dot + 1;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end - dot - 1;
    }

    /**
     * The hostile corpus: every entry is an INPUT a host application can really hand over, chosen because
     * it would produce one of the refused shapes if the guard that stops it were not there. Each entry
     * drives the public facade and every event it puts on the wire is held against every predicate.
     */
    record Hostile(String name, AnvilFeedConfig config, Consumer<AnvilFeedClient> drive) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Map<String, Object> tagsOf(int count, String value) {
        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.range(0, count).forEach(i -> tags.put("tag-" + i, value));
        return tags;
    }

    static List<Hostile> corpus() {
        List<Hostile> corpus = new ArrayList<>();

        corpus.add(new Hostile("forty tags, every value whitespace only", config(),
                client -> ConformanceCaller.report("tag flood", "warning",
                        Map.of("tags", tagsOf(40, "   ")))));

        corpus.add(new Hostile("a message that is nothing but whitespace", config(),
                client -> ConformanceCaller.report("   ", "warning", Map.of())));

        corpus.add(new Hostile("an exception with no message at all", config(),
                client -> ConformanceCaller.fail(new IllegalStateException(), Map.of())));

        corpus.add(new Hostile("a service name far over the cap",
                base().service("s".repeat(400)).build(FeedLogger.toSystemLogger()),
                client -> ConformanceCaller.report("oversized service", "warning", Map.of())));

        corpus.add(new Hostile("an environment a container handed over",
                base().environment("Prod_EU").build(FeedLogger.toSystemLogger()),
                client -> ConformanceCaller.report("bad environment", "warning", Map.of())));

        corpus.add(new Hostile("a request url with a scheme that is not http", config(),
                client -> ConformanceCaller.report("file scheme", "warning",
                        Map.of("request", Map.of("method", "GET", "url", "file:///etc/passwd")))));

        for (String level : List.of("info", "debug", "critical", "ERROR", "")) {
            corpus.add(new Hostile("a level the wire does not know: \"" + level + "\"", config(),
                    client -> ConformanceCaller.report("level " + level, level, Map.of())));
        }

        corpus.add(new Hostile("a caller-supplied copy of the ingest's own pseudonym", config(),
                client -> ConformanceCaller.report("pseudonym", "warning",
                        Map.of("tags", Map.of("user.ip_hash", "deadbeef", "tier", "gold")))));

        // The one public input that can put arbitrary members into a built event. What it adds goes
        // through finalise() a second time, which is what removes user.ip and tags["user.ip_hash"] again.
        corpus.add(new Hostile("a beforeSend hook that adds an address and a pseudonym", config(),
                client -> {
                    client.beforeSend(payload -> {
                        payload.put("user", new LinkedHashMap<>(Map.of(
                                "id", "u-4711", "ip", THE_ADDRESS, "ip_hash", "deadbeef")));
                        Map<String, Object> tags = new LinkedHashMap<>();
                        tags.put("user.ip_hash", "deadbeef");
                        tags.put("tier", "gold");
                        payload.put("tags", tags);
                        return payload;
                    });
                    ConformanceCaller.report("hooked address", "warning", Map.of());
                }));

        corpus.add(new Hostile("a beforeSend hook that rewrites every field this SDK owns", config(),
                client -> {
                    client.beforeSend(payload -> {
                        payload.put("event_id", "01J8ZQK3W9X2M4N6P8R0S2T4V6");
                        payload.put("schema_version", "0.1");
                        payload.put("timestamp", "2026-02-30T10:11:12Z");
                        payload.put("environment", "prod_eu");
                        payload.put("service", "s".repeat(400));
                        payload.put("title", "   ");
                        payload.put("level", "info");
                        return payload;
                    });
                    ConformanceCaller.report("hooked identity", "warning", Map.of());
                }));

        corpus.add(new Hostile("a beforeSend hook that adds secrets and a thirtieth tag", config(),
                client -> {
                    client.beforeSend(payload -> {
                        Map<String, Object> tags = tagsOf(29, "v");
                        tags.put("password", "hunter2");
                        tags.put("api_key", "sk-live-99887766");
                        payload.put("tags", tags);
                        payload.put("breadcrumbs", IntStream.rangeClosed(1, 40)
                                .mapToObj(i -> "step " + i + " email=ada@example.org").toList());
                        return payload;
                    });
                    ConformanceCaller.report("hooked secrets", "warning", Map.of());
                }));

        corpus.add(new Hostile("a beforeSend hook that throws", config(),
                client -> {
                    client.beforeSend(payload -> {
                        throw new IllegalStateException("host code");
                    });
                    ConformanceCaller.report("throwing hook", "warning", Map.of());
                }));

        corpus.add(new Hostile("captureException with no exception at all", config(),
                client -> ConformanceCaller.fail(null, Map.of())));

        corpus.add(new Hostile("a status code in every shape a filter hands one over in", config(),
                client -> {
                    for (Object status : List.of("12abc", "200.5", 200.5d, 404, "404", Boolean.TRUE)) {
                        Map<String, Object> request = new LinkedHashMap<>();
                        request.put("method", "GET");
                        request.put("url", "https://shop.example/checkout");
                        request.put("status_code", status);
                        ConformanceCaller.report("status " + status, "warning",
                                Map.of("request", request));
                    }
                }));

        corpus.add(new Hostile("a title and a message longer than every cap", config(),
                client -> ConformanceCaller.report("x".repeat(3000), "warning",
                        Map.of("tags", Map.of("long", "y".repeat(4000))))));

        // A credential in free text, in every breadcrumb: the key rule reads `token=...` inside a string
        // as readily as inside a map. (A raw ADDRESS in free text is a different question and is pinned
        // in aFreeTextAddressAndAnOrdinaryTagStillTravel(), because no rule claims to remove one.)
        corpus.add(new Hostile("forty breadcrumbs, each with a credential in free text", config(),
                client -> ConformanceCaller.report("breadcrumbs", "warning",
                        Map.of("breadcrumbs", IntStream.rangeClosed(1, 40)
                                .mapToObj(i -> "step " + i + " token=sk-live-" + i).toList()))));

        corpus.add(new Hostile("the whole pii fixture as a caller's context", config(),
                client -> drivePiiFixture()));

        return corpus;
    }

    /** Every event the corpus puts on the wire, entry by entry. */
    private static List<Map<String, Object>> corpusEvents() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Hostile entry : corpus()) {
            List<Map<String, Object>> sent = onTheWire(entry.config(), entry.drive());
            assertThat(sent)
                    .describedAs("%s: a corpus entry that sends nothing proves nothing", entry.name())
                    .isNotEmpty();
            events.addAll(sent);
        }
        return events;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedShapes")
    void theSdkNeverProducesARefusedShape(Refusal refusal) throws Exception {
        assertThat(refusal.describes().test(fixture(refusal.file())))
                .describedAs("%s: the predicate must be TRUE of the fixture itself, or it guards nothing",
                        refusal.file())
                .isTrue();

        for (Hostile entry : corpus()) {
            for (Map<String, Object> event : onTheWire(entry.config(), entry.drive())) {
                assertThat(refusal.describes().test(event))
                        .describedAs("the SDK produced the shape %s describes, capturing: %s%n%s",
                                refusal.file(), entry.name(), event)
                        .isFalse();
            }
        }
    }

    /** A fixture added to the index without a predicate here fails the run instead of being uncovered. */
    @Test
    void everyRefusedFixtureHasAPredicate() throws Exception {
        List<String> indexed = new ArrayList<>();
        for (JsonNode testCase : JSON.readTree(Files.readString(CONFORMANCE.resolve("cases.json")))) {
            if (!testCase.path("valid").asBoolean()) {
                indexed.add(testCase.path("file").asText());
            }
        }

        assertThat(refusedShapes().map(Refusal::file).sorted().toList())
                .describedAs("cases.json and this file must name the same refused fixtures")
                .isEqualTo(indexed.stream().sorted().toList());
    }

    /** Avoiding the forbidden shape is not enough: what replaces it must still be sendable. */
    @Test
    void everyEventOfTheHostileCorpusIsItselfSchemaValid() throws Exception {
        JsonSchema schema = schema();
        for (Map<String, Object> event : corpusEvents()) {
            assertThat(schema.validate(JSON.valueToTree(event)))
                    .describedAs("%s", event)
                    .isEmpty();
        }
    }

    /** Stated the other way round from the golden tests, so a fixture wrong in the same direction cannot hide. */
    @Test
    void noEventEverCarriesAFrameOfThisSdk() {
        for (Map<String, Object> event : corpusEvents()) {
            List<Object> frames = list(map(event.get("exception")).get("stacktrace"));
            if (frames == null) {
                continue;
            }
            for (Object raw : frames) {
                Map<String, Object> frame = map(raw);
                assertThat(OUR_OWN_FILES)
                        .describedAs("a frame of the reporting library in a customer's incident: %s", frame)
                        .doesNotContain(String.valueOf(frame.get("file")));
            }
        }
    }

    /**
     * The review's point: only the pseudonym half was ever checked. The LITERAL address, everywhere — over
     * every route this SDK claims to redact, which is {@code user.ip}, {@code user.ip_hash} and a
     * {@code user.ip_hash} TAG, by the public API and by a {@code beforeSend} hook alike.
     *
     * <p>It is not "every route at all": see
     * {@link #aFreeTextAddressAndAnOrdinaryTagStillTravel()} for the two the contract does not cover, in
     * either language.
     */
    @Test
    void anAddressNeverReachesTheWireByAnyRouteThisSdkRedacts() throws Exception {
        for (Hostile entry : corpus()) {
            for (Map<String, Object> event : onTheWire(entry.config(), entry.drive())) {
                assertThat(JSON.writeValueAsString(event))
                        .describedAs("a caller-supplied address reached the wire, capturing: %s",
                                entry.name())
                        .doesNotContain(THE_ADDRESS);
            }
        }
    }

    // ================================================================= pii/

    /** {@code pii/raw.json}'s own members, handed over the way a caller hands over a context. */
    private static void drivePiiFixture() {
        Map<String, Object> raw;
        try {
            raw = fixture("pii/raw.json");
        } catch (Exception unreadable) {
            throw new AssertionError("pii/raw.json is unreadable", unreadable);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(map(raw.get("request"))));
        context.put("user_id", map(raw.get("user")).get("id"));
        context.put("breadcrumbs", new ArrayList<>(list(raw.get("breadcrumbs"))));
        context.put("tags", new LinkedHashMap<>(map(raw.get("tags"))));
        ConformanceCaller.reportTheAuthFailure(
                String.valueOf(map(raw.get("exception")).get("message")), context);
    }

    /**
     * The claim {@code conformance/README.md} says each SDK makes twice: once on the scrubber
     * ({@link ScrubberTest#scrubsTheConformancePair()}) and once THROUGH the public capture API, because a
     * denylist that stopped reaching the client would leave the first one green.
     */
    @Test
    void theScrubberContractHoldsThroughThePublicCaptureApi() throws Exception {
        List<Map<String, Object>> sent = onTheWire(config(), client -> drivePiiFixture());
        assertThat(sent).hasSize(1);
        Map<String, Object> actual = sent.get(0);
        Map<String, Object> expected = fixture("pii/expected.json");

        assertThat(map(actual.get("exception")).get("message"))
                .isEqualTo(map(expected.get("exception")).get("message"));
        assertThat(map(actual.get("request"))).isEqualTo(map(expected.get("request")));
        assertThat(map(actual.get("user"))).isEqualTo(map(expected.get("user")));
        assertThat(list(actual.get("breadcrumbs"))).isEqualTo(list(expected.get("breadcrumbs")));
        assertThat(map(actual.get("tags"))).isEqualTo(map(expected.get("tags")));
        assertThat(List.copyOf(map(actual.get("tags")).keySet()))
                .describedAs("key order inside tags is part of the pair too")
                .isEqualTo(List.copyOf(map(expected.get("tags")).keySet()));

        String wire = JSON.writeValueAsString(actual);
        for (String secret : PLANTED_SECRETS) {
            assertThat(wire).describedAs("a planted secret survived: %s", secret).doesNotContain(secret);
        }
    }

    /**
     * The second pass a {@code beforeSend} hook's output takes must be a no-op on an event the SDK already
     * built: scrubbing is idempotent, the bounds are idempotent and the level coercion is. A hook that
     * hands the payload straight back therefore changes nothing at all — and if it ever did, every hooked
     * event in production would be a slightly different document from every unhooked one.
     */
    @Test
    void aHookThatChangesNothingChangesNothingOnTheWire() {
        Map<String, Object> plain = onTheWire(config(), client -> drivePiiFixture()).get(0);
        Map<String, Object> hooked = onTheWire(config(), client -> {
            client.beforeSend(payload -> payload);
            drivePiiFixture();
        }).get(0);

        Map<String, Object> comparable = new LinkedHashMap<>(hooked);
        comparable.put("event_id", plain.get("event_id"));
        comparable.put("timestamp", plain.get("timestamp"));
        Map<String, Object> exception = new LinkedHashMap<>(map(comparable.get("exception")));
        exception.put("stacktrace", map(plain.get("exception")).get("stacktrace"));
        comparable.put("exception", exception);

        assertThat(comparable).isEqualTo(plain);
        assertThat(List.copyOf(comparable.keySet())).isEqualTo(List.copyOf(plain.keySet()));
    }

    /**
     * A hook that breaks what the ingest requires is the one case where the event is dropped HERE rather
     * than posted for a 400 nobody can read. It is its own test because a corpus entry that sends nothing
     * would make every predicate vacuous for it.
     */
    @Test
    void anEventAHookMadeUnsendableIsDroppedWithOneLine() {
        RecordingLogger written = new RecordingLogger();
        List<Map<String, Object>> sent = onTheWire(config(), written, client -> {
            client.beforeSend(payload -> {
                payload.remove("language");
                return payload;
            });
            ConformanceCaller.report("unsendable", "warning", Map.of());
        });

        assertThat(sent).describedAs("the ingest would answer 400; the loss is named here instead").isEmpty();
        assertThat(written.matching("language")).isNotEmpty();
    }

    @Test
    void thePlantedSecretsAreReallyInTheRawFixture() throws Exception {
        String raw = Files.readString(CONFORMANCE.resolve("pii/raw.json"));
        for (String secret : PLANTED_SECRETS) {
            assertThat(raw)
                    .describedAs("a secret this suite claims to plant is not in the fixture any more: %s",
                            secret)
                    .contains(secret);
        }
        assertThat(raw).contains(THE_ADDRESS);
    }

    /**
     * The one FR-5 input that is not a caller's argument: the source line an in-app frame carries. It is
     * proved against {@code shop/SecretHolder.java}, a file written for it, because the context window is
     * whatever happens to be around the throw site.
     */
    @Test
    void contextCodeIsScrubbedBeforeItLeavesTheProcess() {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("disponio-api").environment("production")
                .maxFrames(1).contextLines(4)
                .build(FeedLogger.toSystemLogger());

        List<Map<String, Object>> sent =
                onTheWire(config, client -> SecretHolder.failWithACredentialInScope());
        assertThat(sent).hasSize(1);

        Map<String, Object> frame = map(list(map(sent.get(0).get("exception")).get("stacktrace")).get(0));
        assertThat(frame.get("file")).isEqualTo("SecretHolder.java");
        assertThat(String.valueOf(frame.get("context_code")))
                .describedAs("the context window has to have been read at all, or this test proves nothing")
                .contains("api_key");
        assertThat(String.valueOf(frame.get("context_code")))
                .describedAs("a credential in the customer's own source reached the wire")
                .doesNotContain("sk-live-99887766")
                .contains(Scrubber.FILTERED);
    }

    /**
     * <b>An open contract question, pinned rather than fixed.</b> FR-5 c removes {@code user.ip},
     * {@code user.ip_hash} and a {@code tags["user.ip_hash"]} outright, and the denylist redacts a value by
     * its KEY — but {@code tags["ip"]} is neither: the key is not one of the nine terms and the value is
     * just a string. So a caller who puts {@code $request->ip()} into an ordinary tag sends an address,
     * today, in BOTH languages ({@code php/anvil-feed-php} behaves identically; PHP's own task-4 review
     * raised this and did not invent a fixture for it either).
     *
     * <p>This test asserts what happens rather than what should happen, so that the answer — whichever way
     * the spec owners decide it — cannot be reached by accident. If it is a leak, {@code conformance/}
     * needs a {@code sdk-forbidden/} fixture and both SDKs need the rule; if it is not, this test is the
     * place that says so. Task 9 does not decide it: {@code conformance/} may only be made STRICTER by
     * this task, and adding a fixture here would decide it for the server too.
     */
    @Test
    void aFreeTextAddressAndAnOrdinaryTagStillTravel() throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tags", Map.of("ip", THE_ADDRESS));
        context.put("breadcrumbs", List.of("request from " + THE_ADDRESS));

        List<Map<String, Object>> sent = onTheWire(config(), client ->
                ConformanceCaller.report("an address nothing claims to remove", "warning", context));

        assertThat(map(sent.get(0).get("tags")))
                .describedAs("if this ever becomes [Filtered], the contract changed and conformance/ and "
                        + "the PHP SDK have to change with it")
                .containsEntry("ip", THE_ADDRESS);
        assertThat(list(sent.get(0).get("breadcrumbs")))
                .describedAs("the same question in free text: the key rule needs a denylisted KEY and a "
                        + "separator, and a sentence with an address in it has neither")
                .containsExactly("request from " + THE_ADDRESS);
        assertThat(map(sent.get(0).get("user")))
                .describedAs("the routes the contract DOES cover are still shut")
                .isNull();
    }
}
