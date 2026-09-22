package io.nostro.api.ledger;

import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.domain.Account;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.persistence.ledger.Accounts;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Accounts over HTTP, for the Tenant the credential names and no other. An Account of another
 * Tenant is not forbidden, it is absent: the database shows no row, and the answer is 404
 * (ADR-0006, ADR-0014).
 */
@RestController
class AccountsController {

    private final Accounts accounts;

    AccountsController(Accounts accounts) {
        this.accounts = accounts;
    }

    @Requires(Permission.LEDGER_WRITE)
    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> open(@RequestBody OpenAccount request) {
        var currency = Currency.lookup(request.currency())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown currency"));
        Account account;
        try {
            account = new Account(AccountId.random(), request.code(), currency, request.constrained());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        var opened = accounts.open(account);
        return ResponseEntity.created(URI.create("/accounts/" + opened.id())).body(AccountResponse.of(opened));
    }

    @Requires(Permission.LEDGER_READ)
    @GetMapping("/accounts/{id}")
    ResponseEntity<AccountResponse> find(@PathVariable UUID id) {
        return accounts.find(new AccountId(id))
                .map(account -> ResponseEntity.ok(AccountResponse.of(account)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record OpenAccount(String code, String currency, boolean constrained) {
    }

    record AccountResponse(UUID id, String code, String currency, boolean constrained) {
        static AccountResponse of(Account account) {
            return new AccountResponse(account.id().value(), account.code(), account.currency().code(), account.constrained());
        }
    }
}
