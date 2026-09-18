package tech.anvilcoder.feed.spring;

import org.springframework.core.task.TaskDecorator;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

/**
 * Wraps every task Spring's executors run, so an {@code @Async} method that returns void — where the
 * exception has nowhere to go and Spring logs it and moves on — still produces an event (FR-1 b).
 *
 * <p>The throwable is rethrown afterwards: this decorator observes, it does not swallow. It is registered
 * only when the application has no {@link TaskDecorator} of its own, because Spring's executor builders
 * take one decorator and replacing somebody's would take their MDC or their security context away.
 *
 * <p>An {@code @Async} call also reaches {@link AnvilFeedAsyncExceptionHandler}; the same throwable is
 * therefore seen twice and {@link AnvilFeedReported} keeps it at one event.
 */
public final class AnvilFeedTaskDecorator implements TaskDecorator {

    private final AnvilFeedClient client;

    public AnvilFeedTaskDecorator(AnvilFeedClient client) {
        this.client = client;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (RuntimeException | Error theirs) {
                try {
                    if (AnvilFeedReported.firstTime(theirs)) {
                        client.captureException(theirs, Map.of("tags", Map.of("async", "true")));
                    }
                } catch (RuntimeException | Error ours) {
                    // FR-2 b.
                }
                throw theirs;
            }
        };
    }
}
