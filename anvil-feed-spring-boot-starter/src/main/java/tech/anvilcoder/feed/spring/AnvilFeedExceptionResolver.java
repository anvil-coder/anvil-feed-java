package tech.anvilcoder.feed.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import tech.anvilcoder.feed.AnvilFeedClient;

/**
 * FR-1: unhandled exceptions are captured with no code at the place they are raised — and <b>only</b>
 * unhandled ones.
 *
 * <h2>Two invariants of the class</h2>
 * <ol>
 *   <li>It runs at {@link Ordered#HIGHEST_PRECEDENCE}, so it sees the exception as it was raised, before
 *       any resolver can rewrite it into a response.</li>
 *   <li>It <b>always returns null</b>. Returning a {@link ModelAndView} would mean this SDK had handled
 *       the exception, and the application's own error page, its {@code @ControllerAdvice} and its problem
 *       details would all stop running. An error reporter that silently changes what a customer's API
 *       answers is a defect, not a feature.</li>
 * </ol>
 *
 * <p>That is also why {@code @ControllerAdvice} was not used, which is what
 * {@code docs/spec/03-fachliche-anforderungen.md} §5 names: an advice method must return something, and
 * whatever it returns IS the response.
 *
 * <h2>What becomes an incident — the rule, in full</h2>
 * FR-1 says <i>unbehandelte</i> exceptions, and the Laravel twin gates on
 * {@code ExceptionHandler::shouldReport()} for exactly that reason ({@code ReportingHandler.php:39}):
 * a 404, a validation failure and the rest of a framework's don't-report list are not incidents. Without
 * a gate, a public API files an incident for every {@code /favicon.ico}, every scanner probe and every
 * rejected form — and the rate gate then spends a real fault's budget on that noise.
 *
 * <table>
 *   <caption>The four classes</caption>
 *   <tr><th>Class</th><th>Example</th><th>Reported</th></tr>
 *   <tr><td>the exception DECLARES a 4xx</td>
 *       <td>{@code ResponseStatusException(404)}, {@code NoResourceFoundException},
 *           {@code @ResponseStatus(BAD_REQUEST)}</td><td><b>no</b> — a client error, whatever renders it</td></tr>
 *   <tr><td>the exception DECLARES a 5xx</td>
 *       <td>{@code ResponseStatusException(BAD_GATEWAY)}</td><td><b>yes</b> — whatever renders it</td></tr>
 *   <tr><td>everything else, and the request ends <b>below 500</b></td>
 *       <td>a {@code @ControllerAdvice} mapping {@code Declined → 402}</td>
 *       <td><b>no</b> — a deliberate answer</td></tr>
 *   <tr><td>everything else, and the request ends at <b>500 or above</b> — or nothing resolved it at all</td>
 *       <td>a {@code @ControllerAdvice} turning a {@code NullPointerException} into a 500
 *           {@code ProblemDetail}; an {@code IllegalStateException} out of a controller</td>
 *       <td><b>yes</b></td></tr>
 * </table>
 *
 * <p>"Declares" is {@link ErrorResponse} or {@code @ResponseStatus} — the exception's own type, never the
 * response, which is still 200 at this point. For everything else the authority is <b>the status the
 * client actually saw</b>, and that cannot be observed here: this resolver runs first, before any handler
 * has written anything. So a fault with no declared status is PARKED as an {@link AnvilFeedCandidate} and
 * {@link AnvilFeedRequestFilter} reaches the verdict one layer out, after the whole dispatch — which is
 * also the only place that sees a {@code response.sendError(500)} or a container's ERROR dispatch.
 *
 * <p>Deciding on the status rather than on who handled it is deliberate and was the correction of fix
 * round 2. A rule of "an application handler means not an incident" loses the commonest global-error-handler
 * idiom in Spring — a {@code @ControllerAdvice} that turns any unexpected {@code RuntimeException} into a
 * 500 {@code ProblemDetail} — and that is the one fault a repair agent most wants. It is also what
 * Laravel's {@code shouldReport()} does: rendering a custom response has never made a fault unreportable
 * there either.
 */
public final class AnvilFeedExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final AnvilFeedClient client;
    private final AnvilFeedIgnoredPaths ignoredPaths;

    public AnvilFeedExceptionResolver(AnvilFeedClient client, AnvilFeedIgnoredPaths ignoredPaths) {
        this.client = client;
        this.ignoredPaths = ignoredPaths;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception error) {
        try {
            if (ignoredPaths.ignores(request)) {
                return null;   // FR-2 d (b): dark-factory runs this starter AND hosts the ingest
            }
            Integer declared = AnvilFeedRequestContext.declaredStatusOf(error);
            if (declared != null && declared < 500) {
                return null;   // a client error is not an incident
            }

            AnvilFeedCandidate candidate = AnvilFeedCandidate.park(request, error,
                    AnvilFeedRequestContext.of(request, response, error));
            if (declared != null && candidate.claim() && AnvilFeedReported.firstTime(error)) {
                // A 5xx the exception itself declares is a server failure whatever happens next.
                client.captureException(error, candidate.context());
            }
        } catch (RuntimeException | Error ours) {
            // FR-2 b. Nothing from the SDK may reach the application, least of all from its error path.
        }
        return null;   // never handled here. See the class comment.
    }
}
