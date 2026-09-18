package tech.anvilcoder.feed;

import com.darkfactory.domain.service.IncidentFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * FR-7: two runs of the same fault are one incident, and the SDK has to be able to say so BEFORE it sends
 * (FR-2 d).
 *
 * <p>Held to the truth from two directions at once. {@code conformance/fingerprint/vectors.json} is the
 * cross-language contract — PHP, Java and the ingest are all measured against the same fifteen pinned
 * inputs. But fifteen inputs are a sample, and the thing this class ports is a class that exists: since
 * the server runs on the JVM, the server's own {@link IncidentFingerprint} is copied into the test source
 * set and the port is compared against it directly, on the vectors and on ten thousand generated inputs.
 * A vector file only ever proves the cases somebody thought of.
 */
class FingerprintTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VECTORS =
            Path.of(System.getProperty("anvilfeed.conformance")).resolve("fingerprint/vectors.json");

    private static JsonNode vectors() throws Exception {
        return JSON.readTree(Files.readString(VECTORS));
    }

    private static List<Map<String, Object>> framesOf(JsonNode vector) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (JsonNode frame : vector.path("frames")) {
            Map<String, Object> asMap = new LinkedHashMap<>();
            asMap.put("file", frame.path("file").asText(null));
            asMap.put("function", frame.path("function").asText(null));
            asMap.put("in_app", frame.path("in_app").asBoolean(false));
            frames.add(asMap);
        }
        return frames;
    }

    /** The same frames as the oracle's own type sees them. */
    private static List<IncidentFingerprint.Frame> oracleFramesOf(JsonNode vector) {
        List<IncidentFingerprint.Frame> frames = new ArrayList<>();
        for (JsonNode frame : vector.path("frames")) {
            frames.add(new IncidentFingerprint.Frame(frame.path("file").asText(null),
                    frame.path("function").asText(null), frame.path("in_app").asBoolean(false)));
        }
        return frames;
    }

    @Test
    void buildsExactlyTheDocumentedPayload() throws Exception {
        for (JsonNode vector : vectors().path("payload_vectors")) {
            assertEquals(vector.path("expected_payload").asText(),
                    Fingerprint.payload(vector.path("service").asText(),
                            vector.path("exception_type").asText(),
                            vector.path("title").asText(), framesOf(vector)),
                    vector.path("name").asText());
        }
    }

    /**
     * The digest is never hard-coded: it is the SHA-256 of the payload the vector file pins. PHP, Java and
     * the ingest are therefore held to the same bytes without anybody inventing a hash by hand.
     */
    @Test
    void theDigestIsTheDigestOfThatPayload() throws Exception {
        for (JsonNode vector : vectors().path("payload_vectors")) {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(vector.path("expected_payload").asText().getBytes(StandardCharsets.UTF_8));

            assertEquals(HexFormat.of().formatHex(digest),
                    Fingerprint.of(vector.path("service").asText(), vector.path("exception_type").asText(),
                            vector.path("title").asText(), framesOf(vector)),
                    vector.path("name").asText());
        }
    }

    @Test
    void normalizesFilePathsTheWayTheIngestDoes() throws Exception {
        for (JsonNode vector : vectors().path("normalize_file_vectors")) {
            assertEquals(vector.path("expected").asText(),
                    Fingerprint.normalizeFile(vector.path("input").asText()),
                    vector.path("input").asText());
        }
    }

    @Test
    void isStableAcrossTitlesWhenFramesArePresent() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("file", "A.java");
        frame.put("function", "f");
        frame.put("in_app", Boolean.TRUE);

        assertEquals(Fingerprint.of("s", "T", "title", List.of(frame)),
                Fingerprint.of("s", "T", "a completely different title", List.of(frame)));
    }

    // ---------------------------------------------------------------- the server's own class as oracle

    @Test
    void agreesWithTheServersOwnClassOnEveryVector() throws Exception {
        for (JsonNode vector : vectors().path("payload_vectors")) {
            String name = vector.path("name").asText();
            String service = vector.path("service").asText();
            String type = vector.path("exception_type").asText();
            String title = vector.path("title").asText();

            assertEquals(IncidentFingerprint.payload(service, type, title, oracleFramesOf(vector)),
                    Fingerprint.payload(service, type, title, framesOf(vector)), name);
            assertEquals(IncidentFingerprint.of(service, type, title, oracleFramesOf(vector)),
                    Fingerprint.of(service, type, title, framesOf(vector)), name);
        }
    }

    @Test
    void agreesWithTheServersOwnClassOnEveryNormalizeVector() throws Exception {
        for (JsonNode vector : vectors().path("normalize_file_vectors")) {
            String input = vector.path("input").asText();
            assertEquals(IncidentFingerprint.normalizeFile(input), Fingerprint.normalizeFile(input), input);
        }
    }

    /**
     * Ten thousand generated inputs, built out of the pieces the rule actually reacts to — CDN hosts,
     * version directories, content hashes, Windows drives, query strings, blank and null members, frames
     * either side of the in-app partition and stacks either side of the three-frame cut.
     *
     * <p>The seed is fixed so that a failure is reproducible, and the case is printed with the payloads
     * rather than only with the digests: a mismatch has to be readable.
     */
    @Test
    void agreesWithTheServersOwnClassOnGeneratedInput() {
        Random random = new Random(20260917L);

        for (int run = 0; run < 10_000; run++) {
            String service = randomText(random);
            String type = randomText(random);
            String title = randomText(random);

            int frameCount = random.nextInt(6);
            List<Map<String, Object>> ours = new ArrayList<>();
            List<IncidentFingerprint.Frame> theirs = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                String file = randomFile(random);
                String function = randomText(random);
                Boolean inApp = random.nextBoolean() ? Boolean.TRUE : Boolean.FALSE;
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("file", file);
                frame.put("function", function);
                frame.put("in_app", inApp);
                ours.add(frame);
                theirs.add(new IncidentFingerprint.Frame(file, function, inApp));
            }

            assertEquals(IncidentFingerprint.payload(service, type, title, theirs),
                    Fingerprint.payload(service, type, title, ours),
                    "run " + run + " " + ours);
            assertEquals(IncidentFingerprint.of(service, type, title, theirs),
                    Fingerprint.of(service, type, title, ours),
                    "run " + run + " " + ours);
        }
    }

    /**
     * The in-app promotion is the half of the rule the vectors touch only twice, and it is the half that
     * decides whether every fault in a service collapses into one incident. Two faults that differ ONLY in
     * which frame the SDK marked as the customer's own must fingerprint apart — and the server agrees.
     */
    @Test
    void theInAppPromotionIsWhatKeepsTwoFaultsApart() {
        List<Map<String, Object>> dispatcherFirst = List.of(
                frame("vendor/framework/Kernel.php", "handle", false),
                frame("app/Checkout.php", "charge", true),
                frame("vendor/framework/Router.php", "dispatch", false),
                frame("vendor/framework/Http.php", "send", false));
        List<Map<String, Object>> everythingFramework = List.of(
                frame("vendor/framework/Kernel.php", "handle", false),
                frame("app/Checkout.php", "charge", false),
                frame("vendor/framework/Router.php", "dispatch", false),
                frame("vendor/framework/Http.php", "send", false));

        assertNotEquals(Fingerprint.of("s", "T", "t", dispatcherFirst),
                Fingerprint.of("s", "T", "t", everythingFramework),
                "without the promotion both stacks are the framework's three dispatcher frames");
        assertEquals(IncidentFingerprint.of("s", "T", "t", oracle(dispatcherFirst)),
                Fingerprint.of("s", "T", "t", dispatcherFirst));
    }

    /** {@code ofEvent} reads the payload the factory built, which is the normal call site. */
    @Test
    void readsAWholeEventPayload() {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", "java.lang.IllegalStateException");
        exception.put("message", "order 4711 could not be settled");
        exception.put("stacktrace", List.of(frame("Checkout.java", "charge", true)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", "shop-api");
        payload.put("title", "IllegalStateException: order 4711 could not be settled");
        payload.put("exception", exception);

        assertEquals(Fingerprint.of("shop-api", "java.lang.IllegalStateException",
                "IllegalStateException: order 4711 could not be settled",
                List.of(frame("Checkout.java", "charge", true))), Fingerprint.ofEvent(payload));
    }

    /**
     * A payload with nothing in it is a fingerprint, not a {@code NullPointerException} and not the word
     * "null" on the wire: {@code ofEvent} runs on the capture path, inside the block that exists so that
     * error reporting cannot become the second failure (FR-2 b).
     */
    @Test
    void anEmptyOrHalfBuiltPayloadStillHasAFingerprint() {
        assertEquals(Fingerprint.of("", "", "", List.of()), Fingerprint.ofEvent(Map.of()));

        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("service", null);
        odd.put("title", List.of("not a string"));
        odd.put("exception", "not a map either");
        assertEquals(Fingerprint.of("", "", "", List.of()), Fingerprint.ofEvent(odd));
    }

    private static List<IncidentFingerprint.Frame> oracle(List<Map<String, Object>> frames) {
        return frames.stream()
                .map(frame -> new IncidentFingerprint.Frame((String) frame.get("file"),
                        (String) frame.get("function"), (Boolean) frame.get("in_app")))
                .toList();
    }

    private static Map<String, Object> frame(String file, String function, boolean inApp) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("file", file);
        frame.put("function", function);
        frame.put("in_app", inApp);
        return frame;
    }

    private static final List<String> TEXTS = List.of("", " ", "shop-api", " padded ", "Ünïcøde",
            "ab", "1234", "a:b", "emoji 💥", "java.lang.NullPointerException");

    private static final List<String> FILE_PIECES = List.of("app", "src", "assets", "v1.2.3", "v2.0",
            "1.0", "vendor", "node_modules", "index-a3f9.js", "index-A3F9.js", "Checkout.php",
            "A.java", "app.4f2c9b.css", "util-face.js");

    private static String randomText(Random random) {
        return random.nextInt(20) == 0 ? null : TEXTS.get(random.nextInt(TEXTS.size()));
    }

    private static String randomFile(Random random) {
        if (random.nextInt(20) == 0) {
            return null;
        }
        StringBuilder file = new StringBuilder();
        switch (random.nextInt(4)) {
            case 0 -> file.append("https://cdn.example");
            case 1 -> file.append("C:");
            case 2 -> file.append("/");
            default -> { /* a bare relative path */ }
        }
        int segments = 1 + random.nextInt(4);
        for (int i = 0; i < segments; i++) {
            if (!file.isEmpty() && file.charAt(file.length() - 1) != '/' && file.charAt(file.length() - 1) != ':') {
                file.append('/');
            }
            file.append(FILE_PIECES.get(random.nextInt(FILE_PIECES.size())));
        }
        if (random.nextInt(6) == 0) {
            file.append("?v=").append(random.nextInt(100));
        }
        if (random.nextInt(8) == 0) {
            file.append("#L").append(random.nextInt(100));
        }
        return file.toString();
    }
}
