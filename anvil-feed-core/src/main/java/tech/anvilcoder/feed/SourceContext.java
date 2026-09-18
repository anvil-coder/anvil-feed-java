package tech.anvilcoder.feed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Immutable and safe to share across threads.
 */
final class SourceContext {

    /** A compiled class knows its file name, not its path; these are where a checkout keeps them. */
    private static final List<String> ROOT_CANDIDATES =
            List.of("src/main/java", "src/main/kotlin", "src/test/java");

    /** How far up from the working directory a multi-module checkout is looked for. */
    private static final int LEVELS = 3;

    /** The same bound PHP uses: a source file larger than this is not a source file. */
    private static final long MAX_FILE_BYTES = 2_097_152L;

    private final List<Path> roots;
    private final int lines;
    private final int maxLineChars;

    private SourceContext(List<Path> roots, int lines, int maxLineChars) {
        this.roots = roots;
        this.lines = lines;
        this.maxLineChars = maxLineChars;
    }

    /** Discovers the roots once. {@code contextLines == 0} switches the whole thing off, as PHP's does. */
    static SourceContext discover(int contextLines, int maxLineChars) {
        if (contextLines <= 0) {
            return new SourceContext(List.of(), 0, maxLineChars);
        }
        List<Path> found = new ArrayList<>();
        try {
            Path directory = Path.of("").toAbsolutePath();
            for (int level = 0; level <= LEVELS && directory != null; level++) {
                for (String candidate : ROOT_CANDIDATES) {
                    Path root = directory.resolve(candidate);
                    if (Files.isDirectory(root)) {
                        found.add(root);
                    }
                }
                directory = directory.getParent();
            }
        } catch (RuntimeException noWorkingDirectory) {
            return new SourceContext(List.of(), 0, maxLineChars);
        }
        return new SourceContext(List.copyOf(found), contextLines, maxLineChars);
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
        String directory = packagePath(className);
        for (Path root : roots) {
            String window = read(root.resolve(directory).resolve(fileName), line);
            if (window != null) {
                return window;
            }
        }
        return null;
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
