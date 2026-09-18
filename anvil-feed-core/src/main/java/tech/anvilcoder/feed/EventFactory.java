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
 */
public final class EventFactory {

    /**
     * The single source of {@code sdk.version}. It is what tells support which SDK a customer is running,
     * so it must not exist twice: Task 11's release check pins THIS constant against the released tag, and
     * the constructor default below is the only other place it is spelled.
     */
    public static final String SDK_VERSION = "0.1.0";

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

    /**
     * A frame outside these is the customer's own code. The list decides which frames the fingerprint sees
     * first, so a wrong entry here collapses every fault of a service into one incident.
     */
    private static final List<String> NOT_IN_APP = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.",
            "org.apache.", "ch.qos.", "org.slf4j.", "io.micrometer.", "com.fasterxml.", "org.junit.",
            "org.gradle.", "tech.anvilcoder.feed.");

    /** This SDK's own frames: never the customer's code, and never the head of a synthesised stack. */
    private static final String OUR_OWN = "tech.anvilcoder.feed.";

    private final AnvilFeedConfig config;
    private final Scrubber scrubber;
    private final GitContext git;
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

    public EventFactory(AnvilFeedConfig config, Scrubber scrubber, GitContext git,
                        String sdkName, String sdkVersion, String language) {
        this.config = config;
        this.scrubber = scrubber;
        this.git = git;
        this.sdkName = sdkName;
        this.sdkVersion = sdkVersion;
        this.language = language;
    }

    public FeedEvent fromThrowable(Throwable error) {
        return fromThrowable(error, "error", Map.of());
    }

    public FeedEvent fromThrowable(Throwable error, String level, Map<String, Object> context) {
        String type = error.getClass().getName();
        String message = error.getMessage() == null ? "" : error.getMessage();
        return build(title(type, message), level, exception(type, message, frames(error.getStackTrace())),
                context);
    }

    public FeedEvent fromMessage(String message) {
        return fromMessage(message, "warning", Map.of());
    }

    public FeedEvent fromMessage(String message, String level, Map<String, Object> context) {
        String text = message == null ? "" : message;
        return build(text, level, exception(MESSAGE_TYPE, text, callerFrames()), context);
    }

    public FeedEvent testEvent() {
        return build("Anvil Feed test event", "error",
                exception(TEST_TYPE, TEST_MESSAGE, callerFrames()), Map.of());
    }

    /**
     * Scrub, bound, prune — the three steps in the SERVER's own order
     * ({@code IncidentRedaction.redact}: redact, then truncate to the cap), as ONE callable step:
     *
     * <ol>
     *   <li>scrub — the only step that may GROW a value, {@code [Filtered]} being ten characters;</li>
     *   <li>bound — every cap applied to what scrubbing actually produced;</li>
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
     * denylist. {@link Scrubber#scrub} is idempotent, so a second pass cannot double-filter.
     */
    public Map<String, Object> finalise(Map<String, Object> payload) {
        Map<String, Object> result = dropUnsendable(boundTexts(scrubber.scrub(payload)));
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
     * The frame rule the fingerprint partitions on. Public and static because it is worth testing directly
     * and because the Spring adapter has to give the same answer.
     */
    public static boolean isInApp(String className) {
        return className != null && NOT_IN_APP.stream().noneMatch(className::startsWith);
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
            putIfPresent(cleaned, "method", request.get("method"));
            putIfPresent(cleaned, "url", request.get("url"));
            putIfPresent(cleaned, "status_code", request.get("status_code"));
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
            List<String> all = crumbs.stream().filter(Objects::nonNull).map(String::valueOf).toList();
            // The TAIL: breadcrumbs run oldest first and it is the last ones that explain the fault. The
            // ingest keeps the last MAX_BREADCRUMBS past its own cap and so do we.
            payload.put("breadcrumbs",
                    all.size() <= MAX_BREADCRUMBS ? all : all.subList(all.size() - MAX_BREADCRUMBS, all.size()));
        }
        if (context.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<?, ?> tag : tags.entrySet()) {
                if (cleaned.size() >= MAX_TAGS) {
                    break;
                }
                cleaned.put(String.valueOf(tag.getKey()), String.valueOf(tag.getValue()));
            }
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

    /** Every bound schema 1.0 states, applied AFTER scrubbing. */
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
     * <p>A JVM has no source files at runtime, so {@code context_code} is never filled here. The knob
     * exists for C17 — the parameter list is identical in both languages — and it has no effect in v0.1.
     * Chapter 03 section 4 has the server resolve the source from the repository anyway.
     */
    private List<Map<String, Object>> frames(StackTraceElement[] stack) {
        List<Map<String, Object>> frames = new ArrayList<>();
        for (StackTraceElement element : stack) {
            if (frames.size() >= config.maxFrames()) {
                break;
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("file", element.getFileName() == null ? element.getClassName() : element.getFileName());
            frame.put("line", Math.max(0, element.getLineNumber()));
            frame.put("function", element.getMethodName());
            frame.put("in_app", isInApp(element.getClassName()));
            frames.add(frame);
        }
        return List.copyOf(frames);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            target.put(key, value);
        }
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
