package shop;

import tech.anvilcoder.feed.AnvilFeed;

import java.util.Map;

/**
 * Customer code with a credential on the line that fails, so that the {@code context_code} an in-app frame
 * carries can be proved to be scrubbed before it leaves the process — the one FR-5 path whose input is the
 * checkout's own source rather than anything a caller passed in.
 *
 * <p>It lives in its own file precisely because it is the file whose SOURCE is read: a probe for this rule
 * has to be a file nothing else in the suite moves lines around in.
 */
public final class SecretHolder {

    private SecretHolder() {
    }

    /** The throw site, with the secret on the neighbouring line the context window will carry. */
    public static String failWithACredentialInScope() {
        String api_key = "sk-live-99887766";
        return AnvilFeed.captureException(
                new IllegalStateException("charging failed for " + api_key.length() + " characters"),
                Map.of());
    }
}
