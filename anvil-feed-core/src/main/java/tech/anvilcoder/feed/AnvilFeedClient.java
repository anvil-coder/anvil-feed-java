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

    /** What a disabled SDK sends with: nothing, built from nothing. See {@link #create(AnvilFeedConfig)}. */
    private static final Transport NOT_SENDING =
            (url, body, timeoutMs) -> IngestResponse.failure("anvil-feed is disabled; nothing was sent");

    /** Where a misused {@link RequestScope} is reported when no client has been installed. */
    private static final FeedLogger FALLBACK_LOGGER = FeedLogger.toSystemLogger();

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

    /**
     * The whole SDK from a configuration and nothing else: the JDK transport, one logger, git discovered.
     *
     * <p>A DISABLED configuration builds no {@link HttpTransport} at all (review finding 5). That
     * constructor eagerly creates a named daemon executor and a pinned {@code HttpClient}, and in a
     * deliberately disabled process — dev, CI, a host's own test suite — both would live for the life of
     * the JVM without ever being used. What replaces it answers "not sent" and costs nothing.
     */
    public static AnvilFeedClient create(AnvilFeedConfig config) {
        FeedLogger logger = FeedLogger.toSystemLogger();
        GitContext git = GitContext.discover(Path.of(System.getProperty("user.dir", ".")));
        if (!config.enabled()) {
            return create(config, NOT_SENDING, logger, git, null, null, null);
        }
        HttpTransport transport = new HttpTransport(config);
        return create(config, transport, logger, git, null, null, transport);
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
        Transport wire = transport;
        AutoCloseable owned = ownedTransport;
        if (wire == null) {
            // Same rule as create(config): nothing is built to send with when nothing will be sent.
            if (config.enabled()) {
                HttpTransport built = new HttpTransport(config);
                wire = built;
                owned = built;
            } else {
                wire = NOT_SENDING;
            }
        }
        EventQueue events = queue == null ? new EventQueue(config.maxQueueSize(), log) : queue;
        LongSupplier clock = clockMillis == null ? System::currentTimeMillis : clockMillis;
        // ONE guard instance, shared with the sender: the sender raises the re-entrancy flag around every
        // flush, on whichever thread the flush runs on, and the capture path below reads it.
        LoopGuard guard = new LoopGuard(config.ignoredRequestPaths());

        return new AnvilFeedClient(config,
                new EventFactory(config, new Scrubber(config.denylist()), context),
                events,
                new Sender(config, events, wire, log, clock, guard),
                new RateGate(config.fingerprintCap(), config.fingerprintWindowMs(), clock),
                guard,
                log, owned);
    }

    // ------------------------------------------------------------------ the request scope

    /**
     * What the host is serving on THIS thread, for as long as the returned scope is open. FR-2 d (b): an
     * exception raised while serving {@code /api/feed/**} is the ingest's own failure and is not reported.
     *
     * <p><b>Use it with try-with-resources, or with try/finally.</b> A scope that is never closed leaves
     * its path on a POOLED request thread, and if that path matched an ignored pattern every later
     * exception on that worker is dropped in silence — error reporting off for one thread, with nothing
     * in any log:
     *
     * <pre>{@code
     * try (AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest(request.getRequestURI())) {
     *     chain.doFilter(request, response);
     * }
     * }</pre>
     *
     * <p>javac's {@code -Xlint:try} warns when the resource variable is never referenced in the body, and
     * this module (like every module in this build) compiles with {@code -Werror}. Either reference it or
     * write the try/finally form:
     *
     * <pre>{@code
     * AnvilFeedClient.enterRequest(uri);
     * try { chain.doFilter(request, response); } finally { AnvilFeedClient.leaveRequest(); }
     * }</pre>
     *
     * <p>Scopes NEST: closing an inner one restores the path the outer one set, which is the shape a
     * servlet container's ERROR or FORWARD re-dispatch has. A scope may only be closed on the thread that
     * opened it; a {@code close()} from anywhere else changes nothing and is said once, because clearing
     * the closing thread's (empty) entry would leave the owner's path behind for ever.
     *
     * <p>A full URL is accepted as readily as a path, and the path is expected to be DECODED — the guard
     * percent-decodes once as a backstop, but a host that hands over what it actually routed on is the
     * only thing that can be relied on. Nothing in the SDK reads this value except the loop guard, and a
     * capture whose own {@code context} carries a {@code request.url} uses that instead.
     */
    public static RequestScope enterRequest(String pathOrUrl) {
        RequestScope scope = new RequestScope(Thread.currentThread(), REQUEST_PATH.get());
        REQUEST_PATH.set(pathOrUrl);
        return scope;
    }

    /**
     * Clears this thread's path outright, ignoring any nesting — the counterpart of the try/finally form
     * above, where there is one scope and nothing to restore. Idempotent. Prefer {@link RequestScope} in
     * anything that can nest.
     */
    public static void leaveRequest() {
        REQUEST_PATH.remove();
    }

    /** The path the host declared for this thread, or null. */
    public static String currentRequestPath() {
        return REQUEST_PATH.get();
    }

    /**
     * One open request scope. Holds the thread that opened it and the path that was in force before, so
     * that {@link #close()} restores rather than clears (nesting) and refuses to run anywhere else
     * (thread affinity). Both were review finding 2.
     */
    public static final class RequestScope implements AutoCloseable {

        private final Thread owner;
        private final String previous;
        private boolean closed;

        private RequestScope(Thread owner, String previous) {
            this.owner = owner;
            this.previous = previous;
        }

        /** Restores the path this scope replaced. Idempotent, and a no-op on any other thread. */
        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                // Clearing here would clear the CLOSING thread's entry and leave the owner's path in
                // place for ever — on a pooled worker, error reporting silently off. Doing nothing at
                // least leaves the owner able to close it itself.
                scopeLogger().once("request-scope-thread",
                        "anvil-feed: a request scope opened on \"" + owner.getName() + "\" was closed on \""
                                + Thread.currentThread().getName() + "\". It was left open, because "
                                + "closing it here would clear the wrong thread. Close the scope on the "
                                + "thread that opened it — with an async hand-off, open a new scope on "
                                + "the thread that continues the work. This is said once.");
                return;
            }
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                REQUEST_PATH.remove();
            } else {
                REQUEST_PATH.set(previous);
            }
        }
    }

    /**
     * The installed client's logger, so that a misused scope is reported where the host is already
     * reading — and {@code System.err} when nothing has been installed, which is the only sink a static
     * method can be sure of.
     */
    private static FeedLogger scopeLogger() {
        AnvilFeedClient installed = AnvilFeed.current();
        return installed == null ? FALLBACK_LOGGER : installed.logger;
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

    /**
     * The re-entrancy flag is NOT raised here. {@link Sender#flush(long)} raises it, which is the one
     * method every flush funnels through — this one, the daemon pump, the shutdown hook and
     * {@link Sender#close()} — so it is raised on whichever thread actually sends (review finding 1).
     * Raising it here as well would make the sender's own attempt re-entrant and drain nothing.
     */
    private void flushGuarded(Runnable flush) {
        try {
            flush.run();
        } catch (RuntimeException | Error ours) {
            logger.warning("anvil-feed: flush failed: " + ours);
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
                if (rateGate.firstSuppression(fingerprint)) {
                    sayTheStorm(fingerprint);
                }
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
            // logger.once, not a volatile boolean of our own: a plain read-then-write races, and this
            // module already has one mechanism for saying a thing once (review finding 8).
            logger.once("beforeSend-threw", "anvil-feed: beforeSend threw " + theirs.getClass().getName()
                    + ": " + theirs.getMessage()
                    + "; the event is sent unmodified and this is said once.");
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

        if (!refused.isEmpty()) {
            logger.once("beforeSend-read-only", "anvil-feed: beforeSend changed "
                    + String.join(", ", refused)
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
        logger.once("beforeSend-unsendable", "anvil-feed: beforeSend returned an event without "
                + String.join(", ", missing)
                + "; the ingest would refuse it, so it was dropped here. This is said once.");
        return false;
    }

    /**
     * One log line per window per fingerprint — the storm itself must not become the log flood. The "once"
     * is decided by {@link RateGate#firstSuppression}, under the gate's own lock: reading
     * {@code suppressed(...) != 1} here was two synchronized calls with a gap between them, and two
     * threads storming one fingerprint could step over the edge together and BOTH stay silent — losing
     * the line in exactly the multi-threaded case a storm arrives in (review finding 3).
     */
    private void sayTheStorm(String fingerprint) {
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
