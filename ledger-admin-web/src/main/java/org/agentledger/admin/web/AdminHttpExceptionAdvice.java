// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.error.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one handler of HTTP errors of the admin web. The handler of Spring renders the standard exceptions (and the
 * {@code ErrorResponseException} of the size check) as problem details; this class adds the business failures of
 * {@link LedgerException} (also the ones that the security filters hand over through the
 * {@code HandlerExceptionResolver}), the integrity violations and a last catch-all that logs the failure with a trace
 * id and answers with a plain sentence.
 *
 * <p>A browser that navigates (the request accepts {@code text/html}) gets the {@code error} page with the same
 * status — or, when a POST form handler registered an HTML recovery target via {@link #recoverHtml} and the failure is
 * a 4xx client error, re-renders that form page with the inline error banner and preserved user input. Everything else
 * gets the problem detail. The page can only be rendered inside the dispatcher: a failure that a filter hands over
 * before the dispatcher runs is always a problem detail.
 */
@RestControllerAdvice
public class AdminHttpExceptionAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminHttpExceptionAdvice.class);
    private static final String RECOVERY_ATTRIBUTE = AdminHttpExceptionAdvice.class.getName() + ".htmlFormRecovery";

    private record HtmlRecovery(String view, Supplier<Map<String, Object>> model) {
    }

    private final AdminProblems problems;
    private final LocaleResolver locales;

    public AdminHttpExceptionAdvice(AdminProblems problems, LocaleResolver localeResolver) {
        this.problems = problems;
        this.locales = localeResolver;
    }

    /**
     * Registers a form page to re-render with an inline error banner and preserved inputs if this request fails with a
     * 4xx {@link LedgerException} for a browser accepting {@code text/html}. JSON clients are completely unaffected.
     */
    public static void recoverHtml(HttpServletRequest request, String view, Supplier<Map<String, Object>> model) {
        request.setAttribute(RECOVERY_ATTRIBUTE, new HtmlRecovery(view, model));
    }

    /** Every text field of a form is trimmed, and a field that is left empty is absent (null), so that "required" means it. */
    @InitBinder
    void trimText(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }

    @ExceptionHandler(LedgerException.class)
    Object ledger(LedgerException failure, HttpServletRequest request) {
        ProblemDetail problem = problems.problem(failure, locales.resolveLocale(request));
        if (failure instanceof InfrastructureException infrastructure
                && infrastructure.code() != InfrastructureCode.DRIVER_INVALID
                && infrastructure.code() != InfrastructureCode.DRIVER_NOT_CONFIGURED) {
            String traceId = problems.newTraceId();
            problem.setProperty("traceId", traceId);
            LOG.error("the request failed with {} (trace {})", failure.code().token(), traceId, failure);
        } else {
            LOG.debug("the request was refused with {}", failure.code().token(), failure);
        }
        return render(problem, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    Object integrity(DataIntegrityViolationException failure, HttpServletRequest request) {
        return ledger(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of(), failure),
                request);
    }

    /** The boundary: anything unexpected is logged with its cause and answered without any internal detail. */
    @ExceptionHandler(Exception.class)
    Object unexpected(Exception failure, HttpServletRequest request) {
        return ledger(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), failure),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(failure, body, headers, status, request);
        // the base class makes the problem of a standard exception here (the body that is passed in is null), so the
        // code is added to what it answers with
        if (response != null && response.getBody() instanceof ProblemDetail problem && problem.getProperties() == null) {
            problem.setProperty("code", HttpErrorCode.of(status).name());
        }
        return response;
    }

    private Object render(ProblemDetail problem, HttpServletRequest request) {
        if (request.getAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE) != null && navigates(request)) {
            int status = problem.getStatus();
            Object code = problem.getProperties() == null ? "" : problem.getProperties().get("code");
            if (status >= 400 && status < 500
                    && request.getAttribute(RECOVERY_ATTRIBUTE) instanceof HtmlRecovery recovery) {
                Map<String, Object> model = new LinkedHashMap<>(recovery.model().get());
                model.put("formErrorDetail", problem.getDetail());
                model.put("formErrorCode", code);
                return new ModelAndView(recovery.view(), model, HttpStatus.valueOf(status));
            }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("status", status);
            model.put("code", code);
            model.put("detail", problem.getDetail());
            model.put("traceId", problem.getProperties() == null ? null : problem.getProperties().get("traceId"));
            return new ModelAndView("error", model, HttpStatus.valueOf(status));
        }
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** A browser navigation names {@code text/html} in its Accept header; an API client does not. */
    private static boolean navigates(HttpServletRequest request) {
        return new ServletServerHttpRequest(request).getHeaders().getAccept().stream()
                .anyMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
    }
}
