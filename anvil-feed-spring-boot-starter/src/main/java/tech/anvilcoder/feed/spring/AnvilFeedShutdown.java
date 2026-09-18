package tech.anvilcoder.feed.spring;

import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

import tech.anvilcoder.feed.AnvilFeed;
import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The last thing that happens: what is queued goes out, and then everything this starter installed is
 * taken back down.
 *
 * <p>{@link ContextClosedEvent} rather than a destroy method, because it is published BEFORE any singleton
 * is destroyed — the client, the transport and the sender are all still usable, which is what a final
 * flush needs. The budget is {@code request_timeout_ms}: the same bound FR-2 (c) puts on a request, so a
 * shutdown cannot be held up longer than one HTTP call the application was already willing to make.
 *
 * <p>The core's {@code Sender} owns the daemon pump AND a shutdown hook of its own, so a process that is
 * killed still empties its queue; this listener is the orderly path, not a second hook.
 *
 * <p>It also undoes the two pieces of PROCESS state the starter installs: the default uncaught-exception
 * handler and the {@link AnvilFeed} facade. Without that, a second Spring context in the same JVM — a test
 * suite, devtools, an actuator restart — would inherit a facade pointing at a closed client, and
 * {@code AnvilFeed.captureException} from library code would queue into a client that will never send.
 */
public final class AnvilFeedShutdown implements ApplicationListener<ContextClosedEvent> {

    private final AnvilFeedClient client;
    private final AnvilFeedConfig config;
    private final AnvilFeedVerdict verdict;
    private final AnvilFeedUncaughtExceptionHandler handler;
    private final AtomicBoolean done = new AtomicBoolean();

    public AnvilFeedShutdown(AnvilFeedClient client, AnvilFeedConfig config, AnvilFeedVerdict verdict,
                             AnvilFeedUncaughtExceptionHandler handler) {
        this.client = client;
        this.config = config;
        this.verdict = verdict;
        this.handler = handler;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!done.compareAndSet(false, true)) {
            return;   // a context can be closed twice; the queue may not be flushed twice
        }
        try {
            client.flush(config.requestTimeoutMs());
            client.close();
        } catch (RuntimeException | Error ours) {
            // FR-2 b: a failing SDK may not fail a host's shutdown.
        }
        if (handler != null) {
            handler.uninstall();
        }
        if (AnvilFeed.current() == client) {
            AnvilFeed.reset();
        }
        verdict.closeOwned();
        AnvilFeedReported.forget();
    }
}
