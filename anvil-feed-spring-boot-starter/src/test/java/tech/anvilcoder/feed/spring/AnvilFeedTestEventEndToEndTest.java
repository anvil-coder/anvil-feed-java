package tech.anvilcoder.feed.spring;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tech.anvilcoder.feed.AnvilFeedClient;
import tech.anvilcoder.feed.AnvilFeedConfig;
import tech.anvilcoder.feed.FeedLogger;
import tech.anvilcoder.feed.GitContext;
import tech.anvilcoder.feed.HttpTransport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FR-8's four verdicts against a REAL ingest answer.
 *
 * <p>{@link AnvilFeedTestEventTest} already proves the four against {@code StubIngest}, an in-process
 * double this repository wrote — which is enough to hold the wording and the exit code, and not enough to
 * say that the answers it replays are the answers the ingest gives. This class runs
 * {@code --anvil.feed.send-test-event=true}'s runner against {@code conformance/stub/server.mjs}, the
 * replay of dark-factory's published contract, over a socket. The verdict a developer reads in the first
 * fifteen minutes is therefore read off the contract's own status codes and reason codes.
 *
 * <p>The one that matters most is still {@code no_binding}: the token is valid, the event arrived, the
 * ingest refused it, and every proxy for success — "it was sent", "the queue is empty", "no exception" —
 * reports that as a pass.
 */
class AnvilFeedTestEventEndToEndTest {

    private static final Path CONFORMANCE = Path.of(System.getProperty("anvilfeed.conformance"));
    private static final String TOKEN = AnvilFeedTestSupport.TOKEN;

    private static Process stub;
    private static int port;

    @BeforeAll
    static void startTheStub() throws Exception {
        Path journal = CONFORMANCE.resolve("stub/.journals/spring.jsonl");
        Files.createDirectories(journal.getParent());

        ProcessBuilder builder = new ProcessBuilder("node", CONFORMANCE.resolve("stub/server.mjs").toString());
        builder.environment().put("STUB_JOURNAL", journal.toString());
        builder.redirectErrorStream(true);
        stub = builder.start();

        BufferedReader out = new BufferedReader(
                new InputStreamReader(stub.getInputStream(), StandardCharsets.UTF_8));
        String line = out.readLine();
        Matcher matcher = Pattern.compile("listening (\\d+)").matcher(line == null ? "" : line);
        if (!matcher.find()) {
            fail("the ingest stub did not report a port. Node 22+ has to be on PATH; see "
                    + "conformance/stub/README.md. It said: " + line);
        }
        port = Integer.parseInt(matcher.group(1));
        out.readLine();
    }

    @AfterAll
    static void stopTheStub() {
        if (stub != null) {
            stub.destroy();
        }
    }

    private record Run(List<String> lines, int exitCode) {
    }

    /** The runner the auto-configuration registers, against the stub, with nothing else changed. */
    private static Run run(String scenario, UnaryOperator<AnvilFeedConfig.Builder> extra) {
        AnvilFeedConfig config = extra.apply(AnvilFeedConfig.builder()
                        .endpoint("http://127.0.0.1:" + port + "/spring/" + scenario + "/api/feed")
                        .service("shop-api")
                        .environment("production")
                        .contextLines(0))
                .build(FeedLogger.toSystemLogger());

        AnvilFeedVerdict verdict = new AnvilFeedVerdict();
        AnvilFeedClient client = AnvilFeedClient.create(config,
                verdict.recording(new HttpTransport(config)), FeedLogger.toSystemLogger(),
                GitContext.none());
        List<String> printed = new ArrayList<>();
        AnvilFeedTestEvent runner = new AnvilFeedTestEvent(config, client, verdict, printed::add);
        try {
            runner.run();
        } finally {
            client.queue().clear();
            client.close();
        }
        return new Run(printed, runner.getExitCode());
    }

    private static void script(String run, String... answers) throws Exception {
        String body = "{\"run\":\"" + run + "\",\"answers\":[\""
                + String.join("\",\"", answers) + "\"]}";
        HttpResponse<String> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__control"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).isEqualTo(200);
    }

    @Test
    void acceptedIsTheIngestsOwnAnswerAndNamesTheIdToSearchFor() {
        Run run = run("accepted", (builder) -> builder.token(TOKEN));

        assertThat(run.lines()).contains("verdict:     accepted");
        assertThat(run.lines()).anyMatch(line -> line.startsWith("Open the project's Incidents inbox"));
        assertThat(run.exitCode()).isZero();
    }

    /**
     * The environment is not bound at the stub, exactly as it is not bound in a cockpit where step 4 of
     * the guide was skipped. The reason code and the message both come from the contract.
     */
    @Test
    void anUnboundEnvironmentIsReportedAsNoBindingWithTheIngestsOwnMessage() {
        Run run = run("accepted", (builder) -> builder.token(TOKEN).environment("staging"));

        assertThat(run.lines()).contains("verdict:     rejected (no_binding)");
        assertThat(run.lines()).anyMatch(line -> line.contains("no binding for environment 'staging'"));
        assertThat(run.lines()).anyMatch(line -> line.contains("Connect this environment in the Anvil "
                + "cockpit"));
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void aTransientStoreFailureIsReportedAsTransientAndTheEventStaysQueued() throws Exception {
        script("spring", "transient");

        Run run = run("accepted", (builder) -> builder.token(TOKEN));

        assertThat(run.lines()).contains("verdict:     failed transiently");
        assertThat(run.lines()).anyMatch(line -> line.contains("The event stays in the queue"));
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void aTokenTheIngestDoesNotKnowIsReportedAsUnauthorisedRatherThanAsAFailureToSend() {
        Run run = run("accepted", (builder) -> builder.token("anv_srv_too-short"));

        assertThat(run.lines()).contains("verdict:     rejected (unauthorized)");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    /** No token: nothing reaches the socket at all, and the line says which value switched the SDK off. */
    @Test
    void disabledIsAVerdictOfItsOwnAndReachesNoSocket() {
        Run run = run("accepted", (builder) -> builder);

        assertThat(run.lines()).contains("verdict:     disabled");
        assertThat(run.lines()).anyMatch(line -> line.contains("ANVIL_INGEST_TOKEN"));
        assertThat(run.lines()).noneMatch(line -> line.startsWith("event_id:"));
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
