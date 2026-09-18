package tech.anvilcoder.feed.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What was being served when it broke — built once, used by both servlet capture points.
 *
 * <p>Three decisions are taken here and all three were taken in the Laravel package first, which is the
 * tie-breaker whenever this starter and the brief disagree:
 *
 * <ol>
 *   <li><b>{@code url} is the full URL including the query</b> (FR-5 e). {@code getRequestURL()} drops the
 *       query string, and a password-reset link's {@code ?token=} is exactly the value that must not
 *       travel — so it is handed to the core WITH the query and the scrubber filters it parameter by
 *       parameter. It is also what the loop guard is applied to.</li>
 *   <li><b>{@code status_code} is filled only where it is genuinely known</b> (FR-6 c). At the moment an
 *       exception reaches a {@code HandlerExceptionResolver} the response is still 200: the application's
 *       own error handling has not run yet. A guessed {@code 500} would be a fact the cockpit shows and
 *       nobody checked — so the status comes from the exception when the exception carries one
 *       ({@link ErrorResponse}, {@code @ResponseStatus}) or from a response that has already been given a
 *       failing status, and is otherwise left out.</li>
 *   <li><b>The route is a TAG, not part of the request block</b>, because schema 1.0's request block has
 *       exactly three members and because the route pattern is what an operator groups and filters by.
 *       The PATTERN ({@code /orders/{id}}) and not the URI: one incident per route, not one per id.</li>
 * </ol>
 *
 * <p>No user id. Spring Security's principal is routinely a user name or an e-mail address and FR-5
 * forbids both; an application that has a stable opaque id adds it through {@code beforeSend}.
 */
final class AnvilFeedRequestContext {

    private AnvilFeedRequestContext() {
    }

    /** Never throws: a context that cannot be read costs the context, never the event. */
    static Map<String, Object> of(HttpServletRequest request, HttpServletResponse response, Throwable error) {
        Map<String, Object> context = new LinkedHashMap<>();
        try {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("method", request.getMethod());
            block.put("url", url(request));
            Integer status = statusOf(error, response);
            if (status != null) {
                block.put("status_code", status);
            }
            context.put("request", block);

            String route = route(request);
            if (route != null) {
                context.put("tags", Map.of("route", route));
            }
        } catch (RuntimeException | Error ours) {
            // A half-built context is better than no event. FR-2 b.
        }
        return context;
    }

    private static String url(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getRequestURL());
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }

    /**
     * The route PATTERN Spring matched, or null when nothing was matched (a 404, a filter failure).
     *
     * <p>ONE branch, deliberately. The earlier shape had a blank guard and then a fallback line that
     * returned the same blank value anyway, so an empty pattern travelled as {@code tags: {"route": ""}}.
     * FR-6 (c) refuses placeholder values in a field that could not be determined, and an empty tag is
     * the same species: it is a filter an operator can select and never a fact.
     */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return null;
        }
        String text = String.valueOf(pattern);
        return text.isBlank() ? null : text;
    }

    /**
     * The status the EXCEPTION ITSELF declares, or null — and nothing else.
     *
     * <p>This is the gate {@link AnvilFeedExceptionResolver} decides on, so it may not fall back to the
     * response: at the moment an exception reaches a resolver the response is still 200, and a rule that
     * read it there would classify every fault as a server failure. Spring's whole
     * {@code DefaultHandlerExceptionResolver} family implements {@link ErrorResponse}
     * ({@code NoResourceFoundException}, {@code MissingServletRequestParameterException},
     * {@code HttpRequestMethodNotSupportedException}, {@code HttpMessageNotReadableException}, …), so one
     * question answers for all of them.
     */
    static Integer declaredStatusOf(Throwable error) {
        if (error instanceof ErrorResponse carried) {
            return carried.getStatusCode().value();
        }
        if (error != null) {
            ResponseStatus annotated = AnnotatedElementUtils
                    .findMergedAnnotation(error.getClass(), ResponseStatus.class);
            if (annotated != null) {
                return annotated.code().value();
            }
        }
        return null;
    }

    /** The status this fault is KNOWN to produce, or null. See the class comment, point 2. */
    private static Integer statusOf(Throwable error, HttpServletResponse response) {
        Integer declared = declaredStatusOf(error);
        if (declared != null) {
            return declared;
        }
        int written = response == null ? 0 : response.getStatus();
        return written >= 400 ? written : null;
    }
}
