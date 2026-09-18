package tech.anvilcoder.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One event, ready to send: already scrubbed, already bounded and already in canonical key order. Nothing
 * between here and the socket may change it — the order is part of Z1, and it is the cheapest way to
 * notice that two SDKs have drifted apart.
 */
public record FeedEvent(String id, Map<String, Object> payload) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Chapter 03 section 3, top to bottom. Both SDKs emit exactly this order. */
    public static final List<String> KEY_ORDER = List.of(
            "schema_version", "event_id", "timestamp", "title", "level", "environment", "service",
            "language", "sdk", "exception", "release", "request", "user", "breadcrumbs", "tags",
            "enrichment", "agent_context");

    public String canonicalJson() {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            // Every value in the map is a String, a number, a boolean, a List or a Map of those.
            throw new IllegalStateException("anvil-feed: an event could not be serialised", impossible);
        }
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
