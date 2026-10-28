package tech.anvilcoder.feed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The deployed release and WHERE it came from, resolved once at init (issue #13).
 *
 * <p>The platform's incident observation needs a release on every sighting to tell "the fix is running"
 * from "a process still on the old code"; an event without one can never take its incident to resolved.
 * A git checkout answers through {@link GitContext}; a zero-downtime layout ({@code current ->
 * releases/<id>}) has no {@code .git} at all, and asking every host to export {@code ANVIL_RELEASE} is a
 * deploy-script change per host. So the first of these wins:
 *
 * <ol>
 *   <li>{@code ANVIL_RELEASE} (or the {@code release} property) — explicit, as before.</li>
 *   <li>{@code .git/HEAD} — the commit sha, read by {@link GitContext}.</li>
 *   <li>A {@code REVISION} or {@code .release} file in the application root — deploy tools write the
 *       deployed sha there.</li>
 *   <li>The application root's real path, when it sits directly under a {@code releases/} directory —
 *       the release id of a zero-downtime deploy. Not a sha, but unique per deploy.</li>
 *   <li>None (FR-6 c: no placeholder, no "unknown").</li>
 * </ol>
 *
 * <p>The same rules as {@link GitContext}: no subprocess, every file read bounded (the first
 * {@link #MAX_BYTES} bytes), and nothing here ever throws — a failed read is "none". The source is kept so
 * the self-test can say which of the five answered, and an operator sees WHY a release is missing.
 *
 * <p>Immutable and safe to share across threads.
 */
public final class Release {

    public static final String SOURCE_CONFIGURED = "ANVIL_RELEASE";
    public static final String SOURCE_GIT = ".git/HEAD";
    public static final String SOURCE_REVISION_FILE = "REVISION file";
    public static final String SOURCE_RELEASE_FILE = ".release file";
    public static final String SOURCE_RELEASES_DIRECTORY = "releases/ directory name";
    public static final String SOURCE_NONE = "none";

    /** A sha is 7–40 hex characters; a release id written by a tool is a short printable token. */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** A REVISION file is one sha; anything longer than this is not one and is not read further. */
    static final int MAX_BYTES = 128;

    private static final Release NONE = new Release(null, SOURCE_NONE);

    private final String value;
    private final String source;

    private Release(String value, String source) {
        this.value = value;
        this.source = source;
    }

    public static Release none() {
        return NONE;
    }

    /** The release, or {@code null} when none of the five sources answered. */
    public String value() {
        return value;
    }

    /** One of the {@code SOURCE_*} constants: which source answered. */
    public String source() {
        return source;
    }

    /**
     * What is already known without touching the filesystem: the configured value, else git's. For a
     * caller that builds an {@link EventFactory} by hand with a {@link GitContext} of its own.
     */
    public static Release known(String configured, GitContext git) {
        if (configured != null && !configured.isBlank()) {
            return new Release(configured.trim(), SOURCE_CONFIGURED);
        }
        if (git != null && git.release() != null) {
            return new Release(git.release(), SOURCE_GIT);
        }
        return NONE;
    }

    /** The whole chain, for the application root the host names (the working directory, by default). */
    public static Release discover(String configured, GitContext git, Path applicationRoot) {
        Release known = known(configured, git);
        if (known.value() != null || applicationRoot == null) {
            return known;
        }
        try {
            Release fromFile = fromFile(applicationRoot.resolve("REVISION"), SOURCE_REVISION_FILE);
            if (fromFile == null) {
                fromFile = fromFile(applicationRoot.resolve(".release"), SOURCE_RELEASE_FILE);
            }
            if (fromFile != null) {
                return fromFile;
            }
            Release fromDirectory = fromReleasesDirectory(applicationRoot);
            return fromDirectory == null ? NONE : fromDirectory;
        } catch (RuntimeException e) {
            // A release is context, never a reason to fail a boot or a capture (FR-6 b).
            return NONE;
        }
    }

    /** The first line of the file's first MAX_BYTES bytes, trimmed, when it is a token. */
    private static Release fromFile(Path path, String source) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return null;
        }
        byte[] head = new byte[MAX_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.readNBytes(head, 0, MAX_BYTES);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String text = new String(head, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        int newline = text.indexOf('\n');
        String firstLine = (newline >= 0 ? text.substring(0, newline) : text).trim();
        return TOKEN.matcher(firstLine).matches() ? new Release(firstLine, source) : null;
    }

    /**
     * {@code /srv/shop/current} resolves to {@code /srv/shop/releases/20261028104400}; the last segment is
     * the release id when, and only when, its parent is named {@code releases}. A root that is not a
     * symlink is resolved the same way, so a process started inside the release directory answers too.
     */
    private static Release fromReleasesDirectory(Path applicationRoot) {
        Path real;
        try {
            real = applicationRoot.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        Path parent = real.getParent();
        Path name = real.getFileName();
        if (parent == null || parent.getFileName() == null || name == null
                || !"releases".equals(parent.getFileName().toString())) {
            return null;
        }
        String id = name.toString();
        return TOKEN.matcher(id).matches() ? new Release(id, SOURCE_RELEASES_DIRECTORY) : null;
    }
}
