package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

import shop.CheckoutService;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.Transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.map;
import static tech.anvilcoder.feed.spring.AnvilFeedTestSupport.onlyEvent;

/**
 * The four places outside a request where a Spring application loses an exception for good: a raw thread,
 * {@code @Async}, {@code @Scheduled}, and the executor underneath both.
 */
class AnvilFeedBackgroundCaptureTest {

    @AfterEach
    void clearTheDeduplicationRegistry() {
        AnvilFeedReported.forget();
    }

    @Test
    void theTaskDecoratorCapturesAndRethrows() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        RuntimeException failure = CheckoutService.charge();

        Runnable decorated = new AnvilFeedTaskDecorator(client).decorate(() -> {
            throw failure;
        });

        assertThatThrownBy(decorated::run).describedAs("observes, does not swallow").isSameAs(failure);
        assertEquals("true", map(onlyEvent(client).get("tags")).get("async"));
    }

    @Test
    void theAsyncHandlerNamesTheMethodThatFailed() throws Exception {
        AnvilFeedClient client = AnvilFeedTestSupport.client();

        new AnvilFeedAsyncExceptionHandler(client).handleUncaughtException(CheckoutService.charge(),
                CheckoutService.class.getMethod("charge"));

        assertEquals("CheckoutService.charge", map(onlyEvent(client).get("tags")).get("method"));
    }

    /** Spring's own handler logs and keeps the schedule alive; ours adds the event and lets it do that. */
    @Test
    void theScheduledErrorHandlerCapturesAndStillDelegates() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        AtomicReference<Throwable> delegated = new AtomicReference<>();
        ErrorHandler delegate = delegated::set;
        RuntimeException failure = CheckoutService.charge();

        new AnvilFeedErrorHandler(client, delegate).handleError(failure);

        assertEquals("true", map(onlyEvent(client).get("tags")).get("scheduled"));
        assertSame(failure, delegated.get(), "the log line an operator may already be alerting on stays");
    }

    @Test
    void theUncaughtHandlerChainsThePreviousOneAndGivesItBack() {
        AnvilFeedClient client = AnvilFeedTestSupport.client();
        AtomicReference<Throwable> previousSaw = new AtomicReference<>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> previousSaw.set(error));
        try {
            AnvilFeedUncaughtExceptionHandler installed = AnvilFeedUncaughtExceptionHandler.install(client);
            RuntimeException failure = CheckoutService.charge();

            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure);

            Map<String, Object> payload = onlyEvent(client);
            assertEquals("fatal", payload.get("level"));
            assertSame(failure, previousSaw.get(), "the JVM's own stack trace must not disappear");

            installed.uninstall();
            assertThat(Thread.getDefaultUncaughtExceptionHandler())
                    .isNotInstanceOf(AnvilFeedUncaughtExceptionHandler.class);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    /** The handler alone would be a bean nothing consults: Spring reads it off an AsyncConfigurer. */
    @Test
    void theStarterRegistersTheAsyncHook() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(AsyncConfigurer.class);
            assertThat(context.getBean(AsyncConfigurer.class).getAsyncUncaughtExceptionHandler())
                    .isInstanceOf(AnvilFeedAsyncExceptionHandler.class);
            assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                    .describedAs("null keeps Spring's own executor; taking it would be a trade nobody "
                            + "agreed to")
                    .isNull();
        });
    }

    /**
     * A scheduler customizer is an ADDITIVE extension point — Spring Boot's builder applies every one of
     * them. Counting beans is the proxy that hid the defect this test exists for: with
     * {@code @ConditionalOnMissingBean(ThreadPoolTaskSchedulerCustomizer.class)}, any application with a
     * customizer of its own (one line, {@code s -> s.setThreadNamePrefix("app-")}, which most production
     * applications have) silently lost {@code @Scheduled} capture. So this applies what the context really
     * has to a real scheduler and reads the error handler back off it.
     */
    @Test
    void theSchedulerHookSurvivesAnApplicationsOwnCustomizer() {
        ThreadPoolTaskSchedulerCustomizer theirs = scheduler -> scheduler.setThreadNamePrefix("app-");

        runner().withBean("theirs", ThreadPoolTaskSchedulerCustomizer.class, () -> theirs).run(context -> {
            RecordingScheduler scheduler = new RecordingScheduler();
            context.getBeanProvider(ThreadPoolTaskSchedulerCustomizer.class)
                    .orderedStream().forEach(customizer -> customizer.customize(scheduler));

            assertThat(scheduler.handler)
                    .describedAs("the nightly job that fails every night must still reach an inbox")
                    .isInstanceOf(AnvilFeedErrorHandler.class);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("app-");
        });
    }

    /** The same, for the scheduler Boot builds when virtual threads are on. */
    @Test
    void theVirtualThreadSchedulerIsCoveredToo() {
        runner().run(context -> {
            RecordingVirtualScheduler scheduler = new RecordingVirtualScheduler();
            context.getBeanProvider(SimpleAsyncTaskSchedulerCustomizer.class)
                    .orderedStream().forEach(customizer -> customizer.customize(scheduler));

            assertThat(scheduler.handler).isInstanceOf(AnvilFeedErrorHandler.class);
        });
    }

    /**
     * Ours is ordered first, so an application that sets its OWN error handler still wins: the last write
     * to the scheduler is the one that counts.
     */
    @Test
    void anApplicationsOwnErrorHandlerStillWins() {
        ErrorHandler theirs = error -> { };
        ThreadPoolTaskSchedulerCustomizer theirCustomizer = scheduler -> scheduler.setErrorHandler(theirs);

        runner().withBean("theirs", ThreadPoolTaskSchedulerCustomizer.class, () -> theirCustomizer)
                .run(context -> {
                    RecordingScheduler scheduler = new RecordingScheduler();
                    context.getBeanProvider(ThreadPoolTaskSchedulerCustomizer.class)
                            .orderedStream().forEach(customizer -> customizer.customize(scheduler));

                    assertThat(scheduler.handler).isSameAs(theirs);
                });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withBean(Transport.class, () -> new StubIngest())
                .withPropertyValues("anvil.feed.token=" + AnvilFeedTestSupport.TOKEN,
                        "anvil.feed.service=shop-api");
    }

    /** {@code ThreadPoolTaskScheduler} has a setter for the error handler and no getter. */
    static final class RecordingScheduler extends ThreadPoolTaskScheduler {

        private static final long serialVersionUID = 1L;

        private transient ErrorHandler handler;

        @Override
        public void setErrorHandler(ErrorHandler errorHandler) {
            this.handler = errorHandler;
        }
    }

    static final class RecordingVirtualScheduler extends SimpleAsyncTaskScheduler {

        private static final long serialVersionUID = 1L;

        private transient ErrorHandler handler;

        @Override
        public void setErrorHandler(ErrorHandler errorHandler) {
            this.handler = errorHandler;
        }
    }

    /** An application that has configured its own AsyncConfigurer keeps it, handler included. */
    @Test
    void anApplicationsOwnAsyncConfigurerWins() {
        AsyncConfigurer mine = new AsyncConfigurer() { };
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withBean(Transport.class, () -> new StubIngest())
                .withBean(AsyncConfigurer.class, () -> mine)
                .withPropertyValues("anvil.feed.token=" + AnvilFeedTestSupport.TOKEN,
                        "anvil.feed.service=shop-api")
                .run(context -> assertEquals(List.of(mine),
                        List.copyOf(context.getBeansOfType(AsyncConfigurer.class).values())));
    }
}
