package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-6 b/c: filled where determinable, left out where not — no placeholder and no "unknown". */
class GitContextTest {

    private static final String SHA = "a3f9c2e1b4d5e6f708192a3b4c5d6e7f80910111";

    private static Path git(Path root, String head, String originUrl) throws IOException {
        Path gitDir = Files.createDirectories(root.resolve(".git"));
        Files.writeString(gitDir.resolve("HEAD"), head + "\n");
        if (originUrl != null) {
            Files.writeString(gitDir.resolve("config"),
                    "[core]\n\tbare = false\n[remote \"origin\"]\n\turl = " + originUrl + "\n"
                            + "\tfetch = +refs/heads/*:refs/remotes/origin/*\n");
        }
        return gitDir;
    }

    @Test
    void readsTheBranchTheShaAndTheOriginRepo(@TempDir Path root) throws IOException {
        Path gitDir = git(root, "ref: refs/heads/main", "https://forge.example/shop/api.git");
        Files.createDirectories(gitDir.resolve("refs/heads"));
        Files.writeString(gitDir.resolve("refs/heads/main"), SHA + "\n");

        GitContext context = GitContext.discover(Files.createDirectories(root.resolve("src/main/java")));

        assertThat(context.branch()).isEqualTo("main");
        assertThat(context.release()).isEqualTo(SHA);
        assertThat(context.repo()).isEqualTo("shop/api");
    }

    @Test
    void theSshSpellingOfOriginYieldsTheSameOwnerAndName(@TempDir Path root) throws IOException {
        git(root, "ref: refs/heads/feature/x", "git@forge.example:shop/api.git");

        GitContext context = GitContext.discover(root);

        assertThat(context.repo()).isEqualTo("shop/api");
        assertThat(context.branch()).isEqualTo("feature/x");
        assertThat(context.release()).isNull();   // no loose ref, no packed-refs: nothing is invented
    }

    @Test
    void aPackedRefsCheckoutStillYieldsARelease(@TempDir Path root) throws IOException {
        Path gitDir = git(root, "ref: refs/heads/main", null);
        Files.writeString(gitDir.resolve("packed-refs"),
                "# pack-refs with: peeled fully-peeled sorted\n" + SHA + " refs/heads/main\n");

        assertThat(GitContext.discover(root).release()).isEqualTo(SHA);
    }

    /** What a CI checkout normally is. */
    @Test
    void aDetachedHeadIsTheRelease(@TempDir Path root) throws IOException {
        git(root, SHA, null);

        GitContext context = GitContext.discover(root);

        assertThat(context.release()).isEqualTo(SHA);
        assertThat(context.branch()).isNull();
    }

    /**
     * Hermetic (review finding 17). The earlier version handed a {@code @TempDir} to {@code discover} and
     * relied on the machine's temp directory not living inside a checkout — true on a developer's laptop,
     * false on CI images that redirect {@code java.io.tmpdir} under the workspace, and then it failed for
     * a reason that has nothing to do with the rule. A {@code .git} directory that exists and holds
     * nothing readable proves the same thing and depends on nothing.
     */
    @Test
    void nothingIsInventedWhereThereIsNothingToRead(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(".git"));   // present, and empty: no HEAD, no config

        for (GitContext context : new GitContext[] {GitContext.discover(root), GitContext.none(),
                GitContext.discover(null)}) {
            assertThat(context.release()).isNull();
            assertThat(context.repo()).isNull();
            assertThat(context.branch()).isNull();
        }
    }

    /** A repository created with {@code --object-format=sha256} names its objects in 64 hex characters. */
    @Test
    void aSha256DetachedHeadIsARelease(@TempDir Path root) throws IOException {
        String sha256 = "b".repeat(64);
        git(root, sha256, null);

        assertThat(GitContext.discover(root).release()).isEqualTo(sha256);
    }

    /** What is not an object name is left out, never guessed (FR-6 c). */
    @Test
    void aHeadThisVersionCannotReadLeavesTheReleaseOut(@TempDir Path root) throws IOException {
        git(root, "not-a-sha", null);

        assertThat(GitContext.discover(root).release()).isNull();
    }

    // ------------------------------------------------------------------ a .git FILE (finding 11)

    /**
     * The layout {@code git worktree add --detach} leaves behind, built by hand for the reason the rest
     * of this file is built by hand: no subprocess, no git binary, no repository identity to configure.
     * The checkout's {@code .git} is a FILE holding {@code gitdir: …}; the git directory it points at is
     * a per-worktree one under the main repository, and it carries that worktree's own HEAD.
     *
     * @param gitdirLine what the .git file says, so a relative pointer can be tested with the same helper
     */
    private static Path worktree(Path root, String head, String gitdirLine) throws IOException {
        Path main = Files.createDirectories(root.resolve("main/.git"));
        Path perWorktree = Files.createDirectories(main.resolve("worktrees/release"));
        Files.writeString(perWorktree.resolve("HEAD"), head + "\n");
        Files.writeString(perWorktree.resolve("config"),
                "[remote \"origin\"]\n\turl = https://forge.example/shop/api.git\n");
        Path checkout = Files.createDirectories(root.resolve("release"));
        Files.writeString(checkout.resolve(".git"),
                "gitdir: " + (gitdirLine == null ? perWorktree.toString() : gitdirLine) + "\n");
        return checkout;
    }

    /**
     * A deployment checkout made with {@code git worktree} has no {@code .git} DIRECTORY anywhere above
     * it, so before this every such deployment reported no release and no branch at all — FR-6 b's
     * "filled where determinable" silently reading "where the layout is the common one".
     */
    @Test
    void aGitFilePointingAtAWorktreeGitDirectoryIsFollowed(@TempDir Path root) throws IOException {
        Path checkout = worktree(root, SHA, null);

        GitContext context = GitContext.discover(Files.createDirectories(checkout.resolve("src/main")));

        assertThat(context.release()).isEqualTo(SHA);
        assertThat(context.repo()).isEqualTo("shop/api");
    }

    /** A submodule's {@code .git} file is written relative to the checkout, so the pointer must be too. */
    @Test
    void aRelativeGitdirPointerIsResolvedAgainstTheCheckout(@TempDir Path root) throws IOException {
        Path checkout = worktree(root, "ref: refs/heads/main", "../main/.git/worktrees/release");

        assertThat(GitContext.discover(checkout).branch()).isEqualTo("main");
    }

    /**
     * Followed ONCE: a gitdir that names another {@code .git} FILE is not a shape git produces, and a
     * chain is how a loop gets into a path Z4 budgets a millisecond for.
     *
     * <p>Asserted against a SENTINEL rather than against null, and hermetically for the reason
     * {@link #nothingIsInventedWhereThereIsNothingToRead} gives: where nothing is found the walk
     * continues upwards, and on a CI image whose {@code java.io.tmpdir} lives under the workspace that
     * finds the real checkout. What must be true either way is that the second hop's sha is never the
     * answer.
     */
    @Test
    void aGitdirPointerIsNotChased(@TempDir Path root) throws IOException {
        String secondHop = "c".repeat(40);
        Path far = Files.createDirectories(root.resolve("far"));
        Files.writeString(far.resolve("HEAD"), secondHop + "\n");
        Path hop = Files.createDirectories(root.resolve("hop"));
        Files.writeString(hop.resolve(".git"), "gitdir: " + far + "\n");
        Files.writeString(root.resolve(".git"), "gitdir: " + hop.resolve(".git") + "\n");

        assertThat(GitContext.discover(root).release())
                .describedAs("one hop, not a chain")
                .isNotEqualTo(secondHop);
    }
}
