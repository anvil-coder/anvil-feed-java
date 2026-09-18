package tech.anvilcoder.feed.spring;

import jakarta.servlet.Filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.web.servlet.HandlerExceptionResolver;

import tech.anvilcoder.feed.AnvilFeed;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.FeedLogger;
import tech.anvilcoder.feed.GitContext;
import tech.anvilcoder.feed.HttpTransport;
import tech.anvilcoder.feed.Transport;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The whole wiring of the Spring integration. FR-1 c: the diff an application makes to adopt this is the
 * dependency and three properties. There is no code at the place an error is raised.
 *
 * <p>The starter holds no protocol logic (X5): building, scrubbing, bounding, queueing and sending all
 * live in {@code anvil-feed-core}, which knows nothing about Spring and is the same code the Laravel
 * package's PHP twin runs. What lives here is the six places a Spring application loses an exception —
 * the dispatcher servlet, the filter chain, a raw thread, {@code @Async}, {@code @Scheduled} and the
 * shutdown — and the three values only Spring can answer.
 *
 * <h2>The three repairs</h2>
 * <ul>
 *   <li><b>token</b> — Spring's relaxed binding reads {@code anvil.feed.token} from
 *       {@code ANVIL_FEED_TOKEN}, and the name every other Anvil SDK and every Helm chart uses is
 *       {@code ANVIL_INGEST_TOKEN}. Both work here; the property wins.</li>
 *   <li><b>service</b> — falls back to {@code spring.application.name}, which a Spring application has
 *       already set for its own logging and tracing.</li>
 *   <li><b>environment</b> — falls back to the FIRST active profile. This one is a repair and not just a
 *       default: the ingest binds on {@code [a-z][a-z0-9-]&#123;0,63&#125;} and answers anything else with
 *       {@code schema_violation}, which a customer only ever sees as an empty inbox — while a Spring
 *       profile is routinely {@code Prod_EU}, {@code staging2} or {@code default}. The core lower-cases
 *       it, replaces every other character with {@code -}, strips a leading digit or dash, and says once
 *       what it sent instead; where nothing can be repaired it switches the SDK off with a message naming
 *       the rule. So {@code Prod_EU} becomes {@code prod-eu} and one WARN line says so.</li>
 * </ul>
 *
 * <h2>in_app_packages</h2>
 * The core's in-app rule is structural — a file under {@code vendor/} is not the application — and that
 * rule cannot see anything inside a fat jar, where Hibernate and the application are the same directory.
 * Spring knows the answer: the package of the {@code @SpringBootApplication} class, which is what
 * {@link AutoConfigurationPackages} already records. It is seeded from there and handed to the core
 * through {@link AnvilFeedConfig.Builder#inAppPackages(List)}; {@code anvil.feed.in-app-packages}
 * overrides it, and an explicitly empty list restores the structural rule.
 */
@AutoConfiguration
@EnableConfigurationProperties(AnvilFeedProperties.class)
@ConditionalOnProperty(prefix = "anvil.feed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AnvilFeedAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AnvilFeedAutoConfiguration.class);

    /** C18: a clear message naming the required version, never a NoSuchMethodError at a customer's. */
    private static final int REQUIRED_JAVA_FEATURE = 25;

    @Bean
    @ConditionalOnMissingBean
    public AnvilFeedConfig anvilFeedConfig(AnvilFeedProperties properties, Environment environment,
                                           BeanFactory beanFactory) {
        if (Runtime.version().feature() < REQUIRED_JAVA_FEATURE) {
            throw new IllegalStateException("anvil-feed requires Java " + REQUIRED_JAVA_FEATURE
                    + " or newer; this JVM is " + Runtime.version().feature()
                    + ". The supported baseline is the one number in gradle/libs.versions.toml (C18).");
        }

        AnvilFeedConfig.Builder builder = AnvilFeedConfig.builder()
                // Every setter below ignores null, so "the operator said nothing" keeps the core's own
                // default and this class spells no default value of its own.
                .endpoint(first(properties.getEndpoint(), environment.getProperty("ANVIL_INGEST_ENDPOINT")))
                .token(first(properties.getToken(), environment.getProperty("ANVIL_INGEST_TOKEN")))
                .service(first(properties.getService(), environment.getProperty("spring.application.name"),
                        environment.getProperty("ANVIL_SERVICE")))
                .environment(first(properties.getEnvironment(), firstActiveProfile(environment),
                        environment.getProperty("ANVIL_ENVIRONMENT")))
                .release(first(properties.getRelease(), environment.getProperty("ANVIL_RELEASE")))
                .denylist(properties.getDenylist())
                .ignoredRequestPaths(properties.getIgnoredRequestPaths())
                .inAppPackages(first(properties.getInAppPackages(), applicationPackages(beanFactory)))
                .enabled(properties.isEnabled());

        applyIfSet(properties.getMaxQueueSize(), builder::maxQueueSize);
        applyIfSet(properties.getFlushIntervalMs(), builder::flushIntervalMs);
        applyIfSet(properties.getBatchSize(), builder::batchSize);
        applyIfSet(properties.getConnectTimeoutMs(), builder::connectTimeoutMs);
        applyIfSet(properties.getRequestTimeoutMs(), builder::requestTimeoutMs);
        applyIfSet(properties.getMaxFrames(), builder::maxFrames);
        applyIfSet(properties.getContextLines(), builder::contextLines);
        applyIfSet(properties.getMaxContextLineChars(), builder::maxContextLineChars);
        applyIfSet(properties.getFingerprintCap(), builder::fingerprintCap);
        applyIfSet(properties.getFingerprintWindowMs(), builder::fingerprintWindowMs);

        return builder.build(feedLogger());
    }

    /** The recorder the FR-8 verdict is read from. Never a {@link Transport} itself — see its javadoc. */
    @Bean
    @ConditionalOnMissingBean
    public AnvilFeedVerdict anvilFeedVerdict() {
        return new AnvilFeedVerdict();
    }

    /**
     * {@code destroyMethod = ""} on purpose: {@link AnvilFeedShutdown} closes this, after a last flush,
     * on {@code ContextClosedEvent} — which is published while every singleton is still usable. Letting
     * Spring infer {@code close()} would close the sender during singleton destruction, in an order
     * nothing here controls.
     */
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public AnvilFeedClient anvilFeedClient(AnvilFeedConfig config, AnvilFeedVerdict verdict,
                                           ObjectProvider<Transport> transports) {
        Transport wire = transports.getIfAvailable();
        if (wire == null && config.enabled()) {
            // A DISABLED configuration builds no HttpTransport at all: that constructor creates a named
            // daemon executor and a pinned HttpClient, and in a process that will never send one event
            // both would live for the life of the JVM without ever being used.
            HttpTransport built = new HttpTransport(config);
            verdict.own(built);
            wire = built;
        }

        AnvilFeedClient client = AnvilFeedClient.create(config,
                wire == null ? null : verdict.recording(wire), feedLogger(),
                GitContext.discover(Path.of(System.getProperty("user.dir", "."))));
        client.start();
        // So that library code calling the static facade reaches THIS client and not a second one.
        AnvilFeed.init(client);

        if (!config.enabled()) {
            log.info("anvil-feed: the SDK is configured but disabled; nothing will be sent. The line above "
                    + "says which value switched it off.");
        }
        return client;
    }

    @Bean
    @ConditionalOnMissingBean
    public AnvilFeedUncaughtExceptionHandler anvilFeedUncaughtExceptionHandler(AnvilFeedClient client) {
        return AnvilFeedUncaughtExceptionHandler.install(client);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnvilFeedShutdown anvilFeedShutdown(AnvilFeedClient client, AnvilFeedConfig config,
                                               AnvilFeedVerdict verdict,
                                               AnvilFeedUncaughtExceptionHandler handler) {
        return new AnvilFeedShutdown(client, config, verdict, handler);
    }

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public TaskDecorator anvilFeedTaskDecorator(AnvilFeedClient client) {
        return new AnvilFeedTaskDecorator(client);
    }

    /**
     * Spring reads {@code @Async}'s handler off an {@link AsyncConfigurer}, so the handler alone would be
     * a bean nothing consults. Registered only where the application has none of its own: an
     * {@code AsyncConfigurer} also decides the executor, and taking somebody's executor away because they
     * wanted our error handling would be a trade nobody agreed to.
     */
    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer.class)
    public AsyncConfigurer anvilFeedAsyncConfigurer(AnvilFeedClient client) {
        AsyncUncaughtExceptionHandler handler = new AnvilFeedAsyncExceptionHandler(client);
        return new AsyncConfigurer() {
            @Override
            public Executor getAsyncExecutor() {
                return null;   // null means "keep Spring's own", which is the point of this bean
            }

            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return handler;
            }
        };
    }

    /**
     * {@code @Scheduled} is the one place Spring exposes an {@code ErrorHandler} rather than a decorator,
     * and a customizer is the only way to reach the scheduler Spring Boot builds. It is applied to the
     * builder, so an application that sets its own error handler after this still wins.
     */
    @Bean
    @ConditionalOnClass(ThreadPoolTaskSchedulerCustomizer.class)
    @ConditionalOnMissingBean(ThreadPoolTaskSchedulerCustomizer.class)
    public ThreadPoolTaskSchedulerCustomizer anvilFeedTaskSchedulerCustomizer(AnvilFeedClient client) {
        return scheduler -> scheduler.setErrorHandler(new AnvilFeedErrorHandler(client));
    }

    @Bean
    @ConditionalOnProperty(prefix = "anvil.feed", name = "send-test-event", havingValue = "true")
    public AnvilFeedTestEvent anvilFeedTestEvent(AnvilFeedConfig config, AnvilFeedClient client,
                                                 AnvilFeedVerdict verdict) {
        return new AnvilFeedTestEvent(config, client, verdict);
    }

    /**
     * The servlet half. It is a nested configuration and not four {@code @ConditionalOnClass} bean methods
     * because {@code spring-boot-starter-web} is {@code compileOnly}: a batch job or a queue worker uses
     * the same starter, has no servlet API on its class path, and must still get everything above.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass({Filter.class, HandlerExceptionResolver.class})
    public static class ServletCapture {

        @Bean
        @ConditionalOnMissingBean
        public AnvilFeedExceptionResolver anvilFeedExceptionResolver(AnvilFeedClient client) {
            return new AnvilFeedExceptionResolver(client);
        }

        @Bean
        @ConditionalOnMissingBean
        public AnvilFeedRequestFilter anvilFeedRequestFilter(AnvilFeedClient client) {
            return new AnvilFeedRequestFilter(client);
        }
    }

    /** The first value that is neither null nor blank, or null. */
    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String text ? !text.isBlank() : value != null) {
                return value;
            }
        }
        return null;
    }

    private static void applyIfSet(Integer value, java.util.function.IntConsumer setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * The first active profile, or null. The FIRST and not all of them: {@code environment} is one binding
     * key, and an application running {@code prod,eu-west,metrics} has one environment and two flags.
     */
    private static String firstActiveProfile(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? null : active[0];
    }

    /**
     * The packages Spring Boot itself scans — i.e. the package of the {@code @SpringBootApplication} class.
     * Absent in a plain {@code ApplicationContext} that was never bootstrapped by Spring Boot, and the
     * answer there is "decide structurally", which is the core's default.
     */
    private static List<String> applicationPackages(BeanFactory beanFactory) {
        try {
            return AutoConfigurationPackages.has(beanFactory)
                    ? List.copyOf(AutoConfigurationPackages.get(beanFactory)) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The SDK's own log lines, through the host's logging rather than {@code System.err}. */
    private static FeedLogger feedLogger() {
        return FeedLogger.delegatingTo((level, message) -> {
            if ("error".equals(level)) {
                log.error(message);
            } else {
                log.warn(message);
            }
        });
    }
}
