package tech.anvilcoder.feed.spring;

import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.FeedLogger;
import tech.anvilcoder.feed.GitContext;
import tech.anvilcoder.feed.Transport;

import java.util.List;
import java.util.Map;

/** One configured client, built the way the starter builds it but without a context around it. */
final class AnvilFeedTestSupport {

    static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    private AnvilFeedTestSupport() {
    }

    static AnvilFeedConfig config() {
        return AnvilFeedConfig.builder().token(TOKEN).service("shop-api").build(FeedLogger.toSystemLogger());
    }

    static AnvilFeedClient client(Transport transport) {
        // start() is deliberately NOT called: nothing drains the queue, so every assertion about what was
        // captured is about the queue itself rather than about a race with the daemon pump.
        return AnvilFeedClient.create(config(), transport, FeedLogger.toSystemLogger(), GitContext.none());
    }

    static AnvilFeedClient client() {
        return client(new StubIngest());
    }

    /** The one queued event's payload. */
    static Map<String, Object> onlyEvent(AnvilFeedClient client) {
        List<tech.anvilcoder.feed.FeedEvent> taken = client.queue().take(2);
        if (taken.size() != 1) {
            throw new AssertionError("expected exactly one queued event, found " + taken.size());
        }
        return taken.get(0).payload();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
