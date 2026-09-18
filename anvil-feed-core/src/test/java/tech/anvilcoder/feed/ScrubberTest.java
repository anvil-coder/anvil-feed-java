package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** FR-5 d, driven by the same language-independent pair the PHP SDK is driven by. */
class ScrubberTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String name) throws Exception {
        return JSON.readValue(Files.readString(CONFORMANCE.resolve(name)), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Scrubber scrubber() {
        return new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST);
    }

    @Test
    void scrubsTheConformancePair() throws Exception {
        assertEquals(read("pii/expected.json"), scrubber().scrub(read("pii/raw.json")));
    }

    /**
     * The address guard states what it is for and nothing else. It deliberately does NOT pin an empty
     * {@code tags} map: an empty map is a schema violation of its own and dropping it belongs to
     * {@link EventFactory} (PHP task-1 review finding 6).
     */
    @Test
    void neverLeavesAnAddressBehind() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-1");
        user.put("ip", "203.0.113.9");
        user.put("ip_hash", "deadbeef");
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("tenant", "kunde-x");
        tags.put("user.ip_hash", "deadbeef");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", user);
        payload.put("tags", tags);

        Map<String, Object> scrubbed = scrubber().scrub(payload);

        assertEquals(Map.of("id", "u-1"), scrubbed.get("user"));
        assertThat(map(scrubbed.get("tags"))).doesNotContainKey("user.ip_hash")
                .containsEntry("tenant", "kunde-x");
        assertThat(scrubbed.toString()).doesNotContain("203.0.113.9").doesNotContain("deadbeef");
    }

    @Test
    void keepsTheBearerSchemeSoAReaderKnowsWhatWasThere() {
        assertEquals("Authorization: Bearer [Filtered]",
                scrubber().scrubText("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"));
    }

    @Test
    void scrubsOnlyTheMatchingQueryParameter() {
        assertEquals("https://app.example/reset?token=[Filtered]&user=7",
                scrubber().scrubUrl("https://app.example/reset?token=abc123&user=7"));
    }

    /** PHP task-1 review finding 11: the fragment is not part of the query and must survive the walk. */
    @Test
    void theFragmentSurvivesTheLastScrubbedParameter() {
        assertEquals("https://app.example/reset?token=[Filtered]#section-2",
                scrubber().scrubUrl("https://app.example/reset?token=abc123#section-2"));
    }

    /** PHP task-1 review finding 5: a credential with no key at all, which no parameter walk would see. */
    @Test
    void urlCredentialsNeverTravel() {
        assertEquals("https://[Filtered]@host.example/x",
                scrubber().scrubUrl("https://admin:hunter2@host.example/x"));
    }

    /** The legacy separator some stacks still emit; the separator goes back where it was. */
    @Test
    void theLegacySemicolonSeparatorIsScrubbedToo() {
        assertEquals("https://app.example/x?a=1;token=[Filtered]&b=2",
                scrubber().scrubUrl("https://app.example/x?a=1;token=abc&b=2"));
    }

    /** No structural field name of schema 1.0 contains a denylist term. This is the guard for that. */
    @Test
    void doesNotFilterStructuralFields() throws Exception {
        Map<String, Object> example = read("valid/section-3-example.json");

        assertEquals(example, scrubber().scrub(example));
    }
}
