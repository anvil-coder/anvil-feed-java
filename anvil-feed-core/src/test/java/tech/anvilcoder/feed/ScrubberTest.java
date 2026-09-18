package tech.anvilcoder.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
    // ----- Review finding 1: the second pass is part of the contract, not an accident -------------------

    /**
     * {@code scrub(scrub(x)) == scrub(x)} over the whole shared corpus. {@link EventFactory#finalise} is
     * public so a {@code beforeSend} hook's output takes the same pipeline, which means every rule here
     * runs at least twice over a hooked event — and the quoted form, which every JSON, YAML, .env and
     * config line uses, used to gain {@code ]"} on each pass.
     */
    @Test
    void scrubbingTheConformanceCorpusTwiceChangesNothingTheSecondTime() throws Exception {
        Map<String, Object> once = scrubber().scrub(read("pii/raw.json"));

        assertEquals(once, scrubber().scrub(once), "a second pass over an already scrubbed payload changed it");
        assertEquals(read("pii/expected.json"), once);
    }

    /**
     * Each row asserts the exact one-pass output FIRST — so a scrubber that is idempotent because it does
     * nothing cannot pass — and only then that two further passes leave the text byte-identical. The
     * already-filtered rows are the ones that were red: they are what the second pass of a hooked event
     * actually sees.
     */
    @Test
    void onePassScrubsAndEveryFurtherPassLeavesTheTextByteIdentical() {
        List<String[]> rows = List.of(
                new String[] {"an unquoted value", "token=abc123", "token=[Filtered]"},
                new String[] {"a colon separator", "token: abc123", "token: [Filtered]"},
                new String[] {"a double-quoted value",
                        "config: token=\"abc123\" done", "config: token=\"[Filtered]\" done"},
                new String[] {"a single-quoted value",
                        "$api_key = 'sk-live-99';", "$api_key = '[Filtered]';"},
                new String[] {"a value in parentheses", "(token=abc123)", "(token=[Filtered])"},
                new String[] {"two values in one line",
                        "token=abc&secret=xyz", "token=[Filtered]&secret=[Filtered]"},
                new String[] {"a bearer credential",
                        "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9",
                        "Authorization: Bearer [Filtered]"},
                new String[] {"a quoted bearer credential",
                        "authorization: \"Bearer eyJhbGciOiJIUzI1NiJ9\"",
                        "authorization: \"Bearer [Filtered]\""},
                new String[] {"an already filtered value", "token=[Filtered]", "token=[Filtered]"},
                new String[] {"an already filtered double quote",
                        "config: token=\"[Filtered]\" done", "config: token=\"[Filtered]\" done"},
                new String[] {"an already filtered single quote",
                        "$api_key = '[Filtered]';", "$api_key = '[Filtered]';"},
                new String[] {"a JSON member",
                        "{\"password\": \"hunter2\"}", "{\"password\": \"hunter2\"}"});

        Scrubber scrubber = scrubber();
        for (String[] row : rows) {
            String once = scrubber.scrubText(row[1]);
            assertEquals(row[2], once, row[0] + ": the first pass did not produce what this row is named after");
            assertEquals(once, scrubber.scrubText(once),
                    row[0] + ": the second pass changed an already scrubbed text");
            assertEquals(once, scrubber.scrubText(scrubber.scrubText(once)),
                    row[0] + ": the third pass changed an already scrubbed text");
        }
    }

    @Test
    void aScrubbedUrlSurvivesEveryFurtherPassUnchanged() {
        Scrubber scrubber = scrubber();

        String once = scrubber.scrubUrl("https://admin:hunter2@app.example/reset?token=abc&user=7#end");

        assertEquals("https://[Filtered]@app.example/reset?token=[Filtered]&user=7#end", once);
        assertEquals(once, scrubber.scrubUrl(once));
        assertEquals(once, scrubber.scrubUrl(scrubber.scrubUrl(once)));
    }

    /**
     * Review finding 14, recorded as a spec decision in both languages: the key rule needs its separator
     * directly after the word boundary and a JSON member puts a quote there. The server's own
     * {@code IncidentRedaction.JSON_SECRET} is what catches this shape, before anything is stored or shown.
     */
    @Test
    void jsonShapedTextIsDeliberatelyNotScrubbedHere() {
        assertEquals("{\"password\": \"hunter2\"}", scrubber().scrubText("{\"password\": \"hunter2\"}"));
    }

    // ----- Review finding 7: an empty denylist means no key rule, not a pattern that eats everything ----

    @Test
    void anEmptyDenylistSwitchesOffTheKeyRuleAndNothingElse() {
        Scrubber scrubber = new Scrubber(List.of());
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-1");
        user.put("ip", "203.0.113.9");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user", user);
        payload.put("note", "token=abc123 and a time of 10:30");

        Map<String, Object> scrubbed = scrubber.scrub(payload);

        assertEquals("token=abc123 and a time of 10:30", scrubbed.get("note"), "no key rule was configured");
        assertEquals("Bearer [Filtered]", scrubber.scrubText("Bearer eyJhbGciOiJIUzI1NiJ9"));
        assertEquals(Map.of("id", "u-1"), scrubbed.get("user"), "the absolutes are not a knob");
        assertEquals("https://[Filtered]@host.example/x",
                scrubber.scrubUrl("https://admin:hunter2@host.example/x"));
    }
}
