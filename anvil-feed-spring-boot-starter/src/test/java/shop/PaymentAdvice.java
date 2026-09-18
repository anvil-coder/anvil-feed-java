package shop;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The application's own error handling — the thing an error reporter must neither replace nor report.
 * A {@code Declined} is a deliberate 402, not an incident.
 */
@RestControllerAdvice
public class PaymentAdvice {

    @ExceptionHandler(Declined.class)
    public ResponseEntity<String> declined(Declined error) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(error.getMessage());
    }
}
