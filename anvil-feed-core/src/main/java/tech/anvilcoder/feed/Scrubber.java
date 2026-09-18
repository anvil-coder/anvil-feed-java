package tech.anvilcoder.feed;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FR-5: scrubbing happens in the SDK, before anything leaves the process — not on the server. Rule for
 * rule the same as {@code AnvilFeed\Scrubber} in the PHP SDK, and held to that by the same conformance
 * pair ({@code conformance/pii/raw.json} against {@code conformance/pii/expected.json}).
 *
 * <ol>
 *   <li>A map key that matches the denylist replaces its whole value, whatever the value is.</li>
 *   <li>{@code Bearer <value>} keeps the scheme and loses the value: a reader has to be able to tell what
 *       kind of credential was in play.</li>
 *   <li>{@code <denied key><separator><value>} loses the value and keeps the key.</li>
 * </ol>
 *
 * <p>Plus two absolutes that are not configurable: {@code request.url} is scrubbed parameter by parameter
 * (userinfo included), and {@code user.ip} / {@code user.ip_hash} are removed outright (FR-5 c). The
 * ingest forms the pseudonym from the connection address itself; a value we sent would be either a guess
 * or an address.
 *
 * <p>It is pattern work, not a proof. A secret with no recognisable shape survives, which is why the
 * server redacts again on its own side. What this buys is that it never leaves this process.
 */
public final class Scrubber {

    public static final String FILTERED = "[Filtered]";

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{3,}");

    /** {@code scheme://user:pass@host} keeps its shape and loses the credential. */
    private static final Pattern USERINFO = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/@]+)@");

    /** The marker the walk leaves on a request map whose URL was already scrubbed as a URL. */
    private static final String URL_DONE = "__url_done";

    private final List<String> normalizedTerms = new ArrayList<>();
    private final Pattern keyValue;

    public Scrubber(List<String> denylist) {
        List<String> terms = new ArrayList<>();
        // A denylist entry that is null or blank is ignored rather than used: it would compile into an
        // alternative that matches everything, and FR-2 b forbids raising out of this constructor.
        for (String raw : denylist == null ? List.<String>of() : denylist) {
            String term = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty()) {
                terms.add(term);
                normalizedTerms.add(term.replaceAll("[^a-z0-9]", ""));
            }
        }
        if (terms.isEmpty()) {
            terms = AnvilFeedConfig.DEFAULT_DENYLIST;
            for (String term : terms) {
                normalizedTerms.add(term.replaceAll("[^a-z0-9]", ""));
            }
        }
        // "api_key" also matches "api-key", "apiKey" and "API KEY"; "credit_card" likewise.
        String alternatives = String.join("|", terms.stream()
                .map(term -> Pattern.quote(term).replace("_", "\\E[-_ ]?\\Q"))
                .toList());
        this.keyValue = Pattern.compile("(?i)\\b(" + alternatives + ")\\b(\\s*[:=]\\s*)"
                + "(?!\\[Filtered\\])(?!Bearer\\b)([\"']?)([^\\s\"',;&)}\\]]+)\\3?");
    }

    /**
     * Idempotent: a second pass over an already scrubbed payload costs one walk and cannot double-filter.
     * That is what lets a {@code beforeSend} hook's output go through the very same pipeline.
     */
    public Map<String, Object> scrub(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);

        if (copy.get("user") instanceof Map<?, ?> user) {
            Map<String, Object> cleaned = asStringKeys(user);
            cleaned.remove("ip");
            cleaned.remove("ip_hash");
            copy.put("user", cleaned);
        }
        if (copy.get("tags") instanceof Map<?, ?> tags) {
            Map<String, Object> cleaned = asStringKeys(tags);
            cleaned.remove("user.ip_hash");
            copy.put("tags", cleaned);
        }
        if (copy.get("request") instanceof Map<?, ?> request && request.get("url") instanceof String url) {
            Map<String, Object> cleaned = asStringKeys(request);
            cleaned.put("url", scrubUrl(url));
            cleaned.put(URL_DONE, Boolean.TRUE);
            copy.put("request", cleaned);
        }

        Map<String, Object> scrubbed = walk(copy);
        if (scrubbed.get("request") instanceof Map<?, ?> request) {
            Map<String, Object> cleaned = asStringKeys(request);
            cleaned.remove(URL_DONE);
            scrubbed.put("request", cleaned);
        }
        return scrubbed;
    }

    public String scrubText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // The scheme survives; the value does not.
        String withoutBearer = BEARER.matcher(text).replaceAll("Bearer " + Matcher.quoteReplacement(FILTERED));

        Matcher matcher = keyValue.matcher(withoutBearer);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + matcher.group(3) + FILTERED + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * FR-5 e. Three things happen here and the order matters:
     *
     * <ol>
     *   <li>The fragment is split off first. It is not part of the query, and folding it into the last
     *       parameter's value ate it: {@code ?token=abc#section} became {@code ?token=[Filtered]}. A reader
     *       of the cockpit assumes the scrubbed URL is the sent URL minus its secrets, and it has to be
     *       exactly that.</li>
     *   <li>Userinfo is removed: {@code https://admin:hunter2@host/x} carries a credential with no key at
     *       all, so neither the parameter walk here nor {@code IncidentRedaction.KV_SECRET} on the server
     *       would ever see it.</li>
     *   <li>Parameters are scrubbed one by one, split on {@code &} AND {@code ;} — the legacy separator
     *       some stacks still emit — with the separator put back where it was.</li>
     * </ol>
     *
     * <p>Rebuilt by hand rather than through a URI builder, which would percent-encode the square brackets
     * and turn {@code [Filtered]} into {@code %5BFiltered%5D} in every cockpit.
     */
    public String scrubUrl(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash);
        String withoutFragment = hash < 0 ? url : url.substring(0, hash);

        int mark = withoutFragment.indexOf('?');
        String head = scrubUserInfo(mark < 0 ? withoutFragment : withoutFragment.substring(0, mark));
        String query = mark < 0 ? null : withoutFragment.substring(mark + 1);
        if (query == null || query.isEmpty()) {
            return head + (mark < 0 ? "" : "?") + fragment;
        }

        StringBuilder rebuilt = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= query.length(); i++) {
            boolean end = i == query.length();
            if (end || query.charAt(i) == '&' || query.charAt(i) == ';') {
                rebuilt.append(scrubQueryPair(query.substring(start, i)));
                if (!end) {
                    rebuilt.append(query.charAt(i));
                }
                start = i + 1;
            }
        }
        return head + '?' + rebuilt + fragment;
    }

    private String scrubQueryPair(String pair) {
        int equals = pair.indexOf('=');
        if (equals < 0) {
            return pair;
        }
        String key = pair.substring(0, equals);
        return keyIsDenied(decode(key)) ? key + '=' + FILTERED : pair;
    }

    /** A malformed percent escape is somebody's URL, not an exception: the raw key is judged instead. */
    private static String decode(String key) {
        try {
            return URLDecoder.decode(key, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return key;
        }
    }

    private static String scrubUserInfo(String head) {
        Matcher matcher = USERINFO.matcher(head);
        return matcher.find()
                ? matcher.group(1) + FILTERED + "@" + head.substring(matcher.end())
                : head;
    }

    private Map<String, Object> walk(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean urlDone = Boolean.TRUE.equals(value.get(URL_DONE));
        value.forEach((key, item) -> {
            if (keyIsDenied(key)) {
                result.put(key, FILTERED);
            } else if (item instanceof Map<?, ?> map) {
                result.put(key, walk(asStringKeys(map)));
            } else if (item instanceof List<?> list) {
                result.put(key, list.stream().map(this::walkElement).toList());
            } else if (item instanceof String text) {
                result.put(key, "url".equals(key) && urlDone ? text : scrubText(text));
            } else {
                result.put(key, item);
            }
        });
        return result;
    }

    private Object walkElement(Object element) {
        if (element instanceof Map<?, ?> map) {
            return walk(asStringKeys(map));
        }
        if (element instanceof List<?> list) {
            return list.stream().map(this::walkElement).toList();
        }
        if (element instanceof String text) {
            return scrubText(text);
        }
        return element;
    }

    /**
     * A SUBSTRING match on the normalised key — which is why no structural field name of schema 1.0 may
     * ever contain a denylist term, and why {@code ScrubberTest.doesNotFilterStructuralFields} has to be
     * re-run whenever the schema gains a field.
     */
    private boolean keyIsDenied(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String term : normalizedTerms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> asStringKeys(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
