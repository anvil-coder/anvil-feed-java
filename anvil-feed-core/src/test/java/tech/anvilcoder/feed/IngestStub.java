package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A replay of the PUBLISHED ingest on a real socket — {@code com.sun.net.httpserver}, port 0, in the JDK
 * and therefore not a dependency any host ever sees.
 *
 * <p>Two things separate it from {@link LocalIngest}, which is Task 7's transport-level double:
 *
 * <ol>
 *   <li><b>It ENFORCES the request-side rules of {@code docs/anvil-feed-ingest.md} before any scenario
 *       runs</b> — an {@code Origin} header is 403 whatever the token says, a bearer that is not an
 *       {@code anv_(srv|pub)_<32>} key is a bare 401, a body above 262144 bytes is 413, and a body that is
 *       not JSON or a batch above 50 is 400. Every test in {@link MockServerTest} is therefore also a
 *       standing check that the SDK never EARNS one of those answers, instead of only proving how it
 *       reacts once it has.</li>
 *   <li><b>Its answers come out of {@code conformance/responses/index.json}</b>, status, {@code Retry-After}
 *       and body together, rather than out of constants retyped into a test. A window the SDK could guess
 *       cannot discriminate between an SDK that read the header and one that fell back to its own default,
 *       which is why two entries of that index carry 137 and 900.</li>
 * </ol>
 *
 * <p>The scenario travels in the PATH, never in a header: {@code HttpTransport} sends what the contract
 * says it sends and a test may not add to it, or the transport under test is no longer the transport that
 * ships.
 */
final class IngestStub implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path RESPONSES =
            Path.of(System.getProperty("anvilfeed.conformance")).resolve("responses");

    /** {@code docs/anvil-feed-ingest.md}: {@code Bearer anv_srv_<32 base62 characters>}. */
    private static final Pattern SERVER_TOKEN = Pattern.compile("anv_(srv|pub)_[A-Za-z0-9]{32}");

    static final int MAX_BODY_BYTES = 262_144;
    static final int MAX_BATCH = 50;

    /** One request as the stub saw it, plus the verdict its own rules reached. */
    record Recorded(String method, String path, Map<String, String> headers, String body,
                    int status, String refusedBy) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** How many events the body carried; {@code -1} when it was not readable JSON. */
        int events() {
            try {
                var parsed = JSON.readTree(body);
                return parsed.isArray() ? parsed.size() : 1;
            } catch (Exception unreadable) {
                return -1;
            }
        }

        boolean isArray() {
            return body.stripLeading().startsWith("[");
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statusByFixture = new LinkedHashMap<>();
    private final Map<String, Integer> retryAfterByFixture = new LinkedHashMap<>();
    private final Map<String, String> bodyByFixture = new LinkedHashMap<>();

    /** Where the {@code redirect} scenario points; null until {@link #redirectTo(IngestStub)} is called. */
    private volatile String redirectTarget;

    IngestStub() throws Exception {
        for (var entry : JSON.readTree(Files.readString(RESPONSES.resolve("index.json")))) {
            String file = entry.path("file").asText();
            String scenario = file.substring(0, file.length() - ".json".length());
            statusByFixture.put(scenario, entry.path("status").asInt());
            if (!entry.path("retry_after").isNull()) {
                retryAfterByFixture.put(scenario, entry.path("retry_after").asInt());
            }
            bodyByFixture.put(scenario, Files.readString(RESPONSES.resolve(file)));
        }

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Daemon threads: a handler that is deliberately slow must not hold the test JVM open after the
        // suite has gone green (the lesson LocalIngest's header records).
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ingest-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** The {@code endpoint} a config takes for this scenario; {@code /events} and {@code /events/batch} follow. */
    String endpoint(String scenario) {
        return base() + "/" + scenario + "/api/feed";
    }

    String base() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void redirectTo(IngestStub target) {
        this.redirectTarget = target.base() + "/accepted/api/feed/events";
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    List<String> scenarios() {
        return List.copyOf(statusByFixture.keySet());
    }

    int statusOf(String scenario) {
        return statusByFixture.get(scenario);
    }

    Integer retryAfterOf(String scenario) {
        return retryAfterByFixture.get(scenario);
    }

    String bodyOf(String scenario) {
        return bodyByFixture.get(scenario);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ the gate

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(Locale.ROOT),
                        e -> String.join(",", e.getValue()), (a, b) -> a + "," + b));
        String path = exchange.getRequestURI().getPath();

        // The published order: Origin is checked BEFORE the token, because a server token presented with
        // one is treated as leaked whatever it is.
        if (headers.containsKey("origin")) {
            record(exchange, headers, path, body, 403, "an Origin header", "", null);
            return;
        }
        String authorization = headers.getOrDefault("authorization", "");
        if (!authorization.startsWith("Bearer ")
                || !SERVER_TOKEN.matcher(authorization.substring("Bearer ".length())).matches()) {
            record(exchange, headers, path, body, 401, "no usable bearer token", "", null);
            return;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            record(exchange, headers, path, body, 413, "a body above " + MAX_BODY_BYTES + " bytes",
                    bodyByFixture.get("413-payload-too-large"), null);
            return;
        }
        int events;
        try {
            var parsed = JSON.readTree(body);
            if (parsed == null || !(parsed.isArray() || parsed.isObject())) {
                throw new IllegalArgumentException("neither an object nor an array");
            }
            events = parsed.isArray() ? parsed.size() : 1;
        } catch (Exception unreadable) {
            record(exchange, headers, path, body, 400, "a body that is not a JSON event or batch",
                    bodyByFixture.get("400-schema-violation"), null);
            return;
        }
        if (events > MAX_BATCH) {
            record(exchange, headers, path, body, 400, "a batch above " + MAX_BATCH,
                    bodyByFixture.get("400-schema-violation"), null);
            return;
        }

        answerTheScenario(exchange, headers, path, body);
    }

    private void answerTheScenario(HttpExchange exchange, Map<String, String> headers, String path,
                                   String body) throws IOException {
        String scenario = path.startsWith("/") ? path.substring(1) : path;
        int slash = scenario.indexOf('/');
        scenario = slash < 0 ? scenario : scenario.substring(0, slash);

        if (statusByFixture.containsKey(scenario)) {
            record(exchange, headers, path, body, statusByFixture.get(scenario), null,
                    withPostedIds(bodyByFixture.get(scenario), idsIn(body)),
                    retryAfterByFixture.get(scenario));
            return;
        }

        switch (scenario) {
            case "accepted" -> record(exchange, headers, path, body, 202, null,
                    RecordingTransport.acceptEverything(body), null);
            case "always-413" -> record(exchange, headers, path, body, 413, null,
                    bodyByFixture.get("413-payload-too-large"), null);
            case "forbidden" -> record(exchange, headers, path, body, 403, null, "", null);
            case "unexpected-5xx" -> record(exchange, headers, path, body, 500, null, "", null);
            case "no-retry-after-429" -> record(exchange, headers, path, body, 429, null, "{}", null);
            case "no-retry-after-503" -> record(exchange, headers, path, body, 503, null, "{}", null);
            case "redirect" -> {
                exchange.getResponseHeaders().add("Location", redirectTarget);
                record(exchange, headers, path, body, 301, null, "", null);
            }
            case "slow" -> {
                try {
                    // The SERVER is the slow counterparty; the client's own budget is what is under test,
                    // and the assertions are about the queue, never about elapsed time.
                    Thread.sleep(1_500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                record(exchange, headers, path, body, 202, null,
                        RecordingTransport.acceptEverything(body), null);
            }
            default -> record(exchange, headers, path, body, 404, null,
                    "{\"message\":\"unknown scenario " + scenario + "\"}", null);
        }
    }

    private void record(HttpExchange exchange, Map<String, String> headers, String path, String body,
                        int status, String refusedBy, String answer, Integer retryAfterSeconds)
            throws IOException {
        requests.add(new Recorded(exchange.getRequestMethod(), path, headers, body, status, refusedBy));
        byte[] bytes = answer == null ? new byte[0] : answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfterSeconds != null) {
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        }
        // -1 rather than 0 for an empty body: 0 means "chunked, length unknown" in this API.
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    // ------------------------------------------------------------------ published bodies, this request's ids

    private static List<String> idsIn(String body) {
        List<String> ids = new ArrayList<>();
        try {
            var parsed = JSON.readTree(body);
            for (var event : parsed.isArray() ? parsed : List.of(parsed)) {
                if (event.hasNonNull("event_id")) {
                    ids.add(event.get("event_id").asText());
                }
            }
        } catch (Exception unreadable) {
            // A body no ingest could read names no ids.
        }
        return ids;
    }

    /**
     * The published verdict bodies carry the fixtures' own ids. A sender matches verdicts on the id it
     * SENT, so the stub substitutes this request's ids into the published shape — accepted first, then
     * rejected — rather than a test retyping the body. A {@code null} {@code event_id} (the 400 and the
     * 413 carry one) stays null: those verdicts are about the request, not about an event.
     */
    @SuppressWarnings("unchecked")
    private static String withPostedIds(String body, List<String> ids) {
        try {
            Map<String, Object> answer = JSON.readValue(body, LinkedHashMap.class);
            Iterator<String> next = ids.iterator();
            if (answer.get("accepted") instanceof List<?> accepted) {
                List<Object> replaced = new ArrayList<>();
                for (Object ignored : accepted) {
                    replaced.add(next.hasNext() ? next.next() : ignored);
                }
                answer.put("accepted", replaced);
            }
            if (answer.get("rejected") instanceof List<?> rejected) {
                for (Object entry : rejected) {
                    if (entry instanceof Map<?, ?> verdict
                            && ((Map<String, Object>) verdict).get("event_id") != null && next.hasNext()) {
                        ((Map<String, Object>) verdict).put("event_id", next.next());
                    }
                }
            }
            return JSON.writeValueAsString(answer);
        } catch (Exception notAVerdictBody) {
            return body;   // {} — the two rate-limit fixtures, whose decision is the header's
        }
    }
}
