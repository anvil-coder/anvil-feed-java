package tech.anvilcoder.feed.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.Transport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The five environment variables, on the Spring path.
 *
 * <p>{@code conformance/config-defaults.json}'s {@code env} block guarantees the five names in both
 * languages, and both SDKs' parity tests hold their own configuration class to them. The Spring path does
 * not go through that class directly: it goes through {@link AnvilFeedProperties} and Spring's relaxed
 * binding, and two of the five are DELIBERATELY outranked there — {@code spring.application.name} beats
 * {@code ANVIL_SERVICE} and the first active profile beats {@code ANVIL_ENVIRONMENT}, because a Spring
 * application already has both and two names for one thing is a support call waiting to happen. A
 * guarantee that holds in the core and quietly does not hold on the path a Spring user actually takes is
 * worth nothing, so it is proven here as well.
 *
 * <p>The variables are injected through a real {@link SystemEnvironmentPropertySource} — the same class
 * Spring wraps {@code System.getenv()} in, with the same relaxed name matching — rather than through
 * inline properties, because a JVM cannot set its own environment and an inline property would be testing
 * a different source from the one a Helm chart writes to.
 */
class AnvilFeedEnvironmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));

    private static final String TOKEN = AnvilFeedTestSupport.TOKEN;

    /** The {@code env} block of the contract file: variable name → the parameter it fills. */
    private static Map<String, String> contractEnvironmentNames() throws Exception {
        JsonNode block = JSON.readTree(Files.readString(CONFORMANCE.resolve("config-defaults.json")))
                .path("env");
        Map<String, String> names = new LinkedHashMap<>();
        block.fields().forEachRemaining(entry -> {
            if (!"note".equals(entry.getKey())) {
                names.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return names;
    }

    private static ApplicationContextRunner runner(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withBean(Transport.class, StubIngest::new)
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                environmentVariables)));
    }

    /** What each of the five is expected to reach, read off the built configuration. */
    private static final Map<String, Function<AnvilFeedConfig, Object>> READS_BACK = Map.of(
            "endpoint", AnvilFeedConfig::endpoint,
            "token", AnvilFeedConfig::token,
            "service", AnvilFeedConfig::service,
            "environment", AnvilFeedConfig::environment,
            "release", AnvilFeedConfig::release);

    private static final Map<String, String> VALUES = Map.of(
            "endpoint", "https://ingest.example/api/feed",
            "token", TOKEN,
            "service", "shop-api",
            "environment", "staging",
            "release", "1.4.2");

    /**
     * Every name in the contract file, one at a time, with no Spring property anywhere near it. The loop
     * is over the FILE: a variable renamed there and not here fails, and a sixth variable added there
     * fails until this test knows what it fills.
     */
    @Test
    void eachContractVariableIsHonouredOnTheSpringPath() throws Exception {
        Map<String, String> contract = contractEnvironmentNames();
        assertThat(contract).hasSize(5);

        for (Map.Entry<String, String> entry : contract.entrySet()) {
            String parameter = entry.getValue();
            assertThat(READS_BACK).describedAs("a new variable in the contract file, unknown here")
                    .containsKey(parameter);

            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("ANVIL_INGEST_TOKEN", TOKEN);      // or nothing is enabled and nothing is built
            environment.put("ANVIL_SERVICE", "shop-api");
            environment.put(entry.getKey(), VALUES.get(parameter));

            runner(environment).run(context -> {
                AnvilFeedConfig config = context.getBean(AnvilFeedConfig.class);
                assertThat(READS_BACK.get(parameter).apply(config))
                        .describedAs("%s fills %s", entry.getKey(), parameter)
                        .isEqualTo(VALUES.get(parameter));
            });
        }
    }

    /** The deliberate divergence, pinned so it is a decision rather than an accident. */
    @Test
    void springsOwnNamesOutrankTheAnvilVariables() {
        runner(Map.of("ANVIL_INGEST_TOKEN", TOKEN,
                "ANVIL_SERVICE", "from-the-variable",
                "ANVIL_ENVIRONMENT", "from-the-variable"))
                .withPropertyValues("spring.application.name=from-spring",
                        "spring.profiles.active=staging,eu-west")
                .run(context -> {
                    AnvilFeedConfig config = context.getBean(AnvilFeedConfig.class);
                    assertEquals("from-spring", config.service());
                    assertEquals("staging", config.environment());
                });
    }

    /** And an explicit property outranks both, which is the escape hatch the guide points at. */
    @Test
    void anExplicitPropertyOutranksEverything() {
        runner(Map.of("ANVIL_INGEST_TOKEN", TOKEN, "ANVIL_SERVICE", "from-the-variable",
                "ANVIL_ENVIRONMENT", "from-the-variable"))
                .withPropertyValues("spring.application.name=from-spring",
                        "spring.profiles.active=staging",
                        "anvil.feed.service=from-the-property",
                        "anvil.feed.environment=production")
                .run(context -> {
                    AnvilFeedConfig config = context.getBean(AnvilFeedConfig.class);
                    assertEquals("from-the-property", config.service());
                    assertEquals("production", config.environment());
                });
    }

    /**
     * Spring's relaxed binding reads {@code anvil.feed.token} from {@code ANVIL_FEED_TOKEN} as well. Both
     * work, and the documented one is {@code ANVIL_INGEST_TOKEN} because it is the name the PHP SDK, the
     * ingest's own documentation and every Helm chart use.
     */
    @Test
    void theRelaxedSpellingOfTheTokenWorksToo() {
        runner(Map.of("ANVIL_FEED_TOKEN", TOKEN, "ANVIL_SERVICE", "shop-api"))
                .run(context -> assertEquals(TOKEN, context.getBean(AnvilFeedConfig.class).token()));
    }

    /** The documented name, on its own, with nothing in {@code application.yml} at all. */
    @Test
    void theDocumentedTokenNameIsEnoughOnItsOwn() {
        runner(Map.of("ANVIL_INGEST_TOKEN", TOKEN, "ANVIL_SERVICE", "shop-api"))
                .run(context -> {
                    AnvilFeedConfig config = context.getBean(AnvilFeedConfig.class);
                    assertEquals(TOKEN, config.token());
                    assertThat(config.enabled()).isTrue();
                });
    }

    // ---------------------------------------------------------------- the two names spelled out here

    /*
     * Review finding 14. The loop above reads its five names OUT of config-defaults.json, which is what
     * makes it notice a sixth — and is also why it cannot notice a rename: a variable renamed in the
     * starter AND in the contract file keeps that loop green, and the Helm chart that types the old name
     * breaks with the whole build green. Two of the five are only ever asserted by that loop
     * (`grep ANVIL_INGEST_ENDPOINT java/anvil-feed-spring-boot-starter/src/test` found nothing), so they
     * are spelled out HERE, as literals, where a rename has to be made twice on purpose.
     */

    @Test
    void theEndpointVariableIsSpelledAnvilIngestEndpoint() {
        runner(Map.of("ANVIL_INGEST_TOKEN", TOKEN, "ANVIL_SERVICE", "shop-api",
                "ANVIL_INGEST_ENDPOINT", "https://ingest.example/api/feed"))
                .run(context -> assertEquals("https://ingest.example/api/feed",
                        context.getBean(AnvilFeedConfig.class).endpoint()));
    }

    @Test
    void theReleaseVariableIsSpelledAnvilRelease() {
        runner(Map.of("ANVIL_INGEST_TOKEN", TOKEN, "ANVIL_SERVICE", "shop-api",
                "ANVIL_RELEASE", "1.4.2"))
                .run(context -> assertEquals("1.4.2",
                        context.getBean(AnvilFeedConfig.class).release()));
    }
}
