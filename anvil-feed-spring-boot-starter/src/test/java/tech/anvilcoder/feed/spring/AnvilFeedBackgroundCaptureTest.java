package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.AsyncConfigurer;
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
    void theStarterRegistersTheAsyncAndSchedulerHooks() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withBean(Transport.class, () -> new StubIngest())
                .withPropertyValues("anvil.feed.token=" + AnvilFeedTestSupport.TOKEN,
                        "anvil.feed.service=shop-api")
                .run(context -> {
                    assertThat(context).hasSingleBean(AsyncConfigurer.class);
                    assertThat(context).hasSingleBean(ThreadPoolTaskSchedulerCustomizer.class);
                    assertThat(context.getBean(AsyncConfigurer.class)
                            .getAsyncUncaughtExceptionHandler())
                            .isInstanceOf(AnvilFeedAsyncExceptionHandler.class);
                    assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                            .describedAs("null keeps Spring's own executor; taking it would be a trade "
                                    + "nobody agreed to")
                            .isNull();
                });
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
