package io.nostro.api.ledger;

import io.nostro.api.auth.Permission;
import io.nostro.api.auth.Requires;
import io.nostro.api.docs.Refuses;
import io.nostro.api.problem.ProblemType;
import io.nostro.domain.AccountId;
import io.nostro.domain.Balance;
import io.nostro.domain.BalanceReader;
import io.nostro.domain.Position;
import io.nostro.persistence.Installation;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Balance read (ADR-0007). The response shape here is the contract: the projection changes what stands
 * behind {@link BalanceReader}, not what this returns.
 *
 * <p>{@code minPosition} is the Position a caller needs the answer to include. It is always
 * answered {@code 200} with the Position actually reflected, never a {@code 409} or a {@code 503};
 * the caller compares and decides. A token that is not a Position of this installation is
 * {@code 400 malformed} (ADR-0014): it cannot be compared, so it is not a Position here.
 */
@RestController
class BalanceController {

    private final BalanceReader balances;
    private final Installation installation;

    BalanceController(BalanceReader balances, Installation installation) {
        this.balances = balances;
        this.installation = installation;
    }

    @Requires(Permission.LEDGER_READ)
    @Refuses(ProblemType.UNKNOWN_ACCOUNT)
    @GetMapping("/accounts/{id}/balance")
    ResponseEntity<BalanceResponse> read(@PathVariable UUID id, @RequestParam(required = false) @Nullable String minPosition) {
        Optional<Position> minimum = minPosition == null
                ? Optional.empty()
                : Optional.of(installation.parse(minPosition).orElseThrow(() ->
                        ProblemType.MALFORMED.exception("minPosition is not a Position of this ledger")));
        return balances.read(new AccountId(id), minimum)
                .map(balance -> ResponseEntity.ok(BalanceResponse.of(balance)))
                .orElseThrow(() -> LedgerRefusals.absentAccount(new AccountId(id)));
    }

    record BalanceResponse(UUID account, MoneyJson balance, String position) {
        static BalanceResponse of(Balance balance) {
            return new BalanceResponse(balance.account().value(), MoneyJson.of(balance.amount()), balance.position().token());
        }
    }
}
