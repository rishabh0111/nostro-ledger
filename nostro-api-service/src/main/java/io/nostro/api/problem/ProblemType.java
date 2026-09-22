package io.nostro.api.problem;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Every stable, machine-readable {@code type} an RFC 9457 response of this API can carry
 * (ADR-0014). One catalog, so that a client switching on {@code type} sees a closed set and the
 * OpenAPI document can enumerate it; a status alone never says which of a handler's refusals it
 * was.
 *
 * <p>Statuses follow ADR-0014: {@code 400} for a request that could not be read as what it claims
 * to be; {@code 422} for one that could, and that the domain refuses; {@code 404}, never {@code
 * 403}, for a reference to something this Tenant does not have, because {@code 403} would confirm
 * that it exists; {@code 409} for a name already taken.
 */
public enum ProblemType {

    // -- the security layer -----------------------------------------------------------------------

    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Unauthenticated"),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),

    // -- the request itself ------------------------------------------------------------------------

    /** Unreadable, or readable but not as what the endpoint takes: a missing field, an Amount off its Currency's scale, a token that is not ours. */
    MALFORMED("malformed", HttpStatus.BAD_REQUEST, "Malformed request"),
    /** A currency code that is not one the ledger can post: not ISO 4217, or without a smallest unit. */
    UNKNOWN_CURRENCY("unknown-currency", HttpStatus.BAD_REQUEST, "Unknown currency"),

    // -- Accounts --------------------------------------------------------------------------------

    ACCOUNT_CODE_TAKEN("account-code-taken", HttpStatus.CONFLICT, "Account code taken"),

    // -- recording an Entry: one per member of RecordOutcome.Refused ------------------------------

    UNBALANCED("unbalanced", HttpStatus.UNPROCESSABLE_CONTENT, "Unbalanced"),
    UNKNOWN_ACCOUNT("unknown-account", HttpStatus.NOT_FOUND, "Unknown account"),
    CURRENCY_MISMATCH("currency-mismatch", HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),
    INSUFFICIENT_BALANCE("insufficient-balance", HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient balance"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),
    UNKNOWN_ENTRY("unknown-entry", HttpStatus.NOT_FOUND, "Unknown entry"),
    ALREADY_REVERSED("already-reversed", HttpStatus.UNPROCESSABLE_CONTENT, "Already reversed"),

    // -- the control plane (ADR-0015) --------------------------------------------------------------

    TENANT_NAME_TAKEN("tenant-name-taken", HttpStatus.CONFLICT, "Tenant name taken"),
    UNKNOWN_TENANT("unknown-tenant", HttpStatus.NOT_FOUND, "Unknown tenant"),
    STAFF_USERNAME_TAKEN("staff-username-taken", HttpStatus.CONFLICT, "Staff username taken"),
    UNKNOWN_PERMISSION("unknown-permission", HttpStatus.BAD_REQUEST, "Unknown permission");

    public static final String NAMESPACE = "https://nostro.dev/problems/";

    private final URI uri;
    private final HttpStatus status;
    private final String title;

    ProblemType(String slug, HttpStatus status, String title) {
        this.uri = URI.create(NAMESPACE + slug);
        this.status = status;
        this.title = title;
    }

    public URI uri() {
        return uri;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The Problem Details body, with the detail given: a sentence for a person, never a field a client switches on. */
    public ProblemDetail problem(String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(uri);
        problem.setTitle(title);
        return problem;
    }

    /** The same problem, as the exception a handler throws to answer with it. */
    public ProblemException exception(String detail) {
        return new ProblemException(problem(detail));
    }
}
