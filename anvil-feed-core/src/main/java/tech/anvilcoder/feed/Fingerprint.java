package tech.anvilcoder.feed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ingest's grouping rule (FR-7), computed locally.
 *
 * <p>The SDK does not need a fingerprint to send an event — the server groups. It needs one to be able to
 * STOP sending (FR-2 d): a loop throwing the same exception forty thousand times a minute has to be
 * recognised inside the process, before those events reach a queue, a socket and a rate limiter. So this
 * mirrors the server's {@code IncidentFingerprint} v1 exactly, including its quirks.
 *
 * <pre>sha256("incident-fingerprint/v1" + field(shape) + field(service) + field(type) + fields(top frames))</pre>
 *
 * where {@code field(v)} is U+001F, the length in UTF-16 code units, {@code ':'} and the value. The length
 * prefix is what makes the payload unforgeable by choosing content — a separator could be typed into a
 * file name. Deliberately absent: the exception MESSAGE (messages carry order numbers, and one incident
 * per order is no incident at all) and the LINE NUMBER (adding an import must not regroup everything).
 *
 * <p><b>How it is held there.</b> Two ways, because one of them is a sample.
 * {@code conformance/fingerprint/vectors.json} pins fifteen inputs and is the cross-language contract that
 * PHP, Java and the ingest are all measured against. On top of that — and only Java can do this, because
 * the server runs on the JVM — the server's own class is copied into the test source set and this one is
 * compared against it directly, on the vectors and on ten thousand generated inputs. See
 * {@code FingerprintTest} and {@code com.darkfactory.domain.service.IncidentFingerprint}.
 *
 * <p>Stateless, total and safe to call from any thread: nothing here allocates shared state and nothing
 * here throws for any input, which matters because every call site is on the capture path.
 */
public final class Fingerprint {

    private static final int VERSION = 1;
    private static final int TOP_FRAMES = 3;
    private static final char UNIT_SEPARATOR = '\u001F';

    private static final Pattern SCHEME_AND_HOST = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:");
    private static final Pattern VERSION_SEGMENT = Pattern.compile("v?\\d+\\.\\d+(?:\\.\\d+)?");

    /**
     * A content hash before the extension: {@code index-a3f9.js}, {@code app.4f2c9b.css}. Lower-case hex
     * only, exactly as the server's pattern is written — {@code index-A3F9.js} is a file somebody named,
     * not a build artefact, and a case-insensitive port here would diverge from the ingest silently.
     */
    private static final Pattern CONTENT_HASH = Pattern.compile("[-.][0-9a-f]{4,}(?=\\.[A-Za-z0-9]+$)");

    private Fingerprint() {
    }

    /** The lower-case hex SHA-256 of {@link #payload}. */
    public static String of(String service, String exceptionType, String title,
                            List<Map<String, Object>> frames) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload(service, exceptionType, title, frames).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("anvil-feed: SHA-256 is missing from this JVM", impossible);
        }
    }

    /**
     * The whole event payload, for the call sites that have one rather than its parts — which is every
     * call site the client has.
     *
     * <p>Every read is defensive on purpose. This runs inside the block that keeps FR-2 b's promise, so a
     * half-built payload, a {@code null} member or a member of the wrong shape has to produce a
     * fingerprint rather than an exception — and never the literal word {@code "null"}, which would be a
     * perfectly stable identity shared by every malformed event in the process.
     */
    public static String ofEvent(Map<String, Object> payload) {
        Map<?, ?> exception = payload != null && payload.get("exception") instanceof Map<?, ?> map
                ? map : Map.of();
        List<Map<String, Object>> frames = exception.get("stacktrace") instanceof List<?> list
                ? framesOf(list) : List.of();

        return of(text(payload == null ? null : payload.get("service")),
                text(exception.get("type")),
                text(payload == null ? null : payload.get("title")), frames);
    }

    /** The exact pre-hash string. Public because it is what the conformance vectors pin. */
    public static String payload(String service, String exceptionType, String title,
                                 List<Map<String, Object>> frames) {
        List<String> top = topFrames(frames);
        StringBuilder payload = new StringBuilder("incident-fingerprint/v").append(VERSION);
        field(payload, top.isEmpty() ? "title" : "frames");
        field(payload, text(service));
        field(payload, text(exceptionType));
        if (top.isEmpty()) {
            field(payload, text(title));
        } else {
            top.forEach(frame -> field(payload, frame));
        }
        return payload.toString();
    }

    /**
     * Strips everything about a path that changes without the code changing. A version-shaped directory is
     * removed ONLY from a path that is demonstrably a deploy artefact — one that carried a scheme and
     * host, or one that carries a content hash — and only when there is exactly one of them. A service
     * that versions its API by directory ({@code api/1.0/Handler.php}) has two files, and merging them
     * would add two unrelated faults into one counter with nothing in the cockpit saying so.
     */
    public static String normalizeFile(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        String path = file.trim().replace('\\', '/');

        String withoutHost = SCHEME_AND_HOST.matcher(path).replaceFirst("");
        boolean deployArtefact = !withoutHost.equals(path);
        path = WINDOWS_DRIVE.matcher(withoutHost).replaceFirst("");
        path = cutAt(cutAt(path, '?'), '#');

        if (deployArtefact || CONTENT_HASH.matcher(path).find()) {
            path = withoutVersionSegment(path);
        }
        path = CONTENT_HASH.matcher(path).replaceFirst("");
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /**
     * The frames the SDK marked as the customer's own move to the front, keeping their relative order, and
     * then the first three are taken. Without it the top three are the framework's dispatcher on every
     * error the application ever raises, and every fault collapses into one incident.
     */
    private static List<String> topFrames(List<Map<String, Object>> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> inApp = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            if (frame != null) {
                (Boolean.TRUE.equals(frame.get("in_app")) ? inApp : rest).add(frame);
            }
        }
        inApp.addAll(rest);
        return inApp.stream().limit(TOP_FRAMES)
                .map(frame -> normalizeFile(fileOf(frame.get("file"))) + "#" + text(frame.get("function")))
                .toList();
    }

    private static String withoutVersionSegment(String path) {
        List<String> segments = new ArrayList<>(Arrays.asList(path.split("/", -1)));
        int found = -1;
        for (int i = 0; i < segments.size() - 1; i++) {   // never the file name itself
            if (VERSION_SEGMENT.matcher(segments.get(i)).matches()) {
                if (found >= 0) {
                    return path;   // two of them are ambiguous, and a guess is a silent merge
                }
                found = i;
            }
        }
        if (found < 0) {
            return path;
        }
        segments.remove(found);
        return String.join("/", segments);
    }

    /** Whatever the caller handed over, seen as the list of frame maps the payload's shape promises. */
    private static List<Map<String, Object>> framesOf(List<?> stacktrace) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (Object frame : stacktrace) {
            if (frame instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                frames.add(copy);
            }
        }
        return frames;
    }

    private static void field(StringBuilder payload, String value) {
        payload.append(UNIT_SEPARATOR).append(value.length()).append(':').append(value);
    }

    private static String cutAt(String value, char marker) {
        int at = value.indexOf(marker);
        return at < 0 ? value : value.substring(0, at);
    }

    /**
     * {@code file} is a string or it is nothing — the same reading PHP's {@code Fingerprint} takes. A
     * number or a map in that slot is a frame the SDK did not build, and turning it into text would put an
     * object's {@code toString()} into the grouping key.
     */
    private static String fileOf(Object value) {
        return value instanceof String file ? file : null;
    }

    /** The server's {@code text()}: null and anything that is not a scalar are the empty string. */
    private static String text(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value).trim();
        }
        return "";
    }
}
