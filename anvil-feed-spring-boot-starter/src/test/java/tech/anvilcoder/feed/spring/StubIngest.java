package tech.anvilcoder.feed.spring;

import tech.anvilcoder.feed.IngestResponse;
import tech.anvilcoder.feed.Transport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The transport double the starter's tests are driven through.
 *
 * <p>It is NOT a copy of the core's {@code RecordingTransport}, and it deliberately does not try to be
 * one: that class is package-private in {@code anvil-feed-core}'s TEST source set, and reaching it from
 * here would mean either moving it into a {@code testFixtures} source set or publishing the core's test
 * output as an artifact — a restructure of a module this task is not rebuilding. What matters is that the
 * contract is not re-invented: the seam is the core's own public {@link Transport} interface, the scripted
 * answers are consumed in order, and the default answer once they run out is the one a healthy ingest
 * gives — {@code 202} naming every id that was posted, never a filler {@code accepted: []}, which is a
 * response the real server never sends and which the sender is required to notice.
 *
 * <p><b>Task 11 looked at making this one double and decided against it, so that the next reader does
 * not have to look again.</b> The move would be {@code java-test-fixtures} on {@code anvil-feed-core}
 * plus {@code testImplementation(testFixtures(project(":anvil-feed-core")))} here, and it is not clean
 * for three reasons that compound:
 *
 * <ol>
 *   <li>this class is a strict SUPERSET of {@code RecordingTransport} — {@link #rejecting} and
 *       {@link #throwing} have no counterpart there — so the core's double would have to GROW two
 *       behaviours it does not need, for a consumer's benefit;</li>
 *   <li>{@code RecordingTransport} is package-private. A test-fixtures source set publishes a real
 *       variant, so the class would become public API of a third artifact — and what gets published,
 *       under which coordinate, is one of the open owner decisions this version deliberately does not
 *       take;</li>
 *   <li>it changes the source-set layout of a module that has been reviewed and is green.</li>
 * </ol>
 *
 * <p>The duplication that actually matters — two suites disagreeing about what the transport contract
 * is — is not present: both drive the core's own public {@link Transport} interface, and both answer a
 * healthy batch by naming every id that was posted.
 */
final class StubIngest implements Transport {

    record Call(String url, String body, Integer timeoutMs) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();

    private final Deque<IngestResponse> answers = new ArrayDeque<>();

    private volatile RuntimeException throwing;

    StubIngest(IngestResponse... scripted) {
        this.answers.addAll(List.of(scripted));
    }

    private volatile String rejectWith;

    /**
     * The ingest that takes the event and refuses it, naming every id it was given — which is what a real
     * {@code no_binding} looks like. The ids have to be the posted ones: a rejection that names no event
     * is "no verdict for this event" to the sender, and the sender then posts a SECOND time, so the last
     * answer the verdict recorder holds would be the retry's rather than the rejection.
     */
    static StubIngest rejecting(String reasonCode, String message) {
        StubIngest stub = new StubIngest();
        stub.rejectWith = "{\"reason_code\":\"" + reasonCode + "\",\"message\":\"" + message + "\"}";
        return stub;
    }

    /** A transport that breaks the way a host's own decorator breaks: by throwing at the SDK. */
    static StubIngest throwing(RuntimeException failure) {
        StubIngest stub = new StubIngest();
        stub.throwing = failure;
        return stub;
    }

    @Override
    public IngestResponse post(String url, String body, Integer timeoutMs) {
        calls.add(new Call(url, body, timeoutMs));
        if (throwing != null) {
            throw throwing;
        }
        IngestResponse scripted = answers.poll();
        if (scripted != null) {
            return scripted;
        }
        return rejectWith == null
                ? IngestResponse.of(202, acceptEverything(body), null)
                : IngestResponse.of(202, rejectEverything(body, rejectWith), null);
    }

    /** The answer the ingest gives when the token is valid and nothing is bound to it. */
    static String rejectEverything(String body, String reason) {
        StringBuilder rejected = new StringBuilder("{\"accepted\":[],\"rejected\":[");
        boolean first = true;
        for (String id : eventIds(body)) {
            rejected.append(first ? "" : ",").append("{\"event_id\":\"").append(id).append("\",")
                    .append(reason, 1, reason.length());
            first = false;
        }
        return rejected.append("]}").toString();
    }

    /** The answer a healthy ingest gives to this exact body. */
    static String acceptEverything(String body) {
        StringBuilder accepted = new StringBuilder("{\"accepted\":[");
        boolean first = true;
        for (String id : eventIds(body)) {
            accepted.append(first ? "" : ",").append('"').append(id).append('"');
            first = false;
        }
        return accepted.append("],\"rejected\":[]}").toString();
    }

    private static List<String> eventIds(String body) {
        List<String> ids = new java.util.ArrayList<>();
        String marker = "\"event_id\":\"";
        int at = 0;
        while ((at = body.indexOf(marker, at)) >= 0) {
            int from = at + marker.length();
            int to = body.indexOf('"', from);
            ids.add(body.substring(from, to));
            at = to;
        }
        return ids;
    }
}
