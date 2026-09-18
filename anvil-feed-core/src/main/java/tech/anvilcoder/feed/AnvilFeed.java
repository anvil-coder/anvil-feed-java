package tech.anvilcoder.feed;

import java.util.Map;

/**
 * The static face of the SDK, for the call sites that have no container. Every method is a no-op until
 * {@link #init} has run, so a library calling {@code AnvilFeed.captureException} inside an application
 * that never configured the SDK does nothing at all rather than failing.
 *
 * <p>{@link #init(AnvilFeedConfig)} is <b>idempotent</b>: the second call returns the client the first one
 * built, because two clients in one process would be two rate gates, two backoff counters and two sets of
 * "said once" state — and the second one would silently undo the first one's storm suppression. And its
 * <b>disabled path is silent</b>: a configuration that switched the SDK off has already said so once, at
 * build time, and a facade that repeated it would be a second line in every process that deliberately
 * runs without a token.
 *
 * <p>A host with a container should prefer an injected {@link AnvilFeedClient} and call
 * {@link #init(AnvilFeedClient)} once so that library code reaches the same instance.
 */
public final class AnvilFeed {

    private static final Object LOCK = new Object();

    private static volatile AnvilFeedClient client;

    private AnvilFeed() {
    }

    /**
     * Builds, starts and installs the process's client — once. A second call with any configuration
     * returns the client already installed and builds nothing.
     *
     * @return the installed client, never null
     */
    public static AnvilFeedClient init(AnvilFeedConfig config) {
        AnvilFeedClient installed = client;
        if (installed != null) {
            return installed;
        }
        synchronized (LOCK) {
            if (client == null) {
                AnvilFeedClient fresh = AnvilFeedClient.create(config);
                // A no-op for a disabled configuration: no thread, no shutdown hook, and nothing said.
                fresh.start();
                client = fresh;
            }
            return client;
        }
    }

    /** Installs a client the host built itself. Replaces whatever was installed; it does not close it. */
    public static void init(AnvilFeedClient value) {
        synchronized (LOCK) {
            client = value;
        }
    }

    /** The installed client, or null when {@link #init} has not run. */
    public static AnvilFeedClient current() {
        return client;
    }

    /** The name the PHP twin uses. Identical to {@link #current()}. */
    public static AnvilFeedClient client() {
        return client;
    }

    /** For tests and for a host that tears the SDK down; the facade is process-wide state. */
    public static void reset() {
        synchronized (LOCK) {
            client = null;
        }
    }

    public static String captureException(Throwable error) {
        AnvilFeedClient current = client;
        return current == null ? null : current.captureException(error);
    }

    public static String captureException(Throwable error, Map<String, Object> context) {
        AnvilFeedClient current = client;
        return current == null ? null : current.captureException(error, context);
    }

    public static String captureMessage(String message) {
        AnvilFeedClient current = client;
        return current == null ? null : current.captureMessage(message);
    }

    public static String captureMessage(String message, String level, Map<String, Object> context) {
        AnvilFeedClient current = client;
        return current == null ? null : current.captureMessage(message, level, context);
    }

    public static String captureTestEvent() {
        AnvilFeedClient current = client;
        return current == null ? null : current.captureTestEvent();
    }

    public static void flush() {
        AnvilFeedClient current = client;
        if (current != null) {
            current.flush();
        }
    }

    public static void flush(long budgetMs) {
        AnvilFeedClient current = client;
        if (current != null) {
            current.flush(budgetMs);
        }
    }
}
