package tech.anvilcoder.feed;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import shop.CheckoutService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventFactoryTest {

    private static EventFactory factory(String environment, List<String> inAppPackages) {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .environment(environment)
                .inAppPackages(inAppPackages)
                .build(FeedLogger.toSystemLogger());
        return new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());
    }

    private static EventFactory factory(String environment) {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_0123456789abcdef0123456789abcdef")
                .service("shop-api")
                .environment(environment)
                .build(FeedLogger.toSystemLogger());
        return new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> frames(Map<String, Object> payload) {
        return (List<Map<String, Object>>) map(payload.get("exception")).get("stacktrace");
    }

    @Test
    void aMessageEventStillCarriesAnException() {
        Map<String, Object> payload = CheckoutService
                .report(factory("production"), "Reconciliation skipped", Map.of()).payload();

        Map<String, Object> exception = map(payload.get("exception"));
        assertEquals(EventFactory.MESSAGE_TYPE, exception.get("type"));
        assertEquals("Reconciliation skipped", exception.get("message"));
        assertFalse(frames(payload).isEmpty());
    }

    @Test
    void keysAreInCanonicalOrder() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertEquals(List.of("schema_version", "event_id", "timestamp", "title", "level", "environment",
                        "service", "language", "sdk", "exception"),
                payload.keySet().stream().limit(10).toList());
    }

    @Test
    void eventIdIsACanonicalUuid() {
        FeedEvent event = factory("production").fromThrowable(CheckoutService.charge(), "error", Map.of());

        assertTrue(event.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "the ingest refuses anything but a canonical UUID: " + event.id());
        assertEquals(event.id(), event.payload().get("event_id"));
    }

    /** Spring's own profile names routinely look like this. The ingest would answer schema_violation. */
    @Test
    void anEnvironmentTheIngestWouldRefuseIsRepairedRatherThanSent() {
        Map<String, Object> payload = factory("Prod_EU")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertEquals("prod-eu", payload.get("environment"));
        assertTrue(AnvilFeedConfig.ENVIRONMENT.matcher((String) payload.get("environment")).matches());
    }

    @Test
    void releaseIsOmittedRatherThanGuessed() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertFalse(payload.containsKey("release"));
    }

    @Test
    void theApplicationsOwnFramesAreMarkedInApp() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        Map<String, Object> crashSite = frames(payload).get(0);
        assertEquals("charge", crashSite.get("function"));
        assertEquals(Boolean.TRUE, crashSite.get("in_app"), "shop.CheckoutService is the customer's code");
        assertThat(frames(payload)).anySatisfy(frame -> assertEquals(Boolean.FALSE, frame.get("in_app")));
    }

    /**
     * The structural rule alone cannot tell a library apart from the application inside one flat
     * class path: JUnit is not the JDK and a fat jar has no {@code vendor/} directory, so every frame of
     * every dependency is {@code in_app: true} and the fingerprint's in-app partition promotes them over
     * the application's own frames (task 6 review). {@code in_app_packages} is what a host framework can
     * answer and the SDK cannot — the Spring starter seeds it from the {@code @SpringBootApplication}
     * class's package.
     */
    @Test
    void inAppPackagesNarrowTheRuleToTheApplicationsOwnFrames() {
        Map<String, Object> wide = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();
        assertThat(inApp(wide))
                .describedAs("without the knob the test runner's own frames count as the application too")
                .hasSizeGreaterThan(1);

        Map<String, Object> narrow = factory("production", List.of("shop"))
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();
        assertEquals(Boolean.TRUE, frames(narrow).get(0).get("in_app"), "shop.CheckoutService still is");
        assertThat(inApp(narrow))
                .describedAs("nothing outside the configured packages is the application any more")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .endsWith("shop/CheckoutService.java");
    }

    /**
     * Finding 3 of the task-12 fix round, as the claim it is: an absolute path never leaves the process.
     *
     * <p>An absolute path carries the deployment layout, the home directory and usually the user name of
     * whoever the process runs as, into an incident a support engineer — and an agent — reads. It also
     * makes one fault look like two files when the same service runs from two paths. The server resolves a
     * frame against the repository (chapter 03 §4.1), so the relative path is the useful one as well as
     * the safe one. Java's half is smaller than PHP's because a {@link StackTraceElement} carries only a
     * file NAME; what this asserts is that neither branch of that is ever absolute.
     */
    @Test
    void anAbsolutePathNeverLeavesTheProcess() {
        List<Map<String, Object>> frames = frames(factory("production", List.of())
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());

        assertThat(frames).describedAs("a stack with no frames proves nothing here").isNotEmpty();
        for (Map<String, Object> frame : frames) {
            assertThat(String.valueOf(frame.get("file")))
                    .doesNotStartWith("/")
                    .describedAs("nor a Windows drive letter")
                    .doesNotContain(":\\");
        }
    }

    /**
     * Where the checkout is on disk the file NAME is turned back into a repo-relative path, which is what
     * the ingest resolves against the repository. This suite runs from a checkout, so the resolution is
     * exercised rather than assumed; a deployed jar takes the other branch and sends the bare name.
     */
    @Test
    void aFrameIsResolvedToARepositoryRelativePathWhereTheSourceIsOnDisk() {
        List<Map<String, Object>> frames = frames(factory("production", List.of())
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());

        assertThat(String.valueOf(frames.get(0).get("file")))
                .describedAs("the crash site is shop/CheckoutService.java, resolved under project_root")
                .endsWith("shop/CheckoutService.java")
                .contains("src/test/java/");
    }

    /** The root is discovered from user.dir's git root, not from whatever directory a job happens to run in. */
    @Test
    void theProjectRootIsTheGitRootAboveTheWorkingDirectory() {
        String discovered = EventFactory.discoverProjectRoot();

        assertThat(discovered).isNotBlank();
        assertThat(java.nio.file.Files.isDirectory(java.nio.file.Path.of(discovered, ".git")))
                .describedAs("%s is not a git checkout; discovery fell back to user.dir", discovered)
                .isTrue();
    }

    /** The file names of the frames this event calls the application's own. */
    private static List<String> inApp(Map<String, Object> payload) {
        return frames(payload).stream()
                .filter(frame -> Boolean.TRUE.equals(frame.get("in_app")))
                .map(frame -> String.valueOf(frame.get("file")))
                .toList();
    }

    /** A prefix that is a package is compared as a package: "sho" is not "shop". */
    @Test
    void anInAppPrefixOnlyMatchesAtAPackageBoundary() {
        Map<String, Object> payload = factory("production", List.of("sho"))
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertThat(inApp(payload)).isEmpty();
    }

    /**
     * A synthesised stack that starts inside anvil-feed would put OUR frames at the head — and
     * {@code in_app} is what the server's fingerprint partitions on, so every captureMessage() of an
     * application would collapse into one incident.
     */
    @Test
    void aSynthesisedStackStartsAtTheCallerNotInsideTheSdk() {
        Map<String, Object> payload = CheckoutService
                .report(factory("production"), "Reconciliation skipped", Map.of()).payload();

        assertEquals("report", frames(payload).get(0).get("function"));
        assertEquals(Boolean.TRUE, frames(payload).get(0).get("in_app"));
    }

    @Test
    void theTestEventSaysWhatItIsAndIsSafeToClose() {
        Map<String, Object> payload = CheckoutService.verify(factory("production")).payload();

        Map<String, Object> exception = map(payload.get("exception"));
        assertEquals(EventFactory.TEST_TYPE, exception.get("type"));
        assertEquals(EventFactory.TEST_MESSAGE, exception.get("message"));
        assertEquals("Anvil Feed test event", payload.get("title"));
        assertEquals("error", payload.get("level"));
    }

    /** Task 11's release check has exactly one symbol to pin. */
    @Test
    void theSdkNameAndVersionHaveExactlyOneSource() {
        Map<String, Object> sdk = map(factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload().get("sdk"));

        assertEquals(EventFactory.SDK_NAME, sdk.get("name"));
        assertEquals(EventFactory.SDK_VERSION, sdk.get("version"));
        assertEquals("anvil-feed-java", sdk.get("name"));
    }

    @Test
    void aLevelTheWireDoesNotKnowBecomesError() {
        assertEquals("error", EventFactory.coerceLevel("info"));
        assertEquals("error", EventFactory.coerceLevel(null));
        assertEquals("warning", EventFactory.coerceLevel("warning"));
        assertEquals("error", factory("production")
                .fromThrowable(CheckoutService.charge(), "info", Map.of()).payload().get("level"));
    }

    /** A hook that writes a level the wire does not know must not reach the socket either. */
    @Test
    void theSecondPassOverAHooksOutputReappliesTheLevelEnum() {
        EventFactory factory = factory("production");
        Map<String, Object> payload =
                new LinkedHashMap<>(factory.fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());
        payload.put("level", "info");
        payload.put("tags", new LinkedHashMap<>(Map.of("api_key", "sk-live-99887766")));

        Map<String, Object> finalised = factory.finalise(payload);

        assertEquals("error", finalised.get("level"));
        assertEquals("[Filtered]", map(finalised.get("tags")).get("api_key"));
    }

    @Test
    void aBlankTitleFallsBackToTheExceptionTypeRatherThanCostingTheEvent() {
        Map<String, Object> payload =
                CheckoutService.report(factory("production"), "   ", Map.of()).payload();

        assertEquals(EventFactory.MESSAGE_TYPE, payload.get("title"));
    }

    /**
     * Every one of these blocks is typed {@code object} in schema 1.0; an empty one is refused, and the
     * event is then lost for a field the sender never needed to send.
     */
    @Test
    void blocksThatEndUpEmptyAreDroppedRatherThanSentAsAnEmptyMap() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of("url", "file:///etc/passwd")));
        context.put("tags", new LinkedHashMap<>(Map.of("user.ip_hash", "deadbeef")));

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload();

        assertFalse(payload.containsKey("request"), "a request whose only member was a file:// URL");
        assertFalse(payload.containsKey("tags"), "tags whose only member was the ingest's own pseudonym");
    }

    @Test
    void onlyTheLastThirtyBreadcrumbsAndTheFirstTwentyNineTagsSurvive() {
        List<String> crumbs = IntStream.range(0, 40).mapToObj(i -> "step-" + i).toList();
        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.range(0, 40).forEach(i -> tags.put("t" + i, "v" + i));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("breadcrumbs", crumbs);
        context.put("tags", tags);

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload();

        List<?> kept = (List<?>) payload.get("breadcrumbs");
        assertEquals(30, kept.size());
        assertEquals("step-10", kept.get(0), "the TAIL explains the fault; the server keeps the tail too");
        assertEquals("step-39", kept.get(29));
        // 29, not 30: AnvilFeedEventMapper.tags() takes the first slot for its own user.ip_hash.
        assertEquals(29, map(payload.get("tags")).size());
        assertTrue(map(payload.get("tags")).containsKey("t0"));
        assertFalse(map(payload.get("tags")).containsKey("t29"));
    }

    /**
     * The order is the server's own ({@code IncidentRedaction.redact}: redact, THEN truncate). Each value
     * below starts one character UNDER its cap and is built of short denylist assignments, so scrubbing —
     * the only step that may grow a value — carries it over the cap from the far side. Under the inverted
     * order every one of these ends up longer than the schema allows and the event is refused.
     */
    @Test
    void redactionGrowsTextAndEveryCapStillHolds() {
        String message = grows(1_999);
        String crumb = grows(999);
        String tagValue = grows(999);
        String url = "https://app.example/x?" + "token=y&".repeat(100);
        // The negative control: every input is UNDER its cap before the scrubber ever sees it, so the
        // only thing that can carry it over is redaction.
        assertThat(message.length()).isEqualTo(1_999);
        assertThat(crumb.length()).isEqualTo(999);
        assertThat(url.length()).isLessThan(1_000);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of("method", "POST", "url", url)));
        context.put("breadcrumbs", new ArrayList<>(List.of(crumb)));
        context.put("tags", new LinkedHashMap<>(Map.of("k", tagValue)));

        Map<String, Object> payload = factory("production")
                .fromThrowable(new IllegalStateException(message), "error", context).payload();

        assertEquals(2_000, ((String) map(payload.get("exception")).get("message")).length());
        assertEquals(1_000, ((String) map(payload.get("request")).get("url")).length());
        assertEquals(1_000, ((String) ((List<?>) payload.get("breadcrumbs")).get(0)).length());
        assertEquals(1_000, ((String) map(payload.get("tags")).get("k")).length());
        assertThat((String) map(payload.get("exception")).get("message"))
                .contains("[Filtered]").endsWith(" [truncated]");
        assertThat((String) map(payload.get("request")).get("url"))
                .startsWith("https://").contains("token=[Filtered]");
    }

    /** The title has its own cap and its own growth: a message event's title IS the message. */
    @Test
    void aTitleThatOnlyGrowsOverItsCapWhileBeingScrubbedIsStillBounded() {
        String message = grows(511);
        assertThat(message.length()).isEqualTo(511);

        Map<String, Object> payload = CheckoutService.report(factory("production"), message, Map.of()).payload();

        assertEquals(512, ((String) payload.get("title")).length());
        assertThat((String) payload.get("title")).contains("[Filtered]").endsWith(" [truncated]");
    }

    /** "token=y;" is eight characters and scrubs to seventeen. Nothing exotic makes a value grow. */
    private static String grows(int length) {
        StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            text.append("token=y;");
        }
        return text.substring(0, length);
    }
    // ----- Review finding 1: finalise() is run twice on every hooked event ------------------------------

    /**
     * {@code finalise(finalise(p)) == finalise(p)}, through the real factory, with a quoted secret in the
     * message, in a tag and in a breadcrumb — the three places a config line lands. Before the scrubber's
     * quote group moved in front of its two lookaheads, every one of them gained {@code ]"} per pass.
     */
    @Test
    void finalisingAnAlreadyFinalisedPayloadChangesNothing() {
        EventFactory factory = factory("production");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("breadcrumbs", new ArrayList<>(List.of("config: secret='hunter2' loaded")));
        context.put("tags", new LinkedHashMap<>(Map.of("note", "password=\"hunter2\"")));
        Map<String, Object> once = factory
                .fromThrowable(new IllegalStateException("cfg token=\"abc123\" failed"), "error", context)
                .payload();

        Map<String, Object> twice = factory.finalise(new LinkedHashMap<>(once));

        assertEquals(once, twice, "a second pass over an already finalised payload changed it");
        assertEquals("java.lang.IllegalStateException: cfg token=\"[Filtered]\" failed", once.get("title"));
        assertEquals("config: secret='[Filtered]' loaded", ((List<?>) once.get("breadcrumbs")).get(0));
        assertEquals("password=\"[Filtered]\"", map(once.get("tags")).get("note"));
    }

    // ----- Review finding 3: the second pass has to re-count, not only re-clip -------------------------

    /**
     * Enrichment is the single most obvious use of a {@code beforeSend} hook, and a thirtieth tag on a full
     * event costs the whole event: schema 1.0 sets {@code maxProperties: 29}. Each number here is exactly
     * one over its cap, and the directions are asserted with it.
     */
    @Test
    void finaliseReAppliesEveryCountCapABeforeSendHookCanBreak() {
        EventFactory factory = factory("production");
        Map<String, Object> payload =
                new LinkedHashMap<>(factory.fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());

        Map<String, Object> tags = new LinkedHashMap<>();
        IntStream.rangeClosed(1, 30).forEach(i -> tags.put("tag-" + i, "v" + i));
        payload.put("tags", tags);
        payload.put("breadcrumbs", IntStream.rangeClosed(1, 31).mapToObj(i -> "step " + i).toList());
        Map<String, Object> exception = new LinkedHashMap<>(map(payload.get("exception")));
        exception.put("stacktrace", IntStream.rangeClosed(1, 51)
                .mapToObj(i -> (Object) new LinkedHashMap<>(Map.of("file", "Frame" + i + ".java",
                        "line", i, "function", "f", "in_app", Boolean.TRUE)))
                .toList());
        payload.put("exception", exception);

        Map<String, Object> finalised = factory.finalise(payload);

        assertEquals(29, map(finalised.get("tags")).size(), "schema 1.0 allows 29 tags; the 30th costs the event");
        assertTrue(map(finalised.get("tags")).containsKey("tag-1"));
        assertFalse(map(finalised.get("tags")).containsKey("tag-30"));
        List<?> crumbs = (List<?>) finalised.get("breadcrumbs");
        assertEquals(30, crumbs.size());
        assertEquals("step 2", crumbs.get(0), "the TAIL explains the fault, so the front is what goes");
        assertEquals("step 31", crumbs.get(29));
        List<?> frames = (List<?>) map(finalised.get("exception")).get("stacktrace");
        assertEquals(50, frames.size());
        assertEquals("Frame1.java", map(frames.get(0)).get("file"), "frame 0 is the crash site and stays");
    }

    // ----- Review finding 2: request.status_code is an integer on the wire -----------------------------

    @Test
    void aStatusCodeThatTookAStringHopIsStillAnIntegerOnTheWire() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of(
                "method", "GET", "url", "https://a.example/x", "status_code", "500")));

        Map<String, Object> request = map(factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload().get("request"));

        assertEquals(500, request.get("status_code"));
        assertEquals(Integer.class, request.get("status_code").getClass());
    }

    @Test
    void aStatusCodeThatIsNotANumberIsDroppedRatherThanShippedAsAViolation() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", new LinkedHashMap<>(Map.of(
                "method", "GET", "url", "https://a.example/x", "status_code", "not a status")));

        Map<String, Object> request = map(factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload().get("request"));

        assertFalse(request.containsKey("status_code"));
        assertEquals("https://a.example/x", request.get("url"), "the rest of the block survives");
    }

    // ----- Review findings 4 and 6: what a host object becomes ----------------------------------------

    /** PHP's {@code json_encode} fallback, and not Java's {@code toString} — {@code {a=1}} parses nowhere. */
    @Test
    void aNonScalarTagOrBreadcrumbTravelsAsItsJsonNotAsItsToString() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tags", new LinkedHashMap<>(Map.of("k", Map.of("a", 1))));
        context.put("breadcrumbs", new ArrayList<>(List.of(Map.of("b", 2))));

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", context).payload();

        assertEquals("{\"a\":1}", map(payload.get("tags")).get("k"));
        assertEquals("{\"b\":2}", ((List<?>) payload.get("breadcrumbs")).get(0));
    }

    /**
     * A hook that writes a host's own object into a tag used to reach a bare {@code ObjectMapper}, which
     * serialises an unknown type reflectively — every field of it on the wire, none of them ever seen by
     * the denylist, because the scrubber descends only into Map, List and String.
     */
    @Test
    void aHostObjectBecomesTextBeforeJacksonEverSeesIt() {
        EventFactory factory = factory("production");
        Map<String, Object> payload =
                new LinkedHashMap<>(factory.fromThrowable(CheckoutService.charge(), "error", Map.of()).payload());
        payload.put("tags", new LinkedHashMap<>(Map.of("order", new Order("o-1", "sk-live-99887766"))));

        Map<String, Object> finalised = factory.finalise(payload);

        Object tag = map(finalised.get("tags")).get("order");
        assertThat(tag).isInstanceOf(String.class);
        assertThat((String) tag).isEqualTo("Order[id=o-1, apiKey=[Filtered]]");
        assertThat(new FeedEvent("x", finalised).canonicalJson()).doesNotContain("sk-live-99887766");
    }

    /** FR-2 b names serialisation: a payload that cannot be written loses its event, not the host thread. */
    @Test
    void canonicalJsonAnswersNullRatherThanThrowingIntoTheHost() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "1.0");
        payload.put("unserialisable", new Order("o-1", "s"));

        assertNull(new FeedEvent("e-1", payload).canonicalJson());
    }

    /** A host that kept the map it was handed cannot edit an event a sender thread is serialising. */
    @Test
    void theEventPayloadIsNotAWindowIntoTheEvent() {
        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertThrows(UnsupportedOperationException.class, () -> payload.put("level", "info"));
    }

    // ----- Review finding 5: the in-app rule is structural --------------------------------------------

    @Test
    void theInAppRuleIsAPathSegmentAndNotASubstring() {
        assertFalse(EventFactory.isInApp("/app/vendor/acme/lib/Client.php"));
        assertFalse(EventFactory.isInApp("C:\\app\\vendor\\acme\\Client.java"), "the same on Windows");
        assertFalse(EventFactory.isInApp("/app/node_modules/x/index.js"));
        assertTrue(EventFactory.isInApp("/app/vendorish/Client.java"), "a path segment, not a substring");
        assertFalse(EventFactory.isInApp("tech.anvilcoder.feed.EventFactory"), "this SDK is never in app");
        assertFalse(EventFactory.isInApp("/src/main/java/tech/anvilcoder/feed/Scrubber.java"));
        assertTrue(EventFactory.isInApp("shop.CheckoutService"));
        assertFalse(EventFactory.isInApp(null));
        assertFalse(EventFactory.isInApp("  "));
    }

    // ----- Review finding 10: one number -------------------------------------------------------------

    @Test
    void theWireVersionIsThePublishedVersion() {
        assertEquals(System.getProperty("anvilfeed.version"), EventFactory.SDK_VERSION,
                "sdk.version and the Maven coordinate both come from gradle/libs.versions.toml");
    }

    // ----- Review finding 12: the public API is not null-hostile ---------------------------------------

    @Test
    void nothingACallerPassesTurnsIntoTheHostsSecondFailure() {
        EventFactory factory = factory("production");

        Map<String, Object> fromNullThrowable = factory.fromThrowable(null, "error", null).payload();
        assertEquals(EventFactory.MESSAGE_TYPE, map(fromNullThrowable.get("exception")).get("type"));
        assertThat((String) fromNullThrowable.get("title")).contains("without an exception");

        assertThat(factory.fromThrowable(CheckoutService.charge(), "error", null).payload()).isNotEmpty();
        assertThat(factory.fromMessage(null, null, null).payload().get("level")).isEqualTo("error");
    }

    /** A wiring mistake fails where the host can see it, not inside somebody else's incident. */
    @Test
    void aMisWiredFactoryFailsAtConstruction() {
        assertThrows(NullPointerException.class,
            () -> new EventFactory(null, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none()));
    }

    // ----- Review finding 15: context_code, where there is a source to read ----------------------------

    /**
     * A deployed jar has no sources and every frame answers null; a checkout has them, and an operator who
     * raises {@code context_lines} must see the effect the configuration reference promises. The test runs
     * from the module directory, so {@code src/test/java} is the root that is found.
     */
    @Test
    void contextCodeIsFilledForInAppFramesWhereASourceRootExists() {
        Assumptions.assumeTrue(Files.isDirectory(Path.of("src/test/java")),
                "no source root under the working directory — the deployed-jar case, covered below");

        Map<String, Object> payload = factory("production")
                .fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        List<Map<String, Object>> frames = frames(payload);
        assertThat((String) frames.get(0).get("context_code")).contains("Cart.total()");
        assertThat(frames).filteredOn(frame -> Boolean.FALSE.equals(frame.get("in_app")))
                .allSatisfy(frame -> assertFalse(frame.containsKey("context_code"),
                        "a dependency's source lines cost a file read and tell a repair agent nothing"));
    }

    @Test
    void contextLinesZeroSwitchesTheSourceLookupOffEntirely() {
        AnvilFeedConfig config = AnvilFeedConfig.builder()
                .token("anv_srv_x").service("shop-api").contextLines(0).build(FeedLogger.toSystemLogger());
        EventFactory factory =
                new EventFactory(config, new Scrubber(AnvilFeedConfig.DEFAULT_DENYLIST), GitContext.none());

        Map<String, Object> payload = factory.fromThrowable(CheckoutService.charge(), "error", Map.of()).payload();

        assertThat(frames(payload)).allSatisfy(frame -> assertFalse(frame.containsKey("context_code")));
    }

    /**
     * A host's own type — a plain bean with getters, which is what an entity in a {@code tags} map is.
     * Jackson's default mapper would have written {@code {"id":"o-1","apiKey":"sk-live-…"}} for it,
     * reflectively and without the scrubber ever seeing the field; {@link Json} has auto-detection off,
     * so it has no properties, no serialiser, and {@link EventFactory#finalise} turns it into text that
     * the scrubber CAN read.
     */
    private static final class Order {

        private final String id;
        private final String apiKey;

        Order(String id, String apiKey) {
            this.id = id;
            this.apiKey = apiKey;
        }

        public String getId() {
            return id;
        }

        public String getApiKey() {
            return apiKey;
        }

        @Override
        public String toString() {
            return "Order[id=" + id + ", apiKey=" + apiKey + "]";
        }
    }
}
