package io.nostro.api.auth;

import io.nostro.api.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two refusals the security layer makes, as RFC 9457 Problem Details from the one catalog
 * (ADR-0014, {@link ProblemType}). Neither says
 * anything about what exists: a missing or bad credential is 401 whatever was asked for, and a
 * credential without the Permission is 403 whatever the Tenant's data holds.
 */
@Component
class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException refused)
            throws IOException {
        var problem = ProblemType.UNAUTHENTICATED.problem("a valid credential is required");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, problem);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        var problem = ProblemType.FORBIDDEN.problem("the credential does not hold the required permission");
        write(response, problem);
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
