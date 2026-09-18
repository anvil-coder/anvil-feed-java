package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The retry rules of FR-4, read off the published ingest contract — {@code docs/anvil-feed-ingest.md} and
 * {@code AnvilFeedIngestController} — rather than off the specification:
 *
 * <pre>
 *  202  every event the answer NAMES carries a final verdict, accepted or rejected alike. Clear those.
 *       An event the answer names in neither list gets one more attempt and is then dropped BY NAME.
 *  400  the sender is wrong. Drop the batch, say so ONCE per process, keep sending. Never retried.
 *  401  the credential is gone. Stop for the process lifetime, say so once, and drain the queue: a
 *       stopped sender that keeps queueing is a memory leak with a nice name.
 *  403  an Origin header reached the ingest with a server token. This SDK sets none, so something in the
 *       path did. Drop, say so once, keep going — a retry cannot change it.
 *  413  halve the batch and try the halves; a single event over the limit is dropped, with its id and the
 *       server's reason_code in the log.
 *  429  pause for exactly Retry-After (60 s if it is missing). Keep the events. NEVER drop for it.
 *  503  repeat the WHOLE batch after Retry-After (30 s if missing). The delivery key is
 *       (organisation, event_id), so the repeat is counted once.
 *  3xx  never followed. Drop, say so once, and name the endpoint misconfiguration and the Location.
 *  5xx / timeout / DNS / a throwing transport  exponential backoff with jitter, capped; events stay.
 * </pre>
 *
 * <p>Nothing here throws. Every public method is total (FR-2 b): a transport that breaks its own
 * contract, a payload that will not encode, a logger that explodes — each is absorbed, and the worst that
 * happens to the host application is a line in its log.
 *
 * <p>A sender is <b>stateful</b> — the pause, the backoff counter, the stop flag and every "said once"
 * line belong to it — and therefore <b>one per process</b>, not one per request. The counterpart of
 * {@code AnvilFeed\Sender} (C9); what Java adds is the daemon thread PHP has no process to run one in
 * ({@link #start()} / {@link #close()}).
 */
public final class Sender implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_CAP_MS = 30_000L;

    /** Under the ingest's 262144, so a 413 is a bug in this class rather than a routine answer. */
    private static final int MAX_BODY_BYTES = 250_000;

    /** {@code docs/anvil-feed-ingest.md} sends {@code Retry-After: 60} with every 429. */
    private static final int DEFAULT_RATE_LIMIT_PAUSE_SECONDS = 60;
    /** …and {@code Retry-After: 30} with every 503. */
    private static final int DEFAULT_TRANSIENT_PAUSE_SECONDS = 30;

    private final AnvilFeedConfig config;
    private final EventQueue queue;
    private final Transport transport;
    private final FeedLogger logger;
    private final LongSupplier clockMillis;

    /**
     * "Once per process" (ruling P3) lives HERE and not in {@link FeedLogger}: {@code FeedLogger.once()}
     * is per instance, so a host that news up a second logger — a Spring provider binding one per bean is
     * the obvious way — would get every "said once" line twice, and the symptom is a repeated paragraph
     * in a log nobody files a ticket about. A sender is one per process by design, so this is the process
     * flag. Guarded by this object's monitor: every write happens inside {@link #flush(long)}.
     */
    private final Set<String> said = new LinkedHashSet<>();

    /**
     * Events the ingest answered 202 for without naming them in {@code accepted} or {@code rejected}.
     * They get one more attempt and are then dropped — see {@link #applyVerdicts}. Bounded, so a server
     * that answers this way for ever cannot grow it without end.
     */
    private final Set<String> awaitingSecondTry = new LinkedHashSet<>();

    private volatile long pausedUntilMillis;
    private volatile boolean stopped;
    private int consecutiveFailures;

    /** The bound of the flush currently running; {@link Long#MAX_VALUE} outside one. */
    private long deadlineMillis = Long.MAX_VALUE;

    // ---- the daemon thread, guarded by `lifecycle`
    private final Object lifecycle = new Object();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private Thread worker;
    private Thread shutdownHook;
    /** Volatile: {@link #pump()} reads it outside the {@code lifecycle} monitor on every turn. */
    private volatile boolean closed;

    public Sender(AnvilFeedConfig config, EventQueue queue, Transport transport, FeedLogger logger) {
        this(config, queue, transport, logger, System::currentTimeMillis);
    }

    /**
     * @param clockMillis inject it in tests and in a host that has its own clock. Every pause, every
     *                    backoff and every deadline is measured on it, so no test of this class ever has
     *                    to sleep to observe one.
     */
    public Sender(AnvilFeedConfig config, EventQueue queue, Transport transport, FeedLogger logger,
                  LongSupplier clockMillis) {
        this.config = config;
        this.queue = queue;
        this.transport = transport;
        this.logger = logger == null ? FeedLogger.toSystemLogger() : logger;
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
    }

    /** True after a 401, for the lifetime of the process. */
    public boolean isStopped() {
        return stopped;
    }

    /** The absolute instant, on the injected clock, before which no request may leave. */
    public long pausedUntilMillis() {
        return pausedUntilMillis;
    }

    // ------------------------------------------------------------------ flushing

    /** Bounded by {@code request_timeout_ms} from now — 2 s at the defaults. */
    public void flush() {
        flush(clockMillis.getAsLong() + config.requestTimeoutMs());
    }

    /** The same bound, expressed as a budget rather than as an absolute instant. */
    public void flushWithin(long budgetMillis) {
        flush(clockMillis.getAsLong() + Math.max(0L, budgetMillis));
    }

    /**
     * Drains the queue up to an ABSOLUTE deadline on the injected clock. FR-2 (c): the whole call is
     * bounded, and the bound reaches the socket — {@link Transport#post} is handed what is left of it —
     * so a shutdown hook can never hold a JVM open and a request cannot run past what the caller
     * promised. Whatever the deadline cuts short stays in the queue for the next flush.
     */
    public synchronized void flush(long deadlineMillis) {
        if (!config.enabled()) {
            return;
        }
        this.deadlineMillis = deadlineMillis;
        try {
            if (stopped) {
                queue.clear();
                return;
            }
            if (clockMillis.getAsLong() < pausedUntilMillis) {
                return;
            }

            while (queue.size() > 0 && clockMillis.getAsLong() < deadlineMillis && !stopped) {
                List<FeedEvent> batch = queue.take(config.batchSize());
                if (batch.isEmpty() || !send(batch)) {
                    return;      // paused, stopped or out of budget; what had to be kept is back in the queue
                }
            }
        } catch (RuntimeException | Error neverTheHostsProblem) {
            // The last line of FR-2 (b). Everything below is already defensive; this is what makes the
            // promise true even for the case nobody thought of.
            sayOnce("flush-defect", "anvil-feed: internal error while sending — "
                    + IngestResponse.redact(String.valueOf(neverTheHostsProblem.getMessage()))
                    + ". Events are dropped rather than raised.");
        } finally {
            this.deadlineMillis = Long.MAX_VALUE;
        }
    }

    /** @return true when the sender may continue with the next batch */
    private boolean send(List<FeedEvent> batch) {
        if (clockMillis.getAsLong() >= deadlineMillis) {
            queue.unshiftAll(batch);
            return false;
        }

        List<FeedEvent> encodable = new ArrayList<>(batch.size());
        String body = encode(batch, encodable);
        if (encodable.isEmpty()) {
            return true;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return splitAndSend(encodable);
        }

        IngestResponse response;
        try {
            response = transport.post(
                    encodable.size() == 1 ? config.eventsUrl() : config.batchUrl(),
                    body,
                    remainingBudgetMs());
        } catch (RuntimeException | Error contractBreach) {
            // A Transport is contractually not allowed to throw. If one does it is treated as a transport
            // failure rather than being allowed to reach the host application (FR-2 b).
            response = IngestResponse.failure(contractBreach.getMessage());
        }
        if (response == null) {
            response = IngestResponse.failure("the transport returned no answer");
        }
        return apply(encodable, response);
    }

    /**
     * What is left of the flush deadline, in milliseconds. FR-2 (c)'s bound is a bound on the CALL, so it
     * has to reach the socket: a deadline that only decides whether the NEXT request may start still lets
     * a flush run to deadline + request_timeout_ms.
     */
    private int remainingBudgetMs() {
        if (deadlineMillis == Long.MAX_VALUE) {
            return config.requestTimeoutMs();
        }
        long left = deadlineMillis - clockMillis.getAsLong();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, left));
    }

    /**
     * Serialises the batch, dropping the events that cannot be encoded. {@code FeedEvent.canonicalJson()}
     * throws on a payload holding something Jackson has no serialiser for — a host's own object placed
     * there by a context map or a beforeSend hook — and one such event must not cost the forty-nine
     * beside it, nor sit at the head of a bounded queue for ever.
     *
     * @param encodable filled with the events that survived, in order
     */
    private String encode(List<FeedEvent> batch, List<FeedEvent> encodable) {
        List<String> parts = new ArrayList<>(batch.size());
        for (FeedEvent event : batch) {
            try {
                parts.add(event.canonicalJson());
                encodable.add(event);
            } catch (RuntimeException unencodable) {
                logger.warning("anvil-feed: dropping event " + event.id()
                        + " — it cannot be encoded as JSON (" + unencodable.getMessage() + ").");
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return parts.size() == 1 ? parts.get(0) : "[" + String.join(",", parts) + "]";
    }

    /**
     * Halves the batch. The second half goes back to the FRONT of the queue <b>before</b> the first is
     * attempted, so a pause on the first half cannot leave the second one on the floor — and so the queue
     * order is the original one either way. (The plain {@code send(first) && send(second)} loses the
     * second half silently in exactly the situation this SDK exists for.)
     */
    private boolean splitAndSend(List<FeedEvent> batch) {
        if (batch.size() == 1) {
            logger.warning("anvil-feed: dropping event " + batch.get(0).id()
                    + " — on its own it is larger than the ingest accepts (" + MAX_BODY_BYTES
                    + " bytes); there is nothing left to split.");
            return true;
        }
        int half = batch.size() / 2;
        queue.unshiftAll(List.copyOf(batch.subList(half, batch.size())));
        return send(List.copyOf(batch.subList(0, half)));
    }

    // ------------------------------------------------------------------ the decision table

    private boolean apply(List<FeedEvent> batch, IngestResponse response) {
        long now = clockMillis.getAsLong();

        if (response.transportFailure()) {
            return backOff(batch, now, "transport failure: "
                    + IngestResponse.redact(response.failureReason()));
        }

        int status = response.status();
        if (status == 202) {
            return applyVerdicts(batch, response);
        }
        if (status == 400) {
            return drop(batch, 400, "the ingest answered 400 (" + firstReasonCode(response)
                    + "): it refused the whole request — " + firstMessage(response)
                    + ". This is a defect in the SDK or a batch above 50; retrying cannot change it.");
        }
        if (status == 401) {
            return stop("the ingest answered 401 — the token is unknown, revoked or wrong. "
                    + "Sending is switched off for this process.");
        }
        if (status == 403) {
            return drop(batch, 403, "the ingest answered 403. A server token presented with an Origin "
                    + "header is treated as leaked; this SDK sets none, so something in the request path "
                    + "did. Rotate the token.");
        }
        if (status == 413) {
            return tooLarge(batch, response);
        }
        if (status == 429) {
            return pause(batch, now, response.retryAfterSeconds() == null
                    ? DEFAULT_RATE_LIMIT_PAUSE_SECONDS : response.retryAfterSeconds(), "rate limited");
        }
        if (status == 503) {
            return pause(batch, now, response.retryAfterSeconds() == null
                            ? DEFAULT_TRANSIENT_PAUSE_SECONDS : response.retryAfterSeconds(),
                    "transient store failure; the whole batch is repeated");
        }
        if (status >= 500) {
            return backOff(batch, now, "the ingest answered " + status);
        }
        if (status >= 300 && status < 400) {
            return redirected(batch, response);
        }
        return drop(batch, status, "the ingest answered an unexpected " + status);
    }

    /**
     * A 202 is final for every event the answer NAMES — accepted and rejected alike
     * ({@code responses/index.json}: "a verdict, even a refusing one, still clears that event from the
     * buffer").
     *
     * <p>An event the answer does not name is a different case. The ingest promises exactly one verdict
     * per event, so this cannot happen against a healthy server — but a truncated body, a proxy that
     * rewrote the response, or an {@code accepted} list re-spelled in lower case all produce it, and
     * clearing the whole batch on the strength of the status code alone loses those events with no line
     * anywhere. They get ONE more attempt — {@code (organisation, event_id)} is the delivery key, so a
     * repeat of something that did land is counted once — and are then dropped, by name, because the
     * alternative is a queue that never empties.
     */
    private boolean applyVerdicts(List<FeedEvent> batch, IngestResponse response) {
        consecutiveFailures = 0;
        Set<String> verdicts = readVerdicts(response);

        List<FeedEvent> requeue = new ArrayList<>();
        for (FeedEvent event : batch) {
            if (verdicts.contains(event.id())) {
                awaitingSecondTry.remove(event.id());
                continue;
            }
            if (awaitingSecondTry.remove(event.id())) {
                logger.warning("anvil-feed: dropping event " + event.id() + " — the ingest answered 202 "
                        + "twice without naming it in accepted or rejected. Something between this "
                        + "process and the ingest is rewriting the answer.");
                continue;
            }
            rememberAsUnverdicted(event.id());
            requeue.add(event);
        }

        if (!requeue.isEmpty()) {
            sayOnce("unverdicted", "anvil-feed: the ingest answered 202 without a verdict for "
                    + requeue.size() + " event(s) of the batch; they are sent once more and then dropped. "
                    + "Every event is supposed to be named in accepted or in rejected.");
            queue.unshiftAll(requeue);
        }
        return true;
    }

    /** Bounded: a server that never names anything may not grow this without end. */
    private void rememberAsUnverdicted(String id) {
        if (awaitingSecondTry.size() >= config.maxQueueSize()) {
            java.util.Iterator<String> oldest = awaitingSecondTry.iterator();
            oldest.next();
            oldest.remove();
        }
        awaitingSecondTry.add(id);
    }

    /**
     * The ids the answer names, spelled exactly as the ingest returned them — which is exactly as the
     * sender posted them ({@code AnvilFeedIngestController} keeps the sender's spelling on purpose).
     * Logs each rejection on the way past.
     */
    private Set<String> readVerdicts(IngestResponse response) {
        Set<String> verdicts = new LinkedHashSet<>();
        JsonNode answer;
        try {
            answer = JSON.readTree(response.body());
        } catch (Exception unreadable) {
            return verdicts;
        }
        if (answer == null || !answer.isObject()) {
            return verdicts;
        }

        for (JsonNode accepted : answer.path("accepted")) {
            if (accepted.isTextual()) {
                verdicts.add(accepted.asText());
            }
        }
        for (JsonNode rejection : answer.path("rejected")) {
            if (!rejection.isObject()) {
                continue;
            }
            if (rejection.path("event_id").isTextual()) {
                verdicts.add(rejection.path("event_id").asText());
            }
            String code = rejection.path("reason_code").asText("unknown");
            String message = "anvil-feed: event " + rejection.path("event_id").asText("unknown")
                    + " rejected (" + code + "): " + rejection.path("message").asText("");
            // Two of the seven reason codes are the operator's problem, not the developer's, and they
            // otherwise repeat on every single event until somebody notices.
            if ("no_binding".equals(code) || "binding_disabled".equals(code)) {
                sayOnce("reason:" + code, message + " — connect the environment in the Anvil cockpit.");
            } else {
                logger.warning(message);
            }
        }
        return verdicts;
    }

    /**
     * The ingest refused the body for its size. Said once — the halves that follow would say it again
     * otherwise — and then split: the reason code is what tells an operator this is a size problem and
     * not the schema violation a 400 would be.
     */
    private boolean tooLarge(List<FeedEvent> batch, IngestResponse response) {
        consecutiveFailures = 0;
        sayOnce("status:413", "anvil-feed: the ingest answered 413 (" + firstReasonCode(response) + "): "
                + firstMessage(response) + " Halving the batch and trying the halves.");
        return splitAndSend(batch);
    }

    private boolean pause(List<FeedEvent> batch, long now, int seconds, String why) {
        int waited = Math.max(0, seconds);
        // A 429 or a 503 is a healthy answer from a reachable ingest: the connection works and the server
        // is talking to us. Carrying a transport-failure streak across it would resume at the escalated
        // delay after a pause that proved the opposite.
        consecutiveFailures = 0;
        queue.unshiftAll(batch);
        pausedUntilMillis = now + (waited * 1000L);
        logger.warning("anvil-feed: paused for " + waited + "s (" + why + ").");
        return false;
    }

    private boolean backOff(List<FeedEvent> batch, long now, String why) {
        queue.unshiftAll(batch);
        consecutiveFailures++;
        long delay = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(20, consecutiveFailures - 1));
        // Jitter is not optional (X13): without it every pod of a deployment retries in the same second.
        long jittered = (long) (delay * (0.8 + ThreadLocalRandom.current().nextDouble(0.4)));
        pausedUntilMillis = now + jittered;
        logger.warning("anvil-feed: " + why + "; retrying in " + jittered + "ms (attempt "
                + consecutiveFailures + ").");
        return false;
    }

    /**
     * A 4xx the ingest will answer the same way for ever. The events go; the explanation is said ONCE per
     * process and per status, because the alternative is one identical paragraph per batch in a log
     * somebody is reading to find something else.
     */
    private boolean drop(List<FeedEvent> batch, int status, String why) {
        consecutiveFailures = 0;
        sayOnce("status:" + status, "anvil-feed: dropped " + batch.size() + " event(s) — " + why
                + " Further events refused this way are dropped silently.");
        return true;
    }

    /**
     * A redirect is never followed. Two reasons, either of which is enough: following it presents the
     * {@code anv_srv_} token to a host the customer never configured, and the redirect degrades POST to
     * GET, so the events would not even be in the second request — while the target's 2xx would be read
     * as a delivery.
     */
    private boolean redirected(List<FeedEvent> batch, IngestResponse response) {
        consecutiveFailures = 0;
        sayOnce("redirect", "anvil-feed: " + config.endpoint() + " answered " + response.status()
                + (response.location() == null ? "" : " (Location: " + response.location() + ")")
                + " and this SDK never follows a redirect — the ingest token is presented to the "
                + "configured endpoint and to nothing else. Configure \"endpoint\" as the address the "
                + "ingest actually answers on. " + batch.size() + " event(s) dropped; further ones are "
                + "dropped silently.");
        return true;
    }

    private boolean stop(String why) {
        stopped = true;
        queue.clear();
        sayOnce("stopped", "anvil-feed: " + why);
        return false;
    }

    /** Ruling P3's "once per process". See {@link #said}. */
    private void sayOnce(String key, String message) {
        if (said.add(key)) {
            logger.warning(message);
        }
    }

    private static String firstMessage(IngestResponse response) {
        return firstRejection(response, "message", "");
    }

    /**
     * The reason code of a refusal is the one field a reader can act on: {@code schema_violation} is a
     * defect here, {@code payload_too_large} is a batch size, and nothing else in either body tells them
     * apart.
     */
    private static String firstReasonCode(IngestResponse response) {
        return firstRejection(response, "reason_code", "no reason code");
    }

    private static String firstRejection(IngestResponse response, String field, String fallback) {
        try {
            JsonNode value = JSON.readTree(response.body()).path("rejected").path(0).path(field);
            return value.isTextual() && !value.asText().isEmpty() ? value.asText() : fallback;
        } catch (Exception unreadable) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ the daemon thread

    /**
     * Starts the background sender: FR-3 (a), sending is off the host's own threads. Idempotent, and a
     * no-op for a disabled configuration — a host that switched the SDK off pays for no thread at all.
     *
     * <p>The thread is a daemon and can therefore never hold a JVM open; a shutdown hook takes the last
     * pass with {@code request_timeout_ms} as its budget so that what is still queued when the process
     * goes down gets its one chance.
     */
    public void start() {
        synchronized (lifecycle) {
            if (closed || worker != null || !config.enabled()) {
                return;
            }
            Thread pump = new Thread(this::pump, "anvil-feed-sender");
            pump.setDaemon(true);
            worker = pump;
            try {
                Thread hook = new Thread(this::flushOnShutdown, "anvil-feed-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                shutdownHook = hook;
            } catch (RuntimeException | Error alreadyShuttingDown) {
                shutdownHook = null;
            }
            pump.start();
        }
    }

    /** True while the daemon sender thread is alive. */
    public boolean isRunning() {
        synchronized (lifecycle) {
            return worker != null && worker.isAlive();
        }
    }

    /**
     * Stops the daemon thread and takes one last bounded pass over the queue. Idempotent and total; a
     * sender that was never started is closed just as happily.
     *
     * <p>It does not close the {@link Transport}: the sender did not create it and a host that injected
     * its own client keeps owning it.
     */
    @Override
    public void close() {
        Thread pump;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            pump = worker;
            worker = null;
            removeShutdownHook();
        }
        stopSignal.countDown();
        if (pump != null) {
            pump.interrupt();
            try {
                pump.join(Math.max(1, config.requestTimeoutMs()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        flushWithin(config.requestTimeoutMs());
    }

    /**
     * Flush, wait, repeat — and survive everything. A sender thread that dies on the first exception is
     * an SDK that stops reporting and never says so, which is worse than one that never reported at all.
     */
    private void pump() {
        long interval = Math.max(1, config.flushIntervalMs());
        while (!closed) {
            try {
                flushWithin(config.requestTimeoutMs());
            } catch (RuntimeException | Error neverTheHostsProblem) {
                // flush() is already total; this is the belt to its braces.
                logger.once("pump-defect", "anvil-feed: the sender thread absorbed "
                        + neverTheHostsProblem.getClass().getSimpleName() + " and keeps running.");
            }
            try {
                if (stopSignal.await(interval, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException closing) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flushOnShutdown() {
        try {
            flushWithin(config.requestTimeoutMs());
        } catch (RuntimeException | Error neverTheHostsProblem) {
            // A JVM going down is not the moment to raise anything.
        }
    }

    /** Guarded by {@code lifecycle}. */
    private void removeShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shutdownAlreadyRunning) {
            // The hook is running or about to; it is total either way.
        }
    }
}
