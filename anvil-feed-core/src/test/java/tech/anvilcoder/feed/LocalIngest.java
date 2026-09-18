package tech.anvilcoder.feed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real ingest on a real socket: {@code com.sun.net.httpserver.HttpServer} bound to port 0, which is in
 * the JDK and therefore not a dependency a host application ever sees.
 *
 * <p>It exists because the properties this SDK promises are socket properties. "No redirect is ever
 * followed" cannot be proved against a mock that was asked whether it followed one; it is proved by a
 * second server that records what reached it and then asserting that nothing did — with the
 * {@code Authorization} header as the thing that must be absent. The same goes for "the budget is a total
 * wall-clock bound": a server that dribbles one byte at a time is the only witness.
 *
 * <p>Answers are scripted and consumed in order; when they run out every further request is answered the
 * way a healthy ingest answers — {@code 202} naming every id that was posted.
 */
final class LocalIngest implements AutoCloseable {

    /** One answer. It owns the exchange and must close it. */
    interface Answer {
        void write(HttpExchange exchange, String requestBody) throws IOException;
    }

    record Recorded(String method, String uri, String path, Map<String, String> headers, String body) {

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final ConcurrentLinkedDeque<Answer> scripted = new ConcurrentLinkedDeque<>();
    private final Answer fallback;

    /** Scripted answers first, then the healthy 202 a real ingest gives. */
    LocalIngest(Answer... answers) throws IOException {
        this(accepting(), List.of(answers));
    }

    /** One answer for every request, however many there are — the 413 splitter is the case for it. */
    static LocalIngest alwaysAnswering(Answer fallback) throws IOException {
        return new LocalIngest(fallback, List.of());
    }

    private LocalIngest(Answer fallback, List<Answer> answers) throws IOException {
        this.fallback = fallback;
        this.scripted.addAll(answers);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestURI().getPath(), lowerCasedHeaders(exchange), body));
            Answer answer = scripted.poll();
            (answer == null ? this.fallback : answer).write(exchange, body);
        });
        this.server.start();
    }

    /** The value {@code AnvilFeedConfig.endpoint} takes, so that eventsUrl()/batchUrl() land here. */
    String endpoint() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + "/api/feed";
    }

    String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- answers

    /** What the ingest says to a healthy batch: every id it was handed, named in {@code accepted}. */
    static Answer accepting() {
        return (exchange, body) -> send(exchange, 202, RecordingTransport.acceptEverything(body), null, null);
    }

    static Answer answering(int status, String body, Integer retryAfterSeconds) {
        return (exchange, requestBody) -> send(exchange, status, body, retryAfterSeconds, null);
    }

    static Answer redirecting(int status, String location) {
        return (exchange, requestBody) -> send(exchange, status, "", null, location);
    }

    /**
     * Headers, then one byte every {@code gapMillis} for ever. The sleep is the SERVER's — a slow
     * counterparty is what is being simulated — and the assertion is the elapsed time the CLIENT took to
     * give up, never a sleep in the test itself.
     */
    static Answer dribbling(long gapMillis) {
        return (exchange, requestBody) -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, 4096);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 4096; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(gapMillis);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException clientGaveUp) {
                // Which is the point of this answer.
            }
        };
    }

    /** Answers 413 to anything above {@code maxEvents} events and accepts everything else. */
    static Answer refusingBatchesLargerThan(int maxEvents, String tooLargeBody) {
        return (exchange, body) -> {
            int events = body.startsWith("[") ? body.split("\"event_id\"", -1).length - 1 : 1;
            if (events > maxEvents) {
                send(exchange, 413, tooLargeBody, null, null);
            } else {
                send(exchange, 202, RecordingTransport.acceptEverything(body), null, null);
            }
        };
    }

    private static void send(HttpExchange exchange, int status, String body, Integer retryAfterSeconds,
                             String location) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfterSeconds != null) {
            exchange.getResponseHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        }
        if (location != null) {
            exchange.getResponseHeaders().add("Location", location);
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

    private static Map<String, String> lowerCasedHeaders(HttpExchange exchange) {
        return exchange.getRequestHeaders().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(Locale.ROOT),
                        entry -> String.join(",", entry.getValue()),
                        (first, second) -> first + "," + second));
    }
}
