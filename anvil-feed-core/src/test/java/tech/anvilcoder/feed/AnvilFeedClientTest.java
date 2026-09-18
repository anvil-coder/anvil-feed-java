package tech.anvilcoder.feed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import shop.Storefront;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture path end to end: what is queued, what is refused, and what a host's own {@code beforeSend}
 * hook may and may not do to it.
 *
 * <p>Nothing here sends. The transport is a double, the sender's daemon thread is never started, and the
 * one test that flushes does it on the calling thread — which is also the only way to prove that a
 * failure raised INSIDE a flush is not captured.
 */
class AnvilFeedClientTest {

    private static final Transport ACCEPTING =
            (url, body, timeoutMs) -> IngestResponse.of(202, RecordingTransport.acceptEverything(body), null);

    private final RecordingLogger log = new RecordingLogger();

    @AfterEach
    void tearDown() {
        AnvilFeed.reset();
        AnvilFeedClient.leaveRequest();
    }

    private AnvilFeedClient client(AnvilFeedConfig config) {
        return AnvilFeedClient.create(config, ACCEPTING, log.logger(), GitContext.none());
    }

    private AnvilFeedConfig config() {
        return AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .build(log.logger());
    }

    /** Source context quotes THIS file, so a test about a leaked literal has to switch it off first. */
    private AnvilFeedConfig configWithoutContextCode() {
        return AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .contextLines(0)
                .build(log.logger());
    }

    private static Map<String, Object> onlyQueued(AnvilFeedClient client) {
        List<FeedEvent> taken = client.queue().take(1);
        assertEquals(1, taken.size(), "exactly one event was queued");
        return taken.get(0).payload();
    }

    // ------------------------------------------------------------------ queueing

    @Test
    void captureQueuesExactlyOneEvent() {
        AnvilFeedClient client = client(config());

        assertNotNull(client.captureException(new RuntimeException("boom")));
        assertEquals(1, client.queue().size());
    }

    /** The id handed back is the id on the wire — it is what an operator searches the cockpit for. */
    @Test
    void theIdItReturnsIsTheIdItQueued() {
        AnvilFeedClient client = client(config());

        String id = client.captureException(new RuntimeException("boom"));

        assertEquals(id, onlyQueued(client).get("event_id"));
        assertEquals(id, UUID.fromString(id).toString(), "a canonical UUID v4, which is all the ingest takes");
    }

    @Test
    void captureTestEventIsTheVerificationCallAndReturnsItsId() {
        AnvilFeedClient client = client(config());

        String id = Storefront.verify(client);

        Map<String, Object> payload = onlyQueued(client);
        assertEquals(id, payload.get("event_id"));
        assertEquals(EventFactory.TEST_TYPE, exceptionOf(payload).get("type"));
    }

    @Test
    void nothingIsQueuedWithoutAToken() {
        AnvilFeedClient client = client(AnvilFeedConfig.builder().service("shop-api").build(log.logger()));

        assertNull(client.captureException(new RuntimeException("boom")));
        assertEquals(0, client.queue().size());
    }

    /** FR-2 b: a call into the SDK never reaches the caller, not even one the caller got wrong. */
    @Test
    void aNullThrowableIsAnEventRatherThanAnExceptionIntoTheHost() {
        AnvilFeedClient client = client(config());

        assertNotNull(client.captureException(null));
        assertEquals(1, client.queue().size());
    }

    // ------------------------------------------------------------------ the loop guard

    @Test
    void aStormIsSuppressedLocally() {
        AnvilFeedClient client = client(AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api").fingerprintCap(2).build(log.logger()));

        for (int i = 0; i < 20; i++) {
            Storefront.reportFromCheckout(client, "the same fault every time");
        }

        assertTrue(client.queue().size() <= 2, "queued " + client.queue().size());
        assertEquals(1, log.matching("suppressing locally").size(),
                "one line per window per fingerprint — the storm must not become the log flood");
    }

    @Test
    void anExceptionOnAnIgnoredPathIsNotReported() {
        AnvilFeedClient client = client(config());

        assertNull(client.captureException(new RuntimeException("the ingest itself failed"),
                Map.of("request", Map.of("method", "POST", "url", "/api/feed/events"))));
        assertEquals(0, client.queue().size());
    }

    /**
     * The path the HOST declared for this thread. Java has no {@code $_SERVER}, so Task 10's filter opens
     * a scope; nothing in the SDK guesses the value, because a queue worker would otherwise inherit
     * whatever the process started with.
     */
    @Test
    void anExceptionRaisedInsideAnIgnoredRequestScopeIsNotReported() {
        AnvilFeedClient client = client(config());

        try (AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest("/api/feed/events")) {
            assertNotNull(scope);
            assertEquals("/api/feed/events", AnvilFeedClient.currentRequestPath());
            assertNull(client.captureException(new RuntimeException("the ingest itself failed")));
        }

        assertEquals(0, client.queue().size());
        assertNull(AnvilFeedClient.currentRequestPath(), "the scope closed");
        assertNotNull(client.captureException(new RuntimeException("an ordinary fault, outside the scope")));
    }

    @Test
    void anOrdinaryRequestScopeDoesNotSuppressAnything() {
        AnvilFeedClient client = client(config());

        try (AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest("/api/bookings/17")) {
            assertNotNull(scope);
            assertNotNull(client.captureException(new RuntimeException("boom")));
        }
        assertEquals(1, client.queue().size());
    }

    /**
     * A servlet container's ERROR or FORWARD re-dispatch inside a request being served on an ignored path
     * is exactly this shape. Without the restore, the inner {@code close()} cleared the path outright and
     * the rest of that request reported the ingest's own failures to the ingest again.
     */
    @Test
    void scopesNestAndTheInnerOneRestoresTheOuterPath() {
        AnvilFeedClient client = client(config());

        try (AnvilFeedClient.RequestScope outer = AnvilFeedClient.enterRequest("/api/feed/events")) {
            assertNotNull(outer);
            try (AnvilFeedClient.RequestScope inner = AnvilFeedClient.enterRequest("/error")) {
                assertNotNull(inner);
                assertEquals("/error", AnvilFeedClient.currentRequestPath());
                assertNotNull(client.captureException(new RuntimeException("rendering the error page")));
            }
            assertEquals("/api/feed/events", AnvilFeedClient.currentRequestPath(),
                    "the outer path is back, so the rest of the request is still guarded");
            assertNull(client.captureException(new RuntimeException("the ingest itself failed")));
        }
        assertNull(AnvilFeedClient.currentRequestPath());
        assertEquals(1, client.queue().size());
    }

    /**
     * The dangerous direction. With an async servlet, a {@code CompletableFuture} hand-off or a
     * virtual-thread continuation, {@code close()} runs on another thread: clearing there would clear the
     * CLOSING thread's (empty) entry and leave the owner's path in place for ever — and on a pooled
     * request worker whose stale path matched an ignored pattern, that is error reporting silently off
     * for that worker (review finding 2).
     */
    @Test
    void aScopeClosedOnAnotherThreadIsRefusedAndSaidOnce() throws Exception {
        AnvilFeedClient client = client(config());
        AnvilFeed.init(client);

        AnvilFeedClient.RequestScope scope = AnvilFeedClient.enterRequest("/api/bookings/17");
        try {
            Thread other = new Thread(scope::close, "some-other-pool-thread");
            other.start();
            other.join();
            Thread again = new Thread(scope::close, "some-other-pool-thread");
            again.start();
            again.join();

            assertEquals("/api/bookings/17", AnvilFeedClient.currentRequestPath(),
                    "the owner's path is untouched, so the owner can still close it itself");
            assertEquals(1, log.matching("closed on").size(), "said once, not once per hand-off");
        } finally {
            scope.close();
        }
        assertNull(AnvilFeedClient.currentRequestPath(), "and the owner's own close still works");
    }

    /** Closing twice on the owner's thread is not a second restore of a path that has moved on since. */
    @Test
    void closingAScopeTwiceOnItsOwnThreadIsANoOp() {
        AnvilFeedClient.RequestScope first = AnvilFeedClient.enterRequest("/a");
        first.close();
        AnvilFeedClient.enterRequest("/b");
        first.close();

        assertEquals("/b", AnvilFeedClient.currentRequestPath());
    }

    /**
     * The first turn of the loop this whole class exists to stop: the transport fails, the SDK captures
     * its own failure, and the capture is queued to be posted to the endpoint that just failed.
     */
    @Test
    void theSendersOwnFailureIsNeverCaptured() {
        AnvilFeedClient[] holder = new AnvilFeedClient[1];
        Transport reentrant = (url, body, timeoutMs) -> {
            holder[0].captureException(new IllegalStateException("the ingest itself failed"));
            return IngestResponse.failure("connection reset by peer");
        };
        holder[0] = AnvilFeedClient.create(config(), reentrant, log.logger(), GitContext.none());
        holder[0].captureException(new RuntimeException("boom"));

        holder[0].flush();

        assertEquals(1, holder[0].queue().size(),
                "only the original event is left — the sender never queued its own failure");
    }

    /**
     * The same loop, on the thread that actually sends once a host calls {@code start()} — which Task 10's
     * starter does. The flag used to be raised by the CLIENT around its own {@code flush()}, so it was
     * raised on the one thread that rarely sends and never on the daemon pump, where every post leaves.
     * A {@link Transport} — or a host logging appender reached through {@link FeedLogger} on the send
     * path — that captured from its own error branch was captured, queued and posted to the endpoint that
     * had just failed, silently, because the pump absorbs whatever it raises (review finding 1).
     */
    @Test
    void theDaemonPumpsOwnFailureIsNeverCapturedEither() throws Exception {
        CountDownLatch posted = new CountDownLatch(1);
        AtomicInteger postsFromThePump = new AtomicInteger();
        Transport reentrant = (url, body, timeoutMs) -> {
            if (Thread.currentThread().getName().startsWith("anvil-feed-sender")) {
                postsFromThePump.incrementAndGet();
                // Exactly what a host's own transport or appender does in its error branch.
                AnvilFeed.captureException(new IllegalStateException("the ingest itself failed"));
                posted.countDown();
            }
            return IngestResponse.failure("connection reset by peer");
        };
        AnvilFeedClient client = AnvilFeedClient.create(AnvilFeedConfig.builder()
                        .token("anv_srv_0123456789abcdef0123456789abcdef")
                        .service("shop-api").flushIntervalMs(1).build(log.logger()),
                reentrant, log.logger(), GitContext.none());
        AnvilFeed.init(client);
        assertNotNull(client.captureException(new RuntimeException("boom")));

        client.start();
        try {
            assertTrue(posted.await(10, TimeUnit.SECONDS), "the pump never posted");
        } finally {
            client.close();
        }

        assertTrue(postsFromThePump.get() >= 1, "the assertion below has to be about the pump's thread");
        assertEquals(1, client.queue().size(),
                "only the original event is left — the pump never queued its own failure");
    }

    /** FR-2 b, the whole of it: nothing in here ever reaches the caller. */
    @Test
    void aFailingTransportNeverReachesTheCaller() {
        AnvilFeedClient client = AnvilFeedClient.create(config(),
                (url, body, timeoutMs) -> {
                    throw new IllegalStateException("the transport itself exploded");
                },
                log.logger(), GitContext.none());
        client.captureException(new RuntimeException("boom"));

        client.flush();

        assertTrue(true, "reaching this line is the assertion");
    }

    /** A host suite has to be able to drive a sixty-second window without sleeping for a minute. */
    @Test
    void theGatesClockCanBeInjectedThroughCreate() {
        AtomicLong now = new AtomicLong(1_000L);
        AnvilFeedClient client = AnvilFeedClient.create(AnvilFeedConfig.builder()
                        .token("anv_srv_0123456789abcdef0123456789abcdef")
                        .service("shop-api").fingerprintCap(1).build(log.logger()),
                ACCEPTING, log.logger(), GitContext.none(), now::get);

        assertNotNull(Storefront.reportFromCheckout(client, "the same fault every time"));
        assertNull(Storefront.reportFromCheckout(client, "the same fault every time"));

        now.addAndGet(61_000L);
        assertNotNull(Storefront.reportFromCheckout(client, "the same fault every time"),
                "the window moved on, on the clock the host injected");
    }

    // ------------------------------------------------------------------ the caller's own stack

    /**
     * A synthesised exception whose stack starts inside anvil-feed is worse than useless twice over: the
     * operator reads three of OUR frames before their own, and the in-app rule decides the promotion the
     * fingerprint partitions on — so those constant frames would collapse every {@code captureMessage} of
     * an application into a single incident.
     */
    @Test
    void aMessageEventCarriesTheCallersFramesAndNoneOfOurs() {
        AnvilFeedClient client = client(config());

        Storefront.reportFromCheckout(client, "the cart could not be priced");

        List<Map<String, Object>> frames = framesOf(onlyQueued(client));
        assertFalse(frames.isEmpty(), "the caller's stack, not an empty one");
        assertEquals("reportFromCheckout", frames.get(0).get("function"),
                "the stack starts where the caller called");
        for (Map<String, Object> frame : frames) {
            assertFalse(String.valueOf(frame.get("file")).contains("tech/anvilcoder/feed"),
                    "no frame of this SDK is on a synthesised stack: " + frame);
            assertFalse(String.valueOf(frame.get("file")).startsWith("tech.anvilcoder.feed."), "" + frame);
        }
    }

    @Test
    void theTestEventCarriesTheCallersFramesToo() {
        AnvilFeedClient client = client(config());

        Storefront.verify(client);

        List<Map<String, Object>> frames = framesOf(onlyQueued(client));
        assertFalse(frames.isEmpty());
        assertEquals("verify", frames.get(0).get("function"));
    }

    /**
     * Deliberately two different METHODS, and deliberately two tests. The same method called twice would
     * pass even if the caller's stack were thrown away entirely, which is the defect the pair catches: an
     * application whose every {@code captureMessage} shared one fingerprint would have its whole log
     * capped as a single storm.
     */
    @Test
    void aMessageFromOneSiteIsOneIdentityHoweverOftenItIsSaid() {
        AnvilFeedClient client = client(config());

        Storefront.reportFromCheckout(client, "the same words every time");
        String once = Fingerprint.ofEvent(onlyQueued(client));
        Storefront.reportFromCheckout(client, "the same words every time");
        String twice = Fingerprint.ofEvent(onlyQueued(client));

        assertEquals(once, twice, "one site repeating itself is one storm, which is what the cap caps");
    }

    @Test
    void aMessageFromAnotherSiteIsAnotherIdentity() {
        AnvilFeedClient client = client(config());

        Storefront.reportFromCheckout(client, "the same words every time");
        String fromCheckout = Fingerprint.ofEvent(onlyQueued(client));

        Storefront.reportFromShipping(client, "the same words every time");
        String fromShipping = Fingerprint.ofEvent(onlyQueued(client));

        assertNotEquals(fromCheckout, fromShipping,
                "two call sites with identical words are two faults, not one storm");
    }

    // ------------------------------------------------------------------ beforeSend

    @Test
    void beforeSendCanDropAnEvent() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> null);

        assertNull(client.captureException(new RuntimeException("boom")));
        assertEquals(0, client.queue().size());
    }

    @Test
    void beforeSendCanRewriteAnEvent() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            Map<String, Object> rewritten = new LinkedHashMap<>(payload);
            rewritten.put("tags", Map.of("reviewed", "yes"));
            return rewritten;
        });
        client.captureException(new RuntimeException("boom"));

        assertEquals(Map.of("reviewed", "yes"), onlyQueued(client).get("tags"));
    }

    /**
     * The hook edits a copy it may actually edit. {@code FeedEvent.payload()} is an unmodifiable view, and
     * a hook that reached for {@code put()} on it would take an {@code UnsupportedOperationException} —
     * host code punished for using the documented API.
     */
    @Test
    void theHookReceivesAMutableCopyAllTheWayDown() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            payload.put("tags", new LinkedHashMap<>(Map.of("reviewed", "yes")));
            exceptionOf(payload).put("message", "rewritten by the host");
            return payload;
        });

        assertNotNull(client.captureException(new RuntimeException("boom")));
        Map<String, Object> queued = onlyQueued(client);
        assertEquals("yes", asMap(queued.get("tags")).get("reviewed"));
        assertEquals("rewritten by the host", exceptionOf(queued).get("message"),
                "a member the SDK does not own is the hook's to change");
    }

    /**
     * {@code event_id} keys the delivery — {@code Sender} matches the ingest's verdicts on it, and a body
     * naming a different one is retried once and then dropped with a line blaming the network for what
     * host code did. {@code service} and {@code environment} decide which project's incident stream the
     * event lands in. {@code title} and {@code exception.type} are the fingerprint inputs.
     */
    @Test
    void aHookCannotRewriteTheFieldsThisSdkOwns() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            payload.put("event_id", "00000000-0000-4000-8000-000000000000");
            payload.put("service", "somebody-elses-service");
            payload.put("environment", "staging");
            payload.put("title", "a title of the host's choosing");
            exceptionOf(payload).put("type", "com.example.NotWhatHappened");
            payload.put("tags", new LinkedHashMap<>(Map.of("reviewed", "yes")));
            return payload;
        });

        String id = client.captureException(new RuntimeException("boom"));
        Map<String, Object> queued = onlyQueued(client);

        assertEquals(id, queued.get("event_id"), "the body names the id the caller was given");
        assertEquals("shop-api", queued.get("service"));
        assertEquals("production", queued.get("environment"));
        assertEquals("java.lang.RuntimeException: boom", queued.get("title"));
        assertEquals("java.lang.RuntimeException", exceptionOf(queued).get("type"));
        assertEquals("yes", asMap(queued.get("tags")).get("reviewed"), "an ordinary addition still works");
        assertEquals(1, log.matching("which this SDK owns").size(), "said once, naming what was refused");
    }

    /**
     * The local cap was charged against the identity computed BEFORE the hook ran. A hook that changed a
     * fingerprint input would therefore cap one group and send another.
     */
    @Test
    void theQueuedEventStillCarriesTheIdentityTheCapWasChargedAgainst() {
        AnvilFeedClient client = client(config());
        client.captureException(new RuntimeException("boom"));
        String untouched = Fingerprint.ofEvent(onlyQueued(client));

        client.beforeSend(payload -> {
            payload.put("title", "a title of the host's choosing");
            exceptionOf(payload).put("type", "com.example.NotWhatHappened");
            exceptionOf(payload).put("stacktrace", List.of());
            return payload;
        });
        client.captureException(new RuntimeException("boom"));

        assertEquals(untouched, Fingerprint.ofEvent(onlyQueued(client)));
    }

    /**
     * FR-5 is an SDK-side promise, and the server is a backstop rather than the plan. The most obvious use
     * of this hook is enrichment, which would otherwise reach the wire having never met the denylist.
     */
    @Test
    void whatABeforeSendHookAddsIsScrubbedLikeEverythingElse() {
        AnvilFeedClient client = client(configWithoutContextCode());
        client.beforeSend(payload -> {
            Map<String, Object> tags = new LinkedHashMap<>();
            tags.put("password", "hunter2");
            tags.put("order", "4711");
            payload.put("tags", tags);
            payload.put("breadcrumbs", new ArrayList<>(List.of("GET /pay?api_key=sk_live_secret")));
            return payload;
        });

        client.captureException(new RuntimeException("boom"));
        FeedEvent queued = client.queue().take(1).get(0);

        assertEquals(Scrubber.FILTERED, asMap(queued.payload().get("tags")).get("password"));
        assertEquals("4711", asMap(queued.payload().get("tags")).get("order"), "an ordinary tag survives");
        String json = queued.canonicalJson();
        assertNotNull(json);
        assertFalse(json.contains("hunter2"), json);
        assertFalse(json.contains("sk_live_secret"), json);
    }

    /**
     * The count caps, not only the character caps: {@code tags} is {@code maxProperties: 29} in schema 1.0,
     * so a hook that hands over thirty of them would cost the whole event a {@code schema_violation}.
     */
    @Test
    void aHooksOverLongAdditionsAreBoundedLikeEverythingElse() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            Map<String, Object> tags = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                tags.put("tag-" + i, "value");
            }
            payload.put("tags", tags);
            return payload;
        });

        client.captureException(new RuntimeException("boom"));

        assertEquals(29, asMap(onlyQueued(client).get("tags")).size());
    }

    /** The wire's level enum. The ingest stores anything, so nothing would complain about "info". */
    @Test
    void aHookThatSetsAnUnknownLevelIsCoercedToError() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            payload.put("level", "info");
            return payload;
        });

        client.captureException(new RuntimeException("boom"));

        assertEquals("error", onlyQueued(client).get("level"));
    }

    /** FR-2: host code cannot take the host down through us, and it cannot cost the event either. */
    @Test
    void aThrowingBeforeSendCostsTheModificationAndNotTheEvent() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            throw new IllegalStateException("the hook itself exploded");
        });

        assertNotNull(client.captureException(new RuntimeException("boom")));
        assertEquals("java.lang.RuntimeException: boom", onlyQueued(client).get("title"));

        client.captureException(new RuntimeException("and again"));
        assertEquals(1, log.matching("beforeSend threw").size(),
                "said once — a hook that throws throws on every event of the storm that follows");
    }

    /**
     * Stricter than sending on purpose: the ingest would answer 400, which the retry rules never retry, so
     * the event is lost either way — this way the loss is named in the host's own log.
     */
    @Test
    void aHookThatBreaksTheContractDropsTheEventHereRatherThanAtTheIngest() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> {
            payload.remove("language");
            return payload;
        });

        assertNull(client.captureException(new RuntimeException("boom")));
        assertEquals(0, client.queue().size());
        assertEquals(1, log.matching("the ingest would refuse it").size());
    }

    @Test
    void anEmptyPayloadOutOfTheHookIsADropRatherThanAnEmptyEvent() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> new LinkedHashMap<>());

        assertNull(client.captureException(new RuntimeException("boom")));
        assertEquals(0, client.queue().size());
    }

    @Test
    void theHookCanBeRemovedAgain() {
        AnvilFeedClient client = client(config());
        client.beforeSend(payload -> null);
        assertNull(client.captureException(new RuntimeException("boom")));

        client.beforeSend(null);
        assertNotNull(client.captureException(new RuntimeException("boom")));
    }

    // ------------------------------------------------------------------ the static facade

    @Test
    void theFacadeIsSilentUntilInitHasRun() {
        assertNull(AnvilFeed.current());
        assertNull(AnvilFeed.captureException(new RuntimeException("boom")));
        assertNull(AnvilFeed.captureMessage("nobody configured the SDK"));
        assertNull(AnvilFeed.captureTestEvent());
        AnvilFeed.flush();
        AnvilFeed.flush(10L);
    }

    /**
     * Two clients in one process would be two rate gates, two backoff counters and two sets of "said once"
     * state — and the second one would silently undo the first one's storm suppression.
     */
    @Test
    void initIsIdempotent() {
        AnvilFeedConfig disabled = AnvilFeedConfig.builder().service("shop-api").build(log.logger());
        long httpThreadsBefore = httpClientThreads();

        AnvilFeedClient first = AnvilFeed.init(disabled);
        AnvilFeedClient second = AnvilFeed.init(disabled);

        assertSame(first, second, "the second init built nothing");
        assertEquals(httpThreadsBefore, httpClientThreads(),
                "a deliberately disabled process pays for no HttpClient and no executor (finding 5)");
        assertSame(first, AnvilFeed.current());
        assertNull(AnvilFeed.captureException(new RuntimeException("boom")), "the disabled path is silent");
        assertFalse(first.sender().isRunning(), "a disabled configuration pays for no thread at all");
        first.close();
    }

    @Test
    void aHostCanInstallTheClientItBuiltItself() {
        AnvilFeedClient client = client(config());
        AnvilFeed.init(client);

        assertSame(client, AnvilFeed.current());
        assertNotNull(AnvilFeed.captureException(new RuntimeException("boom")));
        assertEquals(1, client.queue().size());

        AnvilFeed.reset();
        assertNull(AnvilFeed.captureException(new RuntimeException("boom")));
        assertEquals(1, client.queue().size(), "reset really does stop it");
    }

    // ------------------------------------------------------------------ helpers

    /** The named pool {@code HttpTransport} creates in its constructor; zero of them is the assertion. */
    private static long httpClientThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("anvil-feed-http-"))
                .count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> exceptionOf(Map<String, Object> payload) {
        return asMap(payload.get("exception"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> framesOf(Map<String, Object> payload) {
        Object frames = exceptionOf(payload).get("stacktrace");
        return frames == null ? List.of() : (List<Map<String, Object>>) frames;
    }
}
