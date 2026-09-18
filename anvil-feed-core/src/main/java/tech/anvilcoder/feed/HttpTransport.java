package tech.anvilcoder.feed;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * The JDK's own client (C4, O11) — no Apache, no OkHttp, nothing in a host application's dependency tree.
 *
 * <p>Four absolutes, and every one of them is a promise rather than a default somebody could change:
 *
 * <ul>
 *   <li><b>No {@code Origin} header, ever.</b> The ingest refuses a server token presented with one as
 *       leaked ({@code 403}) and the token then has to be rotated.</li>
 *   <li><b>No redirect is ever followed</b> ({@link HttpClient.Redirect#NEVER}). One operator typo in
 *       {@code endpoint}, or one corporate proxy bouncing http → https, would otherwise hand the
 *       {@code anv_srv_} token to a host the customer never named — while the redirect degrades POST to
 *       GET, so the events would not even be in the second request. The 3xx is handed to the
 *       {@link Sender}, which drops the batch and says the endpoint is misconfigured.</li>
 *   <li><b>The timeout is a TOTAL budget</b> — connect, write and read together, on the wall clock.
 *       {@link HttpRequest.Builder#timeout} alone is not that on every JDK, so the exchange is also
 *       bounded from the outside and cancelled when the budget is gone; a server that dribbles one byte
 *       at a time is what that is for.</li>
 *   <li><b>Proxy support is the core's, not an adapter's</b> (C11): the JVM's own
 *       {@code http.proxyHost} / {@code https.proxyHost} / {@code http.nonProxyHosts} first, and the
 *       {@code HTTP_PROXY} / {@code HTTPS_PROXY} / {@code ALL_PROXY} environment variables with
 *       {@code NO_PROXY} semantics where the JDK reads none. Basic credentials (C15) come from the proxy
 *       URL's userinfo or from {@code http.proxyUser} / {@code http.proxyPassword} — never from code, and
 *       never into a log: {@link IngestResponse#redact(String)} strips them from every failure reason.</li>
 * </ul>
 *
 * <p><b>One operator note about HTTPS through an authenticating proxy.</b> The JDK disables Basic for the
 * {@code CONNECT} tunnel by default. Where a corporate proxy demands it, the operator clears
 * {@code -Djdk.http.auth.tunneling.disabledSchemes=}. This SDK does not set that system property itself:
 * it is JVM-wide and would weaken every other client in the host application.
 *
 * <p>Trust anchors are the JVM's own truststore (C12), which is what an operator already configures for
 * everything else on the machine.
 */
public final class HttpTransport implements Transport, AutoCloseable {

    /** The single source for the User-Agent; Task 11's release check has one symbol to pin. */
    static final String USER_AGENT = "anvil-feed-java/" + EventFactory.SDK_VERSION;

    /** A budget below this is not a request, it is a failure that costs a socket. */
    private static final int MINIMUM_BUDGET_MS = 1;

    /** Names the pool in a customer's thread dump; a nameless {@code HttpClient-1-Worker-N} does not. */
    private static final AtomicInteger POOL_NUMBER = new AtomicInteger();

    private final AnvilFeedConfig config;
    private final HttpClient client;
    private final ExecutorService executor;
    private final ProxySelector proxies;

    public HttpTransport(AnvilFeedConfig config) {
        this(config, System::getenv);
    }

    HttpTransport(AnvilFeedConfig config, UnaryOperator<String> environment) {
        this.config = config;
        this.proxies = proxySelector(environment);
        // Bounded and named (review finding 10). Concurrency here is exactly one — every post comes from
        // inside one sender's flush — so a cached pool is capacity nobody uses, and its threads show up
        // in a customer's thread dump with no hint of who owns them. Daemon, so it can never hold a JVM.
        int pool = POOL_NUMBER.incrementAndGet();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "anvil-feed-http-" + pool);
            thread.setDaemon(true);
            return thread;
        });
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, config.connectTimeoutMs())))
                // FR-9: the token goes to the configured endpoint and to nowhere else.
                .followRedirects(HttpClient.Redirect.NEVER)
                // Pinned. The ingest is behind nginx, which routes `^~ /api/feed/` to query-svc over
                // HTTP/1.1; the default HTTP_2 costs an `Upgrade: h2c` probe on a plaintext endpoint and
                // an ALPN negotiation on a TLS one, neither of which can win anything for a single small
                // POST — and a corporate proxy in the path (C11) is the least likely hop to speak h2.
                .version(HttpClient.Version.HTTP_1_1)
                .executor(executor)
                .proxy(proxies);
        Authenticator proxyCredentials = proxyAuthenticator(environment);
        if (proxyCredentials != null) {
            builder = builder.authenticator(proxyCredentials);
        }
        this.client = builder.build();
    }

    @Override
    public IngestResponse post(String url, String body, Integer timeoutMs) {
        int budgetMs = Math.max(MINIMUM_BUDGET_MS, Math.min(config.requestTimeoutMs(),
                timeoutMs == null ? config.requestTimeoutMs() : timeoutMs));

        CompletableFuture<HttpResponse<String>> exchange = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(budgetMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (config.token() == null ? "" : config.token()))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            // Not client.send(): HttpRequest.timeout bounds the arrival of the RESPONSE, and a body that
            // dribbles in afterwards is outside it. The budget this SDK promises covers the whole
            // exchange, so it is enforced here and the exchange is cancelled when it runs out.
            exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = exchange.get(budgetMs, TimeUnit.MILLISECONDS);

            // C16: a 407 is the PROXY's answer, and the proxy's identity is the one piece of routing only
            // the transport knows. It travels in `failureReason` so the Sender's 407 line can name it —
            // host and port only, never a credential (C15/X11).
            String proxy = response.statusCode() == 407 ? describeProxyFor(url) : "";
            return new IngestResponse(
                    response.statusCode(),
                    response.body(),
                    retryAfter(response),
                    false,
                    proxy,
                    response.headers().firstValue("Location").orElse(null));
        } catch (TimeoutException tooSlow) {
            return IngestResponse.failure("the ingest did not answer within the budget of " + budgetMs + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return IngestResponse.failure("interrupted");
        } catch (RuntimeException | Error | java.util.concurrent.ExecutionException anything) {
            // FR-2 b is absolute: nothing from here reaches the caller, and a network failure is never a
            // refusal. The reason is redacted by IngestResponse.failure before it exists as a string here.
            Throwable cause = anything instanceof java.util.concurrent.ExecutionException && anything.getCause() != null
                    ? anything.getCause()
                    : anything;
            return IngestResponse.failure(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            if (exchange != null && !exchange.isDone()) {
                exchange.cancel(true);
            }
        }
    }

    /**
     * The proxy this URL would be routed through, as {@code host:port} and nothing else. C15/X11: a
     * credential — cleartext or Base64 — never appears here, which is why the userinfo of the configured
     * proxy URL is deliberately not read.
     */
    String describeProxyFor(String url) {
        try {
            for (Proxy proxy : proxies.select(URI.create(url))) {
                if (proxy.type() != Proxy.Type.DIRECT && proxy.address() instanceof InetSocketAddress at) {
                    return "the proxy " + at.getHostString() + ":" + at.getPort();
                }
            }
        } catch (RuntimeException noRoutingToReport) {
            // A description is a nicety; failing to build one may not change the answer.
        }
        return "";
    }

    /**
     * Releases the client's selector and executor threads, <b>without waiting</b>.
     *
     * <p>{@code HttpClient.close()} is an orderly shutdown that blocks until every in-flight operation
     * has finished — which, behind a counterparty that dribbles, is the same unbounded wait this class
     * exists to prevent one level up. {@code shutdownNow()} cancels instead. FR-2 (c) is a bound on the
     * call, and closing is a call.
     */
    @Override
    public void close() {
        try {
            client.shutdownNow();
        } catch (RuntimeException | Error neverTheHostsProblem) {
            // FR-2 b applies to shutdown too.
        }
        try {
            executor.shutdownNow();
        } catch (RuntimeException | Error neverTheHostsProblem) {
            // Likewise.
        }
    }

    /**
     * Only the delta-seconds form. RFC 9110 also allows an HTTP-date, which this ingest never sends
     * ({@code Retry-After: 60} and {@code Retry-After: 30} are literals in AnvilFeedIngestController); an
     * unreadable value becomes null and the sender falls back to its documented default rather than to 0,
     * which would be a hot loop against a rate limiter.
     */
    private static Integer retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(value -> !value.isEmpty() && value.chars().allMatch(Character::isDigit)
                        && value.length() <= 9)
                .map(Integer::parseInt)
                .orElse(null);
    }

    // ---------------------------------------------------------------- C11 / C15: the proxy

    /**
     * The JVM's own configuration first — {@code http.proxyHost}, {@code https.proxyHost} and
     * {@code http.nonProxyHosts} through {@link ProxySelector#getDefault()}, which is what an operator
     * already sets for everything else in the process. Where that names no proxy, the environment
     * variables the JDK does not read are consulted.
     */
    static ProxySelector proxySelector(UnaryOperator<String> environment) {
        return new EnvironmentProxySelector(environment, ProxySelector.getDefault());
    }

    /**
     * C15: Basic, and the credentials come from configuration or the environment, never from code. A
     * {@code PasswordAuthentication} is only ever handed to a PROXY requestor — an authenticating ingest
     * is not a thing this protocol has, and answering a server challenge with the proxy password is how
     * a credential ends up somewhere it was never meant to go.
     */
    static Authenticator proxyAuthenticator(UnaryOperator<String> environment) {
        // BOTH pairs. An operator with an https endpoint sets the https.* pair, which is the ordinary
        // thing to do and which reading only http.* turned into a silent 407 (review finding 8).
        String user = firstPresent(System.getProperty("https.proxyUser"),
                System.getProperty("http.proxyUser"));
        char[] password = toChars(firstPresent(System.getProperty("https.proxyPassword"),
                System.getProperty("http.proxyPassword")));
        if (user == null || user.isBlank()) {
            URI fromEnvironment = firstProxyUri(environment);
            String userInfo = fromEnvironment == null ? null : fromEnvironment.getUserInfo();
            if (userInfo == null || userInfo.isBlank()) {
                return null;
            }
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            password = toChars(colon < 0 ? "" : userInfo.substring(colon + 1));
        }

        String proxyUser = user;
        char[] proxyPassword = password;
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(proxyUser, proxyPassword.clone());
            }
        };
    }

    private static char[] toChars(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static URI firstProxyUri(UnaryOperator<String> environment) {
        for (String name : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                "ALL_PROXY", "all_proxy")) {
            URI parsed = parseProxy(environment.apply(name));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    static URI parseProxy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.trim();
        try {
            URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException notAUri) {
            return null;
        }
    }

    /**
     * {@code NO_PROXY} semantics as curl and Go's {@code httpproxy} implement them: a comma-separated
     * list, {@code *} for "never proxy anything", a leading dot or a bare domain matching any subdomain,
     * an optional {@code :port} — and, since the task-7 review's finding 7, IP literals that are compared
     * as addresses rather than as names.
     *
     * <p>Three cases the naive suffix rule got wrong:
     * <ul>
     *   <li>{@code ::1} was split at its last colon into host {@code ::} and port {@code 1}, so an IPv6
     *       loopback endpoint went through the proxy however the operator spelled the bypass. A port is
     *       only split off when the entry is bracketed or holds at most one colon.</li>
     *   <li>{@code NO_PROXY=0.0.1} bypassed {@code 10.0.0.1} through {@code endsWith(".0.0.1")}. The
     *       subdomain rule is for NAMES; an IP literal must match exactly.</li>
     *   <li>{@code 192.168.0.0/16} was not understood at all, though it is the form an operator with a
     *       corporate proxy reaches for first.</li>
     * </ul>
     */
    static boolean bypasses(String noProxy, String host, int port) {
        if (noProxy == null || noProxy.isBlank() || host == null || host.isBlank()) {
            return false;
        }
        String target = unbracket(host.toLowerCase(Locale.ROOT));
        for (String rawEntry : noProxy.split(",")) {
            String entry = rawEntry.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if ("*".equals(entry)) {
                return true;
            }
            if (entry.contains("/")) {
                if (matchesCidr(entry, target)) {
                    return true;
                }
                continue;
            }

            String candidate = entry;
            int colon = candidate.lastIndexOf(':');
            boolean portIsSplittable = candidate.startsWith("[")
                    ? candidate.indexOf(']') < colon
                    : candidate.indexOf(':') == colon;
            if (colon > 0 && portIsSplittable
                    && !candidate.substring(colon + 1).isEmpty()
                    && candidate.substring(colon + 1).chars().allMatch(Character::isDigit)) {
                if (port != Integer.parseInt(candidate.substring(colon + 1))) {
                    continue;
                }
                candidate = candidate.substring(0, colon);
            }
            candidate = unbracket(candidate);

            if (isIpLiteral(candidate) || isIpLiteral(target)) {
                // Addresses, not names: no suffix rule, and ::1 == 0:0:0:0:0:0:0:1.
                if (sameAddress(candidate, target)) {
                    return true;
                }
                continue;
            }
            String suffix = candidate.startsWith(".") ? candidate.substring(1) : candidate;
            if (!suffix.isEmpty() && (target.equals(suffix) || target.endsWith("." + suffix))) {
                return true;
            }
        }
        return false;
    }

    private static String unbracket(String value) {
        return value.startsWith("[") && value.endsWith("]") && value.length() > 2
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static boolean isIpLiteral(String value) {
        return value.indexOf(':') >= 0
                || (!value.isEmpty() && value.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.')
                    && value.indexOf('.') > 0);
    }

    private static boolean sameAddress(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        byte[] a = addressOf(left);
        byte[] b = addressOf(right);
        return a != null && b != null && java.util.Arrays.equals(a, b);
    }

    /** {@code 192.168.0.0/16} and {@code fd00::/8}. Never resolves: a literal or nothing. */
    private static boolean matchesCidr(String entry, String target) {
        int slash = entry.lastIndexOf('/');
        byte[] network = addressOf(unbracket(entry.substring(0, slash)));
        byte[] address = addressOf(target);
        if (network == null || address == null || network.length != address.length) {
            return false;
        }
        int bits;
        try {
            bits = Integer.parseInt(entry.substring(slash + 1).trim());
        } catch (NumberFormatException notAPrefix) {
            return false;
        }
        if (bits < 0 || bits > network.length * 8) {
            return false;
        }
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >> (i % 8);
            if ((network[i / 8] & mask) != (address[i / 8] & mask)) {
                return false;
            }
        }
        return true;
    }

    /** The bytes of an IP LITERAL, or null. Never a DNS lookup — a bypass list may not cost a resolve. */
    private static byte[] addressOf(String literal) {
        if (literal == null || literal.isEmpty() || !isIpLiteral(literal)) {
            return null;
        }
        try {
            return java.net.InetAddress.getByName(
                    literal.indexOf(':') >= 0 ? "[" + literal + "]" : literal).getAddress();
        } catch (java.net.UnknownHostException notALiteral) {
            return null;
        }
    }

    /** C11 in one place, and testable without a live proxy in front of it. */
    static final class EnvironmentProxySelector extends ProxySelector {

        private final UnaryOperator<String> environment;
        private final ProxySelector jvmDefault;

        EnvironmentProxySelector(UnaryOperator<String> environment, ProxySelector jvmDefault) {
            this.environment = environment;
            this.jvmDefault = jvmDefault;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) {
                return List.of(Proxy.NO_PROXY);
            }
            // The JVM's own switches win: an operator who set -Dhttp.proxyHost meant it, and
            // http.nonProxyHosts is already honoured by the default selector.
            List<Proxy> fromJvm = jvmDefault == null ? List.of() : jvmDefault.select(uri);
            if (fromJvm.stream().anyMatch(proxy -> proxy.type() != Proxy.Type.DIRECT)) {
                return fromJvm;
            }

            int port = uri.getPort() > 0 ? uri.getPort() : defaultPortOf(uri.getScheme());
            if (bypasses(environment.apply("NO_PROXY"), uri.getHost(), port)
                    || bypasses(environment.apply("no_proxy"), uri.getHost(), port)) {
                return List.of(Proxy.NO_PROXY);
            }

            URI proxy = proxyFor(uri.getScheme());
            if (proxy == null) {
                return List.of(Proxy.NO_PROXY);
            }
            int proxyPort = proxy.getPort() > 0 ? proxy.getPort() : defaultPortOf(proxy.getScheme());
            return List.of(new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(proxy.getHost(), proxyPort)));
        }

        private URI proxyFor(String scheme) {
            List<String> names = "https".equalsIgnoreCase(scheme)
                    ? List.of("HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy")
                    : List.of("HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy");
            for (String name : names) {
                URI parsed = parseProxy(environment.apply(name));
                if (parsed != null) {
                    return parsed;
                }
            }
            return null;
        }

        private static int defaultPortOf(String scheme) {
            return "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            if (jvmDefault != null) {
                jvmDefault.connectFailed(uri, address, failure);
            }
        }
    }
}
