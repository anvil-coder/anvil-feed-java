package shop;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The customer's controller: one route that works, one that breaks, and one that IS the ingest. */
@RestController
public class BookingController {

    @GetMapping("/bookings/{id}")
    public String show(@PathVariable("id") String id) {
        throw new IllegalStateException("Cannot invoke \"Booking.total()\" because \"booking\" is null");
    }

    /**
     * The dogfooding case: {@code dark-factory} runs this starter AND hosts the ingest, so the one route
     * an exception may never be reported from is the ingest's own.
     */
    @GetMapping("/api/feed/events")
    public String ingest() {
        throw new IllegalStateException("the ingest itself failed");
    }

    @GetMapping("/orders")
    public String orders() {
        return "ok";
    }

    /** Handled by the application's own {@code @RestControllerAdvice}: a deliberate 402. */
    @GetMapping("/pay")
    public String pay() {
        throw new Declined("the card was declined");
    }

    /** A client error the exception itself declares. */
    @GetMapping("/missing")
    public String missing() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such booking");
    }

    /** A server failure the exception itself declares. */
    @GetMapping("/upstream")
    public String upstream() {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "the payment provider is down");
    }

    /**
     * The fault nobody expected: no declared status, and the application's own advice turns it into a 500
     * {@code ProblemDetail}. The client saw a server failure, so it is an incident — and the event has to
     * be about the {@code NullPointerException}, not about anything wrapping it.
     */
    @GetMapping("/unexpected")
    public String unexpected() {
        throw new NullPointerException("Cannot invoke \"Cart.total()\" because \"cart\" is null");
    }

    /** No declared status either, and the advice answers 503: still a server failure. */
    @GetMapping("/overloaded")
    public String overloaded() {
        throw new Overloaded("the booking engine is saturated");
    }

    /** Spring's own {@code MissingServletRequestParameterException}: an ErrorResponse with a 400. */
    @GetMapping("/search")
    public String search(@RequestParam("q") String query) {
        return query;
    }
}
