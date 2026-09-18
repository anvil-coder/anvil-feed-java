package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The properties that only a socket can witness. A mock cannot be asked whether it followed a redirect,
 * and a stub cannot dribble: both of these are proved against a real
 * {@code com.sun.net.httpserver.HttpServer} on port 0.
 */
class HttpTransportTest {

    private static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    private static final String BODY = "{\"event_id\":\"11111111-1111-4111-8111-111111111111\"}";

    private static AnvilFeedConfig config(String endpoint, int requestTimeoutMs, int connectTimeoutMs) {
        return AnvilFeedConfig.builder()
                .token(TOKEN)
                .service("shop-api")
                .endpoint(endpoint)
                .requestTimeoutMs(requestTimeoutMs)
                .connectTimeoutMs(connectTimeoutMs)
                .build(FeedLogger.toSystemLogger());
    }

    @Test
    void itPostsTheBodyWithTheTokenAndWithoutAnOriginHeader() throws Exception {
        try (LocalIngest ingest = new LocalIngest();
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(response.status()).isEqualTo(202);
            LocalIngest.Recorded received = ingest.requests.get(0);
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/api/feed/events");
            assertThat(received.body()).isEqualTo(BODY);
            assertThat(received.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(received.header("content-type")).isEqualTo("application/json");
            assertThat(received.header("user-agent")).isEqualTo("anvil-feed-java/0.1.0");
            assertThat(received.header("origin"))
                    .describedAs("a server token presented with an Origin is refused 403 and has to be "
                            + "rotated; this SDK sets none, ever")
                    .isNull();
        }
    }

    @Test
    void itReadsRetryAfterOffTheHeaderAndNotOffTheBody() throws Exception {
        try (LocalIngest ingest = new LocalIngest(LocalIngest.answering(429, "{\"retry_after\":9}", 137));
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(response.retryAfterSeconds())
                    .describedAs("137 is deliberately not the documented 60: an SDK that lost the header "
                            + "and fell back to its own constant would be indistinguishable otherwise")
                    .isEqualTo(137);
        }
    }

    @Test
    void aMissingRetryAfterIsNullRatherThanZero() throws Exception {
        try (LocalIngest ingest = new LocalIngest(LocalIngest.answering(429, "{}", null));
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(response.retryAfterSeconds())
                    .describedAs("0 would turn a rate limit into a hot loop; null lets the Sender use the "
                            + "ingest's documented default")
                    .isNull();
        }
    }

    @Test
    void anHttpDateRetryAfterIsNullRatherThanZero() throws Exception {
        LocalIngest.Answer httpDate = (exchange, body) -> {
            exchange.getResponseHeaders().add("Retry-After", "Fri, 31 Dec 2027 23:59:59 GMT");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        };
        try (LocalIngest ingest = new LocalIngest(httpDate);
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(response.status()).isEqualTo(429);
            assertThat(response.retryAfterSeconds())
                    .describedAs("RFC 9110 allows the date form; this ingest never sends it, and parsing "
                            + "it with a cast would produce 0")
                    .isNull();
        }
    }

    /**
     * The guard for the whole of FR-9: the token is presented to the configured endpoint and to nothing
     * else. The assertion is not "the client reported one hop" — it is that the SECOND server, the one a
     * misconfigured endpoint or a proxy would bounce to, received nothing at all.
     */
    @Test
    void aRedirectIsNeverFollowedAndTheTokenNeverReachesTheTarget() throws Exception {
        try (LocalIngest target = new LocalIngest()) {
            String elsewhere = target.url("/api/feed/events");
            try (LocalIngest ingest = new LocalIngest(LocalIngest.redirecting(302, elsewhere),
                    LocalIngest.redirecting(302, elsewhere));
                 HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

                IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

                // Listed rather than counted, so that a run which goes red PRINTS the credential that
                // travelled instead of printing "expected 0 but was 1".
                assertThat(target.requests.stream()
                        .map(seen -> seen.method() + " " + seen.path() + " -> " + seen.header("authorization"))
                        .toList())
                        .describedAs("the redirect target received a request — and with it the "
                                + "Authorization header")
                        .isEmpty();
                assertThat(response.status()).isEqualTo(302);
                assertThat(response.location())
                        .describedAs("the Location is handed to the Sender so it can name what the "
                                + "operator's endpoint is really pointing at")
                        .isEqualTo(elsewhere);
            }
        }
    }

    /**
     * The same-host case, and the one where the credential really travels: a JDK strips
     * {@code Authorization} when a redirect crosses to another host, but keeps it when the host is the
     * same — which is exactly the shape of a reverse proxy bouncing {@code /api/feed} to
     * {@code /v2/api/feed}, or http to https on one name. Nothing may be presented to a second path
     * either: the operator configured one address and this SDK posts to that one.
     */
    @Test
    void aRedirectWithinTheSameHostIsNotFollowedAndTheTokenStaysAtTheConfiguredPath() throws Exception {
        try (LocalIngest ingest = new LocalIngest(LocalIngest.redirecting(307, "/somewhere-else"));
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(ingest.requests.stream()
                    .filter(seen -> "/somewhere-else".equals(seen.path()))
                    .map(seen -> seen.method() + " " + seen.path() + " -> " + seen.header("authorization")
                            + " body=" + seen.body())
                    .toList())
                    .describedAs("a same-host redirect keeps the Authorization header on every JDK; the "
                            + "only thing that stops the token going to a path nobody configured is "
                            + "refusing to follow it at all")
                    .isEmpty();
            assertThat(response.status()).isEqualTo(307);
            assertThat(response.location()).isEqualTo("/somewhere-else");
        }
    }

    /**
     * {@code HttpRequest.timeout} bounds the arrival of the response on paper; a body that then dribbles
     * in is outside it on more than one JDK. The budget this SDK promises is the whole exchange, and this
     * is the only way to see the difference.
     */
    @Test
    void aServerThatDribblesIsCutOffAtTheBudgetRatherThanHeldOpen() throws Exception {
        LocalIngest.Dribble dribble = LocalIngest.dribbling(100);
        try (LocalIngest ingest = new LocalIngest(dribble);
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 400, 300))) {

            long startedAt = System.nanoTime();
            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, null);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(response.transportFailure())
                    .describedAs("an answer that never finished is a transport failure, not a delivery")
                    .isTrue();
            assertThat(elapsedMs)
                    .describedAs("a 400 ms budget against a server dribbling 4 KiB at 100 ms a byte")
                    .isLessThan(3_000L);

            // The assertion elapsed time cannot make: the exchange was RELEASED, not merely returned
            // from. Without it `cancel(true)` could weaken to `cancel(false)` — or the finally block
            // could go — and a worker would read for 409 s behind every timed-out request with this
            // suite still green.
            assertThat(dribble.clientGone.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .describedAs("the server's own write must fail: the connection is closed, not "
                            + "abandoned with a reader still sitting on it")
                    .isTrue();
        }
    }

    /** C16: a 407 comes from the proxy. The transport hands the Sender the proxy's identity to say so. */
    @Test
    void a407NamesTheProxyItWasRoutedThroughAndNeverACredential() throws Exception {
        LocalIngest.Answer challenge = (exchange, body) -> {
            exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
            exchange.sendResponseHeaders(407, -1);
            exchange.close();
        };
        // No credentials in the environment, which is the unanswerable 407 the operator profile actually
        // produces. With an Authenticator installed the JDK answers the challenge, retries, and — against
        // a proxy that never relents — gives up with an IOException, so the Sender would never see a 407
        // at all; that path is covered by ProxySupportTest's credentialed case.
        try (LocalIngest proxy = LocalIngest.alwaysAnswering(challenge)) {
            AnvilFeedConfig config = config("http://192.0.2.7:8080/api/feed", 4_000, 2_000);
            try (HttpTransport transport = new HttpTransport(config,
                    name -> "HTTP_PROXY".equals(name) ? proxy.url("") : null)) {

                IngestResponse response = transport.post(config.eventsUrl(), BODY, null);

                assertThat(response.status()).isEqualTo(407);
                assertThat(response.failureReason())
                        .describedAs("host and port — the routing only the transport knows, which is why "
                                + "it travels on the response for the Sender's C16 line")
                        .contains("the proxy")
                        .contains("127.0.0.1");
            }

            // And the description of a CREDENTIALED proxy still carries host and port only (C15/X11).
            try (HttpTransport credentialed = new HttpTransport(config,
                    name -> "HTTP_PROXY".equals(name)
                            ? "http://corp-user:s3cr3t-proxy-pw@" + proxy.url("").substring(7)
                            : null)) {

                String described = credentialed.describeProxyFor(config.eventsUrl());

                assertThat(described).contains("127.0.0.1");
                assertThat(described)
                        .describedAs("never the credential, in either form")
                        .doesNotContain("s3cr3t-proxy-pw")
                        .doesNotContain("corp-user");
            }
        }
    }

    @Test
    void theClientIsPinnedToHttp11AndItsWorkerIsANamedDaemon() throws Exception {
        try (LocalIngest ingest = new LocalIngest();
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2000, 1000))) {

            transport.post(ingest.endpoint() + "/events", BODY, null);

            assertThat(ingest.requests.get(0).protocol())
                    .describedAs("the version the server actually saw")
                    .isEqualTo("HTTP/1.1");
            assertThat(ingest.requests.get(0).header("upgrade"))
                    .describedAs("no `Upgrade: h2c` probe: the ingest is behind nginx and the hop before "
                            + "it may be a corporate proxy, neither of which wins anything from HTTP/2 "
                            + "for one small POST")
                    .isNull();
            assertThat(Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().startsWith("anvil-feed-http-"))
                    .toList())
                    .describedAs("a bounded, named, daemon pool — not an anonymous cached one a reader "
                            + "finds in a thread dump with no hint of who owns it")
                    .isNotEmpty()
                    .allSatisfy(thread -> assertThat(thread.isDaemon()).isTrue());
        }
    }

    @Test
    void theCallersBudgetBoundsTheRequest() throws Exception {
        try (LocalIngest ingest = new LocalIngest(LocalIngest.dribbling(100));
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 10_000, 300))) {

            long startedAt = System.nanoTime();
            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, 300);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(response.transportFailure()).isTrue();
            assertThat(elapsedMs)
                    .describedAs("what is left of the flush deadline is what the socket gets — FR-2 (c) "
                            + "holds DURING a request, not only between two")
                    .isLessThan(3_000L);
        }
    }

    @Test
    void aGenerousCallerBudgetIsStillClampedToTheConfiguredTimeout() throws Exception {
        try (LocalIngest ingest = new LocalIngest(LocalIngest.dribbling(100));
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 400, 300))) {

            long startedAt = System.nanoTime();
            IngestResponse response = transport.post(ingest.endpoint() + "/events", BODY, 60_000);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(response.transportFailure()).isTrue();
            assertThat(elapsedMs)
                    .describedAs("a caller asking for a minute does not get a minute of socket; "
                            + "request_timeout_ms is the operator's ceiling")
                    .isLessThan(3_000L);
        }
    }

    /**
     * 192.0.2.1 is TEST-NET-1 (RFC 5737): routed nowhere, so the connect hangs rather than being refused.
     */
    @Test
    void connectTimeoutIsHonouredAndIsNotTheRequestTimeout() {
        AnvilFeedConfig config = config("http://192.0.2.1:81/api/feed", 20_000, 300);
        try (HttpTransport transport = new HttpTransport(config)) {
            long startedAt = System.nanoTime();
            IngestResponse response = transport.post(config.eventsUrl(), BODY, null);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(response.transportFailure()).isTrue();
            assertThat(elapsedMs)
                    .describedAs("connect_timeout_ms is 300 and request_timeout_ms is 20 s; a run that "
                            + "takes seconds means the connect setting has no effect")
                    .isLessThan(10_000L);
        }
    }

    @Test
    void anUnreachableIngestIsATransportFailureAndNeverAnException() throws Exception {
        String endpoint;
        try (LocalIngest ingest = new LocalIngest()) {
            endpoint = ingest.endpoint();          // and then nothing is listening there any more
        }
        try (HttpTransport transport = new HttpTransport(config(endpoint, 1_000, 500))) {
            IngestResponse response = transport.post(endpoint + "/events", BODY, null);

            assertThat(response.transportFailure()).isTrue();
            assertThat(response.status())
                    .describedAs("a fake 0 or 599 would be one typo away from being handled like a 4xx "
                            + "and dropping the customer's errors")
                    .isZero();
            assertThat(response.failureReason()).isNotBlank();
        }
    }

    @Test
    void anUnresolvableHostIsATransportFailureToo() {
        AnvilFeedConfig config = config("http://anvil-feed.invalid/api/feed", 2_000, 1_000);
        try (HttpTransport transport = new HttpTransport(config)) {
            IngestResponse response = transport.post(config.eventsUrl(), BODY, null);

            assertThat(response.transportFailure()).isTrue();
        }
    }

    @Test
    void aMalformedEndpointIsATransportFailureRatherThanAnExceptionIntoTheHost() {
        AnvilFeedConfig config = config("not a url at all", 2_000, 1_000);
        try (HttpTransport transport = new HttpTransport(config)) {
            IngestResponse response = transport.post(config.eventsUrl(), BODY, null);

            assertThat(response.transportFailure())
                    .describedAs("FR-2 (b): an operator's typo is a log line, never a stack trace in the "
                            + "host's own request")
                    .isTrue();
        }
    }

    /** The whole sender over a real socket: the batch shape the ingest refuses the other of. */
    @Test
    void theWholeSenderPostsOneEventAsAnObjectAndTwoAsAnArray() throws Exception {
        RecordingLogger written = new RecordingLogger();
        try (LocalIngest ingest = new LocalIngest();
             HttpTransport transport = new HttpTransport(config(ingest.endpoint(), 2_000, 1_000))) {

            EventQueue queue = new EventQueue(1000, written.logger());
            queue.push(new FeedEvent("11111111-1111-4111-8111-111111111111",
                    java.util.Map.of("event_id", "11111111-1111-4111-8111-111111111111")));
            Sender sender = new Sender(config(ingest.endpoint(), 2_000, 1_000), queue, transport,
                    written.logger());
            sender.flush();

            queue.push(new FeedEvent("22222222-2222-4222-8222-222222222222",
                    java.util.Map.of("event_id", "22222222-2222-4222-8222-222222222222")));
            queue.push(new FeedEvent("33333333-3333-4333-8333-333333333333",
                    java.util.Map.of("event_id", "33333333-3333-4333-8333-333333333333")));
            sender.flush();

            List<LocalIngest.Recorded> seen = List.copyOf(ingest.requests);
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0).path()).isEqualTo("/api/feed/events");
            assertThat(seen.get(0).body()).startsWith("{");
            assertThat(seen.get(1).path()).isEqualTo("/api/feed/events/batch");
            assertThat(seen.get(1).body()).startsWith("[");
            assertThat(queue.size()).isZero();
        }
    }
}
