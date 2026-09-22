package io.nostro.api.ledger;

import io.nostro.api.problem.ProblemException;
import io.nostro.api.problem.ProblemType;
import io.nostro.domain.AccountId;
import io.nostro.domain.RecordOutcome.AlreadyReversed;
import io.nostro.domain.RecordOutcome.CurrencyMismatch;
import io.nostro.domain.RecordOutcome.IdempotencyKeyReused;
import io.nostro.domain.RecordOutcome.InsufficientBalance;
import io.nostro.domain.RecordOutcome.Refused;
import io.nostro.domain.RecordOutcome.Unbalanced;
import io.nostro.domain.RecordOutcome.UnknownAccount;
import io.nostro.domain.RecordOutcome.UnknownEntry;
import java.util.stream.Collectors;

/**
 * The one switch from a domain refusal to its Problem Details (ADR-0014). It is exhaustive over
 * the sealed {@code RecordOutcome.Refused} and has no {@code default}, so a refusal the ledger
 * learns to express without a case here is a compile error, not a 500 found in production.
 *
 * <p>An Account or Entry this Tenant does not have is {@code 404}, and the detail is the same
 * whether it belongs to no one or to another Tenant: a {@code 403} would confirm that it exists.
 */
final class LedgerRefusals {

    private LedgerRefusals() {
    }

    static ProblemException of(Refused refused) {
        return switch (refused) {
            case Unbalanced unbalanced -> ProblemType.UNBALANCED.exception(
                    "the postings do not sum to zero: " + unbalanced.netByCurrency().entrySet().stream()
                            .map(net -> net.getKey().code() + " nets to " + net.getValue().toDecimal().toPlainString())
                            .sorted()
                            .collect(Collectors.joining(", ")));
            case UnknownAccount unknown -> absentAccount(unknown.account());
            case CurrencyMismatch mismatch -> ProblemType.CURRENCY_MISMATCH.exception(
                    "account " + mismatch.account() + " is denominated in " + mismatch.denominated().code()
                            + ", not " + mismatch.posted().code());
            case InsufficientBalance insufficient -> ProblemType.INSUFFICIENT_BALANCE.exception(
                    "account " + insufficient.account() + " is constrained and the entry would take it below zero");
            case IdempotencyKeyReused reused -> ProblemType.IDEMPOTENCY_KEY_REUSED.exception(
                    "idempotency key " + reused.key() + " was presented before with a different request");
            case UnknownEntry unknown -> ProblemType.UNKNOWN_ENTRY.exception(
                    "entry " + unknown.entry() + " is not an entry of this tenant");
            case AlreadyReversed reversed -> ProblemType.ALREADY_REVERSED.exception(
                    "entry " + reversed.entry() + " has already been reversed; an entry is reversed at most once");
        };
    }

    /** The reads' 404 and the write path's, one answer: the row is not there for this Tenant, whoever it is there for. */
    static ProblemException absentAccount(AccountId account) {
        return ProblemType.UNKNOWN_ACCOUNT.exception("account " + account + " is not an account of this tenant");
    }
}
