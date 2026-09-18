package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.Test;

import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.FeedLogger;
import tech.anvilcoder.feed.GitContext;
import tech.anvilcoder.feed.IngestResponse;
import tech.anvilcoder.feed.Transport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-8's four verdicts, in the same words the Laravel package's {@code anvil-feed:test} prints. Every one
 * of them is a case a developer really meets in the first fifteen minutes, and the one that matters most
 * is {@code no_binding}: the token is valid, the event arrived, the ingest refused it, and every proxy for
 * success — "it was sent", "the queue is empty", "no exception" — reports that as a pass.
 */
class AnvilFeedTestEventTest {

    private static final String TOKEN = AnvilFeedTestSupport.TOKEN;

    private record Run(List<String> lines, AnvilFeedClient client, int exitCode) {
    }

    private static Run run(AnvilFeedConfig config, Transport ingest) {
        AnvilFeedVerdict verdict = new AnvilFeedVerdict();
        AnvilFeedClient client = AnvilFeedClient.create(config, verdict.recording(ingest),
                FeedLogger.toSystemLogger(), GitContext.none());
        List<String> printed = new java.util.ArrayList<>();
        AnvilFeedTestEvent runner = new AnvilFeedTestEvent(config, client, verdict, printed::add);
        runner.run();
        return new Run(printed, client, runner.getExitCode());
    }

    private static AnvilFeedConfig config() {
        return AnvilFeedConfig.builder().token(TOKEN).service("shop-api")
                .build(FeedLogger.toSystemLogger());
    }

    @Test
    void acceptedNamesTheEventAndWhereToFindIt() {
        Run run = run(config(), new StubIngest());

        assertThat(run.lines()).anyMatch(line -> line.equals("verdict:     accepted"));
        assertThat(run.lines()).anyMatch(line -> line.startsWith("Open the project's Incidents inbox"));
        assertThat(run.lines()).anyMatch(line -> line.startsWith("event_id:    "));
    }

    /** The whole reason this prints a verdict rather than "sent": the ingest took it and refused it. */
    @Test
    void aRejectionNamesTheReasonCodeAndWhatToDoAboutIt() {
        Run run = run(config(),
                StubIngest.rejecting("no_binding", "no binding for shop-api/production"));

        assertThat(run.lines()).contains("verdict:     rejected (no_binding)");
        assertThat(run.lines()).anyMatch(line -> line.contains("Connect this environment in the Anvil "
                + "cockpit"));
    }

    @Test
    void anUnreachableIngestIsTransientAndSaysTheEventIsStillQueued() {
        Run run = run(config(), new StubIngest(IngestResponse.of(503, "", 30)));

        assertThat(run.lines()).contains("verdict:     failed transiently");
        assertThat(run.lines()).anyMatch(line -> line.contains("The event stays in the queue"));
    }

    @Test
    void aRevokedTokenIsNamedAsSuchRatherThanAsAFailureToSend() {
        Run run = run(config(), new StubIngest(IngestResponse.of(401, "", null)));

        assertThat(run.lines()).contains("verdict:     rejected (unauthorized)");
    }

    /** No token: nothing is sent, and the line says which value switched the SDK off. */
    @Test
    void disabledIsAVerdictOfItsOwn() {
        AnvilFeedConfig disabled = AnvilFeedConfig.builder().service("shop-api")
                .build(FeedLogger.toSystemLogger());

        Run run = run(disabled, new StubIngest());

        assertThat(run.lines()).contains("verdict:     disabled");
        assertThat(run.lines()).anyMatch(line -> line.contains("ANVIL_INGEST_TOKEN"));
        assertThat(run.lines()).noneMatch(line -> line.startsWith("event_id:"));
    }

    /**
     * FR-8 through a smoke-test stage: a stage that passes on a {@code no_binding} rejection is the "it
     * was sent" proxy this class exists to kill, reintroduced one layer up. The twin is a command with
     * {@code self::SUCCESS} / {@code self::FAILURE}; this is the same contract.
     */
    @Test
    void theExitCodeIsZeroOnlyForAccepted() {
        assertThat(run(config(), new StubIngest()).exitCode()).isEqualTo(0);
        assertThat(run(config(), StubIngest.rejecting("no_binding", "nothing bound")).exitCode())
                .isEqualTo(1);
        assertThat(run(config(), new StubIngest(IngestResponse.of(503, "", 30))).exitCode()).isEqualTo(1);

        AnvilFeedConfig disabled = AnvilFeedConfig.builder().service("shop-api")
                .build(FeedLogger.toSystemLogger());
        assertThat(run(disabled, new StubIngest()).exitCode()).isEqualTo(1);
    }

    /** The token is printed because a wrong one is the commonest fault — and only twelve characters of it. */
    @Test
    void theTokenIsShownButNotDisclosed() {
        Run run = run(config(), new StubIngest());

        assertThat(run.lines()).anyMatch(line -> line.startsWith("token:       anv_srv_0123… ("));
        assertThat(run.lines()).noneMatch(line -> line.contains(TOKEN));
    }
}
