package tech.anvilcoder.feed;

/**
 * One POST to the configured ingest and nothing else. It never throws (FR-2 b), it never follows a
 * redirect (the token is presented to the configured endpoint and to nothing else, FR-9), and it never
 * runs longer than the budget it is given.
 *
 * <p>A {@link Sender} treats a throwing transport as a transport failure rather than letting it reach the
 * host application, but an implementation that relies on that is in breach.
 */
@FunctionalInterface
public interface Transport {

    /**
     * @param timeoutMs the TOTAL wall-clock budget for this one call — connect, write and read together.
     *                  Null means the configured {@code request_timeout_ms}. {@link Sender} passes what is
     *                  left of its flush deadline, so FR-2 (c)'s hard 2 s bound holds DURING a request and
     *                  not only between two of them. An implementation clamps it down to
     *                  {@code request_timeout_ms}: a generous caller may not raise the operator's ceiling.
     */
    IngestResponse post(String url, String body, Integer timeoutMs);

    /** The configured {@code request_timeout_ms} as the budget. */
    default IngestResponse post(String url, String body) {
        return post(url, body, null);
    }
}
