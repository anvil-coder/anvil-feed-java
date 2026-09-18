package tech.anvilcoder.feed.spring;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import shop.CheckoutService;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One fault is one event, whichever end of the cause chain a capture point is handed.
 *
 * <p>The earlier registry recorded only the head of the chain, so the walk was one-directional: it caught
 * "wrapper after cause" and missed "cause after wrapper". Both directions really occur — the servlet layer
 * wraps on the way out, Spring's scheduling and async layers unwrap on the way in — and the cost of
 * missing one is the doubled cockpit count this class exists to prevent.
 */
class AnvilFeedReportedTest {

    @AfterEach
    void clear() {
        AnvilFeedReported.forget();
    }

    @Test
    void theCauseAfterTheWrapperIsTheSameFault() {
        Throwable cause = CheckoutService.charge();
        Throwable wrapper = new ServletException("Request processing failed", cause);

        assertTrue(AnvilFeedReported.firstTime(wrapper));
        assertFalse(AnvilFeedReported.firstTime(cause), "unwrapping does not make it a new fault");
    }

    @Test
    void theWrapperAfterTheCauseIsTheSameFault() {
        Throwable cause = CheckoutService.charge();
        Throwable wrapper = new ServletException("Request processing failed", cause);

        assertTrue(AnvilFeedReported.firstTime(cause));
        assertFalse(AnvilFeedReported.firstTime(wrapper), "rewrapping does not make it a new fault");
    }

    @Test
    void twoUnrelatedFaultsAreTwoEvents() {
        assertTrue(AnvilFeedReported.firstTime(CheckoutService.charge()));
        assertTrue(AnvilFeedReported.firstTime(CheckoutService.charge()));
    }

    /** A chain that points at itself is walked once, not for ever, inside an error path. */
    @Test
    void aSelfReferencingChainTerminates() {
        Throwable looping = new IllegalStateException("round and round") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertTrue(AnvilFeedReported.firstTime(looping));
        assertFalse(AnvilFeedReported.firstTime(looping));
    }

    @Test
    void nullIsNeverAFault() {
        assertFalse(AnvilFeedReported.firstTime(null));
    }

    /**
     * The decision has to stay atomic now that the registry is striped rather than behind one monitor:
     * two threads handed the same throwable must still produce one event.
     */
    @Test
    void twoThreadsRacingOnOneThrowableProduceOneEvent() throws Exception {
        for (int round = 0; round < 200; round++) {
            Throwable one = CheckoutService.charge();
            AtomicInteger wins = new AtomicInteger();
            Runnable claim = () -> {
                if (AnvilFeedReported.firstTime(one)) {
                    wins.incrementAndGet();
                }
            };
            Thread first = new Thread(claim);
            Thread second = new Thread(claim);
            first.start();
            second.start();
            first.join();
            second.join();

            assertThat(wins.get()).describedAs("round %d", round).isEqualTo(1);
        }
    }
}
