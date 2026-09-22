package io.nostro.api.ledger;

import io.nostro.api.ApiVersion;
import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.api.docs.Refuses;
import io.nostro.api.problem.ProblemType;
import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.persistence.ledger.Accounts;
import io.nostro.persistence.ledger.Accounts.OpenOutcome.CodeTaken;
import io.nostro.persistence.ledger.Accounts.OpenOutcome.Opened;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts over HTTP, for the Tenant the credential names and no other. An Account of another
 * Tenant is not forbidden, it is absent: the database shows no row, and the answer is
 * {@code 404 unknown-account}, the same as for no Account at all (ADR-0006, ADR-0014).
 */
@RestController
class AccountsController {

    private final Accounts accounts;

    AccountsController(Accounts accounts) {
        this.accounts = accounts;
    }

    @Requires(Permission.LEDGER_WRITE)
    @Refuses({ProblemType.UNKNOWN_CURRENCY, ProblemType.ACCOUNT_CODE_TAKEN})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> open(@RequestBody OpenAccount request) {
        var currency = Requests.currency(Requests.required(request.currency(), "currency"));
        var account = Requests.domain(() ->
                new Account(AccountId.random(), Requests.required(request.code(), "code"), currency, request.constrained()));
        return switch (accounts.open(account)) {
            case Opened opened -> ResponseEntity
                    .created(URI.create(ApiVersion.V1 + "/accounts/" + opened.account().id()))
                    .body(AccountResponse.of(opened.account()));
            case CodeTaken taken -> throw ProblemType.ACCOUNT_CODE_TAKEN.exception(
                    "an account with code '" + taken.code() + "' already exists");
        };
    }

    @Requires(Permission.LEDGER_READ)
    @Refuses(ProblemType.UNKNOWN_ACCOUNT)
    @GetMapping("/accounts/{id}")
    ResponseEntity<AccountResponse> find(@PathVariable UUID id) {
        return accounts.find(new AccountId(id))
                .map(account -> ResponseEntity.ok(AccountResponse.of(account)))
                .orElseThrow(() -> LedgerRefusals.absentAccount(new AccountId(id)));
    }

    record OpenAccount(@Nullable String code, @Nullable String currency, boolean constrained) {
    }

    record AccountResponse(UUID id, String code, String currency, boolean constrained) {
        static AccountResponse of(Account account) {
            return new AccountResponse(account.id().value(), account.code(), account.currency().code(), account.constrained());
        }
    }
}
