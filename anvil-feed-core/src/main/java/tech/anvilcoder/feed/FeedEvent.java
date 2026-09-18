package tech.anvilcoder.feed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One event, ready to send: already scrubbed, already bounded and already in canonical key order. Nothing
 * between here and the socket may change it — the order is part of Z1, and it is the cheapest way to
 * notice that two SDKs have drifted apart.
 *
 * <p>Immutable and safe to share across threads: the payload is wrapped in an unmodifiable view of a copy
 * (the copy keeps the canonical order, which {@code Map.copyOf} would destroy), so the sender thread that
 * serialises an event and the request thread that produced it cannot see each other's edits.
 */
public record FeedEvent(String id, Map<String, Object> payload) {

    /** Chapter 03 section 3, top to bottom. Both SDKs emit exactly this order. */
    public static final List<String> KEY_ORDER = List.of(
            "schema_version", "event_id", "timestamp", "title", "level", "environment", "service",
            "language", "sdk", "exception", "release", "request", "user", "breadcrumbs", "tags",
            "enrichment", "agent_context");

    public FeedEvent {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * The bytes that go on the socket, or {@code null} when this payload cannot be written.
     *
     * <p>It does not throw. FR-2 b names serialisation explicitly, and the one payload that can fail here
     * is the one a {@code beforeSend} hook built — the very reason {@link EventFactory#finalise} is
     * public. A caller drops the event with one log line ({@code Sender.encode} does exactly that); losing
     * one event is the documented outcome, taking the host's thread with it is not.
     */
    public String canonicalJson() {
        return Json.write(payload);
    }

    public static Map<String, Object> inCanonicalOrder(Map<String, Object> payload) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : KEY_ORDER) {
            if (payload.containsKey(key)) {
                ordered.put(key, payload.get(key));
            }
        }
        // Anything a future minor adds keeps its place at the end.
        payload.forEach(ordered::putIfAbsent);
        return ordered;
    }
}
