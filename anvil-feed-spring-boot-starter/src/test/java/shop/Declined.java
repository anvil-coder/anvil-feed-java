package shop;

/** A domain failure the application answers itself, through its own {@code @RestControllerAdvice}. */
public final class Declined extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Declined(String message) {
        super(message);
    }
}
