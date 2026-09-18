package tech.anvilcoder.feed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The whole SDK from the caller's side. Every public capture method runs on the application's own thread,
 * so every one does the same three things: check whether it may run at all, build and queue, and return.
 * Nothing sends. Sending happens on the {@link Sender}'s daemon thread and in its shutdown hook (Z4: no
 * network call in the request path, p99 under one millisecond).
 *
 * <p>FR-2 b is enforced at exactly one place — the try/catch around each capture. Nothing below it is
 * allowed to reach the caller: not the factory, not the serialiser, not the host's own {@code beforeSend}
 * hook, and not the logger.
 *
 * <p><b>Every capture returns the {@code event_id} it queued, or {@code null}.</b> {@code null} is not an
 * error; it is the documented answer for <em>disabled</em>, <em>stopped after a 401</em>, <em>raised
 * inside a flush</em>, <em>an ignored request path</em>, <em>over the per-fingerprint cap</em>,
 * <em>dropped by {@code beforeSend}</em>, and <em>anything at all went wrong</em> — the last one logged,
 * never raised.
 *
 * <p><b>One client per process</b>, like the {@link Sender} it holds: the rate gate's windows, the
 * sender's backoff and its stop flag are all process state. The counterpart of {@code AnvilFeed\Client}
 * (C9); what Java adds is the thread-bound request scope PHP reads off {@code $_SERVER} instead.
 */
public final class AnvilFeedClient implements AutoCloseable {

    /**
     * Top-level members a {@code beforeSend} hook may read but not move. {@code event_id} keys the
     * delivery — {@code Sender} matches the ingest's verdicts on it, and a body naming a different one is
     * retried once and then dropped with a log line blaming the network for what host code did.
     * {@code timestamp}, {@code sdk}, {@code service} and {@code environment} are the SDK's own statement
     * about itself and about the binding the ingest resolves; a hook rewriting {@code service} would post
     * one customer's events into another project's incident stream. {@code title} — and, below,
     * {@code exception.type} and {@code exception.stacktrace} — are the FINGERPRINT inputs: the local cap
     * has already been charged against the identity computed from them, so a hook that changed them would
     * cap one group and send another.
     */
    private static final List<String> READ_ONLY = List.of("schema_version", "event_id", "timestamp", "sdk",
            "service", "environment", "title");

    /** What schema 1.0 requires of every event, checked again after a hook has been over it. */
    private static final List<String> REQUIRED = List.of("schema_version", "event_id", "timestamp", "title",
            "level", "environment", "service", "language");

    /**
     * What the HOST says it is serving, for the thread it is serving it on. The SDK never guesses it:
     * Java has no {@code $_SERVER}, and a value read off anything ambient would be whatever the process
     * happened to start with on a queue worker or a CLI command. Task 10's starter sets it in a filter;
     * see {@link #enterRequest(String)}.
     */
    private static final ThreadLocal<String> REQUEST_PATH = new ThreadLocal<>();

    private final AnvilFeedConfig config;
    private final EventFactory factory;
    private final EventQueue queue;
    private final Sender sender;
    private final RateGate rateGate;
    private final LoopGuard loopGuard;
    private final FeedLogger logger;
    /** Closed by {@link #close()} only when this client created it; an injected transport stays the host's. */
    private final AutoCloseable ownedTransport;

    private volatile UnaryOperator<Map<String, Object>> beforeSend;
    private volatile boolean beforeSendFailureSaid;
    private volatile boolean beforeSendReadOnlySaid;
    private volatile boolean beforeSendUnsendableSaid;

    public AnvilFeedClient(AnvilFeedConfig config, EventFactory factory, EventQueue queue, Sender sender,
                           RateGate rateGate, LoopGuard loopGuard, FeedLogger logger) {
        this(config, factory, queue, sender, rateGate, loopGuard, logger, null);
    }

    private AnvilFeedClient(AnvilFeedConfig config, EventFactory factory, EventQueue queue, Sender sender,
                            RateGate rateGate, LoopGuard loopGuard, FeedLogger logger,
                            AutoCloseable ownedTransport) {
        this.config = config;
        this.factory = factory;
        this.queue = queue;
        this.sender = sender;
        this.rateGate = rateGate;
        this.loopGuard = loopGuard;
        this.logger = logger == null ? FeedLogger.toSystemLogger() : logger;
        this.ownedTransport = ownedTransport;
    }

    /** The whole SDK from a configuration and nothing else: the JDK transport, one logger, git discovered. */
    public static AnvilFeedClient create(AnvilFeedConfig config) {
        FeedLogger logger = FeedLogger.toSystemLogger();
        HttpTransport transport = new HttpTransport(config);
        return create(config, transport, logger,
                GitContext.discover(Path.of(System.getProperty("user.dir", "."))), null, null, transport);
    }

    public static AnvilFeedClient create(AnvilFeedConfig config, Transport transport, FeedLogger logger,
                                         GitContext git) {
        return create(config, transport, logger, git, null, null, null);
    }

    /** @param clockMillis shared by the {@link Sender} and the {@link RateGate}; null means the default. */
    public static AnvilFeedClient create(AnvilFeedConfig config, Transport transport, FeedLogger logger,
                                         GitContext git, LongSupplier clockMillis) {
        return create(config, transport, logger, git, null, clockMillis, null);
    }

    /**
     * Every seam at once, for a host that brings its own. A {@code null} argument means "the default for
     * this one", so a caller that only wants to swap the queue writes only the queue.
     */
    public static AnvilFeedClient create(AnvilFeedConfig config, Transport transport, FeedLogger logger,
                                         GitContext git, EventQueue queue, LongSupplier clockMillis) {
        return create(config, transport, logger, git, queue, clockMillis, null);
    }

    private static AnvilFeedClient create(AnvilFeedConfig config, Transport transport, FeedLogger logger,
                                          GitContext git, EventQueue queue, LongSupplier clockMillis,
                                          AutoCloseable ownedTransport) {
        FeedLogger log = logger == null ? FeedLogger.toSystemLogger() : logger;
        GitContext context = git == null ? GitContext.none() : git;
        Transport wire = transport == null ? new HttpTransport(config) : transport;
        AutoCloseable owned = ownedTransport == null && transport == null ? (AutoCloseable) wire
                : ownedTransport;
        EventQueue events = queue == null ? new EventQueue(config.maxQueueSize(), log) : queue;
        LongSupplier clock = clockMillis == null ? System::currentTimeMillis : clockMillis;

        return new AnvilFeedClient(config,
                new EventFactory(config, new Scrubber(config.denylist()), context),
                events,
                new Sender(config, events, wire, log, clock),
                new RateGate(config.fingerprintCap(), config.fingerprintWindowMs(), clock),
                new LoopGuard(config.ignoredRequestPaths()),
                log, owned);
    }

    // ------------------------------------------------------------------ the request scope

    /**
     * What the host is serving on THIS thread, for as long as the returned scope is open. FR-2 d (b): an
     * exception raised while serving {@code /api/feed/**} is the ingest's own failure and is not reported.
     *
     * <pre>{@code
     * try (AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest(request.getRequestURI())) {
     *     chain.doFilter(request, response);
     * }
     * }</pre>
     *
     * <p>A full URL is accepted as readily as a path. Nothing in the SDK reads this value except the loop
     * guard, and a capture whose own {@code context} carries a {@code request.url} uses that instead — an
     * explicit context always wins.
     */
    public static RequestScope enterRequest(String pathOrUrl) {
        REQUEST_PATH.set(pathOrUrl);
        return AnvilFeedClient::leaveRequest;
    }

    /** Ends the scope. Idempotent; safe on a thread that never entered one. */
    public static void leaveRequest() {
        REQUEST_PATH.remove();
    }

    /** The path the host declared for this thread, or null. */
    public static String currentRequestPath() {
        return REQUEST_PATH.get();
    }

    /** An {@link AutoCloseable} whose {@code close} throws nothing, so try-with-resources stays quiet. */
    @FunctionalInterface
    public interface RequestScope extends AutoCloseable {
        @Override
        void close();
    }

    // ------------------------------------------------------------------ capturing

    public String captureException(Throwable error) {
        return captureException(error, Map.of());
    }

    public String captureException(Throwable error, Map<String, Object> context) {
        Map<String, Object> given = context == null ? Map.of() : context;
        String level = given.get("level") instanceof String text ? text : "error";
        return capture(() -> factory.fromThrowable(error, level, given), given);
    }

    /** FR-4's default severity for a message is {@code warning}, as it is in PHP. */
    public String captureMessage(String message) {
        return captureMessage(message, "warning", Map.of());
    }

    /**
     * There is no message-only event. The ingest requires {@code exception.type}, so {@link EventFactory}
     * synthesises an {@code AnvilFeed.Message} exception carrying the CALLER's own stack — which is also
     * what gives this event a fingerprint worth capping, since a log line repeated in a loop is exactly
     * the storm FR-2 d is about. Two {@code captureMessage} calls from two different places are two
     * identities; see {@code EventFactory.callerFrames()} for how this SDK's own frames are dropped.
     */
    public String captureMessage(String message, String level, Map<String, Object> context) {
        Map<String, Object> given = context == null ? Map.of() : context;
        return capture(() -> factory.fromMessage(message, level, given), given);
    }

    /** FR-8 a: the explicit verification call an integration guide ends with. */
    public String captureTestEvent() {
        return capture(factory::testEvent, Map.of());
    }

    /**
     * The hook of FR-5 and the escape hatch for anything this SDK cannot know about. It receives a MUTABLE
     * COPY of the fully built, already scrubbed payload and returns a payload, or {@code null} to drop the
     * event. {@code beforeSend(null)} removes it.
     *
     * <p>What the hook cannot do, and what happens when it tries:
     * <ul>
     *   <li>The identity fields — {@code event_id}, {@code timestamp}, {@code sdk}, {@code service},
     *       {@code environment}, {@code title}, {@code exception.type}, {@code exception.stacktrace} — are
     *       read-only: a changed value is restored and logged once.</li>
     *   <li>The returned payload goes through {@link EventFactory#finalise} AGAIN — scrub, bounds, counts,
     *       level — before it is queued, so a hook cannot put PII or an over-cap value on the wire (FR-5
     *       is SDK-side; the server is a backstop, not the plan).</li>
     *   <li>A hook that THROWS costs the modification, not the event: the failure is logged once and the
     *       unmodified event is sent (FR-2 — host code cannot take the host down through us).</li>
     *   <li>A hook that RETURNS a payload the schema cannot accept (a required field removed, say
     *       {@code language}) drops the event locally with one log line. That is deliberate and stricter
     *       than sending: the ingest would answer 400, which the retry rules never retry, so the event
     *       would be lost either way — this way the loss is named in the host's own log.</li>
     * </ul>
     */
    public void beforeSend(UnaryOperator<Map<String, Object>> hook) {
        this.beforeSend = hook;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the sender's daemon thread and its shutdown hook. Idempotent; a no-op when disabled. */
    public void start() {
        sender.start();
    }

    /** Bounded by {@code request_timeout_ms} from now. */
    public void flush() {
        flushGuarded(sender::flush);
    }

    /**
     * @param budgetMs the whole call's bound, in milliseconds — the wait for the flush lock included, so a
     *                 response filter that can spare 200 ms never spends the daemon pump's full budget
     *                 getting in.
     */
    public void flush(long budgetMs) {
        flushGuarded(() -> sender.flushWithin(budgetMs));
    }

    /**
     * Stops the sender (which takes one last bounded pass over the queue) and then closes the transport if
     * this client created it. Idempotent and total.
     */
    @Override
    public void close() {
        try {
            sender.close();
        } catch (RuntimeException | Error ours) {
            logger.warning("anvil-feed: closing the sender failed: " + ours);
        }
        if (ownedTransport != null) {
            try {
                ownedTransport.close();
            } catch (Exception | Error ours) {
                logger.warning("anvil-feed: closing the transport failed: " + ours);
            }
        }
    }

    public EventQueue queue() {
        return queue;
    }

    public Sender sender() {
        return sender;
    }

    public AnvilFeedConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ the one path every capture takes

    private void flushGuarded(Runnable flush) {
        if (!loopGuard.enterSend()) {
            return;
        }
        try {
            flush.run();
        } catch (RuntimeException | Error ours) {
            logger.warning("anvil-feed: flush failed: " + ours);
        } finally {
            loopGuard.leaveSend();
        }
    }

    private String capture(Supplier<FeedEvent> build, Map<String, Object> context) {
        try {
            if (!config.enabled() || sender.isStopped()) {
                return null;
            }
            if (loopGuard.isSending()) {
                return null;   // the sender's own failure. Reporting it is the loop.
            }
            if (loopGuard.ignoresPath(requestPath(context))) {
                return null;
            }

            FeedEvent event = build.get();
            String fingerprint = Fingerprint.ofEvent(event.payload());
            if (!rateGate.allow(fingerprint)) {
                sayTheStormOnce(fingerprint);
                return null;
            }

            Map<String, Object> payload = throughBeforeSend(event.payload());
            if (payload == null) {
                return null;
            }

            queue.push(new FeedEvent(event.id(), payload));
            return event.id();
        } catch (RuntimeException | Error ours) {
            // FR-2 b, and the last line of it: an error in the error handler must not recurse.
            try {
                logger.warning("anvil-feed: capture failed: " + ours);
            } catch (RuntimeException | Error nowhereLeftToGo) {
                // Deliberately empty. There is nowhere left to report to.
            }
            return null;
        }
    }

    /**
     * The host's hook is host code, and FR-2 says this SDK cannot take the host down. So an exception out
     * of it costs the modification, not the event: the original payload — the one this SDK built and
     * scrubbed itself — is sent, and the failure is said once rather than once per event, because a hook
     * that throws throws on every single event of the storm that follows.
     *
     * @return the payload to queue, or null when the event is dropped
     */
    private Map<String, Object> throughBeforeSend(Map<String, Object> payload) {
        UnaryOperator<Map<String, Object>> hook = beforeSend;
        if (hook == null) {
            return payload;
        }

        Map<String, Object> modified;
        try {
            // A MUTABLE copy: FeedEvent.payload() is an unmodifiable view, and a hook that reached for
            // put() on it would take an UnsupportedOperationException — host code punished for using the
            // documented API.
            modified = hook.apply(deepCopy(payload));
        } catch (RuntimeException | Error theirs) {
            if (!beforeSendFailureSaid) {
                beforeSendFailureSaid = true;
                logger.warning("anvil-feed: beforeSend threw " + theirs.getClass().getName() + ": "
                        + theirs.getMessage() + "; the event is sent unmodified and this is said once.");
            }
            return payload;
        }
        if (modified == null || modified.isEmpty()) {
            return null;   // null is the documented drop; anything unusable is treated as one
        }

        // The hook edited a VIEW, not the event. Everything below is put back, and then the whole thing
        // goes through the factory's own scrub -> bound -> count -> level pipeline a second time.
        Map<String, Object> restored = withReadOnlyFieldsRestored(modified, payload);
        Map<String, Object> finalised = FeedEvent.inCanonicalOrder(factory.finalise(restored));
        return isSendable(finalised) ? finalised : null;
    }

    /** The fields a hook may not move; see {@link #READ_ONLY} for why each one is on the list. */
    private Map<String, Object> withReadOnlyFieldsRestored(Map<String, Object> modified,
                                                           Map<String, Object> original) {
        Map<String, Object> result = new LinkedHashMap<>(modified);
        List<String> refused = new ArrayList<>();

        for (String key : READ_ONLY) {
            Object ours = original.get(key);
            if (!equal(result.get(key), ours)) {
                refused.add(key);
            }
            if (ours == null) {
                result.remove(key);
            } else {
                result.put(key, ours);
            }
        }

        Object originalException = original.get("exception");
        Map<String, Object> exception = result.get("exception") instanceof Map<?, ?> map
                ? copyOf(map) : null;
        if (exception == null) {
            // The hook replaced the exception block with something that is not a block at all. There is
            // nothing to restore INTO, so the SDK's own block goes back whole.
            if (originalException != null) {
                refused.add("exception");
                result.put("exception", originalException);
            }
        } else {
            Map<String, Object> ourException = originalException instanceof Map<?, ?> map
                    ? copyOf(map) : Map.of();
            for (String member : List.of("type", "stacktrace")) {
                Object ours = ourException.get(member);
                if (!equal(exception.get(member), ours)) {
                    refused.add("exception." + member);
                }
                if (ours == null) {
                    exception.remove(member);
                } else {
                    exception.put(member, ours);
                }
            }
            result.put("exception", exception);
        }

        if (!refused.isEmpty() && !beforeSendReadOnlySaid) {
            beforeSendReadOnlySaid = true;
            logger.warning("anvil-feed: beforeSend changed " + String.join(", ", refused)
                    + ", which this SDK owns; the change was ignored and this is said once.");
        }
        return result;
    }

    /**
     * What the ingest requires, checked on the hook's output only. An event this SDK built correctly and
     * host code then broke must not be posted for the ingest to answer 400 {@code schema_violation} about,
     * with nothing in the log naming the cause.
     */
    private boolean isSendable(Map<String, Object> payload) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED) {
            Object value = payload.get(key);
            if (value == null || "".equals(value)) {
                missing.add(key);
            }
        }
        if (!(payload.get("exception") instanceof Map<?, ?> exception) || exception.get("type") == null) {
            missing.add("exception.type");
        }
        if (missing.isEmpty()) {
            return true;
        }
        if (!beforeSendUnsendableSaid) {
            beforeSendUnsendableSaid = true;
            logger.warning("anvil-feed: beforeSend returned an event without " + String.join(", ", missing)
                    + "; the ingest would refuse it, so it was dropped here. This is said once.");
        }
        return false;
    }

    /** One log line per window per fingerprint — the storm itself must not become the log flood. */
    private void sayTheStormOnce(String fingerprint) {
        if (rateGate.suppressed(fingerprint) != 1) {
            return;
        }
        logger.warning("anvil-feed: more than " + config.fingerprintCap() + " occurrences of "
                + fingerprint.substring(0, Math.min(12, fingerprint.length())) + " per "
                + config.fingerprintWindowMs() + "ms; suppressing locally. The ingest counts occurrences "
                + "server-side, so nothing is lost that matters.");
    }

    /**
     * What the host says it is serving. An explicit context wins over the thread's scope, because a
     * capture that names its own request knows more than a filter that wrapped the whole thread.
     */
    private static String requestPath(Map<String, Object> context) {
        if (context.get("request") instanceof Map<?, ?> request
                && request.get("url") instanceof String url && !url.isEmpty()) {
            return url;
        }
        return currentRequestPath();
    }

    /**
     * Mutable top to bottom, so a hook can edit {@code exception.stacktrace} or {@code tags} in place
     * without meeting an {@link UnsupportedOperationException} two levels down.
     */
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> payload) {
        return (Map<String, Object>) deepCopyValue(payload);
    }

    private static Map<String, Object> copyOf(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static boolean equal(Object one, Object other) {
        return one == null ? other == null : one.equals(other);
    }
}
