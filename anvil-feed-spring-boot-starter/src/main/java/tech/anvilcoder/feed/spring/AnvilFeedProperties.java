package tech.anvilcoder.feed.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code anvil.feed.*} in {@code application.yml} or {@code application.properties}. Every name is the
 * shared configuration name from {@code conformance/config-defaults.json}, in Spring's relaxed spelling —
 * {@code anvil.feed.max-queue-size}, {@code ANVIL_FEED_MAX_QUEUE_SIZE} and {@code maxQueueSize} are one
 * parameter, and {@code ConfigParityTest} holds the values together across both languages.
 *
 * <p><b>Every field here is unset by default, and that is deliberate.</b> The defaults live in exactly one
 * place — {@link tech.anvilcoder.feed.AnvilFeedConfig.Builder}, which is held against
 * {@code conformance/config-defaults.json} by the core's parity tests. Spelling {@code 1000} a second time
 * in this class would create a third home for a number that two languages already have to agree on, with
 * nothing red on the day the two drift apart: a boxed {@code null} means "the operator said nothing", the
 * builder is never called for it, and the core's default is the only one there is.
 * {@code AnvilFeedAutoConfigurationTest.theStarterSpellsNoDefaultOfItsOwn} pins that.
 *
 * <p>Three values are resolved rather than merely read, and {@link AnvilFeedAutoConfiguration} does it:
 * {@code token} also answers to {@code ANVIL_INGEST_TOKEN}, {@code service} falls back to
 * {@code spring.application.name}, and {@code environment} falls back to the first active Spring profile.
 */
@ConfigurationProperties(prefix = "anvil.feed")
public class AnvilFeedProperties {

    /** The ingest base URL. {@code /events} and {@code /events/batch} are appended. Also ANVIL_INGEST_ENDPOINT. */
    private String endpoint;
    /** The connection's ingest token. Without it the SDK is disabled, silently, logged once. Also ANVIL_INGEST_TOKEN. */
    private String token;
    /** The name incidents are filed under. Falls back to {@code spring.application.name}, then ANVIL_SERVICE. */
    private String service;
    /** The binding key. Falls back to the first active profile, then ANVIL_ENVIRONMENT. Repaired if it cannot bind. */
    private String environment;
    /** The deployed version. Falls back to ANVIL_RELEASE, then to the git checkout. */
    private String release;

    /** Events held in memory before the oldest is dropped. */
    private Integer maxQueueSize;
    /** How often the daemon pump empties the queue. */
    private Integer flushIntervalMs;
    /** Events per POST. */
    private Integer batchSize;
    private Integer connectTimeoutMs;
    private Integer requestTimeoutMs;
    /** Stack frames per event. */
    private Integer maxFrames;
    /** Source lines around an in-app frame. A deployed jar has no sources, so it usually has no effect. */
    private Integer contextLines;
    private Integer maxContextLineChars;
    /** The keys whose values never leave this process (FR-5). An EMPTY list is honoured and announced. */
    private List<String> denylist;
    /** Switches the whole starter off: no beans, no thread, no transport. */
    private boolean enabled = true;
    /** Events per fingerprint per window before the SDK stops sending that one (FR-2 d). */
    private Integer fingerprintCap;
    private Integer fingerprintWindowMs;
    /**
     * Exceptions raised while serving one of these are the ingest's own failure and are not reported.
     * The default is <code>["/api/feed/**"]</code> and it matters here: the host may BE the ingest.
     *
     * <p>Write the pattern WITHOUT the context path. A servlet request's URI includes
     * {@code server.servlet.context-path}, so an ingest deployed under {@code /shop} really serves
     * {@code /shop/api/feed/events} — the starter therefore applies every pattern twice, once to the raw
     * URI and once to the path within the application, and either match counts. A pattern written with
     * the context path still works; one written without it now works too, which is what everybody writes.
     */
    private List<String> ignoredRequestPaths;
    /**
     * The packages that are the application, for {@code exception.stacktrace[].in_app}. Unset means "the
     * package of the {@code @SpringBootApplication} class", which is what the auto-configuration seeds it
     * with; an explicitly EMPTY list means "decide structurally", the core's own rule.
     */
    private List<String> inAppPackages;
    /**
     * FR-8: send one test event at startup and print the ingest's verdict. Meant to be passed on the
     * command line for one run — {@code --anvil.feed.send-test-event=true} — not left on in production.
     */
    private boolean sendTestEvent;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getRelease() { return release; }
    public void setRelease(String release) { this.release = release; }
    public Integer getMaxQueueSize() { return maxQueueSize; }
    public void setMaxQueueSize(Integer maxQueueSize) { this.maxQueueSize = maxQueueSize; }
    public Integer getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(Integer flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
    public Integer getBatchSize() { return batchSize; }
    public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }
    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public Integer getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(Integer requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    public Integer getMaxFrames() { return maxFrames; }
    public void setMaxFrames(Integer maxFrames) { this.maxFrames = maxFrames; }
    public Integer getContextLines() { return contextLines; }
    public void setContextLines(Integer contextLines) { this.contextLines = contextLines; }
    public Integer getMaxContextLineChars() { return maxContextLineChars; }
    public void setMaxContextLineChars(Integer maxContextLineChars) {
        this.maxContextLineChars = maxContextLineChars;
    }
    public List<String> getDenylist() { return denylist; }
    public void setDenylist(List<String> denylist) { this.denylist = denylist; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getFingerprintCap() { return fingerprintCap; }
    public void setFingerprintCap(Integer fingerprintCap) { this.fingerprintCap = fingerprintCap; }
    public Integer getFingerprintWindowMs() { return fingerprintWindowMs; }
    public void setFingerprintWindowMs(Integer fingerprintWindowMs) {
        this.fingerprintWindowMs = fingerprintWindowMs;
    }
    public List<String> getIgnoredRequestPaths() { return ignoredRequestPaths; }
    public void setIgnoredRequestPaths(List<String> ignoredRequestPaths) {
        this.ignoredRequestPaths = ignoredRequestPaths;
    }
    public List<String> getInAppPackages() { return inAppPackages; }
    public void setInAppPackages(List<String> inAppPackages) { this.inAppPackages = inAppPackages; }
    public boolean isSendTestEvent() { return sendTestEvent; }
    public void setSendTestEvent(boolean sendTestEvent) { this.sendTestEvent = sendTestEvent; }
}
