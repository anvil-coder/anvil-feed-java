package tech.anvilcoder.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The one mapper this SDK writes JSON with, and the one place a value is turned into wire text.
 *
 * <p><b>Auto-detection is off on purpose (review finding 4 b).</b> A plain {@code new ObjectMapper()}
 * serialises an unknown type <i>reflectively</i>, field by field — so a {@code beforeSend} hook that puts
 * a host's own entity into {@code tags} would put that entity's fields on the wire, fields that never met
 * the denylist because {@link Scrubber} only descends into {@code Map}, {@code List} and {@code String}.
 * With detection disabled such a type has no properties and no serialiser, the write fails instead of
 * leaking, and {@link EventFactory#finalise} has already turned it into text long before that anyway.
 *
 * <p>Immutable and safe to share across threads: a configured {@code ObjectMapper} is thread-safe for
 * writing, which is what this class does and all it does.
 */
final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.AUTO_DETECT_FIELDS,
                    MapperFeature.AUTO_DETECT_GETTERS,
                    MapperFeature.AUTO_DETECT_IS_GETTERS)
            .build();

    private Json() {
    }

    /** @return the JSON text, or {@code null} when this value has no serialiser — never throws. */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException unwritable) {
            return null;
        }
    }

    /**
     * A value as wire TEXT, the way PHP's {@code json_encode} fallback does it: a String stays itself, a
     * scalar becomes its plain text, and anything else becomes its JSON. C9 — the same input must read the
     * same way in both cockpits, and Java's {@code toString} would show {@code {a=1}}, which is not
     * parseable by anything.
     */
    static String text(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        String json = write(value);
        return json != null ? json : String.valueOf(value);
    }

    /** The five shapes schema 1.0 knows. Anything else is a host object and is turned into text first. */
    static boolean isWireValue(Object value) {
        return value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Map<?, ?> || value instanceof List<?>;
    }
}
