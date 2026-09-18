package shop;

/**
 * The customer's own exception class, named the way {@code conformance/valid/minimal.json} spells it.
 *
 * <p>PHP's fixture carries {@code "type": "TypeError"} because PHP's {@code \TypeError} lives in the root
 * namespace. Java has no root namespace a named package can reach — a class in the default package cannot
 * be referenced from {@code shop} at all — so the closest honest counterpart is a class of that simple
 * name in the caller's own package, and {@code exception.type} is then {@code shop.TypeError}. That
 * difference is the one substitution {@code FixtureConformanceTest} makes in the minimal fixture, and it
 * is a language fact rather than a drift: the fixture's own composition rule (title = type, colon, space,
 * message) is asserted from both sides instead.
 */
public final class TypeError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TypeError(String message) {
        super(message);
    }
}
