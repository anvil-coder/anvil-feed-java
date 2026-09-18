package tech.anvilcoder.feed.spring;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

/**
 * The default handler for every thread that has none — a raw {@code new Thread(...)}, a library's own
 * pool, a scheduler nobody configured. Without it, the exceptions that kill a background worker are the
 * ones nobody ever sees, which is the category FR-1 exists for.
 *
 * <p>The previous default is kept and called afterwards, so the JVM's own stack trace on
 * {@code System.err} does not disappear — and {@link #uninstall()} puts it back, so a Spring context that
 * closes does not leave this process's global handler pointing at a client that has been shut down. That
 * matters outside tests too: an application that restarts its context (Spring Boot devtools, an actuator
 * restart, a test suite with several contexts) would otherwise chain one handler per restart.
 */
public final class AnvilFeedUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final AnvilFeedClient client;
    private final Thread.UncaughtExceptionHandler previous;

    public AnvilFeedUncaughtExceptionHandler(AnvilFeedClient client,
                                             Thread.UncaughtExceptionHandler previous) {
        this.client = client;
        this.previous = previous;
    }

    /** Installs itself as the process default, chaining whatever was there. */
    public static AnvilFeedUncaughtExceptionHandler install(AnvilFeedClient client) {
        AnvilFeedUncaughtExceptionHandler installed =
                new AnvilFeedUncaughtExceptionHandler(client, Thread.getDefaultUncaughtExceptionHandler());
        Thread.setDefaultUncaughtExceptionHandler(installed);
        return installed;
    }

    /** Restores the handler that was there before, but only if nobody else has installed one since. */
    public void uninstall() {
        if (Thread.getDefaultUncaughtExceptionHandler() == this) {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            if (AnvilFeedReported.firstTime(error)) {
                client.captureException(error, Map.of("level", "fatal",
                        "tags", Map.of("thread", thread.getName())));
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b, and FR-2 b's own last clause: an error in the error handler must not recurse.
        }
        if (previous != null) {
            previous.uncaughtException(thread, error);
        }
    }
}
