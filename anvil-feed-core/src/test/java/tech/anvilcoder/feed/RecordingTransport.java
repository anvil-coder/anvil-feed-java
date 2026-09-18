package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The transport double the decision table is driven through. It lives in its own file rather than inside
 * a test class (ruling P6, carried over from PHP) so that Task 10's starter tests can drive the same
 * sender without a second copy — a second copy is how two suites start disagreeing about what the
 * transport contract is.
 *
 * <p>The scripted answers are consumed in order. Once they run out every further call is answered the way
 * the ingest answers a healthy batch: {@code 202} naming <b>every id that was posted</b>. That default is
 * not a convenience — a filler {@code {"accepted":[],"rejected":[]}} is a response the real server never
 * sends, and the sender is required to notice exactly that (an event nothing gave a verdict for is sent
 * once more and then dropped).
 */
final class RecordingTransport implements Transport {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Call(String url, String body, Integer timeoutMs) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();

    private final Deque<IngestResponse> answers = new ArrayDeque<>();

    RecordingTransport(IngestResponse... scripted) {
        this.answers.addAll(List.of(scripted));
    }

    @Override
    public IngestResponse post(String url, String body, Integer timeoutMs) {
        calls.add(new Call(url, body, timeoutMs));
        IngestResponse scripted = answers.poll();
        return scripted != null ? scripted : IngestResponse.of(202, acceptEverything(body), null);
    }

    List<String> urls() {
        return calls.stream().map(Call::url).toList();
    }

    List<String> bodies() {
        return calls.stream().map(Call::body).toList();
    }

    List<Integer> budgets() {
        return calls.stream().map(Call::timeoutMs).toList();
    }

    /** The answer a healthy ingest gives to this exact body. */
    static String acceptEverything(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode parsed = JSON.readTree(body);
            for (JsonNode event : parsed.isArray() ? parsed : List.of(parsed)) {
                if (event.hasNonNull("event_id")) {
                    ids.add(event.get("event_id").asText());
                }
            }
        } catch (Exception unreadable) {
            // A body no ingest could read is answered as if it named nothing.
        }
        StringBuilder accepted = new StringBuilder("{\"accepted\":[");
        for (int i = 0; i < ids.size(); i++) {
            accepted.append(i == 0 ? "" : ",").append('"').append(ids.get(i)).append('"');
        }
        return accepted.append("],\"rejected\":[]}").toString();
    }
}
