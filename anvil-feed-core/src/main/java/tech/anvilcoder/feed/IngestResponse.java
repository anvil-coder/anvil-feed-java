package tech.anvilcoder.feed;

import java.util.regex.Pattern;

/**
 * What came back. A transport failure — DNS, connection refused, a timeout — is deliberately NOT modelled
 * as a status code: it is the case the sender must treat like a 5xx, and a fake {@code 0} or {@code 599}
 * would put it one typo away from being treated like a 4xx and dropping a customer's errors on the floor.
 *
 * <p>The counterpart of {@code AnvilFeed\Transport\Response} (C9), including its sixth component: the
 * {@code Location} of a 3xx, so the sender can name the endpoint an operator really configured.
 *
 * @param status             the HTTP status, or {@code 0} for a transport failure
 * @param body               the response body, never null
 * @param retryAfterSeconds  delta-seconds off the {@code Retry-After} header, or null — never {@code 0}
 *                           for a header that could not be read, which would turn a rate limit into a
 *                           hot loop
 * @param transportFailure   true when no HTTP answer was received at all
 * @param failureReason      why, already redacted
 * @param location           the {@code Location} header of a 3xx, or null
 */
public record IngestResponse(int status, String body, Integer retryAfterSeconds,
                             boolean transportFailure, String failureReason, String location) {

    /** Anything that looks like a credential, in a message that is about to be logged (C15, FR-9 e). */
    private static final Pattern AUTHORIZATION = Pattern.compile("Authorization\\s*:?.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INGEST_TOKEN = Pattern.compile("anv_(srv|pub)_[A-Za-z0-9]+");
    /** A proxy URL's Basic credentials: {@code http://user:secret@proxy:3128} (C15, X11). */
    private static final Pattern URL_USERINFO = Pattern.compile("(//)[^/@\\s]+:[^/@\\s]*@");

    public IngestResponse {
        body = body == null ? "" : body;
        failureReason = failureReason == null ? "" : failureReason;
    }

    public static IngestResponse of(int status, String body, Integer retryAfterSeconds) {
        return new IngestResponse(status, body, retryAfterSeconds, false, "", null);
    }

    public static IngestResponse of(int status, String body, Integer retryAfterSeconds, String location) {
        return new IngestResponse(status, body, retryAfterSeconds, false, "", location);
    }

    public static IngestResponse redirect(int status, String location) {
        return new IngestResponse(status, "", null, false, "", location);
    }

    /**
     * The reason is REDACTED here rather than at the log call, so the tainted string never exists on the
     * object at all. The message comes from the host's own stack: a debugging decorator that dumps the
     * whole request would otherwise put {@code Authorization: Bearer anv_srv_…} into the customer's log,
     * against FR-9 (e). The SDK's promise has to be the SDK's own, not every host's.
     */
    public static IngestResponse failure(String reason) {
        return new IngestResponse(0, "", null, true, redact(reason), null);
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = URL_USERINFO.matcher(text).replaceAll("$1[Filtered]@");
        redacted = INGEST_TOKEN.matcher(redacted).replaceAll("anv_$1_[Filtered]");
        return AUTHORIZATION.matcher(redacted).replaceAll("Authorization: [Filtered]");
    }
}
