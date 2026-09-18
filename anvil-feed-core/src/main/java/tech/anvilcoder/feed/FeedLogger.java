package tech.anvilcoder.feed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Logging that cannot become the second failure (FR-2 b, which holds for the logger too: a sink supplied
 * by a host application is somebody else's code and may throw).
 *
 * <p>slf4j is {@code compileOnly}: the core never forces a logging implementation on a host, and an
 * application that has none still gets its messages on {@code System.err}.
 *
 * <p>The counterpart of {@code AnvilFeed\Logger} in the PHP SDK; {@link #once(String, String)} is keyed
 * per instance, so one logger shared between {@link AnvilFeedConfig} and the client keys the whole
 * process. Keys already in use: {@code token}, {@code service}, {@code service-length},
 * {@code environment}, {@code clamp:max_queue_size}.
 */
public final class FeedLogger {

    private final BiConsumer<String, String> sink;
    private final Map<String, Boolean> said = new ConcurrentHashMap<>();

    private FeedLogger(BiConsumer<String, String> sink) {
        this.sink = sink;
    }

    public static FeedLogger toSystemLogger() {
        return new FeedLogger((level, message) -> System.err.println("[" + level + "] " + message));
    }

    public static FeedLogger delegatingTo(BiConsumer<String, String> sink) {
        return new FeedLogger(sink);
    }

    public void warning(String message) {
        write("warn", message);
    }

    public void error(String message) {
        write("error", message);
    }

    /** Says it the first time and never again. */
    public void once(String key, String message) {
        if (said.putIfAbsent(key, Boolean.TRUE) == null) {
            write("warn", message);
        }
    }

    private void write(String level, String message) {
        try {
            sink.accept(level, message);
        } catch (RuntimeException | Error somebodyElsesLogger) {
            // Deliberately empty. There is nowhere left to report to.
        }
    }
}
