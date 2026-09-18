package tech.anvilcoder.feed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns a Throwable, a message or a verification request into an event the ingest accepts.
 *
 * <p>The one thing worth knowing before reading it: the ingest requires {@code exception.type}. There is no
 * message-only event — a {@code captureMessage} without an exception is answered {@code schema_violation}
 * and is lost. So {@link #fromMessage} and {@link #testEvent} synthesise one with the caller's own stack,
 * and the type says plainly which of the two it was.
 *
 * <p>Every bound below is the ingest's own bound ({@code IncidentDelivery.MAX_*}), and where schema 1.0 is
 * stricter than the ingest it is the schema that wins: the ingest truncates a too-long value, the schema
 * refuses the event, and a serialiser that can produce a body the normative schema rejects is a defect
 * either way.
 *
 * <p>Immutable and safe to share across threads once constructed: every field is final and holds
 * immutable or effectively-immutable state.
 */
public final class EventFactory {

    /**
     * The single source of {@code sdk.version} — generated at build time from
     * {@code gradle/libs.versions.toml}, which is also where the published Maven coordinate comes from, so
     * the number a customer reads off a support ticket and the number on their dependency tree cannot
     * disagree (review finding 10).
     */
    public static final String SDK_VERSION = SdkVersion.VALUE;

    /** The single source of {@code sdk.name} for this language. */
    public static final String SDK_NAME = "anvil-feed-java";

    public static final String MESSAGE_TYPE = "AnvilFeed.Message";
    public static final String TEST_TYPE = "AnvilFeed.TestEvent";
    public static final String TEST_MESSAGE = "This event was produced by captureTestEvent() to verify the "
            + "integration. It is safe to ignore or close.";

    /** schema 1.0: title 512, exception.message 2000, everything short-text 1000. */
    private static final int MAX_TITLE_CHARS = 512;
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int MAX_SHORT_TEXT_CHARS = 1_000;
    private static final int MAX_BREADCRUMBS = 30;
    /**
     * 29, not the ingest's {@code MAX_TAGS} of 30: {@code AnvilFeedEventMapper.tags()} writes the server's
     * own {@code user.ip_hash} pseudonym into the first slot before any sender tag, so a sender that fills
     * thirty loses one of them — silently, in the cockpit, weeks later.
     */
    private static final int MAX_TAGS = 29;

    /** The wire's level enum. */
    private static final List<String> LEVELS = List.of("error", "warning", "fatal");

    /** This SDK's own frames: never the customer's code, and never the head of a synthesised stack. */
    private static final String OUR_OWN = "tech.anvilcoder.feed.";

    /** The same path segment PHP asks about, with both separators (review finding 5). */
    private static final Pattern VENDOR = Pattern.compile("[\\\\/](vendor|node_modules)[\\\\/]");

    /**
     * The runtime itself, which is nobody's application code. This is the structural half of the rule, not
     * a list of libraries: PHP's own {@code framesOf()} drops frames that have no file at all, which is
     * exactly what a PHP-internal call is, and these are the Java equivalent.
     */
    private static final List<String> RUNTIME = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    private final AnvilFeedConfig config;
    private final Scrubber scrubber;
    private final GitContext git;
    private final SourceContext sources;
    private final String sdkName;
    private final String sdkVersion;
    private final String language;

    public EventFactory(AnvilFeedConfig config, Scrubber scrubber, GitContext git) {
        this(config, scrubber, git, SDK_NAME, SDK_VERSION, "java");
    }

    public EventFactory(AnvilFeedConfig config, Scrubber scrubber, GitContext git,
                        String sdkName, String sdkVersion) {
        this(config, scrubber, git, sdkName, sdkVersion, "java");
    }

    /**
     * A wiring mistake fails HERE, where the host can see it at startup, rather than on the first captured
     * exception, where it would arrive as a {@code NullPointerException} out of the SDK in the middle of
     * somebody else's incident (review finding 12). Everything the SDK is handed AFTER construction is
     * tolerated instead: see {@link #fromThrowable} and {@link #fromMessage}.
     */
    public EventFactory(AnvilFeedConfig config, Scrubber scrubber, GitContext git,
                        String sdkName, String sdkVersion, String language) {
        this.config = Objects.requireNonNull(config, "config");
        this.scrubber = Objects.requireNonNull(scrubber, "scrubber");
        this.git = Objects.requireNonNull(git, "git");
        this.sdkName = Objects.requireNonNull(sdkName, "sdkName");
        this.sdkVersion = Objects.requireNonNull(sdkVersion, "sdkVersion");
        this.language = Objects.requireNonNull(language, "language");
        // Once, at construction. Doing it per event would put a directory probe on the capture path.
        this.sources = SourceContext.discover(config.contextLines(), config.maxContextLineChars());
    }

    public FeedEvent fromThrowable(Throwable error) {
        return fromThrowable(error, "error", Map.of());
    }

    /**
     * A {@code null} throwable is not an exception into the host: it produces a message event that says
     * what happened, which is the only outcome that keeps FR-2 b's promise and still tells the operator
     * their integration is calling the SDK wrongly.
     */
    public FeedEvent fromThrowable(Throwable error, String level, Map<String, Object> context) {
        Map<String, Object> given = context == null ? Map.of() : context;
        if (error == null) {
            return fromMessage("anvil-feed: captureException was called without an exception", level, given);
        }
        String type = error.getClass().getName();
        String message = error.getMessage() == null ? "" : error.getMessage();
        return build(title(type, message), level, exception(type, message, frames(error.getStackTrace())),
                given);
    }

    public FeedEvent fromMessage(String message) {
        return fromMessage(message, "warning", Map.of());
    }

    public FeedEvent fromMessage(String message, String level, Map<String, Object> context) {
        String text = message == null ? "" : message;
        return build(text, level, exception(MESSAGE_TYPE, text, callerFrames()),
                context == null ? Map.of() : context);
    }

    public FeedEvent testEvent() {
        return build("Anvil Feed test event", "error",
                exception(TEST_TYPE, TEST_MESSAGE, callerFrames()), Map.of());
    }

    /**
     * Coerce, scrub, bound, prune — the SERVER's own order
     * ({@code IncidentRedaction.redact}: redact, then truncate to the cap), as ONE callable step:
     *
     * <ol>
     *   <li>coerce — a value that is not one of the five wire shapes becomes its JSON text, so that the
     *       scrubber can see into it and Jackson never has to reflect over a host's object;</li>
     *   <li>scrub — the only step that may GROW a value, {@code [Filtered]} being ten characters;</li>
     *   <li>bound — every cap, of CHARACTERS and of COUNTS, applied to what scrubbing produced;</li>
     *   <li>drop — the members the contract refuses, and then the maps they leave empty.</li>
     * </ol>
     *
     * <p>Clipping first and redacting second is how a capped value climbs back over its cap: a captured
     * context window with one {@code password = "x"} in it becomes fourteen characters longer, and the
     * event is refused.
     *
     * <p>It is public because a {@code beforeSend} hook's output has to go through exactly the same
     * pipeline as the payload this factory built: FR-5 says the SDK redacts before sending, and the most
     * obvious use of such a hook is enrichment, which would otherwise reach the wire having never met the
     * denylist. {@link Scrubber#scrub} is idempotent and so is this method, so a second pass cannot
     * double-filter, cannot corrupt an already filtered value and cannot grow a value past its cap.
     *
     * <p>"Bound" is both kinds of bound. The counts used to live in {@link #build} alone, so a hook that
     * added a thirtieth tag to a full event passed straight through here — and {@code tags} is
     * {@code maxProperties: 29} in schema 1.0, so the ingest answered {@code schema_violation} and the
     * event was lost for a field the sender never noticed (review finding 3).
     */
    public Map<String, Object> finalise(Map<String, Object> payload) {
        Map<String, Object> result = dropUnsendable(
                boundCounts(boundTexts(scrubber.scrub(coerceValues(payload)))));
        // The level enum is re-applied HERE and not only in build(): finalise() is also the second pass a
        // beforeSend result takes, and a hook that writes level "info" would otherwise reach the wire —
        // the ingest stores it, so nothing complains (conformance/sdk-forbidden/level-info.json).
        result.put("level", coerceLevel(result.get("level")));
        return result;
    }

    /** Anything outside the wire's enum is an error, which is what an unknown severity means to a repair. */
    public static String coerceLevel(Object level) {
        return level instanceof String text && LEVELS.contains(text) ? text : "error";
    }

    /**
     * The frame rule the fingerprint partitions on — PHP's rule, structural rather than a list of library
     * names: a file under a dependency directory is not the application (both separators, so a Windows
     * developer machine and a Linux production pod fingerprint the same fault identically), and this SDK
     * is never the customer's code wherever it is installed.
     *
     * <p>Public and static because it is worth testing directly and because the Spring adapter has to give
     * the same answer. See {@link #frameIsInApp} for the second, Java-only half: the runtime.
     */
    public static boolean isInApp(String fileOrClass) {
        if (fileOrClass == null || fileOrClass.isBlank()) {
            return false;
        }
        String normalized = fileOrClass.replace('\\', '/');
        if (normalized.startsWith(OUR_OWN) || normalized.contains("/tech/anvilcoder/feed/")) {
            return false;
        }
        return !VENDOR.matcher(fileOrClass).find();
    }

    /** A canonical UUID v4. The ingest refuses anything else, however reasonable it looks. */
    public static String uuid4() {
        return UUID.randomUUID().toString();
    }

    private FeedEvent build(String title, String level, Map<String, Object> exception,
                            Map<String, Object> context) {
        String id = uuid4();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "1.0");
        payload.put("event_id", id);
        payload.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        // title is required and must not be blank. A caught exception with no message and an empty log
        // line are both real calls, and neither may cost the event.
        payload.put("title", title == null || title.isBlank() ? String.valueOf(exception.get("type")) : title);
        payload.put("level", coerceLevel(level));
        payload.put("environment", config.environment());
        payload.put("service", config.service());
        payload.put("language", language);
        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", sdkName);
        sdk.put("version", sdkVersion);
        // Not an enum at the ingest: the server's own golden example sends "jdk-25" and nothing validates
        // this field. "server" is what chapter 03 section 3 documents, so that is what is sent.
        sdk.put("runtime", "server");
        payload.put("sdk", sdk);
        payload.put("exception", exception);

        String release = config.release() != null ? config.release() : git.release();
        if (release != null) {
            payload.put("release", release);
        }
        if (context.get("request") instanceof Map<?, ?> request && !request.isEmpty()) {
            // The URL goes in as it came. It is scrubbed (userinfo and parameters) by Scrubber.scrubUrl
            // and only THEN checked for an http(s) scheme, in dropUnsendable() — FR-5 e before the shape
            // rule, so a credential can never survive on the grounds that the URL was going to be dropped.
            Map<String, Object> cleaned = new LinkedHashMap<>();
            putIfPresent(cleaned, "method", asText(request.get("method")));
            putIfPresent(cleaned, "url", asText(request.get("url")));
            // schema 1.0 types this as an INTEGER and PHP casts it. A filter that read the status off a
            // header, or a value that took a JSON or properties hop, hands over the String "500" — and an
            // event carrying "500" is answered schema_violation and lost (review finding 2).
            Integer status = asInteger(request.get("status_code"));
            if (status != null) {
                cleaned.put("status_code", status);
            }
            if (!cleaned.isEmpty()) {
                payload.put("request", cleaned);
            }
        }
        if (context.get("user_id") instanceof String userId && !userId.isBlank()) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", userId);   // an id, never a name and never an address
            payload.put("user", user);
        }
        if (context.get("breadcrumbs") instanceof List<?> crumbs && !crumbs.isEmpty()) {
            // NOT cut here, and not stringified with toString: boundCounts() is the one place every count
            // cap is applied, and Json.text is what PHP's json_encode fallback produces.
            payload.put("breadcrumbs", crumbs.stream().filter(Objects::nonNull).map(Json::text).toList());
        }
        if (context.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            tags.forEach((key, value) -> cleaned.put(String.valueOf(key), Json.text(value)));
            payload.put("tags", cleaned);
        }
        if (git.repo() != null || git.branch() != null) {
            Map<String, Object> agentContext = new LinkedHashMap<>();
            agentContext.put("suggested_ticket_type", "bug");
            putIfPresent(agentContext, "repo", git.repo());
            putIfPresent(agentContext, "branch", git.branch());
            payload.put("agent_context", agentContext);
        }

        return new FeedEvent(id, FeedEvent.inCanonicalOrder(finalise(payload)));
    }

    /**
     * A value that is not one of the five shapes schema 1.0 knows becomes its JSON text.
     *
     * <p>Java-only, and it is Jackson that makes it necessary: a mapper serialises an unknown type
     * reflectively, so a hook that puts a host's entity into {@code tags} would put the entity's fields on
     * the wire — fields the {@link Scrubber} never saw, because it descends only into {@code Map},
     * {@code List} and {@code String}. Turning it into text first closes that hole (review finding 4 b),
     * keeps the event instead of losing it, and gives the scrubber something it can actually read.
     */
    private static Map<String, Object> coerceValues(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> result.put(key, coerceValue(value)));
        return result;
    }

    private static Object coerceValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), coerceValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EventFactory::coerceValue).toList();
        }
        return Json.isWireValue(value) ? value : Json.text(value);
    }

    /** Every bound schema 1.0 states on a VALUE, applied AFTER scrubbing. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> boundTexts(Map<String, Object> payload) {
        if (payload.get("title") instanceof String title) {
            payload.put("title", clip(title, MAX_TITLE_CHARS));
        }
        if (payload.get("service") instanceof String service) {
            payload.put("service", clip(service, AnvilFeedConfig.MAX_SERVICE_CHARS));
        }
        if (payload.get("exception") instanceof Map<?, ?> exception) {
            Map<String, Object> bounded = new LinkedHashMap<>((Map<String, Object>) exception);
            if (bounded.get("message") instanceof String message) {
                bounded.put("message", clip(message, MAX_MESSAGE_CHARS));
            }
            if (bounded.get("stacktrace") instanceof List<?> frames) {
                List<Object> boundedFrames = new ArrayList<>();
                for (Object frame : frames) {
                    if (frame instanceof Map<?, ?> map && map.get("context_code") instanceof String code) {
                        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                        copy.put("context_code", clip(code, MAX_SHORT_TEXT_CHARS));
                        boundedFrames.add(copy);
                    } else {
                        boundedFrames.add(frame);
                    }
                }
                bounded.put("stacktrace", List.copyOf(boundedFrames));
            }
            payload.put("exception", bounded);
        }
        if (payload.get("request") instanceof Map<?, ?> request && request.get("url") instanceof String url) {
            Map<String, Object> bounded = new LinkedHashMap<>((Map<String, Object>) request);
            bounded.put("url", clip(url, MAX_SHORT_TEXT_CHARS));
            payload.put("request", bounded);
        }
        if (payload.get("breadcrumbs") instanceof List<?> crumbs) {
            payload.put("breadcrumbs", crumbs.stream()
                    .map(crumb -> crumb instanceof String text ? clip(text, MAX_SHORT_TEXT_CHARS) : crumb)
                    .toList());
        }
        if (payload.get("tags") instanceof Map<?, ?> tags) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            tags.forEach((key, value) -> bounded.put(String.valueOf(key),
                    value instanceof String text ? clip(text, MAX_SHORT_TEXT_CHARS) : value));
            payload.put("tags", bounded);
        }
        return payload;
    }

    /**
     * Every bound that is a COUNT, in the direction the server keeps: tags the first 29 by insertion order
     * ({@code AnvilFeedEventMapper.tags()} spends the first of its thirty slots on its own pseudonym),
     * breadcrumbs the last 30 ({@code IncidentDelivery.redactedBreadcrumbs} keeps the tail, and it is the
     * tail that explains the fault), frames the first {@code max_frames} of a most-recent-first stack
     * ({@code IncidentDelivery.redactedFrames} keeps the head, and frame 0 is the crash site).
     *
     * <p>This is the ONLY place they are applied. {@link #build} used to cut the tags and the breadcrumbs
     * itself while {@link #finalise} re-applied nothing, so the second pipeline pass — the one a
     * {@code beforeSend} result takes, and the one whose contract says it is the same pipeline — let a
     * thirtieth tag, a thirty-first breadcrumb and a fifty-first frame reach the wire, where schema 1.0
     * refuses all three and the event is lost. One helper rather than two copies is what keeps the two
     * passes from drifting apart again.
     *
     * <p>{@link #frames} still stops collecting at {@code max_frames}, and that is not a second copy of
     * this rule: it is there so that the frames beyond the cap never cost a source read for a
     * {@code context_code} this method would throw away.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> boundCounts(Map<String, Object> payload) {
        if (payload.get("tags") instanceof Map<?, ?> tags && tags.size() > MAX_TAGS) {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (Map.Entry<?, ?> tag : tags.entrySet()) {
                if (kept.size() >= MAX_TAGS) {
                    break;
                }
                kept.put(String.valueOf(tag.getKey()), tag.getValue());
            }
            payload.put("tags", kept);
        }
        if (payload.get("breadcrumbs") instanceof List<?> crumbs && crumbs.size() > MAX_BREADCRUMBS) {
            payload.put("breadcrumbs",
                    List.copyOf(crumbs.subList(crumbs.size() - MAX_BREADCRUMBS, crumbs.size())));
        }
        if (payload.get("exception") instanceof Map<?, ?> exception
                && exception.get("stacktrace") instanceof List<?> frames
                && frames.size() > config.maxFrames()) {
            Map<String, Object> bounded = new LinkedHashMap<>((Map<String, Object>) exception);
            bounded.put("stacktrace", List.copyOf(frames.subList(0, config.maxFrames())));
            payload.put("exception", bounded);
        }
        return payload;
    }

    /**
     * What the contract refuses, and what is left when everything inside a block was refused.
     *
     * <p>The second half is the part that is easy to miss: every one of these blocks is typed
     * {@code object} in schema 1.0, and a request context carrying only a {@code file://} URL, or tags
     * carrying only the ingest's own {@code user.ip_hash}, leaves an EMPTY one behind — and the whole
     * event is then refused for a field the sender never needed to send.
     */
    private static Map<String, Object> dropUnsendable(Map<String, Object> payload) {
        // schema 1.0 requires http(s). A file:// path is somebody's local filesystem and the ingest never
        // asked for it; the documented intent is that the event survives without the URL.
        if (payload.get("request") instanceof Map<?, ?> request && request.containsKey("url")) {
            Map<String, Object> copy = new LinkedHashMap<>();
            request.forEach((key, value) -> copy.put(String.valueOf(key), value));
            if (!(copy.get("url") instanceof String url)
                    || !url.toLowerCase(Locale.ROOT).matches("^https?://.*")) {
                copy.remove("url");
            }
            payload.put("request", copy);
        }
        for (String block : List.of("request", "user", "tags", "agent_context", "enrichment", "sdk")) {
            if (payload.get(block) instanceof Map<?, ?> map && map.isEmpty()) {
                payload.remove(block);
            }
        }
        return payload;
    }

    private static Map<String, Object> exception(String type, String message,
                                                 List<Map<String, Object>> frames) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", type);
        if (!message.isEmpty()) {
            exception.put("message", message);   // bounded after scrubbing, in boundTexts()
        }
        if (!frames.isEmpty()) {
            exception.put("stacktrace", frames);
        }
        return exception;
    }

    /**
     * The stack of whoever called us, with this SDK's own frames removed from the front.
     *
     * <p>A synthesised exception whose stack starts inside anvil-feed is worse than useless twice over: the
     * operator reads three of OUR frames before their own, and {@link #isInApp} decides the promotion the
     * fingerprint partitions on — so those constant frames would collapse every {@code captureMessage()}
     * of an application into a single incident.
     *
     * <p><b>What it costs, and where.</b> {@code getStackTrace()} materialises EVERY frame — in a Spring
     * request routinely 80 to 150 {@link StackTraceElement}s — to keep at most {@code max_frames}, and
     * task 8's ruling (message and test events carry the caller's frames) puts that on every
     * {@code captureMessage} and {@code captureTestEvent}, on the path Z4 bounds at p99 under one
     * millisecond. {@code captureException} does not pay it: a Throwable already carries its own stack.
     * {@code StackWalker.getInstance().walk(s -> s.dropWhile(...).limit(max_frames)...)} does the same job
     * without the tail and is the change to make if a measurement ever says this matters; it is not made
     * here because it is a rewrite of a Task 6 method that no measurement has asked for
     * (task-8 review finding 7).
     */
    private List<Map<String, Object>> callerFrames() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int first = 0;
        while (first < stack.length && stack[first].getClassName().startsWith(OUR_OWN)) {
            first++;
        }
        if (first >= stack.length) {
            // Nothing outside this SDK called us. Inventing a stack out of our own files is the defect
            // above; the event then groups by its title, which is the documented shape without frames.
            return List.of();
        }
        StackTraceElement[] caller = new StackTraceElement[stack.length - first];
        System.arraycopy(stack, first, caller, 0, caller.length);
        return frames(caller);
    }

    /**
     * Most recent call first — {@code stacktrace[0]} is the crash site, which is what
     * {@code IncidentFingerprint.topFrames} and the cockpit inbox both read.
     *
     * <p>{@code context_code} is filled for in_app frames only and only where a source root was found at
     * construction (see {@link SourceContext}): a deployed jar has no sources and every lookup there is
     * one boolean, a checkout has them and an operator who raises {@code context_lines} sees the effect
     * the configuration reference promises. A dependency's source lines would cost a file read per frame
     * on the request path and tell a repair agent nothing it can change — the same rule PHP adopted in
     * {@code perf(php): context code is read for in_app frames only}.
     */
    private List<Map<String, Object>> frames(StackTraceElement[] stack) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (StackTraceElement element : stack) {
            if (frames.size() >= config.maxFrames()) {
                break;
            }
            String file = element.getFileName() == null ? element.getClassName() : element.getFileName();
            boolean inApp = frameIsInApp(element);
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("file", file);
            frame.put("line", Math.max(0, element.getLineNumber()));
            frame.put("function", element.getMethodName());
            frame.put("in_app", inApp);
            if (inApp) {
                String code = sources.codeFor(element.getClassName(), element.getFileName(),
                        element.getLineNumber());
                if (code != null) {
                    frame.put("context_code", code);   // bounded after scrubbing, in boundTexts()
                }
            }
            frames.add(frame);
        }
        return List.copyOf(frames);
    }

    /**
     * {@link #isInApp} plus the one thing a Java stack has and a PHP one does not: the runtime's own
     * frames carry a class name and no dependency path, so the structural question has to be asked of the
     * class name as well.
     */
    private static boolean frameIsInApp(StackTraceElement element) {
        String className = element.getClassName();
        for (String prefix : RUNTIME) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return isInApp(className) && isInApp(element.getFileName() == null ? className : element.getFileName());
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            target.put(key, value);
        }
    }

    /** {@code request.method} is typed {@code string}; a caller may hand over anything. */
    private static String asText(Object value) {
        return value == null ? null : Json.text(value);
    }

    /**
     * @return the status as the schema's {@code integer}, or {@code null} when it is not one.
     *
     * <p>Two rules, and every arm below shares both. <b>Integrality</b>: a floating-point value is only a
     * status code when it IS a whole number — {@code 200.0} is the 200 a JSON or properties hop turned into
     * a double, and {@code 200.5} is not a status at all. <b>Range</b>: a whole number outside a signed
     * 32-bit int is not one either, and it is DROPPED rather than wrapped.
     *
     * <p>Both rules exist because the same defect was found twice. {@code intValue()} alone truncated
     * {@code 200.5} to 200, {@code NaN} to 0 and {@code Infinity} to 2147483647; then, with the float arm
     * bounded, the remaining {@code Number} arm still turned a {@code Long} of 5000000000 into 705032704.
     * Each of those is a status nobody sent, shown in a cockpit as a fact the caller never stated. The
     * string arm has always dropped an out-of-range value ({@code Integer.valueOf} throws), so the shared
     * {@link #withinAnInt(long)} below is what makes the other arms give the same answer rather than a
     * differently-shaped one.
     *
     * <p>PHP's {@code asInteger} bounds its float branch at {@code PHP_INT_MIN/MAX}, which is 64-bit; its
     * integer and string branches are unbounded. The 32-bit bound is the one schema 1.0's readers actually
     * have, and it is on the task-9 parity list for PHP to adopt.
     */
    private static Integer asInteger(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double asDouble = ((Number) value).doubleValue();
            if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)) {
                return null;
            }
            // (long) saturates at Long.MIN/MAX for a value that large, which withinAnInt then drops.
            return withinAnInt((long) asDouble);
        }
        if (value instanceof java.math.BigDecimal decimal) {
            try {
                return withinAnInt(decimal.longValueExact());
            } catch (ArithmeticException notAWholeNumberOrTooLarge) {
                return null;
            }
        }
        if (value instanceof java.math.BigInteger big) {
            try {
                return withinAnInt(big.longValueExact());
            } catch (ArithmeticException tooLarge) {
                return null;
            }
        }
        if (value instanceof Number number) {
            return withinAnInt(number.longValue());
        }
        if (value instanceof String text && INTEGER.matcher(text.trim()).matches()) {
            try {
                return withinAnInt(Long.parseLong(text.trim()));
            } catch (NumberFormatException moreDigitsThanALongHolds) {
                return null;
            }
        }
        // Dropped rather than shipped: a status the SDK cannot read is one member of an optional block,
        // and sending it as a string would cost the whole event.
        return null;
    }

    /**
     * The ONE 32-bit bound every arm of {@link #asInteger} shares. Out of range is dropped, never wrapped:
     * a status the caller never stated is worse than an absent optional field, and the field IS optional.
     */
    private static Integer withinAnInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? Integer.valueOf((int) value) : null;
    }

    private static String title(String type, String message) {
        return message.isEmpty() ? type : type + ": " + message;
    }

    /**
     * Counted in CODE POINTS, because that is what JSON Schema's {@code maxLength} counts and what the
     * ingest's own cap counts; cutting at a char index would split a surrogate pair and put half an emoji
     * on the wire.
     */
    private static String clip(String value, int max) {
        if (value.length() <= max) {
            return value;   // fast path: a char count at or below the cap bounds the code-point count too
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max - 12)) + " [truncated]";
    }
}
