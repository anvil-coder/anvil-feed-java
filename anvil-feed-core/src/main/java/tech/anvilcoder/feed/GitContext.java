package tech.anvilcoder.feed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * release, repo and branch read from the {@code .git} directory — by reading files, never by running git.
 *
 * <p>FR-6 b: filled where determinable, left out where not. FR-6 c: no {@code unknown}, no empty string.
 * A subprocess would need a git binary on a production image that may have none, and a fork in a path
 * Z4 budgets one millisecond for. Three small files answer the same question.
 *
 * <p><b>Call {@link #discover(Path)} ONCE, at startup, and hold the result.</b> It does up to twelve
 * pairs of filesystem probes and three file reads; on the capture path, which Z4 budgets a millisecond
 * for, that is filesystem I/O per event for an answer that cannot change while the process runs.
 *
 * <p>Immutable and safe to share across threads.
 */
public final class GitContext {

    /**
     * An object name: 40 hex characters in a SHA-1 repository, 64 in one created with
     * {@code --object-format=sha256} (review finding 13). Matching only the first would leave
     * {@code release} null on a detached HEAD there — correct behaviour, since nothing is ever guessed,
     * but a silently narrower answer than the operator expects.
     */
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern ORIGIN_URL =
            Pattern.compile("\\[remote \"origin\"][^\\[]*url\\s*=\\s*(\\S+)", Pattern.DOTALL);
    private static final Pattern OWNER_NAME = Pattern.compile("[:/]([^/:]+/[^/]+)$");

    private final String release;
    private final String repo;
    private final String branch;

    private GitContext(String release, String repo, String branch) {
        this.release = release;
        this.repo = repo;
        this.branch = branch;
    }

    public static GitContext none() {
        return new GitContext(null, null, null);
    }

    public String release() {
        return release;
    }

    public String repo() {
        return repo;
    }

    public String branch() {
        return branch;
    }

    /** Walks up at most twelve levels. Everything it cannot read stays null; it never raises. */
    public static GitContext discover(Path start) {
        Path gitDir = findGitDirectory(start);
        if (gitDir == null) {
            return none();
        }
        String head = read(gitDir.resolve("HEAD"));
        if (head == null) {
            return none();
        }

        String branch = null;
        String release = null;
        if (head.startsWith("ref:")) {
            String ref = head.substring(4).trim();
            branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            release = read(gitDir.resolve(ref));
            if (release == null) {
                release = packedRef(gitDir, ref);
            }
        } else if (SHA.matcher(head).matches()) {
            release = head;   // a detached HEAD, which is what a CI checkout normally is
        }
        // Anything else — a HEAD this version cannot read — leaves release null. FR-6 c: omitted, never
        // guessed, and never "unknown".
        return new GitContext(release, originRepo(gitDir), branch);
    }

    private static Path findGitDirectory(Path start) {
        Path directory;
        try {
            directory = start == null ? null : start.toAbsolutePath();
        } catch (RuntimeException unreadable) {
            return null;
        }
        for (int depth = 0; depth < 12 && directory != null; depth++) {
            Path candidate = directory.resolve(".git");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path linked = linkedGitDirectory(candidate);
            if (linked != null) {
                return linked;
            }
            directory = directory.getParent();
        }
        return null;
    }

    /**
     * A {@code .git} that is a FILE rather than a directory (review finding 11). {@code git worktree}
     * checkouts and submodule deployments both have one, and it holds a single line:
     *
     * <pre>gitdir: /srv/app/.git/worktrees/release</pre>
     *
     * <p>Without this the release and the branch are simply absent on both — FR-6 b's "filled where
     * determinable" quietly reading "filled where the layout is the common one", and the
     * <b>deployment</b> layouts are exactly the two that take this branch.
     *
     * <p>Followed <b>once</b>, deliberately: git does not produce a gitdir pointing at another gitdir,
     * and a chain is how a symlink loop gets into a path {@code Z4} budgets a millisecond for. The path
     * may be relative, and is then relative to the directory holding the {@code .git} file.
     *
     * <p>Note what this does NOT do: in a worktree the loose refs and {@code config} live in the MAIN
     * repository's git directory, not in this one, so {@code repo} and a branch's sha can still be null
     * there. Nothing is guessed to cover that (FR-6 c) — a detached worktree, which is what a deployment
     * checkout normally is, carries its sha in its own HEAD and is answered in full.
     */
    private static Path linkedGitDirectory(Path dotGit) {
        try {
            if (!Files.isRegularFile(dotGit)) {
                return null;
            }
            String pointer = read(dotGit);
            if (pointer == null) {
                return null;
            }
            String firstLine = pointer.split("\n", 2)[0].trim();
            if (!firstLine.startsWith("gitdir:")) {
                return null;
            }
            String target = firstLine.substring("gitdir:".length()).trim();
            if (target.isEmpty()) {
                return null;
            }
            Path parent = dotGit.getParent();
            Path resolved = (parent == null ? Path.of(target) : parent.resolve(target)).normalize();
            return Files.isDirectory(resolved) ? resolved : null;
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    /** A packed-refs checkout has no loose ref file for the branch. */
    private static String packedRef(Path gitDir, String ref) {
        String packed = read(gitDir.resolve("packed-refs"));
        if (packed == null) {
            return null;
        }
        for (String line : packed.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.endsWith(" " + ref)) {
                String name = trimmed.substring(0, trimmed.indexOf(' ') < 0 ? 0 : trimmed.indexOf(' '));
                if (SHA.matcher(name).matches()) {
                    return name;
                }
            }
        }
        return null;
    }

    /** {@code owner/name} out of the origin remote, for either the ssh or the https spelling. */
    private static String originRepo(Path gitDir) {
        String config = read(gitDir.resolve("config"));
        if (config == null) {
            return null;
        }
        Matcher url = ORIGIN_URL.matcher(config);
        if (!url.find()) {
            return null;
        }
        String cleaned = url.group(1).replaceAll("\\.git$", "");
        Matcher parts = OWNER_NAME.matcher(cleaned);
        return parts.find() ? parts.group(1) : null;
    }

    private static String read(Path path) {
        try {
            if (!Files.isReadable(path) || Files.size(path) > 1_048_576) {
                return null;
            }
            String contents = Files.readString(path).trim();
            return contents.isEmpty() ? null : contents;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
