package shop;

/**
 * A stand-in for the customer's own code, and the only place in this module's tests that raises the
 * exceptions under test. It lives OUTSIDE {@code tech.anvilcoder.feed} on purpose: every frame inside that
 * package — the starter's own included — is an SDK frame to {@code EventFactory}, so an exception raised
 * from a test class in the starter's package would produce an empty stack and measure nothing.
 */
public final class CheckoutService {

    private CheckoutService() {
    }

    /** The throw site is this method, so {@code stacktrace[0]} names it. */
    public static RuntimeException charge() {
        return new IllegalStateException("Cannot invoke \"Cart.total()\" because \"cart\" is null");
    }
}
