package shop;

/** A domain failure the application answers with a 503 — a server failure it knew about. */
public final class Overloaded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Overloaded(String message) {
        super(message);
    }
}
