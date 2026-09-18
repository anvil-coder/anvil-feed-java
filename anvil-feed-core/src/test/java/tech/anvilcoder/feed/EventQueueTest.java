package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-2 (d) and FR-3 (b): the bound holds, and an overflow is visible rather than silent. */
class EventQueueTest {

    private final RecordingLogger written = new RecordingLogger();

    private static FeedEvent event(String id) {
        return new FeedEvent(id, Map.of("event_id", id));
    }

    private EventQueue queue(int maxSize) {
        return new EventQueue(maxSize, written.logger());
    }

    private static List<String> idsOf(List<FeedEvent> events) {
        return events.stream().map(FeedEvent::id).toList();
    }

    @Test
    void neverExceedsItsBoundAndDropsTheOldest() {
        EventQueue queue = queue(3);
        for (String id : List.of("a", "b", "c", "d", "e")) {
            queue.push(event(id));
        }

        assertThat(queue.size()).isEqualTo(3);
        assertThat(queue.droppedCount()).isEqualTo(2);
        assertThat(idsOf(queue.take(3)))
                .describedAs("the OLDEST goes: the newest error is the one somebody is looking at now")
                .containsExactly("c", "d", "e");
    }

    @Test
    void everyDropIsSaidOnceAndNamesTheRunningCount() {
        EventQueue queue = queue(2);
        for (String id : List.of("a", "b", "c", "d")) {
            queue.push(event(id));
        }

        assertThat(written.matching("local queue full"))
                .describedAs("one warning per drop EVENT, not one per lost event and not one for all of them")
                .hasSize(2);
        assertThat(written.lines().get(1))
                .describedAs("the running total is what tells 'the endpoint was down' from 'this process "
                        + "produces more errors than it can report' apart")
                .contains("2 since start");
    }

    @Test
    void aRequeuedBatchKeepsItsOrderAndGoesToTheFront() {
        EventQueue queue = queue(10);
        queue.push(event("c"));
        queue.unshiftAll(List.of(event("a"), event("b")));

        assertThat(idsOf(queue.take(3))).containsExactly("a", "b", "c");
    }

    @Test
    void aRequeuedBatchIsBoundedToo() {
        EventQueue queue = queue(3);
        queue.push(event("x"));
        queue.unshiftAll(List.of(event("a"), event("b"), event("c")));

        assertThat(queue.size())
                .describedAs("a 429 that requeues a batch of 50 into a queue of 10 may not make it 60")
                .isEqualTo(3);
        assertThat(queue.droppedCount()).isEqualTo(1);
    }

    @Test
    void takeNeverReturnsMoreThanThereIs() {
        EventQueue queue = queue(10);
        queue.push(event("a"));

        assertThat(queue.take(50)).hasSize(1);
        assertThat(queue.take(50)).isEmpty();
        assertThat(queue.take(-1)).isEmpty();
    }

    @Test
    void aBoundBelowOneIsClampedRatherThanTurningEveryPushIntoADropAndAWarning() {
        for (int bound : new int[] {0, -1, -1000}) {
            RecordingLogger sink = new RecordingLogger();
            EventQueue queue = new EventQueue(bound, sink.logger());
            queue.push(event("a"));

            assertThat(queue.size())
                    .describedAs("a bound below one is not a smaller queue, it is a log flood on the one "
                            + "code path that exists to prevent one (bound " + bound + ")")
                    .isEqualTo(1);
            assertThat(queue.droppedCount())
                    .describedAs("and the negative arithmetic must not over-report either")
                    .isZero();
        }
    }

    @Test
    void clearEmptiesIt() {
        EventQueue queue = queue(10);
        queue.push(event("a"));
        queue.clear();

        assertThat(queue.size()).isZero();
    }

    /**
     * The host's request threads push while the daemon sender thread drains. An unsynchronised ArrayDeque
     * loses elements or throws under exactly this, and the symptom at a customer's is an inbox that is
     * missing events nobody can account for.
     */
    @Test
    void survivesConcurrentPushesAndTakes() throws Exception {
        EventQueue queue = queue(100_000);
        int threads = 8;
        int perThread = 2_000;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<FeedEvent> drained = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread pusher = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        queue.push(event(id + "-" + i));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            pusher.setDaemon(true);
            pusher.start();
        }

        Thread drainer = new Thread(() -> {
            while (done.getCount() > 0 || queue.size() > 0) {
                drained.addAll(queue.take(50));
            }
        });
        drainer.setDaemon(true);
        go.countDown();
        drainer.start();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        drainer.join(30_000);

        assertThat(drained.size() + queue.size())
                .describedAs("every event that was pushed is either drained or still queued — none is lost "
                        + "and none is duplicated")
                .isEqualTo(threads * perThread);
        assertThat(queue.droppedCount()).isZero();
    }
}
