package tech.anvilcoder.feed;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The oracle can go stale, and nothing else in this repository would notice.
 *
 * <p>{@code src/test/java/com/darkfactory/domain/service/IncidentFingerprint.java} is a COPY of the
 * server's own grouping rule, taken on 2026-09-17 from the dark-factory worktree. {@link FingerprintTest}
 * compares this SDK against that copy on ten thousand generated inputs, which is the strongest evidence in
 * the repository — and all of it is evidence about a file that stopped being the server's the moment
 * somebody changed the server's. This test is what notices.
 *
 * <p>Two assertions, in order of strength:
 *
 * <ol>
 *   <li>the upstream file's SHA-256 is pinned. Any change at all — a renamed variable, a new comment —
 *       reddens, with the remedy in the message. That is deliberate: re-reading a 191-line file after a
 *       server change is cheap, and deciding from a digest that the change "was only a comment" is exactly
 *       the judgement this guard exists to take away from a hurried reader;</li>
 *   <li>every string literal of the upstream appears in the copy, and every NAMED numeric constant is
 *       found in the copy's own DECLARATION of that name ({@code TOP_FRAMES = 3}, not "the digit 3 occurs
 *       somewhere"). This one survives the four mechanical edits the copy's own header documents, so it
 *       says something the digest cannot say about a copy that was re-taken by hand: the separator, the
 *       version tag {@code incident-fingerprint/v1}, the shape tags, the frame cap and every
 *       path-normalisation pattern are still the server's.</li>
 * </ol>
 *
 * <p>What the second assertion does NOT catch, stated plainly because a reader will otherwise trust the
 * list above: a semantic change that keeps every literal and every constant — reordering the pre-hash
 * components, taking the LAST three frames instead of the first, dropping the in-app promotion. Only the
 * digest sees those, and it sees them because it sees every byte. The chain holds because the documented
 * remedy is "re-copy, then move the pin, in one commit", after which {@link FingerprintTest}'s ten
 * thousand generated comparisons run against the NEW copy.
 *
 * <p><b>Skipped or failed, never quietly passed.</b> Without {@code -Danvilfeed.incidents.worktree} the
 * checkout is simply not on this machine and the test SKIPS, naming the property. WITH the property it is
 * a hard check: a missing file is a failure and so is a moved digest, because somebody who pointed this at
 * a checkout asked the question and is owed an answer. Task 11's CI job sets the property, which is what
 * turns the skip branch into one that never runs where it matters. (Task-8 fix round; the earlier version
 * aborted on a missing file whether or not anybody had asked for the comparison.)
 */
class FingerprintOracleFreshnessTest {

    /**
     * The dark-factory worktree the copy was taken from. A property so that CI (Task 11) can point it at a
     * checkout of its own without editing this file.
     */
    private static final String WORKTREE_PROPERTY = "anvilfeed.incidents.worktree";

    private static final String WORKTREE = System.getProperty(WORKTREE_PROPERTY);

    private static final Path UPSTREAM = Path.of(WORKTREE == null ? "/tmp/wt-incidents" : WORKTREE,
            "domain/src/main/java/com/darkfactory/domain/service/IncidentFingerprint.java");

    /**
     * The copy, in the test source set, under the server's own package and name so it diffs directly.
     * Located from the conformance property rather than from a relative path, which would depend on the
     * working directory a runner happens to choose.
     */
    private static final Path COPY =
            Path.of(System.getProperty("anvilfeed.conformance")).getParent()
                    .resolve("java/anvil-feed-core/src/test/java/com/darkfactory/domain/service/"
                            + "IncidentFingerprint.java");

    /**
     * The digest of the upstream file as it stood when the copy was taken (2026-09-17, task 8). Changing
     * this constant is the second half of re-copying the oracle; changing it WITHOUT re-copying is how the
     * guard is defeated, so do both in one commit.
     */
    private static final String PINNED_UPSTREAM_SHA256 =
            "9419bd37e8f067f7dccbd69edc39d62e201dab7b4dcf1110339a25c8d6b0caa3";

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void theServersOwnRuleHasNotMovedUnderTheOracle() throws Exception {
        assumeTrue(WORKTREE != null,
                "-D" + WORKTREE_PROPERTY + " is not set, so there is no dark-factory checkout on this "
                        + "machine to compare the oracle against. Set it to a checkout — the file looked "
                        + "for is <checkout>/domain/src/main/java/com/darkfactory/domain/service/"
                        + "IncidentFingerprint.java — and this becomes a hard check. Task 11's CI job "
                        + "sets it; the copy under test is " + COPY + ".");

        assertThat(Files.isReadable(UPSTREAM))
                .describedAs("-D%s points at %s, but the server's IncidentFingerprint is not readable "
                        + "there (%s). Point it at a dark-factory checkout, or unset it to skip this "
                        + "check.", WORKTREE_PROPERTY, WORKTREE, UPSTREAM)
                .isTrue();

        assertThat(Files.isReadable(COPY))
                .describedAs("the oracle itself is missing from the test source set (%s); "
                        + "FingerprintTest's whole comparison rests on it", COPY)
                .isTrue();

        String upstream = Files.readString(UPSTREAM);
        assertThat(sha256Hex(upstream.getBytes(StandardCharsets.UTF_8)))
                .describedAs("""
                        The server's IncidentFingerprint has changed since the oracle was copied.

                        Upstream : %s
                        Copy     : %s

                        Re-read the upstream file, re-apply the four mechanical edits its copy's header \
                        lists, run FingerprintTest, and only then move the pinned digest in this test — in \
                        the same commit. If the grouping rule itself moved, conformance/fingerprint/\
                        vectors.json and the PHP SDK have to move with it, because neither has an oracle.""",
                        UPSTREAM, COPY)
                .isEqualTo(PINNED_UPSTREAM_SHA256);

        String copy = Files.readString(COPY);
        for (String literal : constantsOf(upstream)) {
            assertThat(copy)
                    .describedAs("a string literal of the server's rule is not in the oracle any more: %s",
                            literal)
                    .contains(literal);
        }

        String copyCode = commentFreeCode(copy);
        namedNumbersOf(upstream).forEach((name, value) -> assertThat(copyCode)
                .describedAs("the server declares %s = %s and the oracle does not — checked in its "
                        + "DECLARATION, because \"the copy contains the digit 3 somewhere\" is true of "
                        + "almost any Java file", name, value)
                .containsPattern("\\b" + Pattern.quote(name) + "\\s*=\\s*"
                        + Pattern.quote(value) + "\\s*;"));
    }

    /**
     * Every string literal of the upstream source, found with a scanner rather
     * than with a regex over the whole file. A regex is what a first draft reached for and it was wrong in
     * a way that matters here: stripping {@code //} comments first cuts a URL scheme regex
     * ({@code "^[A-Za-z][A-Za-z0-9+.-]*://"}) in half, and the literal that comes back is then a fragment
     * no file contains. The scanner knows what a string is, so a comment inside one and a quote inside a
     * comment both come out right.
     */
    private static List<String> constantsOf(String source) {
        List<String> literals = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    code.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                current.append(c);
                if (c == '\\') {
                    if (i + 1 < source.length()) {
                        current.append(next);
                        i++;
                    }
                    continue;
                }
                if (inString && c == '"') {
                    inString = false;
                    literals.add(current.toString());
                    current.setLength(0);
                } else if (inChar && c == '\'') {
                    inChar = false;
                    literals.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                current.setLength(0);
                current.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                current.setLength(0);
                current.append(c);
                continue;
            }
            code.append(c);
        }

        // A rule with no literals at all would make the assertion above vacuous, and the version tag and
        // the separator are both literals — so the count is a floor, not a formality.
        assertThat(literals)
                .describedAs("the upstream file parsed to almost no literals; the scanner is broken")
                .hasSizeGreaterThanOrEqualTo(8);
        return literals;
    }

    /**
     * Every NAMED numeric constant of the upstream, as {@code name -> value}. It exists because the first
     * version of this check extracted the numbers as bare digit strings and asserted
     * {@code copy.contains("3")} — a substring that occurs in essentially any Java file, so
     * {@code TOP_FRAMES} and {@code VERSION} could not fail however far they drifted, while the class
     * javadoc claimed the frame cap was covered. A constant is only checked here if it is checked in its
     * DECLARATION.
     */
    private static Map<String, String> namedNumbersOf(String source) {
        Map<String, String> numbers = new LinkedHashMap<>();
        Matcher declarations = Pattern.compile(
                "\\b([A-Z][A-Z0-9_]*)\\s*=\\s*(-?\\d+)\\s*;").matcher(commentFreeCode(source));
        while (declarations.find()) {
            numbers.put(declarations.group(1), declarations.group(2));
        }
        assertThat(numbers)
                .describedAs("the upstream declares no named numeric constant at all; either the rule "
                        + "changed shape or this extraction is broken")
                .containsKeys("VERSION", "TOP_FRAMES");
        return numbers;
    }

    /** The source with comments removed, so prose the copy deliberately replaced is not read as code. */
    private static String commentFreeCode(String source) {
        StringBuilder code = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    code.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                } else if ((inString && c == '"') || (inChar && c == '\'')) {
                    inString = false;
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            code.append(c);
        }
        return code.toString();
    }
}
