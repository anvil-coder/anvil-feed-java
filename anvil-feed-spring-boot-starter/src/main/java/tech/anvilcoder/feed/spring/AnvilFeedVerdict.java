package tech.anvilcoder.feed.spring;

import tech.anvilcoder.feed.IngestResponse;
import tech.anvilcoder.feed.Transport;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transport's last answer, and how many bodies this process has handed to it.
 *
 * <p>It exists for one reason: {@link AnvilFeedTestEvent} has to tell a developer what the ingest SAID —
 * accepted, or rejected with a reason code — and the {@code Sender} turns that answer into log lines and
 * an empty queue. "The queue is empty" is exactly the proxy that would report a {@code no_binding}
 * rejection as success, which is the one failure the fifteen-minute guide (FR-8) exists to catch. The
 * Laravel package has the same class under the name {@code VerdictTransport}.
 *
 * <p>It is a recorder rather than a {@link Transport} implementation of its own, so that the starter
 * exposes exactly ONE bean of type {@code Transport} — none — and a host or a test that defines its own
 * {@code Transport} bean is found unambiguously. {@link #recording(Transport)} returns the wrapper the
 * sender is actually given.
 *
 * <p>It also holds the transport this starter CREATED, if it created one, so that the context's shutdown
 * can close it. A transport that was handed in by the host is never closed here: it is not ours.
 */
public final class AnvilFeedVerdict {

    private final AtomicInteger posts = new AtomicInteger();

    private volatile IngestResponse last;

    private volatile AutoCloseable owned;

    /** The transport the sender is given: the real one, plus one assignment. Adds no behaviour. */
    Transport recording(Transport inner) {
        return (url, body, timeoutMs) -> {
            // Counted BEFORE the call, so a transport that throws still shows that something was tried.
            posts.incrementAndGet();
            IngestResponse answer = inner.post(url, body, timeoutMs);
            last = answer;
            return answer;
        };
    }

    /**
     * Counted, not compared: a host that binds its own transport may return a cached or replayed response
     * object, and "the same object as before" would then read a perfectly successful post as a failure to
     * send — in the one command whose whole job is giving the right verdict.
     */
    public int posts() {
        return posts.get();
    }

    /** The last answer, or null when nothing has been posted in this process. */
    public IngestResponse last() {
        return last;
    }

    /** The transport this starter built, or null — either because the host brought one or because the
     * SDK is disabled and nothing was built at all. Read by the tests that prove the disabled path. */
    public AutoCloseable owned() {
        return owned;
    }

    void own(AutoCloseable transport) {
        this.owned = transport;
    }

    /** Idempotent and total: closing a transport may not cost a context its shutdown. */
    void closeOwned() {
        AutoCloseable transport = owned;
        owned = null;
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (Exception | Error ours) {
            // Nothing left to do about it, and nothing that may reach the host's shutdown.
        }
    }
}
