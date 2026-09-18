package tech.anvilcoder.feed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The source lines around a frame — {@code exception.stacktrace[].context_code} of schema 1.0 — for the
 * frames where they can be found at all.
 *
 * <p><b>Why this is not simply "Java has no sources at runtime".</b> That is true of a deployed jar and it
 * is why {@code context_lines} was inert in v0.1, while {@code conformance/config-defaults.json}
 * documents the knob as effective in both languages (review finding 15). It is NOT true of the process a
 * developer runs from a checkout, which is where a repair loop is watched and where an operator raises
 * the knob and expects to see something. So: the roots are looked for ONCE, at construction; if none
 * exists — a jar in production — every lookup answers {@code null} immediately and the capture path pays
 * one boolean, which is the deployed behaviour v0.1 documented.
 *
 * <p><b>in_app frames only</b>, mirroring {@code perf(php): context code is read for in_app frames only}:
 * a dependency's source lines cost a file read per frame on the request path and tell a repair agent
 * nothing it can change.
 *
 * <p>What the server accepts is what is produced: {@code context_code} is a plain string, capped at 1 000
 * characters by {@code IncidentDelivery.MAX_SHORT_TEXT_CHARS} (applied after scrubbing, in
 * {@link EventFactory}), and each individual line is cut to {@code max_context_line_chars} here, because
 * one minified or generated line can be hundreds of kilobytes on its own.
 *
 * <p><b>Cached, because it is on the capture path.</b> Without one, a window was re-read — the whole file
 * through {@code Files.readAllLines}, bounded only by {@code MAX_FILE_BYTES} — for every in_app frame of
 * every capture, so a checkout-based deployment paid up to {@code max_frames} file reads per event on the
 * path Z4 bounds at p99 under one millisecond (task-8 review finding 7). The key is the (file, line) pair
 * the window is asked for, which is what repeats: a loop throwing from one site asks the same question
 * thousands of times. A MISS is cached too, as a marker — a dependency's file that is not under any root
 * is the commonest lookup of all, and re-answering it would be re-probing the filesystem.
 *
 * <p>The cache is bounded at {@link #MAX_CACHED} entries with an LRU eviction, so a process that really
 * does throw from thousands of distinct sites cannot grow it without end (FR-2 d).
 *
 * <p>Safe to share across threads: every field but the cache is immutable, and the cache is guarded by
 * its own monitor.
 */
final class SourceContext {

    /** A compiled class knows its file name, not its path; these are where a checkout keeps them. */
    private static final List<String> ROOT_CANDIDATES =
            List.of("src/main/java", "src/main/kotlin", "src/test/java");

    /** How far up from the working directory a multi-module checkout is looked for. */
    private static final int LEVELS = 3;

    /** The same bound PHP uses: a source file larger than this is not a source file. */
    private static final long MAX_FILE_BYTES = 2_097_152L;

    /** How many (file, line) windows are remembered. Beyond it, the least recently used one goes. */
    private static final int MAX_CACHED = 256;

    /** The marker for "there is no window here", so a miss is answered from memory like a hit. */
    private static final String NO_SOURCE = new String("\u0000no-source");

    private final List<Path> roots;
    private final int lines;
    private final int maxLineChars;
    /** What a resolved path is cut against, or null when nothing can be made relative. */
    private final Path projectRoot;

    /** Access only under its own monitor; {@code accessOrder} true makes {@code get} an LRU touch. */
    private final Map<String, String> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private SourceContext(List<Path> roots, int lines, int maxLineChars, Path projectRoot) {
        this.roots = roots;
        this.lines = lines;
        this.maxLineChars = maxLineChars;
        this.projectRoot = projectRoot;
    }

    /**
     * Discovers the roots once.
     *
     * <p>The roots are looked for whatever {@code contextLines} says, which they were not before the task
     * 12 fix round: they now answer a second question — {@link #pathFor} — and a frame's {@code file} must
     * not depend on whether an operator switched context code off. {@code contextLines == 0} still
     * switches {@link #codeFor} off, exactly as PHP's does; what it costs when it is off is the handful of
     * {@code isDirectory} probes below, once per process.
     */
    static SourceContext discover(int contextLines, int maxLineChars, String projectRoot) {
        List<Path> found = new ArrayList<>();
        Path root = null;
        try {
            Path directory = Path.of("").toAbsolutePath();
            for (int level = 0; level <= LEVELS && directory != null; level++) {
                for (String candidate : ROOT_CANDIDATES) {
                    Path source = directory.resolve(candidate);
                    if (Files.isDirectory(source)) {
                        found.add(source);
                    }
                }
                directory = directory.getParent();
            }
            if (projectRoot != null && !projectRoot.isBlank()) {
                root = Path.of(projectRoot).toAbsolutePath().normalize();
            }
        } catch (RuntimeException noWorkingDirectory) {
            return new SourceContext(List.of(), 0, maxLineChars, null);
        }
        return new SourceContext(List.copyOf(found), contextLines, maxLineChars, root);
    }

    boolean isEmpty() {
        return roots.isEmpty() || lines <= 0;
    }

    /**
     * @return the window around {@code line}, or {@code null} when there is no source to read — which is
     *         every frame of a deployed jar and every frame of a dependency.
     */
    String codeFor(String className, String fileName, int line) {
        if (isEmpty() || line < 1 || className == null || fileName == null || !fileName.endsWith(".java")) {
            return null;
        }
        String key = className + '/' + fileName + '#' + line;
        synchronized (cache) {
            String remembered = cache.get(key);
            if (remembered != null) {
                return remembered == NO_SOURCE ? null : remembered;   // identity: NO_SOURCE is our own instance
            }
        }

        String found = null;
        String directory = packagePath(className);
        for (Path root : roots) {
            found = read(root.resolve(directory).resolve(fileName), line);
            if (found != null) {
                break;
            }
        }
        synchronized (cache) {
            cache.put(key, found == null ? NO_SOURCE : found);
        }
        return found;
    }

    /**
     * The frame's file as it goes ON THE WIRE: the path relative to {@code project_root} when the source
     * file can be found under one of the roots, and {@code null} when it cannot.
     *
     * <p>This is the whole of Java's half of the relative-path rule, and it is smaller than PHP's because
     * a {@link StackTraceElement} carries only a file NAME. Where a checkout is on disk the name can be
     * turned back into {@code java/anvil-feed-core/src/test/java/shop/CheckoutService.java}, which is what
     * the ingest resolves against the repository (chapter 03 §4.1); where it is not — a deployed jar — the
     * caller falls back to the name, which is still not an absolute path and is therefore still safe to
     * publish. Cached like {@link #codeFor}, with its own key space, because a miss is the commonest
     * answer of all and re-answering it would re-probe the filesystem per frame per capture.
     */
    String pathFor(String className, String fileName) {
        if (roots.isEmpty() || className == null || fileName == null || fileName.isBlank()) {
            return null;
        }
        String key = "path\u0000" + className + '/' + fileName;
        synchronized (cache) {
            String remembered = cache.get(key);
            if (remembered != null) {
                return remembered == NO_SOURCE ? null : remembered;   // identity: our own instance
            }
        }

        String found = null;
        String directory = packagePath(className);
        for (Path root : roots) {
            Path candidate = root.resolve(directory).resolve(fileName);
            try {
                if (Files.isRegularFile(candidate)) {
                    found = relativise(candidate);
                    break;
                }
            } catch (RuntimeException unreadable) {
                break;
            }
        }
        synchronized (cache) {
            cache.put(key, found == null ? NO_SOURCE : found);
        }
        return found;
    }

    /** Below the project root: the path with the root removed, forward slashes. Above it: the name. */
    private String relativise(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (projectRoot != null && absolute.startsWith(projectRoot)) {
            return projectRoot.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.getFileName().toString();
    }

    /** {@code shop.Checkout$Inner} lives in {@code shop/}; the class name carries the package, the frame not. */
    private static String packagePath(String className) {
        int nested = className.indexOf('$');
        String outer = nested < 0 ? className : className.substring(0, nested);
        int lastDot = outer.lastIndexOf('.');
        return lastDot < 0 ? "" : outer.substring(0, lastDot).replace('.', '/');
    }

    private String read(Path file, int line) {
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file) || Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            List<String> all = Files.readAllLines(file);
            int around = lines / 2;
            int from = Math.max(0, line - 1 - around);
            int to = Math.min(all.size(), from + lines);
            if (from >= to) {
                return null;
            }
            StringBuilder window = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) {
                    window.append('\n');
                }
                String text = all.get(i);
                window.append(text.length() <= maxLineChars ? text : text.substring(0, maxLineChars));
            }
            return window.toString();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
