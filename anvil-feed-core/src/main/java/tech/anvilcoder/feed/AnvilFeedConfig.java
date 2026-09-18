package tech.anvilcoder.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Every configuration name and every default. The names and the values are identical to the PHP SDK's
 * {@code AnvilFeed\Config} and both are compared against {@code conformance/config-defaults.json}
 * (C9, C17): a knob that exists in one language only is a semantic drift, not a convenience.
 *
 * <p>FR-2 b at the one door a customer touches: nothing here throws into the host. A value that cannot be
 * used becomes the documented default plus exactly one log line, and three conditions — no token, no
 * service, an unrepairable environment — switch {@link #enabled()} off rather than letting the SDK send
 * events the ingest will answer {@code schema_violation} to.
 *
 * <p><b>Check {@link #enabled()}, never the token.</b> {@code enabled} is forced false by four
 * independent things and in three of them the token is present.
 */
public record AnvilFeedConfig(
        String endpoint,
        String token,
        String service,
        String environment,
        String release,
        int maxQueueSize,
        int flushIntervalMs,
        int batchSize,
        int connectTimeoutMs,
        int requestTimeoutMs,
        int maxFrames,
        int contextLines,
        int maxContextLineChars,
        List<String> denylist,
        boolean enabled,
        int fingerprintCap,
        int fingerprintWindowMs,
        List<String> ignoredRequestPaths) {

    public static final String DEFAULT_ENDPOINT = "https://app.anvil-coder.tech/api/feed";

    /** FR-5 a. */
    public static final List<String> DEFAULT_DENYLIST = List.of(
            "password", "token", "secret", "authorization", "api_key", "cookie", "email",
            "credit_card", "iban");

    /** The environment the ingest binds on: trimmed, lower-cased, and then exactly this. */
    public static final Pattern ENVIRONMENT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /**
     * schema 1.0 bounds the service name and the ingest bounds it nowhere. An over-long name is therefore
     * accepted by the server and refused by the contract, which a customer only ever sees as an empty
     * inbox.
     */
    public static final int MAX_SERVICE_CHARS = 128;

    /** The name used when no service was configured. The SDK is disabled in that case. */
    public static final String UNKNOWN_SERVICE = "unknown-service";

    /** Defensive copies, so a caller that kept its list cannot change a built configuration. */
    public AnvilFeedConfig {
        denylist = denylist == null ? DEFAULT_DENYLIST : List.copyOf(denylist);
        ignoredRequestPaths = ignoredRequestPaths == null ? List.of() : List.copyOf(ignoredRequestPaths);
    }

    public String eventsUrl() {
        return endpoint + "/events";
    }

    public String batchUrl() {
        return endpoint + "/events/batch";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AnvilFeedConfig fromEnvironment() {
        return fromEnvironment(FeedLogger.toSystemLogger());
    }

    public static AnvilFeedConfig fromEnvironment(FeedLogger logger) {
        return builder()
                .endpoint(env("ANVIL_INGEST_ENDPOINT"))
                .token(env("ANVIL_INGEST_TOKEN"))
                .service(env("ANVIL_SERVICE"))
                .environment(env("ANVIL_ENVIRONMENT"))
                .release(env("ANVIL_RELEASE"))
                .build(logger);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Every setter ignores a null or blank value and keeps the default, which is what a host framework
     * hands over for an unset property. No setter throws, and {@link #build(FeedLogger)} is total.
     */
    public static final class Builder {
        private String endpoint = DEFAULT_ENDPOINT;
        private String token;
        private String service;
        private String environment = "production";
        private String release;
        private int maxQueueSize = 1000;
        private int flushIntervalMs = 2000;
        private int batchSize = 50;
        private int connectTimeoutMs = 1000;
        private int requestTimeoutMs = 2000;
        private int maxFrames = 50;
        private int contextLines = 20;
        private int maxContextLineChars = 400;
        private List<String> denylist = DEFAULT_DENYLIST;
        private boolean enabled = true;
        private int fingerprintCap = 10;
        private int fingerprintWindowMs = 60_000;
        private List<String> ignoredRequestPaths = List.of("/api/feed/**");

        public Builder endpoint(String value) { if (present(value)) { this.endpoint = value.trim(); } return this; }
        public Builder token(String value) { if (present(value)) { this.token = value.trim(); } return this; }
        public Builder service(String value) { if (present(value)) { this.service = value.trim(); } return this; }
        public Builder environment(String value) { if (present(value)) { this.environment = value.trim(); } return this; }
        public Builder release(String value) { if (present(value)) { this.release = value.trim(); } return this; }
        public Builder maxQueueSize(int value) { this.maxQueueSize = value; return this; }
        public Builder flushIntervalMs(int value) { this.flushIntervalMs = value; return this; }
        public Builder batchSize(int value) { this.batchSize = value; return this; }
        public Builder connectTimeoutMs(int value) { this.connectTimeoutMs = value; return this; }
        public Builder requestTimeoutMs(int value) { this.requestTimeoutMs = value; return this; }
        public Builder maxFrames(int value) { this.maxFrames = value; return this; }
        public Builder contextLines(int value) { this.contextLines = value; return this; }
        public Builder maxContextLineChars(int value) { this.maxContextLineChars = value; return this; }
        /** An empty denylist is refused and the default kept: FR-5 a is not a knob a typo may switch off. */
        public Builder denylist(List<String> value) {
            List<String> kept = strings(value);
            if (!kept.isEmpty()) {
                this.denylist = kept;
            }
            return this;
        }
        public Builder enabled(boolean value) { this.enabled = value; return this; }
        public Builder fingerprintCap(int value) { this.fingerprintCap = value; return this; }
        public Builder fingerprintWindowMs(int value) { this.fingerprintWindowMs = value; return this; }
        /** An EMPTY list is honoured here: "ignore nothing" is a configuration somebody may mean. */
        public Builder ignoredRequestPaths(List<String> value) {
            if (value != null) {
                this.ignoredRequestPaths = strings(value);
            }
            return this;
        }

        public AnvilFeedConfig build(FeedLogger logger) {
            FeedLogger log = logger == null ? FeedLogger.toSystemLogger() : logger;
            boolean on = enabled;

            String name = service;
            if (!present(name)) {
                log.once("service", "anvil-feed: no service name configured; the SDK is disabled.");
                name = UNKNOWN_SERVICE;
                on = false;
            } else if (name.length() > MAX_SERVICE_CHARS) {
                name = name.substring(0, MAX_SERVICE_CHARS);
                log.once("service-length", "anvil-feed: the service name is longer than " + MAX_SERVICE_CHARS
                        + " characters and was sent as \"" + name + "\". Configure it that way to avoid "
                        + "the rewrite.");
            }
            if (token == null) {
                // Silent, once, at init. A library that says this on every capture gets switched off.
                log.once("token", "anvil-feed: no ingest token (ANVIL_INGEST_TOKEN); the SDK is disabled.");
                on = false;
            }
            String normalized = normalizeEnvironment(environment, log);
            if (normalized == null) {
                on = false;
            }

            return new AnvilFeedConfig(
                    trimTrailingSlash(endpoint), token, name,
                    normalized == null ? "production" : normalized, release,
                    atLeastOne(maxQueueSize, "max_queue_size", log),
                    flushIntervalMs, Math.min(50, Math.max(1, batchSize)),
                    connectTimeoutMs, requestTimeoutMs,
                    Math.min(50, Math.max(1, maxFrames)), Math.min(20, Math.max(0, contextLines)),
                    Math.min(400, Math.max(1, maxContextLineChars)),
                    denylist, on, fingerprintCap, fingerprintWindowMs, ignoredRequestPaths);
        }

        /**
         * The ingest trims, lower-cases and then applies {@link #ENVIRONMENT}. Anything else is a per-event
         * {@code schema_violation}, which a customer only ever discovers by finding no incidents at all.
         * Spring's own {@code spring.profiles.active} routinely holds values this refuses, so the value is
         * repaired here, once, loudly — and where it cannot be repaired, sending is switched off with a
         * message naming the rule rather than the symptom.
         */
        private static String normalizeEnvironment(String raw, FeedLogger logger) {
            String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            String repaired = lower.replaceAll("[^a-z0-9-]+", "-").replaceAll("^[-0-9]+", "");
            if (repaired.length() > 64) {
                repaired = repaired.substring(0, 64);
            }
            if (repaired.isEmpty() || !ENVIRONMENT.matcher(repaired).matches()) {
                logger.once("environment", "anvil-feed: environment \"" + raw + "\" cannot be made to match "
                        + ENVIRONMENT.pattern() + "; the SDK is disabled. An environment must start with a "
                        + "lower-case letter and hold only a-z, 0-9 and \"-\".");
                return null;
            }
            if (!repaired.equals(lower)) {
                logger.once("environment", "anvil-feed: environment \"" + raw + "\" is not a valid binding key "
                        + "and was sent as \"" + repaired + "\". Configure it that way to avoid the rewrite.");
            }
            return repaired;
        }

        /**
         * A queue bound of zero or less is not a smaller queue, it is a queue that drops every event it is
         * given and writes a warning about each — a log flood on the one code path that exists to prevent
         * one.
         */
        private static int atLeastOne(int value, String key, FeedLogger logger) {
            if (value >= 1) {
                return value;
            }
            logger.once("clamp:" + key, "anvil-feed: \"" + key + "\" is " + value + "; 1 is used instead — a "
                    + "bound below one would drop every event and write a warning about each.");
            return 1;
        }

        /** A list handed in by a host framework may hold nulls and blanks; neither may reach a regex. */
        private static List<String> strings(List<String> value) {
            List<String> kept = new ArrayList<>();
            if (value != null) {
                for (String entry : value) {
                    if (present(entry)) {
                        kept.add(entry.trim());
                    }
                }
            }
            return List.copyOf(kept);
        }

        private static String trimTrailingSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }

        private static boolean present(String value) {
            return value != null && !value.isBlank();
        }
    }
}
