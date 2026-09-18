package tech.anvilcoder.feed;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Three ways one process can report its own reporting, and all three have to be shut (FR-2 d).
 *
 * <p>dark-factory runs this SDK AND hosts the ingest it posts to. On that machine a fault in the ingest is
 * an exception inside a request to {@code /api/feed/events}, which the SDK would capture, queue and post
 * to {@code /api/feed/events} — where it fails again. The load is bounded only by how fast the machine can
 * fail.
 *
 * <ol>
 *   <li><b>Reentrancy</b>: nothing raised while the sender is running is ever captured. The flag is per
 *       THREAD, because the sender runs on its own daemon thread and an application thread failing at the
 *       same moment has nothing to do with it — a process-wide flag would silently drop the customer's
 *       own errors for as long as a flush was in flight.</li>
 *   <li><b>Path</b>: an exception raised while serving a request whose path matches an ignored pattern is
 *       not reported. Default {@code /api/feed/**}. The path is what the HOST says it is serving — see
 *       {@link AnvilFeedClient#enterRequest(String)}; this SDK never guesses it.</li>
 *   <li><b>Volume</b>: {@link RateGate}, because the first two do nothing about an ordinary application
 *       loop.</li>
 * </ol>
 *
 * <p>The counterpart of {@code AnvilFeed\LoopGuard} (C9). Safe to share across threads: the patterns are
 * immutable after construction and the reentrancy flag is thread-confined.
 */
public final class LoopGuard {

    private final ThreadLocal<Boolean> sending = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final List<Pattern> patterns;

    /** A list handed over by a host framework may hold nulls and blanks; neither may reach a regex. */
    public LoopGuard(List<String> ignoredPaths) {
        List<Pattern> compiled = new ArrayList<>();
        if (ignoredPaths != null) {
            for (String glob : ignoredPaths) {
                if (glob != null && !glob.isBlank()) {
                    compiled.add(toPattern(glob.trim().toLowerCase(Locale.ROOT)));
                }
            }
        }
        this.patterns = List.copyOf(compiled);
    }

    /** @return false when this thread is already inside a send; the caller must then do nothing */
    public boolean enterSend() {
        if (Boolean.TRUE.equals(sending.get())) {
            return false;
        }
        sending.set(Boolean.TRUE);
        return true;
    }

    /** {@code remove()} rather than {@code set(FALSE)}: a pooled request thread keeps no entry behind. */
    public void leaveSend() {
        sending.remove();
    }

    public boolean isSending() {
        return Boolean.TRUE.equals(sending.get());
    }

    public boolean ignoresPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String normalized = canonical(percentDecoded(cutQuery(withoutOrigin(path))));
        for (Pattern pattern : patterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code **} crosses segment boundaries, {@code *} does not — the usual reading, spelled out because
     * it matters.
     *
     * <p>One addition to the usual reading: a pattern ending in {@code /**} also matches the directory
     * ITSELF. {@code /api/feed/**} has to cover {@code /api/feed}, because that is the route the ingest's
     * own collection endpoint sits on and the plain spelling an operator writes in config is
     * {@code /api/feed}. Without it the two spellings of the same intention protect different sets of
     * paths, and the one that misses is the default. {@code /api/feedback} is still not matched: the
     * boundary is the slash, not the prefix.
     */
    private static Pattern toPattern(String glob) {
        String path = "/" + glob.replaceFirst("^/+", "");
        String optionalTail = "";
        if (path.endsWith("/**")) {
            path = path.substring(0, path.length() - 3);
            optionalTail = "(/.*)?";
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '*') {
                if (i + 1 < path.length() && path.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append(optionalTail).append('$').toString());
    }

    /**
     * A host that hands over a full URL rather than a path must get the same answer as one that hands over
     * the path: the SDK's own {@code request.url} is an absolute URL by the time the contract has seen it,
     * and a guard that only understood paths would report every one of the ingest's own failures.
     */
    private static String withoutOrigin(String path) {
        return path.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*", "");
    }

    /**
     * The path as the pattern will be compared against it: lower-cased, {@code .} and {@code ..} segments
     * resolved, repeated slashes collapsed, a trailing slash dropped.
     *
     * <p>This guard's whole job is to stop a feedback loop, and every one of these shapes can reach a front
     * controller through a rewrite rule or a permissive router: {@code /API/FEED/events},
     * {@code /app/../api/feed/events}, {@code //api/feed/events}, {@code /api/feed/}. A guard that answered
     * "not ignored" to any of them would report the ingest's own failure to the ingest.
     */
    private static String canonical(String path) {
        List<String> resolved = new ArrayList<>();
        for (String segment : path.toLowerCase(Locale.ROOT).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
                continue;
            }
            resolved.add(segment);
        }
        return "/" + String.join("/", resolved);
    }

    /**
     * Percent-decoding, ONCE (review finding 9). A host is expected to hand over the path it actually
     * routed on, and this is the backstop for the ones that do not: {@code /api/%66eed/events} and
     * {@code /app/%2e%2e/api/feed/events} both reach a permissive router or a rewrite rule as written,
     * and a guard that matched them raw would answer "not ignored" and report the ingest's own failure
     * to the ingest.
     *
     * <p>Once and not to a fixed point: decoding repeatedly would turn {@code %2525} into {@code %} and
     * make a literal path segment somebody really has match a pattern it does not. A sequence that is not
     * valid percent-encoding is left exactly as it is, because a path is not required to be encoded at
     * all. Bytes are decoded and then read as UTF-8, which is what a servlet container does; anything
     * undecodable comes back through the replacement character rather than through an exception, since
     * this runs on the capture path.
     */
    private static String percentDecoded(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            int high;
            int low;
            if (c == '%' && i + 2 < path.length()
                    && (high = Character.digit(path.charAt(i + 1), 16)) >= 0
                    && (low = Character.digit(path.charAt(i + 2), 16)) >= 0) {
                bytes.write((high << 4) + low);
                i += 2;
            } else {
                // Not a percent-escape: write the character's own UTF-8, so a path that was never
                // encoded survives byte for byte.
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String cutQuery(String path) {
        int at = path.length();
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '?' || path.charAt(i) == '#') {
                at = i;
                break;
            }
        }
        return path.substring(0, at);
    }
}
