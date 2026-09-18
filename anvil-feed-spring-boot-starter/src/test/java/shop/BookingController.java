package shop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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
}
