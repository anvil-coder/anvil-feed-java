package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * C17: identical names, identical values in both languages. The table below is the one in the PHP
     * SDK's {@code Config} and in the report's option table; Task 9 replaces it with a comparison against
     * {@code conformance/config-defaults.json}, which is a shared file and not this task's to create.
     */
    @Test
    void everyDefaultIsTheOneBothLanguagesAgreeOn() {
        AnvilFeedConfig config = usable().build(logger);

        assertEquals("https://app.anvil-coder.tech/api/feed", config.endpoint());
        assertEquals("production", config.environment());
        assertEquals(1000, config.maxQueueSize());
        assertEquals(2000, config.flushIntervalMs());
        assertEquals(50, config.batchSize());
        assertEquals(1000, config.connectTimeoutMs());
        assertEquals(2000, config.requestTimeoutMs());
        assertEquals(50, config.maxFrames());
        assertEquals(20, config.contextLines());
        assertEquals(400, config.maxContextLineChars());
        assertEquals(List.of("password", "token", "secret", "authorization", "api_key", "cookie", "email",
                "credit_card", "iban"), config.denylist());
        assertEquals(10, config.fingerprintCap());
        assertEquals(60_000, config.fingerprintWindowMs());
        assertEquals(List.of("/api/feed/**"), config.ignoredRequestPaths());
        assertTrue(config.enabled());
        // token, service and release have NO default. A token that defaults to anything is a token
        // somebody ships.
        assertEquals(null, config.release());
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
        assertEquals(AnvilFeedConfig.DEFAULT_DENYLIST, config.denylist());
        assertEquals(List.of("x"), config.ignoredRequestPaths());
        assertEquals("production", config.environment());
    }

    /** An empty denylist is a PII hole, so it is refused; "ignore no request path" is a real intent. */
    @Test
    void anEmptyDenylistIsRefusedAndAnEmptyIgnoreListIsHonoured() {
        AnvilFeedConfig config = usable().denylist(List.of()).ignoredRequestPaths(List.of()).build(logger);

        assertEquals(AnvilFeedConfig.DEFAULT_DENYLIST, config.denylist());
        assertEquals(List.of(), config.ignoredRequestPaths());
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
