package io.nostro.api.projection;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The projection could not be asked: a {@code 503}, with {@code Retry-After}, the one status RFC
 * 9110 pairs it with. Not a refusal, so not in the {@code ProblemType} catalog, which is refusals
 * only; like every other {@code 5xx} here its type is {@code about:blank} (ADR-0014).
 *
 * <p>Never for lag. A projection that is behind answers {@code 200} with the Position it reflects
 * (ADR-0007); this is for one that does not answer at all. There is no stale Balance to fall back
 * on and no circuit breaker to open (ADR-0011), and writes are unaffected: the floor is checked in
 * the ledger's own database, so an outage of the read model costs reads, never correctness.
 */
class ProjectionUnavailable extends ErrorResponseException {

    ProjectionUnavailable(String detail, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail), cause);
        getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
    }
}
