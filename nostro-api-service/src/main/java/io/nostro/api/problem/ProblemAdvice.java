package io.nostro.api.problem;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The framework's Problem Details handler, with two additions (ADR-0014).
 *
 * <p>A request the framework refuses before any handler runs — a body it cannot read, a path
 * variable it cannot convert — is a {@code 400} whose type would be {@code about:blank}; here it is
 * {@code malformed}, the same type a handler gives a body it could read but not use, so a client
 * has one type to switch on for "fix the request". Every other framework refusal keeps
 * {@code about:blank}: for a {@code 405} or a {@code 415} the status is the whole story.
 *
 * <p>Anything else that escapes a handler is a {@code 500} with no detail: what broke is for the
 * log, not the client. The security layer's exceptions are not "anything else" — they are
 * rethrown untouched so the filter chain answers them as {@code 401} and {@code 403}.
 */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    /**
     * Every framework refusal passes through here with its body built, whichever handler built it;
     * {@code handleExceptionInternal} sees a null body for most of them and is the wrong seam.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getStatus() == HttpStatus.BAD_REQUEST.value()
                && (problem.getType() == null || ABOUT_BLANK.equals(problem.getType()))) {
            problem.setType(ProblemType.MALFORMED.uri());
            problem.setTitle(ProblemType.MALFORMED.title());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("unhandled failure while serving a request", ex);
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    }
}
