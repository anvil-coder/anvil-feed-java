package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C11 — proxy support is part of the CORE, not of a framework adapter — and C15 — Basic, from
 * configuration or the environment, never from code and never in a log.
 *
 * <p>The forward proxy here is a real server the client is made to speak to; "it went through the proxy"
 * is asserted by the proxy having seen the ORIGINAL target host in the request line, which is the one
 * thing a direct connection can never produce.
 */
class ProxySupportTest {

    private static final String TOKEN = "anv_srv_0123456789abcdef0123456789abcdef";

    private static final String BODY = "{\"event_id\":\"11111111-1111-4111-8111-111111111111\"}";

    /** A host that must never be reachable directly, so that only a proxy can have answered. */
    private static final String UNREACHABLE = "http://192.0.2.7:8080/api/feed";

    private static AnvilFeedConfig config(String endpoint) {
        return AnvilFeedConfig.builder()
                .token(TOKEN).service("shop-api").endpoint(endpoint)
                .requestTimeoutMs(4_000).connectTimeoutMs(2_000)
                .build(FeedLogger.toSystemLogger());
    }

    private static UnaryOperator<String> environment(Map<String, String> values) {
        return values::get;
    }

    @Test
    void theRequestGoesThroughTheProxyNamedByHttpProxy() throws Exception {
        try (LocalIngest proxy = new LocalIngest()) {
            AnvilFeedConfig config = config(UNREACHABLE);
            try (HttpTransport transport = new HttpTransport(config,
                    environment(Map.of("HTTP_PROXY", proxy.url(""))))) {

                IngestResponse response = transport.post(config.eventsUrl(), BODY, null);

                assertThat(response.status())
                        .describedAs("192.0.2.7 is TEST-NET-1 and answers nothing; a 202 can only have "
                                + "come from the proxy")
                        .isEqualTo(202);
                assertThat(proxy.requests).hasSize(1);
                assertThat(proxy.requests.get(0).uri())
                        .describedAs("a forward proxy is addressed with the absolute target URI — this is "
                                + "the request line a direct connection never sends")
                        .contains("192.0.2.7:8080");
                assertThat(proxy.requests.get(0).header("authorization")).isEqualTo("Bearer " + TOKEN);
            }
        }
    }

    @Test
    void noProxyKeepsTheRequestDirect() throws Exception {
        try (LocalIngest ingest = new LocalIngest();
             LocalIngest proxy = new LocalIngest()) {

            AnvilFeedConfig config = config(ingest.endpoint());
            try (HttpTransport transport = new HttpTransport(config, environment(Map.of(
                    "HTTP_PROXY", proxy.url(""),
                    "NO_PROXY", "127.0.0.1,localhost")))) {

                IngestResponse response = transport.post(config.eventsUrl(), BODY, null);

                assertThat(response.status()).isEqualTo(202);
                assertThat(ingest.requests).hasSize(1);
                assertThat(proxy.requests)
                        .describedAs("an operator's NO_PROXY is the list of hosts the proxy must not see")
                        .isEmpty();
            }
        }
    }

    /**
     * C15: the proxy answers 407, the client answers the challenge with Basic credentials it took from
     * the {@code HTTP_PROXY} userinfo — never from code.
     */
    @Test
    void basicProxyCredentialsComeFromTheEnvironmentAndAnswerA407() throws Exception {
        LocalIngest.Answer challenge = (exchange, body) -> {
            exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"anvil\"");
            exchange.sendResponseHeaders(407, -1);
            exchange.close();
        };
        try (LocalIngest proxy = new LocalIngest(challenge)) {
            AnvilFeedConfig config = config(UNREACHABLE);
            String proxyUrl = "http://corp-user:s3cr3t-proxy-pw@" + proxy.url("").substring("http://".length());

            try (HttpTransport transport = new HttpTransport(config,
                    environment(Map.of("HTTP_PROXY", proxyUrl)))) {

                transport.post(config.eventsUrl(), BODY, null);
            }

            List<LocalIngest.Recorded> seen = List.copyOf(proxy.requests);
            assertThat(seen).describedAs("the challenge, then the answer to it").hasSizeGreaterThanOrEqualTo(2);
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("corp-user:s3cr3t-proxy-pw".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(seen.get(seen.size() - 1).header("proxy-authorization")).isEqualTo(expected);
        }
    }

    /**
     * The 407 nobody can answer, which is the case the operator profile of 2026-09-04 actually produces:
     * credentials absent, mistyped or rotated — or the proxy in front of <b>https</b>, where the JDK
     * refuses Basic on a {@code CONNECT} tunnel by default and the SDK cannot answer at all.
     *
     * <p>Before the task-7 review this fell through to "the ingest answered an unexpected 407": the batch
     * was dropped, the line named the wrong component, and every later event went in silence.
     */
    @Test
    void anUnanswerable407NamesTheProxyAndKeepsTheEvents() throws Exception {
        LocalIngest.Answer alwaysChallenging = (exchange, body) -> {
            exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
            exchange.sendResponseHeaders(407, -1);
            exchange.close();
        };
        RecordingLogger written = new RecordingLogger();

        try (LocalIngest proxy = LocalIngest.alwaysAnswering(alwaysChallenging)) {
            AnvilFeedConfig config = AnvilFeedConfig.builder()
                    .token(TOKEN).service("shop-api").endpoint(UNREACHABLE)
                    .requestTimeoutMs(4_000).connectTimeoutMs(2_000)
                    .build(written.logger());

            EventQueue queue = new EventQueue(1000, written.logger());
            queue.push(new FeedEvent("11111111-1111-4111-8111-111111111111",
                    Map.of("event_id", "11111111-1111-4111-8111-111111111111")));

            // No credentials anywhere: the challenge cannot be answered.
            try (HttpTransport transport = new HttpTransport(config,
                    environment(Map.of("HTTP_PROXY", proxy.url(""))))) {

                Sender sender = new Sender(config, queue, transport, written.logger(), () -> 1_000_000L);
                sender.flush();

                assertThat(queue.size())
                        .describedAs("every event this SDK exists to deliver would otherwise be discarded "
                                + "in the one environment C11/C15 were written for")
                        .isEqualTo(1);
                assertThat(sender.isStopped()).isFalse();
            }

            List<String> said = written.matching("PROXY refused");
            assertThat(said).hasSize(1);
            assertThat(said.get(0))
                    .describedAs("it names the proxy, not the Anvil ingest")
                    .contains("PROXY refused")
                    .contains("127.0.0.1");
            assertThat(written.matching("unexpected"))
                    .describedAs("never the generic 4xx row, which drops and then goes silent")
                    .isEmpty();
            assertThat(String.join("\n", written.lines()))
                    .describedAs("C15/X11 in the line the operator actually reads")
                    .doesNotContain("s3cr3t")
                    .doesNotContain(TOKEN);
        }
    }

    @Test
    void theJvmsOwnProxySettingsWinOverTheEnvironment() {
        ProxySelector jvm = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP,
                        java.net.InetSocketAddress.createUnresolved("jvm-proxy.internal", 3128)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // nothing to do
            }
        };
        HttpTransport.EnvironmentProxySelector selector = new HttpTransport.EnvironmentProxySelector(
                environment(Map.of("HTTP_PROXY", "http://env-proxy.internal:8080")), jvm);

        List<Proxy> chosen = selector.select(URI.create("http://app.anvil-coder.tech/api/feed/events"));

        assertThat(((java.net.InetSocketAddress) chosen.get(0).address()).getHostString())
                .describedAs("an operator who set -Dhttp.proxyHost meant it, and http.nonProxyHosts is "
                        + "already honoured there")
                .isEqualTo("jvm-proxy.internal");
    }

    @Test
    void anEnvironmentProxyIsUsedWhereTheJvmNamesNone() {
        HttpTransport.EnvironmentProxySelector selector = new HttpTransport.EnvironmentProxySelector(
                environment(Map.of("HTTPS_PROXY", "https://env-proxy.internal:8443")),
                directOnly());

        List<Proxy> chosen = selector.select(URI.create("https://app.anvil-coder.tech/api/feed/events"));

        assertThat(chosen.get(0).type()).isEqualTo(Proxy.Type.HTTP);
        java.net.InetSocketAddress address = (java.net.InetSocketAddress) chosen.get(0).address();
        assertThat(address.getHostString()).isEqualTo("env-proxy.internal");
        assertThat(address.getPort()).isEqualTo(8443);
    }

    @Test
    void noProxyConfiguredAnywhereIsADirectConnection() {
        HttpTransport.EnvironmentProxySelector selector =
                new HttpTransport.EnvironmentProxySelector(environment(Map.of()), directOnly());

        assertThat(selector.select(URI.create("https://app.anvil-coder.tech/api/feed/events")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @ParameterizedTest(name = "NO_PROXY=\"{0}\" host={1}:{2} bypasses={3}")
    @CsvSource({
            "'*',               app.anvil-coder.tech, 443, true",
            "'.internal',       build.internal,       443, true",
            "'internal',        build.internal,       443, true",
            "'internal',        internal,             443, true",
            "'internal',        notinternal,          443, false",
            "'localhost',       127.0.0.1,            443, false",
            "'127.0.0.1',       127.0.0.1,            443, true",
            "'example.com:8080',api.example.com,     8080, true",
            "'example.com:8080',api.example.com,      443, false",
            "'',                api.example.com,      443, false",
            // IPv6 literals. `::1` was split at its LAST colon into host `::` and port `1`, so an IPv6
            // loopback endpoint went through the proxy however the operator spelled the bypass.
            "'::1',             ::1,                  443, true",
            "'::1',             '[::1]',              443, true",
            "'[::1]',           ::1,                  443, true",
            "'[::1]:8080',      ::1,                 8080, true",
            "'[::1]:8080',      ::1,                  443, false",
            "'::1',             0:0:0:0:0:0:0:1,      443, true",
            "'fd00::1',         fd00::2,              443, false",
            // The subdomain rule is for NAMES: `0.0.1` bypassed `10.0.0.1` by suffix match on an address.
            "'0.0.1',           10.0.0.1,             443, false",
            "'10.0.0.1',        10.0.0.1,             443, true",
            // CIDR — what curl and Go's httpproxy accept, and what an operator reaches for first.
            "'192.168.0.0/16',  192.168.44.7,         443, true",
            "'192.168.0.0/16',  192.169.44.7,         443, false",
            "'10.0.0.0/8',      10.255.3.1,           443, true",
            "'fd00::/8',        fd00::99,             443, true",
            "'fd00::/8',        fe00::99,             443, false",
            "'192.168.0.0/junk',192.168.44.7,         443, false",
    })
    void noProxySemanticsAreTheOnesEveryOtherRuntimeImplements(String noProxy, String host, int port,
                                                               boolean bypasses) {
        assertThat(HttpTransport.bypasses(noProxy, host, port)).isEqualTo(bypasses);
    }

    /**
     * C15 and X11: Basic transmits the password merely Base64-encoded; once it is in a log or a support
     * ticket it is compromised. The SDK's own promise, kept by the SDK itself.
     */
    @Test
    void aProxyPasswordNeverSurvivesIntoALogLine() {
        String leaked = IngestResponse.failure(
                "ConnectException: connecting to http://corp-user:s3cr3t-proxy-pw@proxy.internal:3128 "
                        + "failed while sending Authorization: Bearer " + TOKEN).failureReason();

        assertThat(leaked).doesNotContain("s3cr3t-proxy-pw");
        assertThat(leaked).doesNotContain(TOKEN);
        assertThat(leaked).contains("[Filtered]");
    }

    /**
     * C15's acceptance criterion names two things to grep for: the proxy password <b>and its Base64
     * encoding</b>. The cleartext form was covered; the encoded one — which is what Basic actually puts on
     * the wire, and what a host's HTTP debugging decorator dumps — was not.
     */
    @Test
    void theBase64OfTheProxyPasswordNeverSurvivesEither() {
        String base64 = Base64.getEncoder().encodeToString(
                "corp-user:s3cr3t-proxy-pw".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Three shapes: the header the SDK sends, a bare credential in a message, and a proxy dump.
        for (String message : List.of(
                "Proxy-Authorization: Basic " + base64,
                "handshake failed after offering Basic " + base64 + " to corp-gw",
                "ProxyException: 407 from corp-gw; sent Basic " + base64)) {

            String redacted = IngestResponse.failure(message).failureReason();

            assertThat(redacted)
                    .describedAs("Basic transmits the password merely Base64-encoded; once that string is "
                            + "in a log or a support ticket the credential is compromised — message was: "
                            + message)
                    .doesNotContain(base64);
            assertThat(redacted).contains("[Filtered]");
        }
    }

    @Test
    void redactionLeavesOrdinaryTextAlone() {
        String ordinary = IngestResponse.failure(
                "ConnectException: Connection refused to app.anvil-coder.tech:443").failureReason();

        assertThat(ordinary)
                .describedAs("a redactor that eats the diagnosis is a redactor nobody can debug behind")
                .isEqualTo("ConnectException: Connection refused to app.anvil-coder.tech:443");
    }

    @Test
    void aMalformedProxyUrlIsIgnoredRatherThanFatal() {
        assertThat(HttpTransport.parseProxy("   ")).isNull();
        assertThat(HttpTransport.parseProxy(null)).isNull();
        assertThat(HttpTransport.parseProxy("::::")).isNull();
        assertThat(HttpTransport.parseProxy("proxy.internal:3128"))
                .describedAs("the bare host:port form curl accepts")
                .isNotNull();
    }

    private static ProxySelector directOnly() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // nothing to do
            }
        };
    }
}
