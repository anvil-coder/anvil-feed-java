package shop;

import tech.anvilcoder.feed.AnvilFeed;

import java.util.Map;

/**
 * The stand-in for customer code that the golden fixtures are captured through — the counterpart of
 * {@code php/anvil-feed-php/tests/Support/ConformanceCaller.php}, and in its own file for the same reason:
 * {@code conformance/valid/message-event.json} and {@code valid/test-event.json} exist because an event
 * whose stack leads with the reporting library's own files is a defect, and a frame belonging to
 * anvil-feed must not be able to pass for one. A caller inside {@code tech.anvilcoder.feed} would have
 * every frame dropped and the assertion would be measuring an empty stack.
 *
 * <p>Every method here goes through the STATIC facade, so what is exercised is the whole documented path
 * {@code AnvilFeed.init} → capture → {@code flush} and not an object a test happened to hold.
 *
 * <p>One method per fixture, deliberately: two captures from one method share a fingerprint whatever the
 * caller-stack rule does, which is the defect the pair of report methods in {@link Storefront} exists to
 * catch.
 */
public final class ConformanceCaller {

    private ConformanceCaller() {
    }

    /** {@code conformance/valid/message-event.json}. */
    public static String reportTheReconciliation() {
        return AnvilFeed.captureMessage("Payment reconciliation skipped for 3 accounts", "warning", Map.of());
    }

    /** {@code conformance/valid/test-event.json}. */
    public static String verifyTheIntegration() {
        return AnvilFeed.captureTestEvent();
    }

    /** {@code conformance/valid/minimal.json} — a real throw, with the fixture's own message. */
    public static String reportTheMinimalFault() {
        return AnvilFeed.captureException(new TypeError("Cannot read property 'id' of undefined"));
    }

    /** {@code conformance/pii/raw.json} — the raw fixture's own secrets, handed in as a caller's context. */
    public static String reportTheAuthFailure(String message, Map<String, Object> context) {
        return AnvilFeed.captureException(new IllegalStateException(message), context);
    }

    /** A capture with whatever a hostile-corpus entry wants to hand over. */
    public static String report(String message, String level, Map<String, Object> context) {
        return AnvilFeed.captureMessage(message, level, context);
    }

    /** A capture of a real throwable with whatever context a hostile-corpus entry wants to hand over. */
    public static String fail(Throwable error, Map<String, Object> context) {
        return AnvilFeed.captureException(error, context);
    }
}
