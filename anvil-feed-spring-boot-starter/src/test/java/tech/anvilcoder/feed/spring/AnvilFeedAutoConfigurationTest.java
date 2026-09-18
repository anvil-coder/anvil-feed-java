package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskDecorator;

import tech.anvilcoder.feed.AnvilFeed;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.FeedLogger;
import tech.anvilcoder.feed.Transport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class AnvilFeedAutoConfigurationTest {

    private static final String TOKEN = AnvilFeedTestSupport.TOKEN;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class));

    @AfterEach
    void theFacadeIsProcessState() {
        assertNull(AnvilFeed.current(), "a closed context must leave nothing installed behind it");
    }

    @Test
    void wiresAClientFromThreeProperties() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api",
                "anvil.feed.environment=production").run(context -> {
            assertThat(context).hasSingleBean(AnvilFeedClient.class);
            AnvilFeedConfig config = context.getBean(AnvilFeedConfig.class);
            assertEquals("shop-api", config.service());
            assertEquals("production", config.environment());
            assertTrue(config.enabled());
            assertEquals(context.getBean(AnvilFeedClient.class), AnvilFeed.current(),
                    "library code calling the static facade must reach THIS client");
        });
    }

    /**
     * The defaults live in {@code AnvilFeedConfig.Builder} and in {@code conformance/config-defaults.json},
     * and the core's parity tests hold those two together across both languages. A second spelling here
     * would be a third home for the same numbers with nothing red on the day they drift.
     */
    @Test
    void theStarterSpellsNoDefaultOfItsOwn() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .run(context -> assertEquals(
                        AnvilFeedConfig.builder().token(TOKEN).service("shop-api")
                                .build(FeedLogger.toSystemLogger()),
                        context.getBean(AnvilFeedConfig.class)));
    }

    /** A Spring profile name is routinely a value the ingest answers schema_violation to. */
    @Test
    void repairsAnEnvironmentTheIngestWouldRefuse(CapturedOutput output) {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api",
                "anvil.feed.environment=Prod_EU").run(context -> {
            assertEquals("prod-eu", context.getBean(AnvilFeedConfig.class).environment());
            assertThat(output).contains("is not a valid binding key");
        });
    }

    @Test
    void theEnvironmentFallsBackToTheFirstActiveProfile() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api",
                        "spring.profiles.active=staging,eu-west")
                .run(context -> assertEquals("staging",
                        context.getBean(AnvilFeedConfig.class).environment()));
    }

    @Test
    void theServiceFallsBackToSpringApplicationName() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "spring.application.name=checkout")
                .run(context -> assertEquals("checkout", context.getBean(AnvilFeedConfig.class).service()));
    }

    /**
     * Spring's relaxed binding reads {@code anvil.feed.token} from {@code ANVIL_FEED_TOKEN}, and every
     * other Anvil SDK, every Helm chart and the ingest's own documentation say {@code ANVIL_INGEST_TOKEN}.
     * An operator who sets the documented name and gets a silently disabled SDK has no way to find out why.
     */
    @Test
    void theTokenAlsoAnswersToTheNameEveryOtherSdkUses() {
        runner.withPropertyValues("ANVIL_INGEST_TOKEN=" + TOKEN, "anvil.feed.service=shop-api")
                .run(context -> assertTrue(context.getBean(AnvilFeedConfig.class).enabled()));
    }

    @Test
    void withoutATokenTheSdkIsDisabledRatherThanBroken(CapturedOutput output) {
        runner.withPropertyValues("anvil.feed.service=shop-api").run(context -> {
            assertFalse(context.getBean(AnvilFeedConfig.class).enabled());
            assertThat(context).hasSingleBean(AnvilFeedClient.class);
            assertNull(context.getBean(AnvilFeedVerdict.class).owned(),
                    "a process that will never send one event must not build an HttpTransport");
            assertThat(output).containsOnlyOnce("no ingest token (ANVIL_INGEST_TOKEN)");
        });
    }

    @Test
    void enabledFalseLeavesNoBeansAtAll() {
        runner.withPropertyValues("anvil.feed.enabled=false", "anvil.feed.token=" + TOKEN)
                .run(context -> assertThat(context).doesNotHaveBean(AnvilFeedClient.class));
    }

    @Test
    void anApplicationsOwnClientWins() {
        AnvilFeedClient mine = AnvilFeedTestSupport.client();
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .withBean(AnvilFeedClient.class, () -> mine)
                .run(context -> assertEquals(1, context.getBeansOfType(AnvilFeedClient.class).size()));
        AnvilFeed.reset();   // the context installed the host's client; nothing else did
    }

    /** Replacing somebody's decorator would take their MDC or their security context away. */
    @Test
    void anApplicationsOwnTaskDecoratorWins() {
        TaskDecorator mine = runnable -> runnable;
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .withBean(TaskDecorator.class, () -> mine)
                .run(context -> assertEquals(mine, context.getBean(TaskDecorator.class)));
    }

    /** A host that brings its own transport keeps it, and the starter builds none. */
    @Test
    void aHostsOwnTransportIsUsedAndIsNotOwned() {
        StubIngest ingest = new StubIngest();
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .withBean(Transport.class, () -> ingest)
                .run(context -> assertNull(context.getBean(AnvilFeedVerdict.class).owned()));
    }

    @Test
    void inAppPackagesAreConfigurable() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api",
                        "anvil.feed.in-app-packages=com.acme.shop,com.acme.billing")
                .run(context -> assertEquals(List.of("com.acme.shop", "com.acme.billing"),
                        context.getBean(AnvilFeedConfig.class).inAppPackages()));
    }

    /** An explicitly EMPTY list is "decide structurally", which is the core's own rule and not "unset". */
    @Test
    void anEmptyInAppPackageListIsHonoured() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api",
                        "anvil.feed.in-app-packages=")
                .run(context -> assertThat(context.getBean(AnvilFeedConfig.class).inAppPackages()).isEmpty());
    }

    /**
     * An application in the DEFAULT package: {@code AutoConfigurationPackages.get} answers {@code [""]},
     * which is not a package prefix at all. The core's builder drops blanks, so the list ends up empty and
     * the structural rule applies — which is the only honest answer for an application that told us
     * nothing.
     */
    @Test
    void anApplicationInTheDefaultPackageFallsBackToTheStructuralRule() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withInitializer(context -> AutoConfigurationPackages.register(
                        (BeanDefinitionRegistry) context.getBeanFactory(), ""))
                .withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .run(context -> assertThat(context.getBean(AnvilFeedConfig.class).inAppPackages())
                        .isEmpty());
    }

    @Test
    void theIgnoredPathListIsConfigurableAndTheDefaultIsTheIngestsOwn() {
        runner.withPropertyValues("anvil.feed.token=" + TOKEN, "anvil.feed.service=shop-api")
                .run(context -> assertEquals(List.of("/api/feed/**"),
                        context.getBean(AnvilFeedConfig.class).ignoredRequestPaths()));
    }
}
