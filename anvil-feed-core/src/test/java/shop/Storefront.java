package shop;

import tech.anvilcoder.feed.AnvilFeedClient;

import java.util.Map;

/**
 * The customer's own code, calling the CLIENT rather than the factory. It lives OUTSIDE
 * {@code tech.anvilcoder.feed} on purpose, and so does {@link CheckoutService}: every frame of the SDK's
 * own package is dropped from a synthesised stack, so a test that called {@code captureMessage} from
 * inside the SDK's package would be measuring an empty stack and would pass whatever the rule did.
 *
 * <p>The two report methods are deliberately two DIFFERENT methods. Two calls from one method would share
 * a fingerprint even if the caller's stack were thrown away entirely, which is exactly the defect the
 * pair is there to catch.
 */
public final class Storefront {

    private Storefront() {
    }

    public static String reportFromCheckout(AnvilFeedClient client, String message) {
        return client.captureMessage(message, "warning", Map.of());
    }

    public static String reportFromShipping(AnvilFeedClient client, String message) {
        return client.captureMessage(message, "warning", Map.of());
    }

    public static String verify(AnvilFeedClient client) {
        return client.captureTestEvent();
    }

    public static String fail(AnvilFeedClient client, Map<String, Object> context) {
        return client.captureException(CheckoutService.charge(), context);
    }
}
