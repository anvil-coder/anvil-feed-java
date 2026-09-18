package tech.anvilcoder.feed.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;

import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.IngestResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * FR-8: install, configure, run this, see the event. That is the fifteen minutes.
 *
 * <p>Run with {@code --anvil.feed.send-test-event=true} on the command line — one run, no code, and no
 * HTTP endpoint of its own. An endpoint was considered and rejected: a starter that mounts a route into a
 * customer's application changes that application's surface, and a route that sends an event on request is
 * a route somebody will find.
 *
 * <p>What it prints is a <b>verdict</b>, not a confirmation that something was sent. "Sent" is the proxy
 * that makes a developer wait ten minutes for an event the ingest rejected with {@code no_binding} half a
 * second after it arrived. Four answers are possible and all four are named, in the same words the Laravel
 * package's {@code anvil-feed:test} uses:
 *
 * <pre>
 *   accepted             the ingest stored it; it is in the cockpit inbox
 *   rejected             it arrived and was refused, with the ingest's own reason_code
 *   failed transiently   it did not arrive; the event stays in the queue and the log says why
 *   disabled             nothing was sent, because the SDK is switched off — a missing token, usually
 *   nothing was queued   a 401 earlier in this process stopped the SDK for its lifetime
 * </pre>
 *
 * <h2>The exit code</h2>
 * {@code accepted} is 0 and every other verdict is 1, which is the Laravel twin's contract
 * ({@code TestCommand::handle(): int}, {@code self::SUCCESS} / {@code self::FAILURE}) — a smoke-test stage
 * that runs this and passes on a {@code no_binding} rejection is the "it was sent" proxy this class exists
 * to kill, reintroduced one layer up.
 *
 * <p><b>Spring Boot only uses an {@link ExitCodeGenerator} where the application actually exits.</b> In a
 * long-running web application the runner prints its verdict and the server keeps serving; the code is
 * used when the process ends through {@code SpringApplication.exit(...)}, which is what a smoke-test
 * launcher does:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     System.exit(SpringApplication.exit(SpringApplication.run(ShopApplication.class, args)));
 * }
 * }</pre>
 */
public final class AnvilFeedTestEvent implements CommandLineRunner, ExitCodeGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Twelve characters: the eight-character prefix {@code anv_srv_} plus four of the secret — enough to
     * see that the format is right, in a line that ends up in a CI log or a screen share. */
    private static final int TOKEN_PREVIEW_CHARS = 12;

    private final AnvilFeedConfig config;
    private final AnvilFeedClient client;
    private final AnvilFeedVerdict verdict;
    private final Consumer<String> out;

    public AnvilFeedTestEvent(AnvilFeedConfig config, AnvilFeedClient client, AnvilFeedVerdict verdict) {
        this(config, client, verdict, System.out::println);
    }

    AnvilFeedTestEvent(AnvilFeedConfig config, AnvilFeedClient client, AnvilFeedVerdict verdict,
                       Consumer<String> out) {
        this.config = config;
        this.client = client;
        this.verdict = verdict;
        this.out = out;
    }

    private volatile int exitCode;

    @Override
    public void run(String... args) {
        List<String> lines = report();
        for (String line : lines) {
            out.accept(line);
        }
        exitCode = lines.contains("verdict:     accepted") ? 0 : 1;
    }

    /** 0 for {@code accepted}, 1 for every other verdict — and 0 before {@link #run} has said anything. */
    @Override
    public int getExitCode() {
        return exitCode;
    }

    /** Every line this would print, in order. Separated from printing so a test can read the verdict. */
    List<String> report() {
        List<String> lines = new ArrayList<>();
        lines.add("endpoint:    " + config.eventsUrl());
        lines.add("service:     " + config.service());
        lines.add("environment: " + config.environment());
        lines.add("release:     " + (config.release() == null
                ? "(none — not a git checkout, and ANVIL_RELEASE is unset)" : config.release()));
        lines.add("token:       " + (config.token() == null
                ? "MISSING — set ANVIL_INGEST_TOKEN"
                : config.token().substring(0, Math.min(TOKEN_PREVIEW_CHARS, config.token().length()))
                        + "… (" + config.token().length() + " characters)"));

        if (!config.enabled()) {
            lines.add("verdict:     disabled");
            lines.add(config.token() == null
                    ? "             no ingest token (ANVIL_INGEST_TOKEN). Nothing was sent."
                    : "             the configuration above was refused; the log line beside it says which "
                            + "value. Nothing was sent.");
            return lines;
        }

        String id = client.captureTestEvent();
        if (id == null) {
            lines.add("verdict:     nothing was queued");
            lines.add("             The log says why. A 401 earlier in this process stops the SDK for its "
                    + "lifetime.");
            return lines;
        }
        lines.add("event_id:    " + id);

        int postsBefore = verdict.posts();
        client.flush();
        IngestResponse answer = verdict.last();

        if (client.sender().isStopped()) {
            lines.add("verdict:     rejected (unauthorized)");
            lines.add("             The ingest answered 401: the token is unknown, revoked, or belongs to "
                    + "another organisation.");
            return lines;
        }
        if (answer == null || verdict.posts() == postsBefore) {
            lines.add("verdict:     failed transiently");
            lines.add("             Nothing reached the ingest. The sender is paused after an earlier 429 "
                    + "or 503, and the event is still in the queue.");
            return lines;
        }

        Verdict read = readVerdict(answer, id);
        lines.add(switch (read.outcome()) {
            case "accepted" -> "verdict:     accepted";
            case "rejected" -> "verdict:     rejected (" + read.label() + ")";
            default -> "verdict:     failed transiently";
        });
        for (String detail : read.details()) {
            lines.add("             " + detail);
        }
        if ("accepted".equals(read.outcome())) {
            lines.add("Search the Anvil cockpit inbox for " + id + ". It is there within a minute.");
        }
        return lines;
    }

    private record Verdict(String outcome, String label, List<String> details) {
    }

    /**
     * The ingest's own answer, read the way {@code docs/anvil-feed-ingest.md} defines it: a 202 carries a
     * verdict per event in {@code accepted} or {@code rejected}, and a rejection always names a
     * {@code reason_code}.
     */
    private Verdict readVerdict(IngestResponse answer, String id) {
        JsonNode body = parse(answer.body());
        JsonNode rejection = rejectionFor(body, id);

        if (answer.status() == 202) {
            for (JsonNode accepted : body.path("accepted")) {
                if (id.equals(accepted.asText())) {
                    return new Verdict("accepted", "accepted", List.of("The ingest stored the event."));
                }
            }
            if (rejection != null) {
                return new Verdict("rejected", reasonCode(rejection), explain(rejection));
            }
            return new Verdict("transient", "no verdict", List.of("The ingest answered 202 without a "
                    + "verdict for this event. The SDK sends it once more and then drops it; the "
                    + "configuration is not at fault."));
        }

        if (answer.transportFailure()) {
            return new Verdict("transient", "unreachable", List.of(answer.failureReason(),
                    "The event stays in the queue and goes out with the next flush."));
        }

        int status = answer.status();
        if (status == 400) {
            return new Verdict("rejected", reasonCode(rejection), explain(rejection));
        }
        if (status == 401) {
            return new Verdict("rejected", "unauthorized", List.of("The ingest answered 401: the token is "
                    + "unknown, revoked, or belongs to another organisation."));
        }
        if (status == 403) {
            return new Verdict("rejected", "forbidden", List.of("The ingest answered 403. A proxy in front "
                    + "of this application is adding an Origin header to a server-token request; fix the "
                    + "proxy, do not rotate the token."));
        }
        if (status == 413) {
            return new Verdict("rejected", "payload_too_large", explain(rejection));
        }
        if (status == 429) {
            return new Verdict("transient", "rate_limited", List.of("The ingest is rate limiting this "
                    + "token. The event stays in the queue and goes out after Retry-After."));
        }
        if (status >= 500) {
            return new Verdict("transient", "ingest_unavailable",
                    List.of("The ingest answered " + status + ". The event stays in the queue."));
        }
        return new Verdict("rejected", "http_" + status, explain(rejection));
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode parsed = JSON.readTree(body == null ? "" : body);
            return parsed == null ? JSON.createObjectNode() : parsed;
        } catch (Exception unreadable) {
            return JSON.createObjectNode();
        }
    }

    /** A rejection naming this event, or naming none at all — the ingest's answer when it could not read
     * the body far enough to name one. */
    private static JsonNode rejectionFor(JsonNode body, String id) {
        for (JsonNode entry : body.path("rejected")) {
            JsonNode named = entry.path("event_id");
            if (id.equals(named.asText()) || named.isMissingNode() || named.isNull()) {
                return entry;
            }
        }
        return null;
    }

    private static String reasonCode(JsonNode rejection) {
        if (rejection == null) {
            return "unknown";
        }
        JsonNode code = rejection.path("reason_code");
        return code.isTextual() ? code.asText() : "unknown";
    }

    /** What the reason code means for the person who just ran this, in the words they need to act on. */
    private static List<String> explain(JsonNode rejection) {
        List<String> lines = new ArrayList<>();
        if (rejection != null && rejection.path("message").isTextual()
                && !rejection.path("message").asText().isBlank()) {
            lines.add(rejection.path("message").asText());
        }
        String hint = switch (reasonCode(rejection)) {
            case "no_binding" -> "Connect this environment in the Anvil cockpit: the token is valid, and "
                    + "nothing is bound to it for the service and environment printed above.";
            case "binding_disabled" -> "The binding exists in the cockpit and is switched off. Switch it on.";
            case "schema_violation" -> "This is an SDK bug, not a configuration mistake. Report it with "
                    + "the message above.";
            default -> "";
        };
        if (!hint.isEmpty()) {
            lines.add(hint);
        }
        return lines.isEmpty() ? List.of("The ingest gave no reason code.") : List.copyOf(lines);
    }
}
