package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilFeedConfigTest {

    private final List<String> said = new ArrayList<>();
    private final FeedLogger logger = FeedLogger.delegatingTo((level, message) -> said.add(level + ": " + message));

    private AnvilFeedConfig.Builder usable() {
        return AnvilFeedConfig.builder().token("anv_srv_0123456789abcdef").service("shop-api");
    }

    /**
     * C17's acceptance criterion is that there is exactly ONE table. It lives in
     * {@code conformance/config-defaults.json} and both SDKs are compared against it, key by key, so a
     * default that drifts in one language fails here rather than in a customer's cockpit (review
     * finding 8). The file is read from the repository root through the property the build wires up —
     * there is no copy under {@code java/}.
     *
     * <p>This overlaps {@link ConfigParityTest#everyDefaultMatchesTheSharedList()}, which came later and
     * holds the same claim in BOTH directions. The overlap is left standing rather than resolved here:
     * this test also pins that {@code release} has no default and that the three usable values are enough
     * to switch the SDK on, and deleting a reviewed test to remove a duplication is a decision for
     * whoever consolidates the two, not a side effect of adding a block to the file.
     */
    @Test
    void everyDefaultIsTheOneBothLanguagesAgreeOn() throws Exception {
        Path fixture = Path.of(System.getProperty("anvilfeed.conformance")).resolve("config-defaults.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> expected =
                new ObjectMapper().readValue(Files.readString(fixture), LinkedHashMap.class);
        // The keys of that file that are not parameters. The list is ConfigParityTest's, deliberately:
        // this test and that one read the same file and must agree about what counts as a parameter, and
        // a second copy of the list here is a second place to forget when the file grows a block.
        ConfigParityTest.NOT_A_PARAMETER.forEach(expected::remove);

        AnvilFeedConfig config = usable().build(logger);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("endpoint", config.endpoint());
        actual.put("environment", config.environment());
        actual.put("max_queue_size", config.maxQueueSize());
        actual.put("flush_interval_ms", config.flushIntervalMs());
        actual.put("batch_size", config.batchSize());
        actual.put("connect_timeout_ms", config.connectTimeoutMs());
        actual.put("request_timeout_ms", config.requestTimeoutMs());
        actual.put("max_frames", config.maxFrames());
        actual.put("context_lines", config.contextLines());
        actual.put("max_context_line_chars", config.maxContextLineChars());
        actual.put("denylist", config.denylist());
        actual.put("fingerprint_cap", config.fingerprintCap());
        actual.put("fingerprint_window_ms", config.fingerprintWindowMs());
        actual.put("ignored_request_paths", config.ignoredRequestPaths());
        // null, and null is the VALUE the contract names: it means "discovered at init", and a discovered
        // absolute path is a property of the machine rather than of the contract.
        actual.put("project_root", config.projectRoot());

        assertEquals(expected, actual);
        // A key the fixture grows and this test does not know about is a drift too.
        assertEquals(expected.keySet(), actual.keySet());
        // token, service and release have NO default. A token that defaults to anything is a token
        // somebody ships.
        assertEquals(null, config.release());
        assertTrue(config.enabled());
    }

    @Test
    void theUrlsAreBuiltFromTheEndpointWithoutADoubleSlash() {
        AnvilFeedConfig config = usable().endpoint("https://ingest.example/api/feed/").build(logger);

        assertEquals("https://ingest.example/api/feed/events", config.eventsUrl());
        assertEquals("https://ingest.example/api/feed/events/batch", config.batchUrl());
    }

    @Test
    void aMissingTokenDisablesTheSdkWithOneLine() {
        AnvilFeedConfig config = AnvilFeedConfig.builder().service("shop-api").build(logger);

        assertFalse(config.enabled());
        assertThat(said).singleElement().asString().contains("ANVIL_INGEST_TOKEN");
    }

    @Test
    void aMissingServiceDisablesTheSdkAndIsNeverInvented() {
        AnvilFeedConfig config = AnvilFeedConfig.builder().token("anv_srv_x").build(logger);

        assertFalse(config.enabled());
        assertEquals("unknown-service", config.service());
        assertThat(said).singleElement().asString().contains("no service name configured");
    }

    /** Check enabled(), never the token: here the token is perfectly present. */
    @Test
    void anEnvironmentThatCannotBeRepairedDisablesTheSdkWithTheRuleNotTheSymptom() {
        AnvilFeedConfig config = usable().environment("...").build(logger);

        assertFalse(config.enabled());
        assertEquals("production", config.environment());
        assertThat(config.token()).isNotNull();
        assertThat(said).singleElement().asString()
                .contains("must start with a lower-case letter");
    }

    @Test
    void anEnvironmentTheIngestWouldRefuseIsRepairedAndSaidOnce() {
        AnvilFeedConfig config = usable().environment("  Prod_EU  ").build(logger);

        assertEquals("prod-eu", config.environment());
        assertTrue(config.enabled());
        assertThat(said).singleElement().asString().contains("was sent as \"prod-eu\"");
    }

    @Test
    void anOverLongServiceNameIsBoundedToWhatTheSchemaAllows() {
        AnvilFeedConfig config = usable().service("s".repeat(200)).build(logger);

        assertEquals(AnvilFeedConfig.MAX_SERVICE_CHARS, config.service().length());
        assertTrue(config.enabled(), "a long name is a rewrite, not a reason to send nothing");
        assertThat(said).singleElement().asString().contains("longer than 128");
    }

    @Test
    void everyKnobIsClampedToTheRangeTheContractAllows() {
        AnvilFeedConfig config = usable()
                .maxQueueSize(0).batchSize(9_000).maxFrames(9_000).contextLines(9_000)
                .maxContextLineChars(0).build(logger);

        assertEquals(1, config.maxQueueSize());
        assertEquals(50, config.batchSize());
        assertEquals(50, config.maxFrames());
        assertEquals(20, config.contextLines());
        assertEquals(1, config.maxContextLineChars());
        assertThat(said).anySatisfy(line -> assertThat(line).contains("max_queue_size"));
    }

    @Test
    void anExplicitlyDisabledSdkStaysDisabledEvenWhenEverythingElseIsRight() {
        assertFalse(usable().enabled(false).build(logger).enabled());
    }

    /** FR-2 b: nothing a host can put in here reaches the host back as an exception. */
    @Test
    void aConfigurationOfPureRubbishStillProducesAConfig() {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .endpoint(null).token(null).service("  ").environment("")
                .release(" ").denylist(Arrays.asList(null, "  ")).ignoredRequestPaths(Arrays.asList(null, "x"))
                .build(null);

        assertFalse(config.enabled());
        assertEquals("https://app.anvil-coder.tech/api/feed", config.endpoint());
        // A list of nothing but nulls and blanks IS an empty list, and an empty list is honoured.
        assertEquals(List.of(), config.denylist());
        assertEquals(List.of("x"), config.ignoredRequestPaths());
        assertEquals("production", config.environment());
    }

    /**
     * PHP's {@code listOption} honours an explicit empty list and so does this (review finding 7) — but
     * switching FR-5's key rule off is not something anybody may discover from a cockpit full of secrets,
     * so it is said once.
     */
    @Test
    void anEmptyDenylistIsHonouredAndAnnounced() {
        AnvilFeedConfig config = usable().denylist(List.of()).ignoredRequestPaths(List.of()).build(logger);

        assertEquals(List.of(), config.denylist());
        assertEquals(List.of(), config.ignoredRequestPaths());
        assertThat(said).singleElement().asString()
                .contains("the denylist is empty").contains("Bearer tokens");
    }

    /** null is "not configured" and is not the same statement as an empty list. */
    @Test
    void aNullDenylistKeepsTheDefaultAndSaysNothing() {
        AnvilFeedConfig config = usable().denylist(null).build(logger);

        assertEquals(AnvilFeedConfig.DEFAULT_DENYLIST, config.denylist());
        assertThat(said).isEmpty();
    }

    /**
     * {@code connect_timeout_ms = 0} reads to an operator like "no timeout" and is an
     * IllegalArgumentException out of {@code HttpClient.Builder.connectTimeout} on the first send;
     * a zero flush interval is a busy loop (review finding 11).
     */
    @Test
    void theKnobsTaskSevenWouldChokeOnAreClampedTooAndSaidOnce() {
        AnvilFeedConfig config = usable()
                .flushIntervalMs(0).connectTimeoutMs(0).requestTimeoutMs(-1)
                .fingerprintCap(-5).fingerprintWindowMs(-1)
                .build(logger);

        assertEquals(1, config.flushIntervalMs());
        assertEquals(1, config.connectTimeoutMs());
        assertEquals(1, config.requestTimeoutMs());
        assertEquals(0, config.fingerprintCap());
        assertEquals(0, config.fingerprintWindowMs());
        assertThat(said).hasSize(5);
    }

    @Test
    void readingTheEnvironmentNeverThrowsIntoTheHost() {
        assertThat(AnvilFeedConfig.fromEnvironment(logger)).isNotNull();
    }

    @Test
    void aBuiltConfigurationCannotBeChangedThroughTheListTheCallerKept() {
        List<String> mine = new ArrayList<>(List.of("password"));
        AnvilFeedConfig config = usable().denylist(mine).build(logger);
        mine.add("tenant");

        assertEquals(List.of("password"), config.denylist());
    }
}
