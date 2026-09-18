package shop;

import tech.anvilcoder.feed.EventFactory;
import tech.anvilcoder.feed.FeedEvent;

import java.util.Map;

/**
 * A stand-in for the customer's own code. It lives OUTSIDE {@code tech.anvilcoder.feed} on purpose: the
 * in-app rule and the caller-stack rule are both package rules, and a test that calls the factory from the
 * SDK's own package would be measuring neither.
 */
public final class CheckoutService {

    private CheckoutService() {
    }

    /** The throw site is this method, so {@code stacktrace[0]} must name it. */
    public static RuntimeException charge() {
        return new IllegalStateException("Cannot invoke \"Cart.total()\" because \"cart\" is null");
    }

    /**
     * The same, with the CALLER's message. It exists so that the two SDKs' end-to-end runs can throw one
     * identical message and {@code exception.message} can leave the cross-language mask — the type and the
     * frames cannot be held together across two languages, and the message is the only part of the block a
     * caller controls. Created HERE, like {@link #charge()}, so {@code stacktrace[0]} names customer code.
     */
    public static RuntimeException failWith(String message) {
        return new RuntimeException(message);
    }

    public static FeedEvent report(EventFactory factory, String message, Map<String, Object> context) {
        return factory.fromMessage(message, "warning", context);
    }

    public static FeedEvent verify(EventFactory factory) {
        return factory.testEvent();
    }
}
