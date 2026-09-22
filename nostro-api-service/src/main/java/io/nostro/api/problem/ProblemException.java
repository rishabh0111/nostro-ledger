package io.nostro.api.problem;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A refusal thrown from a handler, carrying its Problem Details body. The framework's own handler
 * ({@code spring.mvc.problemdetails.enabled}) renders an {@link ErrorResponseException} as
 * {@code application/problem+json} with the body it carries, so nothing here re-implements that;
 * the class exists so that every refusal with a {@link ProblemType} looks the same at the throw site.
 */
public class ProblemException extends ErrorResponseException {

    ProblemException(ProblemDetail problem) {
        super(HttpStatusCode.valueOf(problem.getStatus()), problem, null);
    }
}
