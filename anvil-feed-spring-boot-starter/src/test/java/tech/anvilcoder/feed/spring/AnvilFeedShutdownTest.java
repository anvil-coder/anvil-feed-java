package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tech.anvilcoder.feed.AnvilFeed;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.Transport;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What happens when the context closes, and what must be left behind: nothing.
 *
 * <p>{@code flush-interval-ms} is a quarter of an hour in every test here, so that the daemon pump cannot
 * be the thing that empties the queue. Whatever goes out, goes out because the shutdown sent it.
 */
class AnvilFeedShutdownTest {

    private static final String[] CONFIGURED = {
            "anvil.feed.token=" + AnvilFeedTestSupport.TOKEN,
            "anvil.feed.service=shop-api",
            "anvil.feed.flush-interval-ms=900000"
    };

    private ApplicationContextRunner runner(Transport transport) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AnvilFeedAutoConfiguration.class))
                .withBean(Transport.class, () -> transport)
                .withPropertyValues(CONFIGURED);
    }

    /** FR-2: an application that stops does not lose the events it had already recorded. */
    @Test
    void contextClosedFlushesWhatIsQueuedAndThenClosesTheClient() {
        StubIngest ingest = new StubIngest();
        AtomicReference<AnvilFeedClient> client = new AtomicReference<>();

        runner(ingest).run(context -> {
            AnvilFeedClient bean = context.getBean(AnvilFeedClient.class);
            client.set(bean);
            bean.captureMessage("queued while the context was up");
            assertThat(ingest.calls).describedAs("nothing may go out before the shutdown").isEmpty();
        });

        assertThat(ingest.calls).describedAs("the queue went out on ContextClosedEvent").hasSize(1);
        assertFalse(client.get().sender().isRunning(), "the daemon pump is stopped");
    }

    /**
     * Two pieces of PROCESS state, and both have to be given back: a second context in the same JVM —
     * devtools, an actuator restart, the next test class — would otherwise inherit a facade pointing at a
     * closed client and a chain of uncaught-exception handlers one link longer per restart.
     */
    @Test
    void nothingProcessWideSurvivesTheContext() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();

        runner(new StubIngest()).run(context -> {
            assertSame(context.getBean(AnvilFeedClient.class), AnvilFeed.current());
            assertThat(Thread.getDefaultUncaughtExceptionHandler())
                    .isInstanceOf(AnvilFeedUncaughtExceptionHandler.class);
        });

        assertNull(AnvilFeed.current(), "the facade must not point at a client that has been closed");
        assertSame(before, Thread.getDefaultUncaughtExceptionHandler());
    }

    /** And again, because "it did not leak once" and "it does not leak" are different claims. */
    @Test
    void twoContextsInOneJvmDoNotInterfere() {
        AtomicReference<AnvilFeedClient> first = new AtomicReference<>();

        runner(new StubIngest()).run(context -> first.set(context.getBean(AnvilFeedClient.class)));
        assertNull(AnvilFeed.current());

        runner(new StubIngest()).run(context -> {
            AnvilFeedClient second = context.getBean(AnvilFeedClient.class);
            assertThat(second).isNotSameAs(first.get());
            assertSame(second, AnvilFeed.current());
        });
        assertNull(AnvilFeed.current());
    }

    /** The transport the starter built is the starter's to close; one the host brought is not. */
    @Test
    void aTransportTheStarterDidNotBuildIsNotClosedByIt() {
        StubIngest ingest = new StubIngest();
        runner(ingest).run(context -> assertNull(context.getBean(AnvilFeedVerdict.class).owned()));
    }
}
