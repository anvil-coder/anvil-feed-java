package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #13: the release of a deployment that is not a git checkout. One test per source, one per
 * precedence edge, and the three rules the chain shares with {@link GitContext} — no subprocess, bounded
 * reads, never a throw. Built by hand on a temporary tree, exactly like {@link GitContextTest}.
 */
class ReleaseTest {

    private static final String SHA = "a3f9c2e1b4d5e6f708192a3b4c5d6e7f80910111";

    private static GitContext gitWith(Path scratch, String sha) throws IOException {
        Path root = Files.createDirectories(scratch.resolve("git-" + sha.substring(0, 6)));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"), sha + "\n");
        return GitContext.discover(root);
    }

    // ---------------------------------------------------------------- one test per source

    @Test
    void aConfiguredReleaseWinsAndNamesItsSource(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("REVISION"), SHA + "\n");

        Release release = Release.discover(" 1.4.2 ", gitWith(root, SHA), root);

        assertThat(release.value()).as("trimmed, as the config trims it").isEqualTo("1.4.2");
        assertThat(release.source()).isEqualTo(Release.SOURCE_CONFIGURED);
    }

    @Test
    void theGitShaIsTheSecondSource(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("REVISION"), "deadbeef\n");

        Release release = Release.discover(null, gitWith(root, SHA), root);

        assertThat(release.value()).isEqualTo(SHA);
        assertThat(release.source()).isEqualTo(Release.SOURCE_GIT);
    }

    /** Deploy tools write the deployed sha into REVISION; a zero-downtime release has no .git. */
    @Test
    void aRevisionFileInTheApplicationRootIsTheThirdSource(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("REVISION"), SHA + "\n");

        Release release = Release.discover(null, GitContext.none(), root);

        assertThat(release.value()).isEqualTo(SHA);
        assertThat(release.source()).isEqualTo(Release.SOURCE_REVISION_FILE);
    }

    @Test
    void aDotReleaseFileAnswersWhenThereIsNoRevisionFile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".release"), "2026.10.28-3\n");

        Release release = Release.discover(null, GitContext.none(), root);

        assertThat(release.value()).isEqualTo("2026.10.28-3");
        assertThat(release.source()).isEqualTo(Release.SOURCE_RELEASE_FILE);
    }

    @Test
    void revisionIsReadBeforeDotRelease(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("REVISION"), "from-revision\n");
        Files.writeString(root.resolve(".release"), "from-dot-release\n");

        assertThat(Release.discover(null, GitContext.none(), root).value()).isEqualTo("from-revision");
    }

    /**
     * The zero-downtime layout: {@code current} is a symlink to {@code releases/<id>}, and the application
     * root the host names is the symlink. The id is not a sha, but it is unique per deploy, which is all
     * the platform's observation needs to tell the fixed code from the old one.
     */
    @Test
    void theReleasesDirectoryNameIsTheFourthSourceThroughTheCurrentSymlink(@TempDir Path root) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases/20261028104400"));
        Files.createSymbolicLink(root.resolve("current"), release);

        Release resolved = Release.discover(null, GitContext.none(), root.resolve("current"));

        assertThat(resolved.value()).isEqualTo("20261028104400");
        assertThat(resolved.source()).isEqualTo(Release.SOURCE_RELEASES_DIRECTORY);
    }

    @Test
    void aProcessStartedInsideTheReleaseDirectoryAnswersTheSameId(@TempDir Path root) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases/20261028104400"));

        assertThat(Release.discover(null, GitContext.none(), release).value()).isEqualTo("20261028104400");
    }

    @Test
    void aDirectoryThatIsNotUnderReleasesIsNotARelease(@TempDir Path root) throws IOException {
        Path deploy = Files.createDirectories(root.resolve("deploys/20261028104400"));

        Release release = Release.discover(null, GitContext.none(), deploy);

        assertThat(release.value()).as("a last path segment alone is not a release id").isNull();
        assertThat(release.source()).isEqualTo(Release.SOURCE_NONE);
    }

    @Test
    void withoutAnySourceThereIsNoReleaseAndNoPlaceholder(@TempDir Path root) {
        Release release = Release.discover(null, GitContext.none(), root);

        assertThat(release.value()).isNull();
        assertThat(release.source()).isEqualTo(Release.SOURCE_NONE);
    }

    // ---------------------------------------------------------------- precedence

    @Test
    void aRevisionFileBeatsTheReleasesDirectory(@TempDir Path root) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases/20261028104400"));
        Files.writeString(release.resolve("REVISION"), SHA + "\n");

        Release resolved = Release.discover(null, GitContext.none(), release);

        assertThat(resolved.value()).isEqualTo(SHA);
        assertThat(resolved.source()).isEqualTo(Release.SOURCE_REVISION_FILE);
    }

    // ---------------------------------------------------------------- the shared rules

    @Test
    void onlyTheFirstLineOfTheFirst128BytesIsReadAndOnlyATokenIsAccepted(@TempDir Path root) throws IOException {
        Path revision = root.resolve("REVISION");

        Files.writeString(revision, SHA + "\nbranch: main\n");
        assertThat(Release.discover(null, GitContext.none(), root).value())
                .as("the first line is the sha; a second line is ignored, not glued on").isEqualTo(SHA);

        Files.writeString(revision, "a".repeat(Release.MAX_BYTES + 1) + "\n");
        assertThat(Release.discover(null, GitContext.none(), root).value())
                .as("a line past the bound is cut at the bound and is then no token: refused, never a prefix").isNull();

        Files.writeString(revision, "Deployed by Ada <ada@example.test> on Monday\n");
        assertThat(Release.discover(null, GitContext.none(), root).value())
                .as("prose is not a release id; the next source is asked instead").isNull();

        Files.writeString(revision, "\n\n");
        assertThat(Release.discover(null, GitContext.none(), root).value()).isNull();

        Files.writeString(revision, " ".repeat(Release.MAX_BYTES + 72) + "deadbeef\n");
        assertThat(Release.discover(null, GitContext.none(), root).value())
                .as("a token that begins past the bound is never seen: the read stops at MAX_BYTES, whatever follows")
                .isNull();
    }

    @Test
    void anUnreadableRevisionFallsThroughToTheNextSource(@TempDir Path root) throws IOException {
        Path release = Files.createDirectories(root.resolve("releases/20261028104400"));
        Files.createDirectories(release.resolve("REVISION"));   // a directory of that name: not a readable file

        Release resolved = Release.discover(null, GitContext.none(), release);

        assertThat(resolved.value()).as("the directory name answers when the file cannot be read")
                .isEqualTo("20261028104400");
        assertThat(resolved.source()).isEqualTo(Release.SOURCE_RELEASES_DIRECTORY);
    }

    @Test
    void anApplicationRootThatDoesNotExistIsNoneRatherThanAThrow(@TempDir Path root) {
        Release release = Release.discover(null, GitContext.none(), root.resolve("gone/releases/20261028104400"));

        assertThat(release.value()).isNull();
        assertThat(release.source()).isEqualTo(Release.SOURCE_NONE);
    }

    /** What a hand-built EventFactory gets: the two sources that need no filesystem, nothing probed. */
    @Test
    void knownAnswersConfiguredThenGitAndNeverProbesTheFilesystem(@TempDir Path root) throws IOException {
        GitContext git = gitWith(root, SHA);

        assertThat(Release.known("1.0", git).source()).isEqualTo(Release.SOURCE_CONFIGURED);
        assertThat(Release.known(null, git).source()).isEqualTo(Release.SOURCE_GIT);
        assertThat(Release.known("  ", GitContext.none()).source()).isEqualTo(Release.SOURCE_NONE);
    }
}
