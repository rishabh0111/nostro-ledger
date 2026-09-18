package io.nostro.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntryBalanceTest {

    static final Currency USD = Currency.of("USD");
    static final Currency EUR = Currency.of("EUR");
    static final AccountId A = new AccountId(UUID.randomUUID());
    static final AccountId B = new AccountId(UUID.randomUUID());
    static final AccountId C = new AccountId(UUID.randomUUID());

    @Test
    void postingsThatSumToZeroInTheirCurrencyBalance() {
        var postings = List.of(
                new Posting(A, Money.ofMinor(-1_000, USD)),
                new Posting(B, Money.ofMinor(600, USD)),
                new Posting(C, Money.ofMinor(400, USD)));

        assertThat(Entry.imbalance(postings)).isEmpty();
    }

    @Test
    void anEntryBalancesWithinEachCurrencySeparately() {
        var exchange = List.of(
                new Posting(A, Money.ofMinor(-1_000, USD)),
                new Posting(B, Money.ofMinor(1_000, USD)),
                new Posting(A, Money.ofMinor(920, EUR)),
                new Posting(C, Money.ofMinor(-920, EUR)));

        assertThat(Entry.imbalance(exchange)).isEmpty();
    }

    @Test
    void currenciesNeverNetAgainstEachOther() {
        var crossed = List.of(
                new Posting(A, Money.ofMinor(-1_000, USD)),
                new Posting(B, Money.ofMinor(1_000, EUR)));

        assertThat(Entry.imbalance(crossed)).hasValueSatisfying(unbalanced ->
                assertThat(unbalanced.netByCurrency())
                        .containsEntry(USD, Money.ofMinor(-1_000, USD))
                        .containsEntry(EUR, Money.ofMinor(1_000, EUR)));
    }

    @Test
    void theRefusalNamesOnlyTheCurrenciesThatDoNotBalance() {
        var partly = List.of(
                new Posting(A, Money.ofMinor(-1_000, USD)),
                new Posting(B, Money.ofMinor(1_000, USD)),
                new Posting(A, Money.ofMinor(5, EUR)),
                new Posting(C, Money.ofMinor(-4, EUR)));

        assertThat(Entry.imbalance(partly)).hasValueSatisfying(unbalanced ->
                assertThat(unbalanced.netByCurrency()).containsOnlyKeys(EUR));
    }

    @Test
    void aPostingCarriesANonZeroAmount() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Posting(A, Money.ofMinor(0, USD)));
    }

    @Test
    void anEntryCannotBeConstructedUnbalanced() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Entry(
                new EntryId(UUID.randomUUID()),
                List.of(new Posting(A, Money.ofMinor(1, USD))),
                Optional.empty(),
                "lopsided"));
    }

    @Test
    void anEntryHasAtLeastOnePosting() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Entry(
                new EntryId(UUID.randomUUID()), List.of(), Optional.empty(), "empty"));
    }

    @Test
    void aReversalIsTheNegationOfEveryPosting() {
        var original = new Entry(
                new EntryId(UUID.randomUUID()),
                List.of(new Posting(A, Money.ofMinor(-1_000, USD)), new Posting(B, Money.ofMinor(1_000, USD))),
                Optional.empty(),
                "rent");

        var reversal = original.reversal(new EntryId(UUID.randomUUID()), "rent, reversed");

        assertThat(reversal.reverses()).contains(original.id());
        assertThat(reversal.postings()).containsExactly(
                new Posting(A, Money.ofMinor(1_000, USD)), new Posting(B, Money.ofMinor(-1_000, USD)));
    }
}
