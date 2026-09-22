package io.nostro.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.nostro.api.problem.ProblemType;
import io.nostro.domain.AccountId;
import io.nostro.domain.Currency;
import io.nostro.domain.EntryId;
import io.nostro.domain.IdempotencyKey;
import io.nostro.domain.Money;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
import io.nostro.domain.RecordOutcome.CurrencyMismatch;
import io.nostro.domain.RecordOutcome.IdempotencyKeyReused;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.domain.RecordOutcome.Refused;
import io.nostro.domain.RecordOutcome.Unbalanced;
import io.nostro.domain.RecordOutcome.UnknownAccount;
import io.nostro.domain.RecordOutcome.UnknownEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Every refusal the ledger can express has a status and a stable type (ADR-0014). The switch is
 * exhaustive over the sealed hierarchy, so a member without a case does not compile; this test
 * pins what each case says, and that no member is missing from the table here either.
 */
class LedgerRefusalsTest {

    static final Currency USD = Currency.of("USD");
    static final Currency EUR = Currency.of("EUR");
    static final AccountId ACCOUNT = AccountId.random();
    static final EntryId ENTRY = EntryId.random();

    record Expected(Refused refusal, HttpStatus status, ProblemType type) {
    }

    static final List<Expected> TABLE = List.of(
            new Expected(new Unbalanced(Map.of(USD, Money.ofMinor(100, USD))), HttpStatus.UNPROCESSABLE_CONTENT, ProblemType.UNBALANCED),
            new Expected(new UnknownAccount(ACCOUNT), HttpStatus.NOT_FOUND, ProblemType.UNKNOWN_ACCOUNT),
            new Expected(new CurrencyMismatch(ACCOUNT, EUR, USD), HttpStatus.UNPROCESSABLE_CONTENT, ProblemType.CURRENCY_MISMATCH),
            new Expected(new InsufficientBalance(ACCOUNT), HttpStatus.UNPROCESSABLE_CONTENT, ProblemType.INSUFFICIENT_BALANCE),
            new Expected(new IdempotencyKeyReused(new IdempotencyKey("k")), HttpStatus.UNPROCESSABLE_CONTENT, ProblemType.IDEMPOTENCY_KEY_REUSED),
            new Expected(new UnknownEntry(ENTRY), HttpStatus.NOT_FOUND, ProblemType.UNKNOWN_ENTRY),
            new Expected(new AlreadyReversed(ENTRY), HttpStatus.UNPROCESSABLE_CONTENT, ProblemType.ALREADY_REVERSED));

    @Test
    @DisplayName("each refusal maps to its status and type; 404, not 403, for an Account this Tenant does not have")
    void eachRefusalHasAStatusAndAType() {
        for (var expected : TABLE) {
            var exception = LedgerRefusals.of(expected.refusal());
            assertThat(exception.getStatusCode()).as(expected.refusal().toString()).isEqualTo(expected.status());
            assertThat(exception.getBody().getType()).as(expected.refusal().toString()).isEqualTo(expected.type().uri());
            assertThat(exception.getBody().getDetail()).as(expected.refusal().toString()).isNotBlank();
        }
    }

    @Test
    @DisplayName("the table above names every member of the sealed hierarchy")
    void theTableIsComplete() {
        List<Class<?>> members = Arrays.asList(Refused.class.getPermittedSubclasses());
        List<Class<?>> tabled = TABLE.stream().<Class<?>>map(expected -> expected.refusal().getClass()).toList();

        assertThat(tabled).containsExactlyInAnyOrderElementsOf(members);
    }

    @Test
    @DisplayName("an unbalanced refusal names the Currencies that fail and what they net to")
    void anUnbalancedRefusalNamesTheNet() {
        var exception = LedgerRefusals.of(new Unbalanced(Map.of(USD, Money.ofMinor(150, USD))));

        assertThat(exception.getBody().getDetail()).contains("USD").contains("1.50");
    }
}
