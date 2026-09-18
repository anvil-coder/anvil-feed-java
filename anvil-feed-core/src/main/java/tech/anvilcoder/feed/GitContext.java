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
 */
public final class GitContext {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
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
            directory = directory.getParent();
        }
        return null;
    }

    /** A packed-refs checkout has no loose ref file for the branch. */
    private static String packedRef(Path gitDir, String ref) {
        String packed = read(gitDir.resolve("packed-refs"));
        if (packed == null) {
            return null;
        }
        for (String line : packed.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.endsWith(" " + ref) && trimmed.length() > 40) {
                return trimmed.substring(0, 40);
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
