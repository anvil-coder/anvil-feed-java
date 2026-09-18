package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C9 and C17: identical parameter names, identical defaults and identical BEHAVIOUR in both languages.
 * A drift here fails the build.
 *
 * <p>The first test is the shared list itself ({@code conformance/config-defaults.json}) held against this
 * SDK's effective defaults, and it fails when the file gains a key nothing here reads — a parameter that
 * exists in the list and in neither language's test is a parameter nobody is holding to anything.
 *
 * <p>Everything after it is a RULE rather than a value, and every one of those rules was written down
 * somewhere before it was written here: the ledger of the PHP parity round
 * ({@code .superpowers/sdd/2026-09-17-sdk-v0.1/php-parity-fix-report.md}), the task-6 and task-7 reports,
 * and {@code conformance/README.md}. The Java side of each is pinned here so a later change in either
 * language has something to be red against. What PHP still owes is named in the task-9 report, not
 * silently fixed from here: {@code php/} is read-only for this task.
 */
class ConfigParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));

    private static AnvilFeedConfig.Builder base() {
        return AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api");
    }

    private static EventFactory factory(AnvilFeedConfig config) {
        return new EventFactory(config, new Scrubber(config.denylist()), GitContext.none());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    // ================================================================= the shared list

    /**
     * Every key of {@code config-defaults.json}, compared to what this SDK's builder produces with nothing
     * configured but the two parameters that HAVE no default ({@code token}, {@code service}) — which is
     * why those two are absent from the shared list.
     */
    @Test
    void everyDefaultMatchesTheSharedList() throws Exception {
        JsonNode expected = JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json")));
        AnvilFeedConfig config = base().build(FeedLogger.toSystemLogger());

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

        List<String> listed = sharedListKeys();

        assertThat(actual.keySet())
                .describedAs("a parameter in the shared list that this test does not read is a parameter "
                        + "nobody is holding either language to")
                .containsExactlyInAnyOrderElementsOf(listed);

        for (Map.Entry<String, Object> entry : actual.entrySet()) {
            JsonNode wanted = expected.path(entry.getKey());
            Object value = wanted.isArray() ? strings(wanted)
                    : wanted.isNumber() ? (Object) wanted.asInt() : wanted.asText();
            assertThat(entry.getValue()).describedAs(entry.getKey()).isEqualTo(value);
        }
    }

    /**
     * The components of {@link AnvilFeedConfig} that deliberately have no entry in the shared list, each
     * with the reason it has none. Adding a name here is how a knob LEAVES the parity contract, and it has
     * to be a decision somebody wrote down rather than a silent omission. The first four are the same four
     * PHP's twin exempts, spelled the same way.
     *
     * <p>The fifth, {@code in_app_packages}, is the exception that has to be read rather than copied. It
     * is a real knob with a real default (the empty list) and it BELONGS in the shared list the day PHP
     * grows a namespace equivalent — task 9's handover says so. It is exempt today because the shared list
     * is a two-language contract: adding the key to {@code conformance/config-defaults.json} makes PHP's
     * {@code testEveryDefaultMatchesTheSharedList} red for a parameter PHP does not have, and the Spring
     * starter (task 10) needs the knob now. The exemption is therefore the DECISION "Java first, PHP next"
     * written down where it is read, and not a silent omission. Since task 11 the same decision is also in
     * {@code conformance/config-defaults.json}'s {@code language_specific} block, where the other language
     * and a human reader find it, and {@link #theLanguageSpecificBlockAgreesWithThisSdksExemptions()} holds
     * the two copies to each other. Moving the knob into the shared list therefore takes three edits in
     * one change — this map, that block, and PHP's half.
     */
    private static final Map<String, String> NOT_IN_THE_SHARED_LIST = Map.of(
            "token", "no default: without it the SDK is switched off rather than guessing one",
            "service", "no default: without it the SDK is switched off rather than guessing one",
            "release", "no default: unset means \"read it from git or send none\"",
            "enabled", "derived from the three above, not a value an operator sets on its own",
            "inAppPackages", "Java-only today: the shared list is a two-language contract and PHP has no "
                    + "namespace equivalent yet. It joins the list together with PHP's half.");

    /**
     * The exemptions that are NOT a language decision: three parameters with no default at all, and one
     * derived from them. Separating them out is what lets {@link
     * #theLanguageSpecificBlockAgreesWithThisSdksExemptions()} say anything about the rest — without it,
     * "exempt" would be one word covering two entirely different reasons and the file could never be
     * checked against it.
     */
    private static final List<String> NO_DEFAULT_OR_DERIVED =
            List.of("token", "service", "release", "enabled");

    /**
     * The other direction, and the one this test was missing: every component {@link AnvilFeedConfig}
     * carries has to appear in the shared list, or in {@link #NOT_IN_THE_SHARED_LIST} with its reason.
     *
     * <p>Without it, {@code everyDefaultMatchesTheSharedList} catches only a key added to the FILE that no
     * test reads. The direction that actually breaks C17 is the opposite one: a knob added to
     * {@code AnvilFeedConfig} that never reaches {@code conformance/config-defaults.json} — Task 10's
     * in-app-package property, say — leaves PHP unable to be configured the same way and nothing red. This
     * fails on the day the knob is added rather than on the day somebody notices.
     */
    @Test
    void everyKnobOfThisSdkIsNamedInTheSharedList() throws Exception {
        List<String> knobs = Arrays.stream(AnvilFeedConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !NOT_IN_THE_SHARED_LIST.containsKey(name))
                .map(ConfigParityTest::snakeCase)
                .sorted()
                .toList();

        assertThat(knobs)
                .describedAs("a knob this SDK has and conformance/config-defaults.json does not name is a "
                        + "parameter the other language was never asked for — add it to the file and to "
                        + "both parity tests, or to NOT_IN_THE_SHARED_LIST with the reason it has no "
                        + "default")
                .isEqualTo(sharedListKeys().stream().sorted().toList());
    }

    /** An exemption whose name is not a component at all is a stale exemption, and it hides a knob. */
    @Test
    void everyExemptionNamesAComponentThatReallyExists() {
        List<String> components = Arrays.stream(AnvilFeedConfig.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(components).containsAll(NOT_IN_THE_SHARED_LIST.keySet());
        assertThat(NOT_IN_THE_SHARED_LIST.values())
                .describedAs("an exemption without a reason is a silent omission with extra steps")
                .allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    /** {@code maxQueueSize} is {@code max_queue_size}; the shared list is snake_case in both languages. */
    private static String snakeCase(String camel) {
        return camel.replaceAll("(?<!^)([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    /**
     * The keys of {@code config-defaults.json} that are NOT parameters, and so are no part of the
     * name-and-value contract the two tests above enforce.
     *
     * <p>Each of them is held to something of its own instead — {@link
     * #theEnvBlockNamesTheFiveVariablesThisSdkReads()} and {@link
     * #theLanguageSpecificBlockAgreesWithThisSdksExemptions()}. That pairing is the rule this list carries:
     * a name added here without a test added with it turns a contract file into a file with prose in it,
     * and prose in a contract is what goes quietly wrong while every guard stays green.
     */
    private static final List<String> NOT_A_PARAMETER = List.of("note", "env", "language_specific");

    private static List<String> sharedListKeys() throws Exception {
        JsonNode file = JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json")));
        List<String> keys = new ArrayList<>();
        file.fieldNames().forEachRemaining(keys::add);
        keys.removeAll(NOT_A_PARAMETER);
        return keys;
    }

    /**
     * The {@code env} block of {@code config-defaults.json}, held against the names this SDK actually asks
     * the environment for and against the parameter each one is declared to fill.
     *
     * <p>{@link #theFiveEnvironmentVariableNamesAreTheOnesPhpReads()} pins the five names inside this test
     * class. This one pins the FILE to the same five, and the difference is the whole point: the file is
     * what the other language's twin reads and what a human consults, and until this test existed the
     * block could have named a sixth variable, or misspelled one of the five, with nothing anywhere going
     * red. The lookup is driven with an environment that answers {@code null} to everything, because what
     * is being measured is which names are ASKED for, not what comes back.
     */
    @Test
    void theEnvBlockNamesTheFiveVariablesThisSdkReads() throws Exception {
        Map<String, String> declared = new LinkedHashMap<>();
        JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json"))).path("env").fields()
                .forEachRemaining(field -> {
                    if (!"note".equals(field.getKey())) {
                        declared.put(field.getKey(), field.getValue().asText());
                    }
                });

        List<String> asked = new ArrayList<>();
        AnvilFeedConfig.fromEnvironment(FeedLogger.toSystemLogger(), name -> {
            asked.add(name);
            return null;
        });

        assertThat(asked)
                .describedAs("the env block of config-defaults.json names a variable this SDK never reads, "
                        + "or this SDK reads one the block does not name")
                .containsExactlyInAnyOrderElementsOf(declared.keySet());

        List<String> parameters = Arrays.stream(AnvilFeedConfig.class.getRecordComponents())
                .map(RecordComponent::getName).map(ConfigParityTest::snakeCase).toList();
        assertThat(parameters)
                .describedAs("the env block maps a variable onto a parameter this SDK does not have")
                .containsAll(declared.values());
    }

    /**
     * The {@code language_specific} block, held against this test class's own exemption list.
     *
     * <p>An entry naming {@code java} alone is the written-down form of one decision: this knob exists in
     * Java and does not exist in PHP yet. That decision necessarily lives in two places — in {@link
     * #NOT_IN_THE_SHARED_LIST}, where the parity tests read it, and in the shared file, where the other
     * language and a human reader do — and two places is precisely how a decision comes apart. This test
     * makes them one place: on the day PHP grows the knob, the exemption goes, and the entry has to go
     * with it into the parameter list, or this is red.
     *
     * <p>An entry naming BOTH languages is the opposite claim — a parameter of the list above whose
     * meaning is the same on both sides — and it is checked as that: it has to be in the shared list, and
     * it has to say so.
     */
    @Test
    void theLanguageSpecificBlockAgreesWithThisSdksExemptions() throws Exception {
        JsonNode block = JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json")))
                .path("language_specific");

        List<String> javaOnly = new ArrayList<>();
        List<String> bothLanguages = new ArrayList<>();
        block.fields().forEachRemaining(entry -> {
            if ("note".equals(entry.getKey())) {
                return;
            }
            List<String> languages = strings(entry.getValue().path("languages"));
            assertThat(languages)
                    .describedAs("%s names no language, so it says nothing at all", entry.getKey())
                    .isNotEmpty();
            if (languages.equals(List.of("java"))) {
                javaOnly.add(entry.getKey());
            } else {
                bothLanguages.add(entry.getKey());
                assertThat(entry.getValue().path("semantics").asText())
                        .describedAs("%s claims both languages without claiming they mean the same thing, "
                                + "which is the one thing a reader consults this block for", entry.getKey())
                        .isEqualTo("identical");
            }
        });

        // The exemptions that are a LANGUAGE decision. The other four are exempt for a reason that has
        // nothing to do with a language: three have no default at all and one is derived from them.
        List<String> exemptedForBeingJavaOnly = NOT_IN_THE_SHARED_LIST.keySet().stream()
                .filter(name -> !NO_DEFAULT_OR_DERIVED.contains(name))
                .map(ConfigParityTest::snakeCase)
                .sorted().toList();

        assertThat(javaOnly.stream().sorted().toList())
                .describedAs("a knob exempt from the shared list for being Java-only has to say so in "
                        + "conformance/config-defaults.json, and nothing else may claim to be")
                .isEqualTo(exemptedForBeingJavaOnly);

        assertThat(sharedListKeys())
                .describedAs("language_specific calls a parameter shared that the parameter list does not "
                        + "carry")
                .containsAll(bothLanguages);
    }

    // ================================================================= the environment variables

    /**
     * The five names both languages read, pinned by the set of names the lookup is ASKED for as well as by
     * the values that come back. C17 is about identical names, and an environment variable is the one name
     * a customer types by hand — into a Helm values file, a systemd unit, a Dockerfile. A rename in one
     * language costs them that service's whole error stream with nothing to see, and
     * {@code config-defaults.json} names none of them, so this test is the only place either language is
     * held to them.
     *
     * <p>PHP pins the same five with {@code putenv}. A JVM cannot set its own environment, which is why
     * {@link AnvilFeedConfig#fromEnvironment(FeedLogger, java.util.function.UnaryOperator)} exists.
     */
    @Test
    void theFiveEnvironmentVariableNamesAreTheOnesPhpReads() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("ANVIL_INGEST_ENDPOINT", "https://ingest.example/api/feed");
        environment.put("ANVIL_INGEST_TOKEN", "anv_srv_0123456789abcdef0123456789abcdef");
        environment.put("ANVIL_SERVICE", "shop-api");
        environment.put("ANVIL_ENVIRONMENT", "staging");
        environment.put("ANVIL_RELEASE", "v1.2.3");

        List<String> asked = new ArrayList<>();
        AnvilFeedConfig config = AnvilFeedConfig.fromEnvironment(FeedLogger.toSystemLogger(), name -> {
            asked.add(name);
            return environment.get(name);
        });

        assertThat(asked)
                .describedAs("the SDK read a variable the shared contract does not name, or stopped "
                        + "reading one it does")
                .containsExactlyInAnyOrderElementsOf(environment.keySet());
        assertThat(config.endpoint()).isEqualTo("https://ingest.example/api/feed");
        assertThat(config.token()).isEqualTo("anv_srv_0123456789abcdef0123456789abcdef");
        assertThat(config.service()).isEqualTo("shop-api");
        assertThat(config.environment()).isEqualTo("staging");
        assertThat(config.release()).isEqualTo("v1.2.3");
        assertThat(config.enabled())
                .describedAs("the five alone must be enough to switch the SDK on")
                .isTrue();
    }

    /** An empty variable is unset, not a configured empty string — in both languages. */
    @Test
    void anEmptyEnvironmentVariableCountsAsUnset() {
        AnvilFeedConfig config = AnvilFeedConfig.fromEnvironment(FeedLogger.toSystemLogger(),
                name -> "ANVIL_SERVICE".equals(name) ? "shop-api"
                        : "ANVIL_INGEST_TOKEN".equals(name) ? "anv_srv_0123456789abcdef0123456789abcdef"
                        : "   ");

        assertThat(config.endpoint()).isEqualTo(AnvilFeedConfig.DEFAULT_ENDPOINT);
        assertThat(config.environment()).isEqualTo("production");
        assertThat(config.release()).isNull();
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    /** The default denylist is FR-5's nine terms, and the file is where the nine are written down. */
    @Test
    void theDefaultDenylistIsTheOneBothLanguagesShip() throws Exception {
        JsonNode expected = JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json")));

        assertThat(AnvilFeedConfig.DEFAULT_DENYLIST).isEqualTo(strings(expected.path("denylist")));
    }

    // ================================================================= the five floors

    /**
     * The floors PHP's parity round mirrored from this SDK ({@code php-parity-fix-report.md}, item B).
     * A floor test that only asserted "some clamp exists" would have passed the defect that round found,
     * so every row pins the EXACT value.
     */
    @ParameterizedTest(name = "{0} = {1} becomes {2}")
    @CsvSource({
            "max_queue_size,      0,  1",
            "max_queue_size,     -5,  1",
            "flush_interval_ms,   0,  1",
            "flush_interval_ms,  -5,  1",
            "connect_timeout_ms,  0,  1",
            "connect_timeout_ms, -5,  1",
            "request_timeout_ms,  0,  1",
            "request_timeout_ms, -5,  1",
    })
    void aKnobWithAFloorOfOneIsClampedAndSaidOnce(String key, int given, int expected) {
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig.Builder builder = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef").service("shop-api");
        switch (key) {
            case "max_queue_size" -> builder.maxQueueSize(given);
            case "flush_interval_ms" -> builder.flushIntervalMs(given);
            case "connect_timeout_ms" -> builder.connectTimeoutMs(given);
            default -> builder.requestTimeoutMs(given);
        }
        AnvilFeedConfig config = builder.build(written.logger());

        int actual = switch (key) {
            case "max_queue_size" -> config.maxQueueSize();
            case "flush_interval_ms" -> config.flushIntervalMs();
            case "connect_timeout_ms" -> config.connectTimeoutMs();
            default -> config.requestTimeoutMs();
        };
        assertThat(actual).describedAs("%s was not clamped to its floor of %d", key, expected)
                .isEqualTo(expected);
        assertThat(written.matching("\"" + key + "\" is " + given))
                .describedAs("the clamp is announced, once, naming the value the operator wrote")
                .hasSize(1);
    }

    /**
     * {@code fingerprint_cap} and {@code fingerprint_window_ms} floor at ZERO, not at one, in both
     * languages: zero means "no repeat suppression", which is a setting an operator may mean, and clamping
     * it to one would switch a feature back on for somebody who switched it off. The gate then floors its
     * own arithmetic at 1 — a two-stage rule, identical on both sides (task-8 report, discrepancy 13).
     */
    @ParameterizedTest(name = "{0} = {1} becomes {2}")
    @CsvSource({
            "fingerprint_cap,        -1,     0",
            "fingerprint_window_ms,  -60000, 0",
    })
    void aKnobWithAFloorOfZeroIsClampedAndSaidOnce(String key, int given, int expected) {
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig.Builder builder = base();
        if ("fingerprint_cap".equals(key)) {
            builder.fingerprintCap(given);
        } else {
            builder.fingerprintWindowMs(given);
        }
        AnvilFeedConfig config = builder.build(written.logger());

        assertThat("fingerprint_cap".equals(key) ? config.fingerprintCap() : config.fingerprintWindowMs())
                .isEqualTo(expected);
        assertThat(written.matching("\"" + key + "\" is " + given)).hasSize(1);
    }

    @Test
    void aFingerprintCapOrWindowOfZeroIsLeftAloneAndSaysNothing() {
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig config = base().fingerprintCap(0).fingerprintWindowMs(0).build(written.logger());

        assertThat(config.fingerprintCap()).isZero();
        assertThat(config.fingerprintWindowMs()).isZero();
        assertThat(written.matching("clamp")).isEmpty();
        assertThat(written.matching("fingerprint")).isEmpty();
    }

    /** And the second stage: the gate itself never divides by a zero window or admits an unbounded cap. */
    @Test
    void theGateFloorsAConfiguredZeroRatherThanObeyingIt() {
        RateGate gate = new RateGate(0, 0, () -> 1_000L);

        assertThat(gate.allow("f")).describedAs("the first occurrence of a window is always allowed")
                .isTrue();
        assertThat(gate.allow("f")).describedAs("a cap of 0 is one per window, never zero and never "
                + "unlimited — the same two-stage rule PHP has").isFalse();
    }

    // ================================================================= request.status_code

    /**
     * The coordinator's ruling from the task-6 re-review: an integer, or a string that is entirely an
     * optional minus followed by digits. Anything else is DROPPED rather than guessed at, because
     * {@code (int) "12abc"} is 12 and {@code (int) "abc"} is 0 — a status nobody ever sent, shown in a
     * cockpit as fact.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("statusCodes")
    void aStatusCodeIsAnIntegerOrIsDropped(Object given, Integer expected) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "GET");
        request.put("url", "https://shop.example/checkout");
        if (given != null) {
            request.put("status_code", given);
        }

        Map<String, Object> payload = factory(base().build(FeedLogger.toSystemLogger()))
                .fromThrowable(new IllegalStateException("boom"), "error", Map.of("request", request))
                .payload();
        Map<String, Object> sent = map(payload.get("request"));

        if (expected == null) {
            assertThat(sent)
                    .describedAs("a status code that is not an integer was guessed at rather than dropped")
                    .doesNotContainKey("status_code");
        } else {
            assertThat(sent.get("status_code")).isEqualTo(expected);
        }
    }

    /**
     * The accepted and dropped shapes PHP pins, in the same order, plus the rows that are Java-only
     * because Java has the types for them ({@code Long}, {@code BigInteger}, {@code short},
     * {@code BigDecimal} — PHP has one integer type and one float type).
     *
     * <p>The float rows were the last divergence: at the time of the task-9 report PHP dropped EVERY
     * float, and this javadoc said so. It landed in {@code 51fc722} and PHP's own table now carries
     * {@code 'an integral float' => [200.0, 200]}, so there is no row of this table the two languages do
     * not share. Checked rather than assumed, because a comment that describes the other language is a
     * comment nothing in this module can keep honest.
     */
    static Stream<Arguments> statusCodes() {
        return Stream.of(
                Arguments.of(404, 404),
                Arguments.of("404", 404),
                Arguments.of("  404  ", 404),
                Arguments.of("-1", -1),
                Arguments.of(404L, 404),
                Arguments.of(BigInteger.valueOf(404), 404),
                Arguments.of((short) 404, 404),
                Arguments.of(Integer.MAX_VALUE, Integer.MAX_VALUE),
                Arguments.of(Integer.MIN_VALUE, Integer.MIN_VALUE),

                // Out of a signed 32-bit int: DROPPED, never wrapped. intValue() made 5000000000 into
                // 705032704 and sent it, which is the same defect the float rows below were fixed for.
                // The first row is Integer.MAX_VALUE + 1 exactly: a bound is only proven at the step that
                // crosses it, and 5000000000 is far enough past the edge to pass an off-by-one bound.
                Arguments.of(2_147_483_648L, null),
                Arguments.of(5_000_000_000L, null),
                Arguments.of(-5_000_000_000L, null),
                Arguments.of(new BigInteger("99999999999"), null),
                Arguments.of(new BigDecimal("3000000000"), null),
                Arguments.of("3000000000", null),
                Arguments.of("99999999999999999999999", null),
                Arguments.of(3.0e9d, null),

                Arguments.of(200.0d, 200),
                Arguments.of(200.0f, 200),
                Arguments.of(new BigDecimal("200.00"), 200),
                Arguments.of(200.5d, null),
                Arguments.of(-0.5d, null),
                Arguments.of(new BigDecimal("200.5"), null),
                Arguments.of(Double.NaN, null),
                Arguments.of(Double.POSITIVE_INFINITY, null),

                Arguments.of("12abc", null),
                Arguments.of("abc", null),
                Arguments.of("200.5", null),
                Arguments.of("", null),
                Arguments.of(Boolean.TRUE, null),
                Arguments.of(List.of(500), null),
                Arguments.of(null, null));
    }

    // ================================================================= the denylist

    /**
     * An empty denylist means NO key rule — not a rule that matches everywhere, which is what an empty
     * alternation compiles to and what PHP's parity round fixed (item A). The absolutes keep running.
     *
     * <p>Java also SAYS it, once, at configuration time. PHP honours the empty list silently; that line is
     * the divergence the task-9 report hands back.
     */
    @Test
    void anEmptyDenylistIsSaidOnceAndSwitchesOffTheKeyRuleAndNothingElse() {
        RecordingLogger written = new RecordingLogger();
        AnvilFeedConfig config = base().denylist(List.of()).build(written.logger());
        base().denylist(List.of()).build(written.logger());   // a second build must not say it twice

        assertThat(config.denylist()).isEmpty();
        assertThat(written.matching("the denylist is empty"))
                .describedAs("said once per logger, which is once per process")
                .hasSize(1);

        Scrubber scrubber = new Scrubber(config.denylist());
        assertThat(scrubber.scrubText("user=alice&mode=fast"))
                .describedAs("the defect an empty alternation produces: every readable key=value destroyed")
                .isEqualTo("user=alice&mode=fast");
        assertThat(scrubber.scrubText("Authorization: Bearer abc.def"))
                .describedAs("the absolutes are not configurable and keep running")
                .isEqualTo("Authorization: Bearer " + Scrubber.FILTERED);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-1");
        user.put("ip", "203.0.113.9");
        assertThat(map(scrubber.scrub(Map.of("user", user)).get("user"))).containsOnlyKeys("id");
    }

    /** {@code null} is "not configured" and keeps the nine terms; only an explicit empty list turns it off. */
    @Test
    void aNullDenylistIsNotAnEmptyOne() {
        RecordingLogger written = new RecordingLogger();

        assertThat(base().denylist(null).build(written.logger()).denylist())
                .isEqualTo(AnvilFeedConfig.DEFAULT_DENYLIST);
        assertThat(written.matching("the denylist is empty")).isEmpty();
    }

    // ================================================================= the ignored-path glob

    /**
     * {@code *} stops at a slash, {@code **} crosses one, a trailing {@code /**} also matches the directory
     * itself, and the path is canonicalised before it is matched. Rule for rule
     * {@code AnvilFeed\LoopGuard::toRegex} and {@code ::canonical}.
     */
    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource({
            "/api/feed/**,   /api/feed/events,                    true",
            "/api/feed/**,   /api/feed,                           true",
            "/api/feed/**,   /api/feed/,                          true",
            "/api/feed/**,   /API/FEED/events,                    true",
            "/api/feed/**,   //api/feed//events,                  true",
            "/api/feed/**,   /app/../api/feed/events,             true",
            "/api/feed/**,   /api/feed/events?x=1,                true",
            "/api/feed/**,   /api/feed/events#frag,               true",
            "/api/feed/**,   https://host/api/feed/events,        true",
            "/api/feed/**,   /api/feedback,                       false",
            "/api/feed/**,   /api/feeds,                          false",
            "/api/*,         /api/feed,                           true",
            "/api/*,         /api/feed/events,                    false",
            "/api/**,        /api/feed/events,                    true",
            "api/feed/**,    /api/feed/events,                    true",
    })
    void theIgnoredPathGlobMeansWhatItMeansInPhp(String glob, String path, boolean ignored) {
        assertThat(new LoopGuard(List.of(glob)).ignoresPath(path)).isEqualTo(ignored);
    }

    @Test
    void anEmptyIgnoredPathListIsHonouredAndANullEntryIsNotARegex() {
        assertThat(new LoopGuard(List.of()).ignoresPath("/api/feed/events")).isFalse();
        assertThat(base().ignoredRequestPaths(List.of()).build(FeedLogger.toSystemLogger())
                .ignoredRequestPaths()).isEmpty();

        List<String> withHoles = new ArrayList<>();
        withHoles.add(null);
        withHoles.add("   ");
        withHoles.add("/api/feed/**");
        assertThat(new LoopGuard(withHoles).ignoresPath("/api/feed")).isTrue();
        assertThat(new LoopGuard(withHoles).ignoresPath("/anything")).isFalse();
    }

    // ================================================================= the level enum

    /** {@code error|warning|fatal}; everything else is {@code error}, because an unknown severity is one. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {
            "error,    error",
            "warning,  warning",
            "fatal,    fatal",
            "info,     error",
            "debug,    error",
            "critical, error",
            "ERROR,    error",
            "'',       error",
            "NULL,     error",
    })
    void theLevelEnumCoercionIsTheOneBothLanguagesApply(String given, String expected) {
        assertThat(EventFactory.coerceLevel(given)).isEqualTo(expected);

        Map<String, Object> payload = factory(base().build(FeedLogger.toSystemLogger()))
                .fromThrowable(new IllegalStateException("boom"), given, Map.of()).payload();
        assertThat(payload.get("level")).isEqualTo(expected);

        // And again on the second pass, which is the one a beforeSend hook's output takes.
        Map<String, Object> hooked = new LinkedHashMap<>(payload);
        hooked.put("level", given);
        assertThat(factory(base().build(FeedLogger.toSystemLogger())).finalise(hooked).get("level"))
                .describedAs("finalise() re-applies the enum, which is what keeps a hook's \"info\" off "
                        + "the wire")
                .isEqualTo(expected);
    }

    // ================================================================= the count caps

    /**
     * Tags: the first 29 by insertion order. Breadcrumbs: the LAST 30. Frames: the first
     * {@code max_frames}. Three different rules, and each one is chosen rather than accidental — the tail
     * of a breadcrumb trail is what leads to the fault, the head of a stack is where the fault is.
     */
    @Test
    void theCountCapsAreTagsTwentyNineBreadcrumbsThirtyFramesMaxFrames() {
        AnvilFeedConfig config = base().maxFrames(3).build(FeedLogger.toSystemLogger());
        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 40).forEach(i -> tags.put("tag-" + i, "v" + i));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tags", tags);
        context.put("breadcrumbs", IntStream.rangeClosed(1, 40).mapToObj(i -> "step " + i).toList());

        Map<String, Object> payload = factory(config)
                .fromThrowable(new IllegalStateException("boom"), "error", context).payload();

        assertThat(map(payload.get("tags"))).hasSize(29).containsKey("tag-1").doesNotContainKey("tag-30");

        List<?> breadcrumbs = (List<?>) payload.get("breadcrumbs");
        assertThat(breadcrumbs).hasSize(30);
        assertThat(breadcrumbs.get(0))
                .describedAs("the LAST thirty: the tail is what leads to the fault").isEqualTo("step 11");
        assertThat(breadcrumbs.get(29)).isEqualTo("step 40");
        assertThat((List<?>) map(payload.get("exception")).get("stacktrace")).hasSize(3);
    }

    // ================================================================= idempotency

    /**
     * {@code finalise()} is public so a {@code beforeSend} hook's output takes the SAME pipeline, which
     * means every rule in it runs at least twice over a hooked event. Over the shared PII corpus, the
     * second pass must change nothing at all — in both languages.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theWholePipelineIsIdempotentOverTheSharedPiiCorpus() throws Exception {
        EventFactory factory = factory(base().build(FeedLogger.toSystemLogger()));
        Map<String, Object> raw = JSON.readValue(
                Files.readString(CONFORMANCE.resolve("pii/raw.json")), LinkedHashMap.class);

        Map<String, Object> once = factory.finalise(raw);
        Map<String, Object> twice = factory.finalise(new LinkedHashMap<>(once));

        assertThat(twice).isEqualTo(once);
        assertThat(JSON.writeValueAsString(twice)).isEqualTo(JSON.writeValueAsString(once));
        assertThat(map(twice.get("user")))
                .describedAs("the absolutes are applied on every pass, not only the first")
                .doesNotContainKey("ip");
    }
}
