package tech.anvilcoder.feed.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import tech.anvilcoder.feed.AnvilFeedClient;

/**
 * FR-1: unhandled exceptions are captured with no code at the place they are raised.
 *
 * <p>Two properties of this class are the whole of it, and both are easy to get wrong:
 *
 * <ol>
 *   <li>It runs at {@link Ordered#HIGHEST_PRECEDENCE}, so it sees the exception before any application
 *       resolver can turn it into a response.</li>
 *   <li>It <b>always returns null</b>. Returning a {@link ModelAndView} would mean this SDK had handled
 *       the exception, and the application's own error page, its {@code @ControllerAdvice} and its problem
 *       details would all stop running. An error reporter that silently changes what a customer's API
 *       answers is a defect, not a feature.</li>
 * </ol>
 *
 * <p>It is also why {@code @ControllerAdvice} was not used, which is what
 * {@code docs/spec/03-fachliche-anforderungen.md} §5 names for Spring Boot: an advice method must return
 * something, and whatever it returns IS the response. The requirement is that unhandled exceptions become
 * events without the application writing code; a resolver at highest precedence meets it without taking
 * the application's error handling away. FR-2 (a) — the SDK never changes what the host answers.
 */
public final class AnvilFeedExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final AnvilFeedClient client;

    public AnvilFeedExceptionResolver(AnvilFeedClient client) {
        this.client = client;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception error) {
        try {
            if (AnvilFeedReported.firstTime(error)) {
                client.captureException(error, AnvilFeedRequestContext.of(request, response, error));
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b. Nothing from the SDK may reach the application, least of all from its error path.
        }
        return null;   // never handled here. See the class comment.
    }
}
