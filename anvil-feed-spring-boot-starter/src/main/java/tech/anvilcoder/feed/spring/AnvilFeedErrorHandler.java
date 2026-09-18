package tech.anvilcoder.feed.spring;

import org.springframework.scheduling.support.TaskUtils;
import org.springframework.util.ErrorHandler;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

/**
 * What a {@code @Scheduled} method's exception meets (FR-1 b). Spring's task scheduler catches it, hands
 * it to its {@link ErrorHandler} and keeps the schedule running — so a nightly reconciliation job can fail
 * every night for a month with one {@code WARN} per night and nothing in any inbox.
 *
 * <p>Spring's default is {@code TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER}, and that default is DELEGATED
 * to rather than replaced: the log line an operator's alerting may already be watching for keeps being
 * written, and the event is added beside it. Suppressing (rather than propagating) is also what keeps the
 * schedule alive, which is Spring's decision to make and not this SDK's.
 */
public final class AnvilFeedErrorHandler implements ErrorHandler {

    private final AnvilFeedClient client;
    private final ErrorHandler delegate;

    public AnvilFeedErrorHandler(AnvilFeedClient client) {
        this(client, TaskUtils.getDefaultErrorHandler(true));
    }

    AnvilFeedErrorHandler(AnvilFeedClient client, ErrorHandler delegate) {
        this.client = client;
        this.delegate = delegate;
    }

    @Override
    public void handleError(Throwable error) {
        try {
            if (AnvilFeedReported.firstTime(error)) {
                client.captureException(error, Map.of("tags", Map.of("scheduled", "true")));
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b.
        }
        delegate.handleError(error);
    }
}
