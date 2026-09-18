package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import shop.Storefront;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code conformance/fingerprint/vectors.json} reached the way the CLIENT reaches it — through
 * {@link Fingerprint#ofEvent(Map)} on a whole event payload, which is the one call
 * {@code AnvilFeedClient.capture} makes — and then the local storm cap held to the identity that call
 * produces.
 *
 * <p>{@link FingerprintTest} already holds the rule itself against the server's own class on the vectors
 * and on ten thousand generated inputs. What is added here is the two steps between that rule and a
 * customer: that a whole EVENT payload (with the members the rule must ignore actually present — a line
 * number, an exception message, a {@code context_code}) yields the vector's digest, and that the cap the
 * public API charges is charged against exactly that digest and not against something else that happens
 * to be stable.
 *
 * <p>An unqualified {@code exception.type} like the vectors' {@code TypeError} cannot come out of a Java
 * {@code captureException} — {@code getClass().getName()} is always package-qualified — so the vectors are
 * driven as payloads and the end-to-end half uses real Java faults. That is a language fact and it is why
 * the second half asserts the RELATION (one site, one identity; two sites, two) rather than a literal
 * digest.
 */
class FingerprintConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VECTORS =
            Path.of(System.getProperty("anvilfeed.conformance")).resolve("fingerprint/vectors.json");

    @AfterEach
    void takeTheFacadeDown() {
        AnvilFeed.reset();
    }

    record Vector(String name, JsonNode node) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> payloadVectors() throws Exception {
        List<Vector> vectors = new ArrayList<>();
        for (JsonNode vector : JSON.readTree(Files.readString(VECTORS)).path("payload_vectors")) {
            vectors.add(new Vector(vector.path("name").asText(), vector));
        }
        assertThat(vectors).hasSizeGreaterThanOrEqualTo(6);
        return vectors;
    }

    static List<Vector> normalizeVectors() throws Exception {
        List<Vector> vectors = new ArrayList<>();
        for (JsonNode vector : JSON.readTree(Files.readString(VECTORS)).path("normalize_file_vectors")) {
            String input = vector.path("input").asText("(null)");
            vectors.add(new Vector(input.isEmpty() ? "(the empty path)" : input, vector));
        }
        assertThat(vectors).hasSizeGreaterThanOrEqualTo(9);
        return vectors;
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The vector assembled into a whole event payload — the shape {@code Fingerprint.ofEvent} is handed by
     * the client — with a line number, a message and a {@code context_code} on every frame, none of which
     * is part of the identity.
     */
    private static Map<String, Object> eventOf(JsonNode vector, int lineSeed) {
        List<Map<String, Object>> frames = new ArrayList<>();
        int line = lineSeed;
        for (JsonNode frame : vector.path("frames")) {
            Map<String, Object> asMap = new LinkedHashMap<>();
            if (!frame.path("file").isNull() && frame.has("file")) {
                asMap.put("file", frame.path("file").asText());
            }
            asMap.put("line", line += 7);
            if (!frame.path("function").isNull() && frame.has("function")) {
                asMap.put("function", frame.path("function").asText());
            }
            asMap.put("in_app", frame.path("in_app").asBoolean(false));
            asMap.put("context_code", "int x = " + line + ";");
            frames.add(asMap);
        }

        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", vector.path("exception_type").asText());
        exception.put("message", "a message that is deliberately not part of the identity " + lineSeed);
        if (!frames.isEmpty()) {
            exception.put("stacktrace", frames);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "1.0");
        payload.put("event_id", "9b1c7f2e-5a34-4c18-8e0d-1f7a2b3c4d5e");
        payload.put("title", vector.path("title").asText());
        payload.put("level", "error");
        payload.put("environment", "production");
        payload.put("service", vector.path("service").asText());
        payload.put("language", "java");
        payload.put("exception", exception);
        return payload;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadVectors")
    void aVectorIsReproducedFromAWholeEventPayload(Vector vector) throws Exception {
        String expectedPayload = vector.node().path("expected_payload").asText();

        assertThat(Fingerprint.payload(
                vector.node().path("service").asText(),
                vector.node().path("exception_type").asText(),
                vector.node().path("title").asText(),
                framesOf(vector.node())))
                .describedAs("the pre-hash string is the contract; the digest is only its consequence")
                .isEqualTo(expectedPayload);

        assertThat(Fingerprint.ofEvent(eventOf(vector.node(), 100)))
                .describedAs("the whole-payload call the client makes must reach the same identity")
                .isEqualTo(sha256Hex(expectedPayload));
    }

    /** Line numbers and the exception message are deliberately absent from the identity. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadVectors")
    void neitherTheLineNumbersNorTheMessageArePartOfTheIdentity(Vector vector) {
        assertThat(Fingerprint.ofEvent(eventOf(vector.node(), 100)))
                .describedAs("a redeploy that moved every line would otherwise open a new incident for "
                        + "every fault in the process")
                .isEqualTo(Fingerprint.ofEvent(eventOf(vector.node(), 9_000)));
    }

    private static List<Map<String, Object>> framesOf(JsonNode vector) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (JsonNode frame : vector.path("frames")) {
            Map<String, Object> asMap = new LinkedHashMap<>();
            asMap.put("file", frame.path("file").asText(null));
            asMap.put("function", frame.path("function").asText(null));
            asMap.put("in_app", frame.path("in_app").asBoolean(false));
            frames.add(asMap);
        }
        return frames;
    }

    /** Each path rule reached through {@code exception.stacktrace[0].file} of a whole event. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("normalizeVectors")
    void aNormalizeFileVectorIsReachedThroughAFrameOfAnEvent(Vector vector) throws Exception {
        String input = vector.node().path("input").isNull() ? null : vector.node().path("input").asText();
        String expected = vector.node().path("expected").isNull()
                ? null : vector.node().path("expected").asText();

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("file", input);
        frame.put("line", 12);
        frame.put("function", "handle");
        frame.put("in_app", true);
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", "java.lang.IllegalStateException");
        exception.put("stacktrace", List.of(frame));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", "shop-api");
        payload.put("title", "Payment failed");
        payload.put("exception", exception);

        // The expected payload is composed here from the format the vectors' own note states, so the
        // assertion does not run through the implementation it is checking.
        String field = (expected == null ? "" : expected) + "#handle";
        String expectedPayload = "incident-fingerprint/v1"
                + "" + "6:frames"
                + "" + "8:shop-api"
                + "" + "31:java.lang.IllegalStateException"
                + "" + field.length() + ":" + field;

        assertThat(Fingerprint.ofEvent(payload)).isEqualTo(sha256Hex(expectedPayload));
    }

    // ================================================================= the cap, through the public API

    private static AnvilFeedConfig config(int cap) {
        return AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api").environment("production").contextLines(0)
                .fingerprintCap(cap).fingerprintWindowMs(60_000)
                .build(FeedLogger.toSystemLogger());
    }

    /**
     * The storm cap is charged against the identity of the event that was QUEUED — the log line names the
     * first twelve characters of exactly that fingerprint, not of something else that happens to be
     * stable for the run.
     */
    @Test
    void aStormIsCappedOnTheFingerprintOfTheEventThatWasQueued() {
        RecordingLogger written = new RecordingLogger();
        RecordingTransport transport = new RecordingTransport();
        try (AnvilFeedClient client = AnvilFeedClient.create(config(3), transport, written.logger(),
                GitContext.none())) {
            AnvilFeed.init(client);

            String first = Storefront.reportFromCheckout(client, "the same fault");
            for (int i = 0; i < 20; i++) {
                Storefront.reportFromCheckout(client, "the same fault");
            }

            List<FeedEvent> queued = client.queue().take(100);
            assertThat(queued).describedAs("exactly the cap is admitted").hasSize(3);
            assertThat(queued.get(0).id()).isEqualTo(first);

            String identity = Fingerprint.ofEvent(queued.get(0).payload());
            assertThat(written.matching(identity.substring(0, 12)))
                    .describedAs("the suppression line names the identity of the queued event")
                    .hasSize(1);
        } finally {
            AnvilFeed.reset();
        }
    }

    /** A different fault is never silenced by another's storm — which is what makes the cap usable. */
    @Test
    void aDifferentFaultIsNotSilencedByAnothersStorm() {
        RecordingTransport transport = new RecordingTransport();
        try (AnvilFeedClient client = AnvilFeedClient.create(config(2), transport,
                new RecordingLogger().logger(), GitContext.none())) {
            AnvilFeed.init(client);

            for (int i = 0; i < 20; i++) {
                Storefront.reportFromCheckout(client, "the hot fault");
            }
            assertThat(Storefront.reportFromShipping(client, "a different site"))
                    .describedAs("a second call site is a second identity and has its own budget")
                    .isNotNull();
        } finally {
            AnvilFeed.reset();
        }
    }

    /**
     * The gate's table holds 500 entries. A storming fingerprint must survive a churn of MORE distinct
     * faults than that: under an eviction rule that drops the oldest entry outright, the storming one is
     * the oldest and is evicted first, so the storm becomes reportable again with a fresh count — the
     * exact defect PHP's review found and the reason this is asserted through the public API.
     */
    @Test
    void aStormingFingerprintSurvivesAChurnOfMoreDistinctFaultsThanTheGateCanHold() {
        RecordingTransport transport = new RecordingTransport();
        try (AnvilFeedClient client = AnvilFeedClient.create(config(3), transport,
                new RecordingLogger().logger(), GitContext.none())) {
            AnvilFeed.init(client);

            for (int i = 0; i < 10; i++) {
                Storefront.reportFromCheckout(client, "the hot fault");
            }
            assertThat(Storefront.reportFromCheckout(client, "the hot fault"))
                    .describedAs("over the cap already").isNull();

            for (int i = 0; i < 520; i++) {
                // Every churn fault is a distinct identity, because the identity is (service, type, top
                // frames) and a fabricated stack is the only lever a test has over the frames.
                IllegalStateException fault = new IllegalStateException("churn " + i);
                fault.setStackTrace(new StackTraceElement[]{
                        new StackTraceElement("shop.Churn" + i, "run", "Churn" + i + ".java", 10)});
                client.captureException(fault);
            }

            assertThat(Storefront.reportFromCheckout(client, "the hot fault"))
                    .describedAs("the storming fingerprint was evicted by the churn and walked straight "
                            + "back through with a fresh count")
                    .isNull();
        } finally {
            AnvilFeed.reset();
        }
    }

    /** Frame order is significant, and the SDK's own events are the proof. */
    @Test
    void twoCallSitesAreTwoIdentitiesAndOneSiteIsOneIdentity() {
        RecordingTransport transport = new RecordingTransport();
        try (AnvilFeedClient client = AnvilFeedClient.create(config(1000), transport,
                new RecordingLogger().logger(), GitContext.none())) {
            AnvilFeed.init(client);

            Storefront.reportFromCheckout(client, "one");
            Storefront.reportFromCheckout(client, "two — a different message, the same site");
            Storefront.reportFromShipping(client, "three — another site");

            List<FeedEvent> queued = client.queue().take(3);
            String one = Fingerprint.ofEvent(queued.get(0).payload());
            String two = Fingerprint.ofEvent(queued.get(1).payload());
            String three = Fingerprint.ofEvent(queued.get(2).payload());

            assertThat(two).describedAs("the message is not part of the identity").isEqualTo(one);
            assertThat(three).describedAs("a different call site is a different fault").isNotEqualTo(one);
        } finally {
            AnvilFeed.reset();
        }
    }
}
