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

    @Test
    void nothingIsInventedWhereThereIsNoGit(@TempDir Path root) {
        for (GitContext context : new GitContext[] {GitContext.discover(root), GitContext.none(),
                GitContext.discover(null)}) {
            assertThat(context.release()).isNull();
            assertThat(context.repo()).isNull();
            assertThat(context.branch()).isNull();
        }
    }
}
