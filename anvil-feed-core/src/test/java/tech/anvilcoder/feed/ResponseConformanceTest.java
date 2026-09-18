package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code conformance/README.md}: "Feed the {@code body} of the matching entry in
 * {@code responses/index.json} to the SDK's response parser and assert the resulting decision (drop,
 * retry, pause, stop) against that entry's {@code status} and {@code retry_after} — the actual HTTP
 * status and header the SDK's retry rule keys on, not the file name."
 *
 * <p>This is that gate, and it is driven by the index rather than by a transcription of it: a response
 * the server learns to give is added in one place and this suite starts exercising it. Every case goes
 * over a <b>real socket</b>, so the {@code Retry-After} that decides the pause is one the transport read
 * off a header block and not one a test handed the sender.
 */
class ResponseConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path RESPONSES =
            Path.of(System.getProperty("anvilfeed.conformance")).resolve("responses");

    private static final String FIRST = "f4b1a0c2-0f4a-4a1e-9d7a-2b6c5d8e1f30";
    private static final String SECOND = "9b1c7f2e-5a34-4c18-8e0d-1f7a2b3c4d5e";

    record Entry(String file, int status, Integer retryAfter, String retry, String body) {

        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<Entry> fixtures() throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : JSON.readTree(Files.readString(RESPONSES.resolve("index.json")))) {
            String file = node.path("file").asText();
            entries.add(new Entry(
                    file,
                    node.path("status").asInt(),
                    node.path("retry_after").isNull() ? null : node.path("retry_after").asInt(),
                    node.path("retry").asText(),
                    Files.readString(RESPONSES.resolve(file))));
        }
        assertThat(entries).isNotEmpty();
        return entries.stream();
    }

    @Test
    void theIndexNamesEveryResponseFixture() throws Exception {
        List<String> indexed = fixtures().map(Entry::file).sorted().toList();
        try (var files = Files.list(RESPONSES)) {
            List<String> onDisk = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !"index.json".equals(name))
                    .sorted().toList();

            assertThat(indexed)
                    .describedAs("a response fixture the index does not name is a decision no SDK test makes")
                    .isEqualTo(onDisk);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theSenderMakesTheDecisionTheIndexNamesOverARealSocket(Entry entry) throws Exception {
        RecordingLogger written = new RecordingLogger();
        long now = 1_000_000L;

        // Everything after the first answer is a healthy 202, so that a decision of "split and try the
        // halves" can finish and be seen as what it is rather than as an endless loop.
        try (LocalIngest ingest = new LocalIngest(
                LocalIngest.answering(entry.status(), entry.body(), entry.retryAfter()))) {

            AnvilFeedConfig config = AnvilFeedConfig.builder()
                    .token("anv_srv_0123456789abcdef0123456789abcdef")
                    .service("shop-api")
                    .endpoint(ingest.endpoint())
                    .build(written.logger());

            EventQueue queue = new EventQueue(1000, written.logger());
            queue.push(event(FIRST));
            queue.push(event(SECOND));

            try (HttpTransport transport = new HttpTransport(config)) {
                Sender sender = new Sender(config, queue, transport, written.logger(), () -> 1_000_000L);
                sender.flush();

                assertThat(sender.isStopped())
                        .describedAs("none of these responses is a credential failure; only a 401 stops "
                                + "the process")
                        .isFalse();

                switch (entry.retry()) {
                    case "none" -> {
                        assertThat(queue.size())
                                .describedAs(entry.file() + ": the verdict is final — nothing of this "
                                        + "batch may be sent again")
                                .isZero();
                        assertThat(sender.pausedUntilMillis())
                                .describedAs(entry.file() + ": a final verdict is not a reason to stop "
                                        + "sending anything else")
                                .isLessThanOrEqualTo(now);
                    }
                    case "after-delay" -> {
                        assertThat(queue.size())
                                .describedAs(entry.file() + ": a rate limit keeps the events — it is the "
                                        + "one status that must never drop one")
                                .isEqualTo(2);
                        assertThat(sender.pausedUntilMillis())
                                .describedAs(entry.file() + ": the pause is exactly the Retry-After the "
                                        + "server sent, not a backoff or a constant of our own")
                                .isEqualTo(now + entry.retryAfter() * 1000L);
                    }
                    case "whole-batch" -> {
                        assertThat(queue.size())
                                .describedAs(entry.file() + ": the WHOLE batch is repeated — "
                                        + "(organisation, event_id) makes that idempotent")
                                .isEqualTo(2);
                        assertThat(sender.pausedUntilMillis())
                                .describedAs(entry.file() + ": the pause is exactly Retry-After")
                                .isEqualTo(now + entry.retryAfter() * 1000L);
                    }
                    default -> fail("unknown retry verdict " + entry.retry());
                }

                for (String code : reasonCodes(entry.body())) {
                    assertThat(written.matching(code))
                            .describedAs(entry.file() + ": the reason code " + code + " is the whole "
                                    + "message; an SDK that swallows it leaves the customer with an empty "
                                    + "inbox and no explanation")
                            .isNotEmpty();
                }
            }
        }
    }

    /**
     * The 413 split measured the only way that cannot be faked: by how many REQUESTS the ingest saw and
     * how many events each carried.
     */
    @Test
    void a413IsSplitIntoHalvesAndTheSplitIsVisibleAsRequestCount() throws Exception {
        RecordingLogger written = new RecordingLogger();
        String tooLarge = Files.readString(RESPONSES.resolve("413-payload-too-large.json"));

        try (LocalIngest ingest = LocalIngest.alwaysAnswering(
                LocalIngest.refusingBatchesLargerThan(2, tooLarge))) {
            AnvilFeedConfig config = AnvilFeedConfig.builder()
                    .token("anv_srv_0123456789abcdef0123456789abcdef")
                    .service("shop-api")
                    .endpoint(ingest.endpoint())
                    .build(written.logger());

            EventQueue queue = new EventQueue(1000, written.logger());
            for (int i = 0; i < 4; i++) {
                queue.push(event(String.format("11111111-1111-4111-8111-%012d", i)));
            }

            try (HttpTransport transport = new HttpTransport(config)) {
                new Sender(config, queue, transport, written.logger(), () -> 1_000_000L).flush();
            }

            List<LocalIngest.Recorded> seen = List.copyOf(ingest.requests);
            assertThat(seen).describedAs("one refused batch of four, then two halves of two").hasSize(3);
            assertThat(eventsIn(seen.get(0).body())).isEqualTo(4);
            assertThat(eventsIn(seen.get(1).body())).isEqualTo(2);
            assertThat(eventsIn(seen.get(2).body())).isEqualTo(2);
            assertThat(queue.size()).isZero();
            assertThat(written.matching("payload_too_large")).isNotEmpty();
        }
    }

    /** {@code no_binding} and {@code binding_disabled} are the operator's to fix, and are said once. */
    @Test
    void anOperatorSideReasonCodeIsSaidOnceAndNamesTheCockpit() throws Exception {
        RecordingLogger written = new RecordingLogger();
        String body = Files.readString(RESPONSES.resolve("202-binding-disabled.json"));

        try (LocalIngest ingest = new LocalIngest(
                LocalIngest.answering(202, body, null), LocalIngest.answering(202, body, null))) {

            AnvilFeedConfig config = AnvilFeedConfig.builder()
                    .token("anv_srv_0123456789abcdef0123456789abcdef")
                    .service("shop-api")
                    .endpoint(ingest.endpoint())
                    .build(written.logger());
            EventQueue queue = new EventQueue(1000, written.logger());

            try (HttpTransport transport = new HttpTransport(config)) {
                Sender sender = new Sender(config, queue, transport, written.logger(), () -> 1_000_000L);
                queue.push(event(SECOND));
                sender.flush();
                queue.push(event(SECOND));
                sender.flush();
            }

            assertThat(written.matching("binding_disabled")).hasSize(1);
            assertThat(written.matching("cockpit")).isNotEmpty();
        }
    }

    private static FeedEvent event(String id) {
        return new FeedEvent(id, Map.of("event_id", id, "service", "shop-api"));
    }

    private static int eventsIn(String body) throws Exception {
        JsonNode parsed = JSON.readTree(body);
        return parsed.isArray() ? parsed.size() : 1;
    }

    private static List<String> reasonCodes(String body) throws Exception {
        List<String> codes = new ArrayList<>();
        for (JsonNode rejection : JSON.readTree(body).path("rejected")) {
            if (rejection.path("reason_code").isTextual()) {
                codes.add(rejection.path("reason_code").asText());
            }
        }
        return codes;
    }
}
