package tech.anvilcoder.feed.spring;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * The {@code @Async} path Spring routes void-returning methods through (FR-1 b). It is registered through
 * an {@code AsyncConfigurer} only when the application has not configured one of its own — replacing
 * somebody's handler would silence their logging, and an SDK that does that gets removed the first time an
 * incident needs the log.
 *
 * <p>Spring's own default for this hook logs the throwable and stops. Ours logs nothing and captures; the
 * logging that was there is Spring's {@code SimpleAsyncUncaughtExceptionHandler}, which this replaces
 * only where the application never supplied one — so what is lost is a line nobody configured, and what
 * is gained is the event. The method's name travels as a tag because "an async method failed" without
 * the name is not something anybody can act on.
 */
public final class AnvilFeedAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

    private final AnvilFeedClient client;

    public AnvilFeedAsyncExceptionHandler(AnvilFeedClient client) {
        this.client = client;
    }

    @Override
    public void handleUncaughtException(Throwable error, Method method, Object... params) {
        try {
            if (AnvilFeedReported.firstTime(error)) {
                client.captureException(error, Map.of("tags",
                        Map.of("async", "true", "method", method.getDeclaringClass().getSimpleName()
                                + "." + method.getName())));
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b.
        }
    }
}
