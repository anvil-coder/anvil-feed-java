package shop;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The application's own error handling — and all three shapes of it, because the difference between them
 * is exactly what the report gate decides on.
 *
 * <ul>
 *   <li>{@code Declined} becomes a 402: a deliberate answer, and not an incident.</li>
 *   <li>{@code Overloaded} becomes a 503: the application knew about it and it is still a server
 *       failure.</li>
 *   <li>Anything unexpected becomes a 500 {@code ProblemDetail}. This is the commonest global-error-handler
 *       idiom in Spring and the fault a repair agent most wants; a gate that asked "did a handler answer
 *       it?" instead of "what did the client see?" would lose it.</li>
 * </ul>
 */
@RestControllerAdvice
public class PaymentAdvice {

    @ExceptionHandler(Declined.class)
    public ResponseEntity<String> declined(Declined error) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error.getMessage());
    }

    @ExceptionHandler(Overloaded.class)
    public ResponseEntity<String> overloaded(Overloaded error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error.getMessage());
    }

    @ExceptionHandler(NullPointerException.class)
    public ProblemDetail unexpected(NullPointerException error) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setDetail("Something went wrong. The incident has been recorded.");
        return problem;
    }
}
